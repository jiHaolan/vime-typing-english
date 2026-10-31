"""Build and inspect signed APK candidates; does not upload or publish anything."""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from verify_release import PREVIEW_CERT, verify
from verify_notices import verify as verify_notices
from optimize_apk_payload import sign_optimized

ROOT = Path(__file__).resolve().parents[1]
SIGNING_ENV = ('VIME_RELEASE_STORE_FILE', 'VIME_RELEASE_KEY_ALIAS',
               'VIME_RELEASE_STORE_PASSWORD', 'VIME_RELEASE_KEY_PASSWORD', 'VIME_RELEASE_CERT_SHA256')
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86_64'}
OPTIONAL_TEST = ('com.vime.ime.LexiconDatasetTest', 'officialPackCoverageAndDesktopQueryBenchmark')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--gradle', type=Path, default=ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--previous-apk', type=Path)
    parser.add_argument('--validation', action='store_true', help='Allow dirty sources and no previous APK; mark every artifact as validation only')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    receipt = {'started_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
               'profile': 'validation-only' if args.validation else 'release-candidate',
               'published': False, 'passed': False, 'artifacts': []}
    secrets = [os.environ.get(name, '') for name in SIGNING_ENV[2:4]]

    def redact(text):
        for secret in secrets:
            if secret:
                text = text.replace(secret, '[REDACTED]')
        return text

    def capture(command):
        result = subprocess.run([str(x) for x in command], cwd=ROOT, capture_output=True, timeout=90)
        output = redact((result.stdout + result.stderr).decode('utf-8', 'replace'))
        if result.returncode:
            raise RuntimeError(f'{Path(str(command[0])).name} failed ({result.returncode}): {output[-2000:]}')
        return output

    def source_fingerprint():
        digest = hashlib.sha256()
        digest.update(capture(['git', 'rev-parse', 'HEAD']).encode('utf-8'))
        digest.update(capture(['git', 'diff', 'HEAD', '--binary']).encode('utf-8'))
        for name in sorted(capture(['git', 'ls-files', '--others', '--exclude-standard', '-z']).split('\0')):
            if name:
                path = ROOT / name
                digest.update(name.encode('utf-8'))
                digest.update(hashlib.sha256(path.read_bytes()).digest())
        return digest.hexdigest()

    output = args.output.resolve()
    try:
        receipt['notices'] = verify_notices(ROOT / 'app/src/main/assets', commercial=not args.validation)
        if not receipt['notices']['passed']:
            blockers = receipt['notices'].get('release_blockers', [])
            raise ValueError('Third-party source/notice audit incomplete: ' + ', '.join(row['id'] for row in blockers)
                             + '; see notices/components.json and tools/verify_notices.py')
        missing = [name for name in SIGNING_ENV if not os.environ.get(name, '').strip()]
        if missing:
            raise ValueError('Missing signing environment variables: ' + ', '.join(missing))
        cert = os.environ['VIME_RELEASE_CERT_SHA256'].lower().replace(':', '')
        if not re.fullmatch('[a-f0-9]{64}', cert) or cert == PREVIEW_CERT:
            raise ValueError('An independent expected release certificate SHA-256 is required; the preview certificate is not accepted')
        store_path = Path(os.environ['VIME_RELEASE_STORE_FILE']).resolve()
        if not store_path.is_file():
            raise ValueError('Signing store does not exist')
        if store_path == ROOT or ROOT in store_path.parents:
            raise ValueError('Keep private signing stores outside the Git working tree')
        if not args.validation and not args.previous_apk:
            raise ValueError('Release candidates require --previous-apk to verify certificate continuity and increasing version code; signing-key rotation is not implemented here')
        if not (args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')).is_file():
            raise ValueError('JDK not found')
        if not args.gradle.is_file():
            raise ValueError('Gradle launcher not found')
        if output == ROOT or ROOT in output.parents:
            raise ValueError('The delivery directory must be outside the Git working tree')
        if output.exists():
            raise ValueError('Use a new output directory; existing deliveries are never overwritten')
        if args.version_code <= 0 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9]+)*', args.version_name):
            raise ValueError('Invalid expected version')
        before = capture(['git', 'status', '--porcelain'])
        if before.strip() and not args.validation:
            raise ValueError('Commit source changes before preparing a release candidate')
        receipt['source'] = {'commit': capture(['git', 'rev-parse', 'HEAD']).strip(),
                             'dirty': bool(before.strip()), 'status': before.splitlines()}
        receipt['source']['fingerprint_sha256'] = source_fingerprint()
        receipt['expected_certificate_sha256'] = cert
        receipt['version'] = {'name': args.version_name, 'code': args.version_code}
        output.mkdir(parents=True)
        os.environ['JAVA_HOME'] = str(args.java_home.resolve())
        os.environ['ANDROID_HOME'] = str(args.sdk.resolve())
        os.environ['PYTHONIOENCODING'] = 'utf-8'
        os.environ['JAVA_OPTS'] = '-Xmx2048m -Dfile.encoding=UTF-8'
        socket_dir = args.sdk.resolve().parent / 'jtmp'
        socket_dir.mkdir(exist_ok=True)
        os.environ['JAVA_TOOL_OPTIONS'] = '-Djdk.net.unixdomain.tmpdir="' + str(socket_dir) + '"'

        if args.previous_apk:
            # Inspect the signed previous package. Older target SDKs are allowed for this check.
            sdk_tools = args.sdk / 'build-tools' / '36.0.0'
            with tempfile.TemporaryDirectory(prefix='vime-previous-') as folder:
                staged = Path(folder) / 'previous.apk'
                shutil.copyfile(args.previous_apk, staged)
                signature = capture([sdk_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner'),
                                     'verify', '--verbose', '--print-certs', staged])
                digests = re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]+)', signature)
                if len(digests) != 1 or digests[0].lower() != cert:
                    raise ValueError('Previous APK certificate differs; do not deliver an update that users cannot install over their existing app')
                metadata = capture([sdk_tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2'), 'dump', 'badging', staged])
                package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", metadata)
                if not package or package[1] != 'com.vime.ime' or int(package[2]) >= args.version_code:
                    raise ValueError('Previous APK must be this application with a lower version code')
                receipt['upgrade_preflight'] = {'previous_sha256': hashlib.sha256(staged.read_bytes()).hexdigest(),
                                                 'previous_version_code': int(package[2]), 'same_certificate': True,
                                                 'runtime_upgrade_verified': False}
        else:
            receipt['upgrade_preflight'] = {'verified': False, 'reason': 'validation-only run without previous APK'}

        gradle_version = capture([args.gradle, '--version'])
        if not re.search(r'\bGradle 8\.11\.1\b', gradle_version):
            raise ValueError('Use the pinned Gradle 8.11.1 distribution')
        receipt['gradle'] = '8.11.1'
        commands = [
            [args.gradle, '--no-daemon', '--console=plain', '-Dorg.gradle.jvmargs=',
             *(['--offline'] if args.offline else []), ':app:writeRuntimeInventory'],
            [sys.executable, ROOT / 'tools/verify_notices.py', ROOT / 'app/src/main/assets',
             '--inventory', ROOT / 'app/build/reports/runtime-components.json',
             *([] if args.validation else ['--release']), '--report', output / 'notices-source.json'],
            [args.gradle, '--no-daemon', '--console=plain', '-Dorg.gradle.jvmargs=',
             *(['--offline'] if args.offline else []), ':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintRelease'],
            [sys.executable, ROOT / 'Verify-Reference.py', output / 'static-reference.json']]
        with (output / 'build.log').open('w', encoding='utf-8') as log:
            for command in commands:
                process = subprocess.Popen([str(x) for x in command], cwd=ROOT, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
                for line in process.stdout:
                    line = redact(line)
                    log.write(line)
                    if line.startswith(('> Task', 'BUILD ', 'FAILURE:')):
                        print(line.rstrip(), flush=True)
                if process.wait():
                    raise RuntimeError('Build or verification failed; see sanitized build.log')
        suites = list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
        counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
        for file in suites:
            suite = ET.parse(file).getroot()
            for key in counts:
                counts[key] += int(suite.get(key, 0))
            for test in suite.findall('testcase'):
                if test.find('skipped') is not None and (test.get('classname'), test.get('name')) != OPTIONAL_TEST:
                    raise ValueError('An unexpected unit test was skipped')
        if not suites or counts['tests'] <= counts['skipped'] or counts['failures'] or counts['errors']:
            raise ValueError('Unit test reports are missing or contain failures')
        receipt['unit_tests'] = counts
        lint_path = ROOT / 'app/build/reports/lint-results-release.xml'
        lint = ET.parse(lint_path).getroot()
        severities = {}
        for issue in lint.findall('issue'):
            severity = issue.get('severity')
            severities[severity] = severities.get(severity, 0) + 1
        if severities.get('Error', 0) or severities.get('Fatal', 0):
            raise ValueError('Release lint contains errors')
        receipt['lint'] = severities
        shutil.copyfile(lint_path, output / 'lint-results-release.xml')
        apk_dir = ROOT / 'app/build/outputs/apk/release'
        metadata = json.loads((apk_dir / 'output-metadata.json').read_text('utf-8'))
        seen = set()
        for element in metadata['elements']:
            filters = element['filters']
            if len(filters) != 1 or filters[0]['filterType'] != 'ABI':
                raise ValueError('Unexpected APK split configuration')
            abi = filters[0]['value']
            if abi not in ABIS or abi in seen:
                raise ValueError('Unexpected or duplicate ABI')
            seen.add(abi)
            source = (apk_dir / element['outputFile']).resolve()
            if source.parent != apk_dir.resolve():
                raise ValueError('APK metadata points outside build output')
            notices = verify_notices(source, json.loads((ROOT / 'app/build/reports/runtime-components.json').read_text('utf-8')),
                                     commercial=not args.validation)
            (output / f'notices-{abi}.json').write_text(json.dumps(notices, ensure_ascii=False, indent=2), 'utf-8')
            if not notices['passed']:
                raise ValueError('Packaged third-party notices do not match verified source/runtime dependencies')
            optimized=output/f'optimized-{abi}.apk'
            compression=sign_optimized(source,optimized,args.sdk,args.java_home,store_path,
                                       os.environ['VIME_RELEASE_KEY_ALIAS'],capture)
            (output/f'compression-{abi}.json').write_text(json.dumps(compression,indent=2),'utf-8')
            source=optimized
            report = verify(source, args.sdk, '36.0.0', cert, args.version_name, args.version_code)
            (output / f'verify-{abi}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
            if not report['passed'] or report['abis'] != [abi]:
                raise ValueError(f"APK verification failed for {abi}: {report['failed_checks']}")
            prefix = 'Vime-validation' if args.validation else 'Vime'
            destination = output / f'{prefix}-{args.version_name}-{abi}.apk'
            shutil.copyfile(source, destination)
            receipt['artifacts'].append({'file': destination.name, 'sha256': report['sha256'], 'bytes': report['bytes'], 'abi': abi})
        if seen != ABIS:
            raise ValueError('One or more required ABI packages are missing')
        after = capture(['git', 'status', '--porcelain'])
        if before != after or receipt['source']['fingerprint_sha256'] != source_fingerprint():
            raise ValueError('Source checkout changed during the build; retry after reviewing changes')
        (output / 'SHA256SUMS.txt').write_text(''.join(f"{a['sha256']}  {a['file']}\n" for a in receipt['artifacts']), 'utf-8')
        receipt['passed'] = True
    except Exception as error:
        receipt['error'] = redact(str(error))
    receipt['finished_utc'] = dt.datetime.now(dt.timezone.utc).isoformat()
    if output.is_dir() and 'source' in receipt:
        (output / 'release-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({'passed': receipt['passed'], 'profile': receipt['profile'], 'error': receipt.get('error'),
                      'artifacts': len(receipt['artifacts'])}, ensure_ascii=False))
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
