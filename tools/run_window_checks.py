"""Run actual keyboard/window/Back checks on a dedicated emulator, preserving keyboard settings."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

RUNNER = 'com.vime.ime.WindowInteractionInstrumentation'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--app-apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    receipt = {'device': args.device, 'passed': False, 'settings_restored': False}
    output = args.output.resolve()
    adb_tool = args.sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
    adb_base = [str(adb_tool), '-s', args.device]
    previous_ime = None
    previous_hardware = None
    vime_was_enabled = False
    configured = False
    created = False

    def run(command, timeout=30):
        result = subprocess.run([str(x) for x in command], capture_output=True, timeout=timeout)
        text = (result.stdout + result.stderr).decode('utf-8', 'replace')
        if result.returncode:
            raise RuntimeError(text[-1500:] or f'Command failed ({result.returncode})')
        return text

    def adb(*command, timeout=30): return run(adb_base + list(command), timeout)

    def digest(path):
        with path.open('rb') as source:
            return hashlib.file_digest(source, 'sha256').hexdigest()

    try:
        # Check the live target before installing, selecting an IME, or creating output.
        if adb('shell', 'getprop', 'ro.kernel.qemu', timeout=8).strip() != '1':
            raise ValueError('Dedicated emulator required; this command never changes a physical phone')
        sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk', timeout=8).strip())
        if sdk < 30:
            raise ValueError('The window-metrics suite requires API30+')
        receipt['sdk'] = sdk
        aapt = args.sdk / 'build-tools' / '36.0.0' / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
        with tempfile.TemporaryDirectory(prefix='vime-window-apk-') as folder:
            # Native SDK tools may reject non-ASCII paths on Windows.
            staged = Path(folder) / 'test.apk'
            __import__('shutil').copyfile(args.test_apk, staged)
            manifest = run([aapt, 'dump', 'xmltree', staged, '--file', 'AndroidManifest.xml'])
            names = re.findall(r'android:name\([^)]*\)="([^"]+)"', manifest)
            if RUNNER not in names:
                raise ValueError('Wrong test APK profile; build with -PvimeWindowChecks=true')
        if output.exists():
            raise ValueError('Use a new output directory; existing results are preserved')
        output.mkdir(parents=True); created = True
        receipt['app_sha256'] = digest(args.app_apk)
        receipt['test_sha256'] = digest(args.test_apk)
        previous_ime = adb('shell', 'settings', 'get', 'secure', 'default_input_method').strip()
        previous_hardware = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').strip()
        previous_enabled = adb('shell', 'settings', 'get', 'secure', 'enabled_input_methods').strip()
        vime_was_enabled = any(part.split(';')[0].startswith('com.vime.ime/') for part in previous_enabled.split(':'))
        receipt['settings_before'] = {'default_input_method': previous_ime,
                                      'show_ime_with_hard_keyboard': previous_hardware,
                                      'vime_enabled': vime_was_enabled}
        if not previous_ime or previous_ime == 'null':
            raise ValueError('The emulator has no established input method; finish its startup first')
        adb('install', '-r', args.app_apk, timeout=90)
        adb('install', '-r', '-t', args.test_apk, timeout=90)
        configured = True
        # Set the emulator's hardware-keyboard policy before binding the IME.
        adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
        adb('shell', 'ime', 'enable', 'com.vime.ime/.VimeImeService')
        # The native runner selects Vime after its new process and Home window
        # exist. Selecting it here binds a service that instrumentation then kills.
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        process = subprocess.Popen(adb_base + ['shell', 'am', 'instrument', '-w', f'com.vime.ime.test/{RUNNER}'],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            data, _ = process.communicate(timeout=150)
        except subprocess.TimeoutExpired:
            process.kill(); data, _ = process.communicate()
            (output / 'instrumentation.log').write_bytes(data)
            adb('shell', 'am', 'force-stop', 'com.vime.ime')
            raise RuntimeError('Window instrumentation exceeded 150s; inspect system/app ANR evidence')
        text = data.decode('utf-8', 'replace')
        (output / 'instrumentation.log').write_text(text, 'utf-8')
        for line in text.splitlines():
            if line.startswith(('PASS ', 'FAIL ', 'INSTRUMENTATION_CODE:')):
                print(line, flush=True)
        match = re.search(r'^INSTRUMENTATION_RESULT: summary=(\{.*\})\s*$', text, re.M)
        if process.returncode or not match:
            raise ValueError('Missing actual instrumentation result Bundle; see instrumentation.log')
        result = json.loads(match[1])
        code = re.search(r'^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', text, re.M)
        receipt['instrumentation_code'] = int(code[1]) if code else None
        (output / 'window-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        receipt['result'] = result
        receipt['passed'] = (receipt['instrumentation_code'] == -1 and result.get('expected') == 7
                             and result.get('passed') == 7 and result.get('failed') == 0 and result.get('not_run') == 0)
        if not receipt['passed']:
            receipt['error'] = 'One or more real window checks failed or did not run'
    except Exception as error:
        receipt['error'] = str(error)
    finally:
        if configured:
            restore_errors = []
            restore_commands = [['shell', 'ime', 'set', previous_ime],
                                ['shell', 'settings', *(['delete', 'secure', 'show_ime_with_hard_keyboard'] if previous_hardware == 'null'
                                                      else ['put', 'secure', 'show_ime_with_hard_keyboard', previous_hardware])]]
            if not vime_was_enabled:
                restore_commands.append(['shell', 'ime', 'disable', 'com.vime.ime/.VimeImeService'])
            for command in restore_commands:
                try: adb(*command)
                except Exception as error: restore_errors.append(str(error))
            try:
                restored_ime = adb('shell', 'settings', 'get', 'secure', 'default_input_method').strip()
                restored_hardware = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').strip()
                enabled = adb('shell', 'settings', 'get', 'secure', 'enabled_input_methods').strip()
                restored_enabled = any(part.split(';')[0].startswith('com.vime.ime/') for part in enabled.split(':'))
                receipt['settings_after'] = {'default_input_method': restored_ime,
                                             'show_ime_with_hard_keyboard': restored_hardware,
                                             'vime_enabled': restored_enabled}
                if receipt['settings_before'] != receipt['settings_after']:
                    restore_errors.append('Read-back settings differ from the original emulator settings')
            except Exception as error:
                restore_errors.append(str(error))
            receipt['settings_restored'] = not restore_errors
            if restore_errors:
                receipt['restore_errors'] = restore_errors
                receipt['passed'] = False
        if created:
            (output / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({'passed': receipt['passed'], 'error': receipt.get('error'), 'settings_restored': receipt['settings_restored']}))
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
