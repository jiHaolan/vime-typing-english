"""Compare logical Rime records in immutable before/after emulator snapshots.

The native reader opens newly pushed copies, never a live product database.
LevelDB may compact/rename .ldb/.sst tables, so byte-identical table filenames
are not an adequate persistence invariant. Raw records remain external/private.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import uuid


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify(args):
    if args.output.exists():
        raise ValueError("Preserve earlier userdb evidence")
    base = [str(args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")), "-s", args.device]
    def adb(*command):
        return subprocess.check_output(base + [str(value) for value in command], stderr=subprocess.STDOUT, timeout=60)
    if args.device != "emulator-5580" or adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise ValueError("Owned emulator required; never reads phone databases")
    if adb("shell", "getprop", "ro.product.cpu.abi").strip() != b"x86_64":
        raise ValueError("This reader fixture requires the owned x86_64 emulator")
    before = args.before / "files/rime/user"
    after = args.after / "files/rime/user"
    names = sorted(path.relative_to(before).as_posix() for path in before.rglob("*.userdb") if path.is_dir())
    if not names:
        raise ValueError("A real preexisting native user database is required")
    for folder in (before, after):
        for path in folder.rglob("*"):
            if path.is_symlink() or not path.resolve().is_relative_to(folder.resolve()):
                raise ValueError("Linked snapshot path is not accepted")
    args.output.mkdir(parents=True)
    remote = "/data/local/tmp/vime-userdb-audit-" + uuid.uuid4().hex
    adb("shell", "mkdir", "-m", "700", remote)
    adb("push", args.reader, remote + "/reader")
    adb("push", args.runtime, remote + "/libc++_shared.so")
    adb("shell", "chmod", "700", remote + "/reader")
    reader_sha = sha(args.reader)
    rows = []
    for index, name in enumerate(names):
        if not re.fullmatch(r"[A-Za-z0-9_./-]+\.userdb", name) or not (after / name).is_dir():
            raise ValueError("Native database removed or unexpected snapshot path")
        records = []
        for label, root in (("before", before), ("after", after)):
            destination = remote + "/" + label + "-" + str(index)
            source = root / name
            original = {path.relative_to(source).as_posix(): sha(path) for path in source.rglob("*") if path.is_file()}
            adb("push", source, destination)
            command = "LD_LIBRARY_PATH=" + shlex.quote(remote) + " " + shlex.quote(remote + "/reader") + " " + shlex.quote(destination)
            data = adb("shell", command).replace(b"\r\n", b"\n")
            (args.output / (label + "-" + str(index) + "-private-records.tsv")).write_bytes(data)
            table = {}
            for line in data.splitlines():
                key, value = (bytes.fromhex(part.decode("ascii")) for part in line.split(b"\t"))
                if key in table: raise ValueError("Duplicate logical database record")
                table[key] = value
            if original != {path.relative_to(source).as_posix(): sha(path) for path in source.rglob("*") if path.is_file()}:
                raise ValueError("Original snapshot mutated")
            records.append(table)
        old, new = records
        if not old or not old.keys() <= new.keys():
            raise ValueError("Existing native records lost during upgrade")
        changed = 0
        for key, value in old.items():
            current = new[key]
            if value == current: continue
            changed += 1
            # Rime user_db.cc packs learned values as c=<commits> d=<weight>
            # t=<tick>. Queries in the native upgrade checks legitimately learn
            # an existing word, so ticks/commits may increase during the run.
            def unpack(raw):
                text = raw.decode("ascii")
                fields = dict(part.split("=", 1) for part in text.split())
                if set(fields) != {"c", "d", "t"}: raise ValueError("Unknown changed native record format")
                return int(fields["c"]), float(fields["d"]), int(fields["t"])
            if key == b"\x01/tick":
                if int(current) < int(value): raise ValueError("Native database tick regressed")
            else:
                allowed = getattr(args, "allowed_word", None) or ["你好"]
                if not any(key.endswith(word.encode("utf-8")) for word in allowed):
                    raise ValueError("Unexpected changed learned record outside the test's explicit selections")
                a, b = unpack(value), unpack(current)
                if b[0] < a[0] or b[2] < a[2] or not math.isfinite(b[1]):
                    raise ValueError("Learned native record regressed")
        rows.append({"database": name, "before_logical_records": len(old), "after_logical_records": len(new),
                     "before_learned_records": sum(not key.startswith(b"\x01") for key in old),
                     "all_preexisting_keys_retained": True, "changed_records_with_monotonic_learning": changed,
                     "before_table_files": sum(path.suffix in {".ldb", ".sst"} for path in (before / name).rglob("*")),
                     "after_table_files": sum(path.suffix in {".ldb", ".sst"} for path in (after / name).rglob("*"))})
    if not re.fullmatch(r"/data/local/tmp/vime-userdb-audit-[a-f0-9]{32}", remote):
        raise ValueError("Unexpected reader-owned temporary directory")
    adb("shell", "rm", "-rf", "--", remote)
    result = {"passed": True, "format": "vime-native-userdb-semantic-check-1", "databases": rows,
              "reader_sha256": reader_sha, "snapshots_unchanged": True, "live_product_database_opened": False,
              "expected_test_learning_updates_only": True, "remote_snapshot_copies_removed": True,
              "physical_phone_used": False, "raw_record_files_are_private_and_not_for_public_source_archives": True}
    (args.output / "receipt.json").write_text(json.dumps(result, indent=2) + "\n", "utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sdk", "reader", "runtime", "before", "after", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--allowed-word", action="append", help="Explicit native selections made by the upgrade test; default:你好")
    print(json.dumps(verify(parser.parse_args())))
