"""Compile pinned public Rime inputs twice in an isolated, owned 16KB emulator.

No product APK is installed, no product/user directory is read, and only a fixed
set of public compiler outputs is exported. Existing evidence is never replaced.
An active instrumentation process is observed to completion, never restarted on
an observation timeout. The CLI's own exec handle can be polled while it runs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import uuid
import zipfile

PACKAGE = "com.vime.dictionary.compiler"
RUNNER = "com.vime.compiler.CompileInstrumentation"
INPUTS = {"default.yaml", "essay.txt", "vime_pinyin.dict.yaml", "vime_pinyin.schema.yaml", "vime_nine.schema.yaml"}
OUTPUTS = {"default.yaml", "vime_nine.prism.bin", "vime_nine.schema.yaml", "vime_pinyin.prism.bin",
           "vime_pinyin.reverse.bin", "vime_pinyin.schema.yaml", "vime_pinyin.table.bin"}
COMPAT_OFF = {"bionic.linker.16kb.app_compat.enabled": "false", "pm.16kb.app_compat.disabled": "true"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def check_source(folder):
    manifest = json.loads((folder / "manifest.json").read_bytes())
    if manifest["format"] != "vime-rime-2" or manifest["compiled"] or set(manifest["files"]) != INPUTS:
        raise ValueError("Only clean plaintext source manifests may be compiled")
    if set(p.name for p in folder.iterdir()) != INPUTS | {"manifest.json"}:
        raise ValueError("Source directory contains extra files, possibly old compiled/user data")
    hashes = {name: sha((folder / name).read_bytes()) for name in sorted(INPUTS)}
    if hashes != manifest["files"] or sha(encode(hashes)) != manifest["source_id"]:
        raise ValueError("Source content differs from manifest")
    return manifest


def check_apk(apk, source, manifest, libraries):
    with zipfile.ZipFile(apk) as archive:
        assets = {name.removeprefix("assets/rime/") for name in archive.namelist() if name.startswith("assets/rime/")}
        if assets != INPUTS | {"manifest.json"}:
            raise ValueError("Compiler APK must contain exactly the public plaintext sources")
        for name in assets:
            if archive.read("assets/rime/" + name) != (source / name).read_bytes():
                raise ValueError("Rebuild compiler APK: packaged source differs from selected source")
        native = {}
        for name in ("libvime_rime.so", "libc++_shared.so"):
            data = archive.read("lib/x86_64/" + name)
            if data != (libraries / name).read_bytes():
                raise ValueError("Compiler native code differs from the product's pinned library")
            native[name] = sha(data)
        if any(name.startswith("lib/") and not name.startswith("lib/x86_64/") for name in archive.namelist()):
            raise ValueError("The isolated compiler must be emulator-only")
    return native


def run(args):
    repo = Path(__file__).resolve().parents[1]
    source = args.sources.resolve()
    manifest = check_source(source)
    libraries = repo / "app/src/main/jniLibs/x86_64"
    native = check_apk(args.apk, source, manifest, libraries)
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Use a fresh output directory; prior evidence is preserved")
    base = [str(args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")), "-s", args.device]

    def command(argv):
        completed = subprocess.run([str(v) for v in argv], capture_output=True)
        if completed.returncode:
            raise RuntimeError((completed.stdout + completed.stderr).decode("utf-8", "replace")[-2000:])
        return completed.stdout

    def adb(*argv): return command(base + list(argv))
    def prop(name): return adb("shell", "getprop", name).decode().strip()
    def setprop(name, value): adb("shell", "setprop " + name + " " + shlex.quote(value))

    # Refuse physical devices and unrelated emulators before any install/mutation.
    if args.device != "emulator-5580" or prop("ro.kernel.qemu") != "1" or prop("sys.boot_completed") != "1":
        raise ValueError("Use the explicitly owned, fully booted emulator-5580")
    if adb("shell", "getconf", "PAGE_SIZE").strip() != b"16384":
        raise ValueError("A real 16KB userspace is required")
    aapt = args.sdk / "build-tools/36.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
    badging = command([aapt, "dump", "badging", args.apk]).decode("utf-8", "replace")
    if "package: name='" + PACKAGE + "'" not in badging:
        raise ValueError("Isolated compiler APK required; never install a product APK here")
    output.mkdir(parents=True)
    receipt = {"format": "vime-isolated-rime-compile-1", "passed": False, "device": args.device,
               "page_size": 16384, "source_id": manifest["source_id"], "native_sha256": native,
               "compiler_apk_sha256": sha(args.apk.read_bytes()), "product_app_changed": False, "runs": []}
    original = {}
    try:
        original = {name: prop(name) for name in COMPAT_OFF}
        receipt["compat_before"] = original
        for name, value in COMPAT_OFF.items(): setprop(name, value)
        receipt["compat_during"] = {name: prop(name) for name in COMPAT_OFF}
        if receipt["compat_during"] != COMPAT_OFF:
            raise RuntimeError("Cannot disable/read back the 16KB compatibility fallback")
        command(base + ["install", "-r", "-t", str(args.apk)])
        for attempt in ("a", "b"):
            run_id = "compile_" + attempt + "_" + uuid.uuid4().hex[:16]
            log = output / ("run-" + attempt + ".instrumentation.log")
            argv = base + ["shell", "am", "instrument", "-w", "-e", "runId", run_id, PACKAGE + "/" + RUNNER]
            with log.open("wb") as stream:
                process = subprocess.Popen(argv, stdout=stream, stderr=subprocess.STDOUT)
                (output / "active-process.json").write_bytes(encode({"pid": process.pid, "run_id": run_id, "argv": argv}))
                print("Observing isolated compiler " + run_id + " pid=" + str(process.pid), flush=True)
                code = process.wait()
            text = log.read_text("utf-8", errors="replace")
            match = re.search(r"^INSTRUMENTATION_RESULT: report=(\{.*\})\s*$", text, re.M)
            status = re.search(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.M)
            if code or not match or not status:
                raise RuntimeError("Compiler exited without an actual result Bundle; inspect preserved log")
            envelope = json.loads(match[1])
            if envelope.get("run_id") != run_id or not re.fullmatch(r"[a-f0-9]{64}", envelope.get("report_sha256", "")):
                raise RuntimeError("No authenticated compiler report reference")
            report_bytes = adb("exec-out", "run-as", PACKAGE, "cat", "files/runs/" + run_id + "/report.json")
            if sha(report_bytes) != envelope["report_sha256"] or len(report_bytes) != envelope["report_bytes"]:
                raise RuntimeError("Compiler report was truncated or changed")
            (output / ("run-" + attempt + ".report.json")).write_bytes(report_bytes)
            report = json.loads(report_bytes)
            receipt["runs"].append(report)
            if int(status[1]) != -1 or not report.get("passed"):
                raise RuntimeError("Compiler failed: " + report.get("error", "unknown"))
            if report["source_id"] != manifest["source_id"] or report["run_id"] != run_id or report["process_page_size"] != 16384:
                raise RuntimeError("Compiler environment or source identity differs")
            if set(report["outputs"]) != OUTPUTS:
                raise RuntimeError("Unexpected public output set")
            run_output = output / ("run-" + attempt)
            run_output.mkdir()
            for name in sorted(OUTPUTS):
                data = adb("exec-out", "run-as", PACKAGE, "cat", "files/runs/" + run_id + "/user/build/" + name)
                expected = report["outputs"][name]
                if sha(data) != expected["sha256"] or len(data) != expected["bytes"]:
                    raise RuntimeError("Export hash mismatch " + name)
                (run_output / name).write_bytes(data)
            print("Compiled and probed run " + attempt + " in " + str(report["compile_ms"]) + "ms", flush=True)
        first, second = receipt["runs"]
        receipt["public_outputs_byte_reproducible"] = first["outputs"] == second["outputs"]
        if not receipt["public_outputs_byte_reproducible"]:
            raise RuntimeError("Independent fresh compiler runs produced different bytes; preserve and investigate")
        final = output / "compiled-rime"
        shutil.copytree(source, final)
        (final / "build").mkdir()
        for name in sorted(OUTPUTS): shutil.copyfile(output / "run-a" / name, final / "build" / name)
        manifest["files"].update({"build/" + name: first["outputs"][name]["sha256"] for name in sorted(OUTPUTS)})
        manifest.update(compiled=True, dataset_id=sha(encode(dict(sorted(manifest["files"].items())))),
                        compiler={"rime_version": first["rime_version"], "native_sha256": native, "page_size": 16384,
                                  "fresh_runs": 2, "public_outputs_byte_reproducible": True, "private_user_data_exported": False})
        (final / "manifest.json").write_bytes(encode(manifest))
        receipt.update(passed=True, dataset_id=manifest["dataset_id"], compiled_manifest_sha256=sha((final / "manifest.json").read_bytes()))
    except Exception as error:
        receipt["error"] = str(error)
    finally:
        if original:
            errors = []
            for name, value in original.items():
                try: setprop(name, value)
                except Exception as error: errors.append(str(error))
            try:
                after = {name: prop(name) for name in original}
                receipt["compat_after"] = after
                if after != original: errors.append("Compatibility properties were not restored")
            except Exception as error: errors.append(str(error))
            receipt["compat_restored"] = not errors
            if errors: receipt.update(passed=False, restore_errors=errors)
        (output / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2), "utf-8")
        if (output / "active-process.json").exists():
            (output / "active-process.json").rename(output / "last-process.json")
    print(json.dumps({k: receipt.get(k) for k in ("passed", "dataset_id", "public_outputs_byte_reproducible", "error")}), flush=True)
    return 0 if receipt["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    raise SystemExit(run(parser.parse_args()))
