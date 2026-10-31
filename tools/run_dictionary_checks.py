"""Run real packaged dictionary checks and verify a populated emulator upgrade.

Private snapshots stay only in the explicitly selected evidence directory.
The owned emulator is the only permitted target; never installs on a phone.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile

from verify_rime_upgrade import databases, preferences


def run(args):
    output = args.output.resolve()
    if output.exists(): raise ValueError("Preserve prior evidence")
    base = [str(args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")), "-s", args.device]
    def adb(*command): return subprocess.check_output(base + list(command))
    if args.device != "emulator-5580" or adb("shell","getprop","ro.kernel.qemu").strip() != b"1":
        raise ValueError("Only the owned emulator-5580 is allowed")
    if adb("shell","getconf","PAGE_SIZE").strip() != b"16384": raise ValueError("Actual16KB userspace required")
    baseline = json.loads((args.baseline / "baseline.json").read_bytes())
    if baseline["wordbook_count"] < 2 or baseline["review_events"] < 1:
        raise ValueError("A populated real learning baseline is required")
    output.mkdir(parents=True)
    receipt = {"passed": False, "device": args.device, "userspace_page_size": 16384,
               "physical_phone_changed": False, "uninstalled": False, "seeded_data": False}
    def sha(path):
        with path.open("rb") as stream: return hashlib.file_digest(stream,"sha256").hexdigest()
    try:
        for name,path in (("app",args.app_apk),("test",args.test_apk)):
            receipt[name+"_apk_sha256"] = sha(path)
            log = adb("install","-r",str(path))
            (output/(name+"-install.log")).write_bytes(log)
            if b"Success" not in log: raise RuntimeError("APK upgrade failed")
        version = adb("shell","dumpsys","package","com.vime.ime").decode()
        if "versionName="+args.version+"\n" not in version.replace("\r",""):
            raise ValueError("Installed version differs")
        with (output/"instrumentation.log").open("wb") as log:
            process = subprocess.Popen(base+["shell","am","instrument","-w","-r",
                "com.vime.ime.test/com.vime.ime.NoticesInstrumentation"],stdout=log,stderr=subprocess.STDOUT)
            print("Observing dictionary instrumentation pid="+str(process.pid),flush=True)
            if process.wait(): raise RuntimeError("Instrumentation transport failed")
        text = (output/"instrumentation.log").read_text("utf-8")
        summary = re.search(r"INSTRUMENTATION_RESULT: summary=(\{.*\})",text)
        if not summary: raise RuntimeError("No instrumentation result")
        result=json.loads(summary[1]);(output/"dictionary-results.json").write_text(json.dumps(result,indent=2),"utf-8")
        if result["expected"]!=9 or result["passed"]!=9 or result["failed"] or result["not_run"] or "INSTRUMENTATION_CODE: -1" not in text:
            raise RuntimeError("Packaged dictionary checks failed")
        adb("shell","am","force-stop","com.vime.ime")
        data=adb("exec-out","run-as","com.vime.ime","tar","-cf","-","databases","shared_prefs","files/rime/user")
        (output/"after-private.tar").write_bytes(data)
        after=output/"after"
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:archive.extractall(after,filter="data")
        tables=databases(after)
        if tables!=baseline["tables"]:raise RuntimeError("Learning records changed during upgrade")
        if preferences(after)!=baseline["preferences"]:raise RuntimeError("Preferences changed during upgrade")
        ssts=list((args.baseline/"before/files/rime/user").rglob("*.sst"))
        for before in ssts:
            relative=before.relative_to(args.baseline/"before")
            if not (after/relative).is_file() or sha(before)!=sha(after/relative):
                raise RuntimeError("Existing native user database bytes changed")
        settings={key:adb("shell","settings","get","secure",key).decode().strip() for key in baseline["settings"]}
        if settings!=baseline["settings"]:raise RuntimeError("Original input method settings changed")
        receipt.update(passed=True, version=args.version, dictionary_checks_passed=9,
                       populated_learning_rows_preserved=True, preferences_preserved=True,
                       original_sst_files_preserved=len(ssts), input_method_settings_unchanged=True,
                       wordbook_count=baseline["wordbook_count"],review_events=baseline["review_events"])
    except Exception as error:
        receipt["error"]=str(error)
    (output/"receipt.json").write_text(json.dumps(receipt,indent=2),"utf-8")
    print(json.dumps(receipt),flush=True)
    return 0 if receipt["passed"] else 1


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("sdk","app-apk","test-apk","baseline","output"):parser.add_argument("--"+name,required=True,type=Path)
    parser.add_argument("--device",required=True);parser.add_argument("--version",required=True)
    raise SystemExit(run(parser.parse_args()))
