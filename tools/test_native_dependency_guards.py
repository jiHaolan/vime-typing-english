"""Exercise real bad native inputs against the source dependency verifier.

Copies only source/prefix/compile evidence to a fresh external directory. The
authoritative build and APK files are never mutated or rebuilt by these checks.
"""
import argparse
import copy
import json
from pathlib import Path
import shutil

from build_native_engine import sha
from verify_native_dependencies import verify


def run(args):
    if args.output.exists():
        raise ValueError("Use a fresh external evidence directory")
    verify(args.build, args.lock, args.sdk)
    args.output.mkdir(parents=True)
    clone = args.output / "fixture"
    original = json.loads((args.build / "receipt.json").read_bytes())
    protected = {p: sha(p) for p in args.build.rglob("*") if p.is_file() and
                 (p.is_relative_to(args.build / "prefix") or p.name == "receipt.json")}
    for name in ("sources", "prefix"):
        shutil.copytree(args.build / name, clone / name)
    for p in (args.build / "build").rglob("compile_commands.json"):
        dest = clone / p.relative_to(args.build)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, dest)
    receipt_file = clone / "receipt.json"
    cases = []

    def reject(name, receipt, change=None, restore=None):
        receipt_file.write_text(json.dumps(receipt), "utf-8")
        if change:
            change()
        rejected = False
        try:
            verify(clone, args.lock, args.sdk)
        except ValueError as error:
            rejected = True
            reason = str(error)
        finally:
            if restore:
                restore()
        if not rejected:
            raise AssertionError("Bad native input accepted: " + name)
        cases.append({"name": name, "passed": True, "rejected_reason": reason})

    bad = copy.deepcopy(original)
    del bad["builds"]["lua/armeabi-v7a"]
    reject("missing-one-ABI-dependency", bad)
    bad = copy.deepcopy(original)
    bad["provider_binary_inputs_used"] = True
    reject("provider-archive-receipt", bad)
    bad = copy.deepcopy(original)
    bad["sources"]["leveldb"]["commit"] = "0" * 40
    reject("different-upstream-commit", bad)
    header = clone / "prefix/lua/arm64-v8a/include/lua.h"
    content = header.read_bytes()
    reject("changed-installed-header", original,
           lambda: header.write_bytes(content + b"\n/* tampered */\n"), lambda: header.write_bytes(content))
    source_parent = clone / "sources/leveldb"
    source = next(source_parent.iterdir()) / "db/db_impl.cc"
    content = source.read_bytes()
    reject("changed-native-source-snapshot", original,
           lambda: source.write_bytes(content + b"\n/* tampered */\n"), lambda: source.write_bytes(content))
    # Recompute the manifest digests, so this case must inspect actual ELF
    # machines rather than passing because the caller supplied matching hashes.
    target = clone / "prefix/leveldb/arm64-v8a/lib/libleveldb.a"
    content = target.read_bytes()
    swapped = (clone / "prefix/leveldb/x86_64/lib/libleveldb.a").read_bytes()
    bad = copy.deepcopy(original)
    target.write_bytes(swapped)
    meta = {"bytes": len(swapped), "sha256": sha(target)}
    bad["installed_files"]["leveldb/arm64-v8a/lib/libleveldb.a"] = meta
    bad["builds"]["leveldb/arm64-v8a"].update(meta)
    reject("wrong-machine-with-valid-content-digests", bad, restore=lambda: target.write_bytes(content))
    receipt_file.write_text(json.dumps(original), "utf-8")
    verify(clone, args.lock, args.sdk)
    unchanged = all(sha(p) == value for p, value in protected.items())
    if not unchanged:
        raise AssertionError("Authoritative build changed during negative cases")
    result = {"passed": True, "negative_cases_passed": len(cases), "cases": cases,
              "restored_clone_verified": True, "authoritative_build_unchanged": unchanged,
              "physical_phone_used": False}
    (args.output / "receipt.json").write_text(json.dumps(result, indent=2) + "\n", "utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build", "lock", "sdk", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    print(json.dumps(run(parser.parse_args())))
