"""Exercise the actual Rime library on an emulator with an explicitly required page size."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

RUNNER = 'com.vime.ime.NativeRuntimeInstrumentation'
COMPAT_OFF = {'bionic.linker.16kb.app_compat.enabled': 'false', 'pm.16kb.app_compat.disabled': 'true'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--page-size', type=int, choices=(4096, 16384), required=True)
    parser.add_argument('--app-apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    base = [str(args.sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')), '-s', args.device]
    output = args.output.resolve()
    receipt = {'device': args.device, 'required_page_size_bytes': args.page_size, 'passed': False}
    original = {}
    created = False

    def run(command, timeout=30):
        process = subprocess.run([str(x) for x in command], capture_output=True, timeout=timeout)
        data = (process.stdout + process.stderr).decode('utf-8', 'replace')
        if process.returncode:
            raise RuntimeError(data[-1500:] or f'Command failed ({process.returncode})')
        return data

    def adb(*command, timeout=30): return run(base + list(command), timeout)
    def prop(name): return adb('shell', 'getprop', name).strip()
    def setprop(name, value): return adb('shell', f'setprop {name} {shlex.quote(value)}')

    try:
        # Live guards precede any install, property change or output creation.
        if prop('ro.kernel.qemu') != '1':
            raise ValueError('Dedicated emulator required; physical phones are never changed')
        if prop('sys.boot_completed') != '1':
            raise ValueError('Finish emulator startup before running native checks')
        page_size = int(adb('shell', 'getconf', 'PAGE_SIZE').strip())
        if page_size != args.page_size:
            raise ValueError(f'Required page size {args.page_size}, live target reports {page_size}')
        if output.exists():
            raise ValueError('Use a new output directory; old evidence is preserved')
        aapt = args.sdk / 'build-tools/36.0.0' / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
        with tempfile.TemporaryDirectory(prefix='vime-native-apk-') as temp:
            staged = Path(temp) / 'test.apk'
            shutil.copyfile(args.test_apk, staged)
            if RUNNER not in run([aapt, 'dump', 'xmltree', staged, '--file', 'AndroidManifest.xml']):
                raise ValueError('Build the native test APK with -PvimeNativeChecks=true')
        output.mkdir(parents=True); created = True
        receipt.update(sdk=int(prop('ro.build.version.sdk')), fingerprint=prop('ro.build.fingerprint'),
                       page_size_bytes=page_size, abi=prop('ro.product.cpu.abi'))
        for kind, path in [('app', args.app_apk), ('test', args.test_apk)]:
            with path.open('rb') as stream:
                receipt[kind + '_sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        if args.page_size == 16384:
            original = {name: prop(name) for name in COMPAT_OFF}
            receipt['compat_properties_before'] = original
            for name, value in COMPAT_OFF.items(): setprop(name, value)
            during = {name: prop(name) for name in COMPAT_OFF}
            receipt['compat_properties_during'] = during
            if during != COMPAT_OFF:
                raise RuntimeError('Cannot verify that 16KB compatibility fallback is disabled')
        adb('install', '-r', args.app_apk, timeout=90)
        adb('install', '-r', '-t', args.test_apk, timeout=90)
        process = subprocess.Popen(base + ['shell', 'am', 'instrument', '-w', '-e', 'expectedPageSize', str(args.page_size),
                                          f'com.vime.ime.test/{RUNNER}'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            data, _ = process.communicate(timeout=180)
        except subprocess.TimeoutExpired:
            process.kill(); data, _ = process.communicate()
            (output / 'instrumentation.log').write_bytes(data)
            adb('shell', 'am', 'force-stop', 'com.vime.ime')
            raise RuntimeError('Native checks exceeded 180s; inspect the preserved log and system state')
        text = data.decode('utf-8', 'replace')
        (output / 'instrumentation.log').write_text(text, 'utf-8')
        summary = re.search(r'^INSTRUMENTATION_RESULT: summary=(\{.*\})\s*$', text, re.M)
        code = re.search(r'^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', text, re.M)
        if process.returncode or not summary or not code:
            raise RuntimeError('No actual instrumentation result Bundle; inspect instrumentation.log')
        result = json.loads(summary[1])
        (output / 'native-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        receipt.update(result=result, instrumentation_code=int(code[1]))
        receipt['passed'] = (int(code[1]) == -1 and result['expected'] == result['passed'] == 9
                             and result['failed'] == result['not_run'] == 0
                             and result['observations']['process_page_size_bytes'] == args.page_size)
        if not receipt['passed']: receipt['error'] = 'Native runtime checks failed or did not all run'
        for case in result['cases']:
            print(f"{'PASS' if case['pass'] else 'FAIL'} {case['name']}", flush=True)
    except Exception as error:
        receipt['error'] = str(error)
    finally:
        if original:
            errors = []
            for name, value in original.items():
                try: setprop(name, value)
                except Exception as error: errors.append(str(error))
            try:
                after = {name: prop(name) for name in original}
                receipt['compat_properties_after'] = after
                if after != original: errors.append('Property read-back differs from original values')
            except Exception as error: errors.append(str(error))
            receipt['compat_properties_restored'] = not errors
            if errors:
                receipt['restore_errors'] = errors
                receipt['passed'] = False
        if created:
            (output / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({'passed': receipt['passed'], 'error': receipt.get('error')}))
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
