"""Export only the clean public Rime table twice; never access product user data."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

from compile_clean_dictionary import COMPAT_OFF, INPUTS, OUTPUTS, encode, sha
from build_dictionary_exporter import RIME, digest


def export(args):
    repo = Path(__file__).resolve().parents[1]
    manifest = json.loads((args.compiled / "manifest.json").read_bytes())
    expected_names = INPUTS | {"build/" + name for name in OUTPUTS}
    if not manifest["compiled"] or set(manifest["files"]) != expected_names:
        raise ValueError("Clean public compiled dataset required")
    if sha(encode(dict(sorted(manifest["files"].items())))) != manifest["dataset_id"]:
        raise ValueError("Compiled dataset identity mismatch")
    for name, expected in manifest["files"].items():
        if digest(args.compiled / name) != expected: raise ValueError("Compiled input hash mismatch")
    build = json.loads((args.tool / "receipt.json").read_bytes())
    if not build["passed"] or build["rime_commit"] != RIME or build["tool_source_modified"]:
        raise ValueError("Pinned unmodified upstream exporter required")
    binary = args.tool / "rime_table_decompiler"
    if digest(binary) != build["binary_sha256"]: raise ValueError("Exporter binary changed")
    libraries = repo / "app/src/main/jniLibs/x86_64"
    for name, expected in build["libraries_sha256"].items():
        if name not in ("libvime_rime.so", "libc++_shared.so") or digest(libraries / name) != expected:
            raise ValueError("Exporter native library changed")
    output = args.output.resolve()
    if output.exists(): raise ValueError("Preserve previous export evidence")
    base = [str(args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")), "-s", args.device]
    def command(argv):
        process = subprocess.run([str(v) for v in argv], capture_output=True)
        if process.returncode: raise RuntimeError((process.stdout + process.stderr).decode("utf-8", "replace")[-2000:])
        return process.stdout
    def adb(*argv): return command(base + list(argv))
    def prop(name): return adb("shell", "getprop", name).decode().strip()
    def setprop(name, value): adb("shell", "setprop " + name + " " + shlex.quote(value))
    if args.device != "emulator-5580" or prop("ro.kernel.qemu") != "1" or prop("sys.boot_completed") != "1":
        raise ValueError("Use the explicitly owned emulator-5580; physical devices are never changed")
    page = int(adb("shell", "getconf", "PAGE_SIZE").strip())
    if page != 16384: raise ValueError("A real 16KB userspace is required")
    output.mkdir(parents=True)
    receipt = {"format": "vime-clean-rime-vocabulary-export-1", "passed": False, "device": args.device,
               "userspace_page_size": page, "dataset_id": manifest["dataset_id"], "source_id": manifest["source_id"],
               "table_sha256": manifest["files"]["build/vime_pinyin.table.bin"],
               "source_lock_sha256": manifest["source_lock_sha256"], "tool_build": build,
               "private_product_data_read": False, "product_apk_installed": False, "runs": []}
    original = {}
    try:
        original = {name: prop(name) for name in COMPAT_OFF}
        receipt["compat_before"] = original
        for name, value in COMPAT_OFF.items(): setprop(name, value)
        receipt["compat_during"] = {name: prop(name) for name in COMPAT_OFF}
        if receipt["compat_during"] != COMPAT_OFF: raise RuntimeError("Cannot disable/read back compatibility fallback")
        for attempt in ("a", "b"):
            remote = "/data/local/tmp/vime-public-dict-" + uuid.uuid4().hex
            adb("shell", "mkdir", remote)
            uploads = {"rime_table_decompiler": binary, "table.bin": args.compiled / "build/vime_pinyin.table.bin",
                       **{name: libraries / name for name in build["libraries_sha256"]}}
            for name, local in uploads.items():
                adb("push", local, remote + "/" + name)
                remote_hash = adb("shell", "sha256sum", remote + "/" + name).decode().split()[0]
                if remote_hash != digest(local): raise RuntimeError("Remote public input hash differs")
            adb("shell", "chmod", "700", remote + "/rime_table_decompiler")
            argv = base + ["shell", "LD_LIBRARY_PATH=" + remote + " " + remote + "/rime_table_decompiler " + remote + "/table.bin " + remote + "/vocabulary.tsv"]
            log = output / ("run-" + attempt + ".log")
            with log.open("wb") as stream:
                process = subprocess.Popen(argv, stdout=stream, stderr=subprocess.STDOUT)
                (output / "active-process.json").write_bytes(encode({"pid": process.pid, "argv": argv, "remote": remote}))
                print("Observing public table export " + attempt + " pid=" + str(process.pid), flush=True)
                code = process.wait()
            if code: raise RuntimeError("Native exporter failed; inspect " + str(log))
            text = log.read_text("utf-8", errors="strict")
            metadata = {name: int(value) for name, value in re.findall(r"(num_syllables|num_entries): (\d+)", text)}
            if set(metadata) != {"num_syllables", "num_entries"}: raise RuntimeError("No native table metadata")
            data = adb("exec-out", "cat", remote + "/vocabulary.tsv")
            remote_hash = adb("shell", "sha256sum", remote + "/vocabulary.tsv").decode().split()[0]
            if sha(data) != remote_hash: raise RuntimeError("Public vocabulary transfer was truncated or changed")
            (output / ("run-" + attempt + ".tsv")).write_bytes(data)
            body = data.decode("utf-8").partition("...\n")[2]
            rows = [line for line in body.splitlines() if line]
            if len(rows) != metadata["num_entries"]: raise RuntimeError("Exporter did not enumerate the complete public table")
            receipt["runs"].append({"remote": remote, "sha256": sha(data), "bytes": len(data), "rows": len(rows), **metadata})
            print("Exported " + str(len(rows)) + " public rows", flush=True)
        if receipt["runs"][0]["sha256"] != receipt["runs"][1]["sha256"]:
            raise RuntimeError("Repeated public table exports differ")
        receipt.update(passed=True, vocabulary_sha256=receipt["runs"][0]["sha256"], rows=receipt["runs"][0]["rows"],
                       exports_byte_identical=True)
    except Exception as error:
        receipt["error"] = str(error)
    finally:
        if original:
            restored = False
            errors = []
            # Restoring known properties is idempotent; a transient observation
            # failure never restarts the exporter or emulator.
            for attempt in range(3):
                try:
                    for name, value in original.items(): setprop(name, value)
                    after = {name: prop(name) for name in original}
                    receipt["compat_after"] = after
                    restored = after == original
                    if restored: break
                except Exception as error: errors.append(str(error))
                time.sleep(1)
            receipt["compat_restored"] = restored
            if errors: receipt["restore_observations"] = errors
            if not restored: receipt["passed"] = False
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2), "utf-8")
        if (output / "active-process.json").exists(): (output / "active-process.json").rename(output / "last-process.json")
    print(json.dumps({k: receipt.get(k) for k in ("passed", "rows", "vocabulary_sha256", "error")}), flush=True)
    return 0 if receipt["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sdk", "compiled", "tool", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--device", required=True)
    raise SystemExit(export(parser.parse_args()))
