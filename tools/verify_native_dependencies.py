"""Verify the actual source-built dependency tree before it can be linked."""
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import subprocess

from build_native_engine import ABIS, sha
from build_native_dependencies import MACHINES, RECIPES, native_files


def inside(root, relative):
    name = PurePosixPath(relative)
    if name.is_absolute() or ".." in name.parts or "\\" in relative or ":" in relative:
        raise ValueError("Unsafe dependency receipt path")
    path = root.joinpath(*name.parts)
    if not path.resolve().is_relative_to(root.resolve()) or path.is_symlink():
        raise ValueError("Dependency input escapes build root")
    return path


def verify(build, lock_path, sdk, receipt_path=None):
    receipt_path = receipt_path or build / "receipt.json"
    receipt = json.loads(receipt_path.read_bytes())
    lock = json.loads(lock_path.read_bytes())
    if (receipt["format"] != "vime-native-dependency-build-1" or receipt["passed"] is not True or
            receipt["source_lock_sha256"] != sha(lock_path) or
            receipt["builder_sha256"] != sha(Path(__file__).with_name("build_native_dependencies.py")) or
            receipt["provider_binary_inputs_used"] is not False or
            receipt["upstream_cpp_sources_changed"] is not False or
            receipt["android_api"] != 24 or receipt["stl"] != "c++_shared" or receipt["abis"] != list(ABIS)):
        raise ValueError("Dependency receipt does not match source build policy")
    ndk = sdk.resolve() / "ndk/28.0.13004108"
    cmake = sdk.resolve() / "cmake/3.31.6/bin/cmake.exe"
    expected_tools = [cmake, cmake.with_name("ninja.exe"), ndk / "build/cmake/android.toolchain.cmake",
                      ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe",
                      ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readobj.exe",
                      ndk / "source.properties"]
    if {str(p): sha(p) for p in expected_tools} != receipt["toolchain_files"]:
        raise ValueError("Pinned SDK tool evidence differs")
    expected_keys = {package + "/" + abi for package, *_ in RECIPES for abi in ABIS}
    if set(receipt["builds"]) != expected_keys:
        raise ValueError("All six dependencies and three ABIs are required")
    expected_sources = {package for package, *_ in RECIPES}
    if set(receipt["sources"]) != expected_sources:
        raise ValueError("Dependency source set differs")
    pinned = {row["id"]: row for row in lock["sources"]}
    for package, source_id, *_ in RECIPES:
        source = receipt["sources"][package]
        if (source["commit"] != pinned[source_id]["commit"] or
                source["archive_sha256"] != pinned[source_id]["archive_sha256"] or
                source["native_sources_unchanged"] is not True):
            raise ValueError("Dependency source differs: " + package)
        source_parent = build / "sources" / package
        children = list(source_parent.iterdir())
        if len(children) != 1 or not children[0].is_dir() or children[0].is_symlink():
            raise ValueError("Ambiguous source snapshot")
        if native_files(children[0]) != source["native_files_before"]:
            raise ValueError("Native source snapshot changed: " + package)
    prefix = build / "prefix"
    actual = {p.relative_to(prefix).as_posix() for p in prefix.rglob("*") if p.is_file()}
    if actual != set(receipt["installed_files"]):
        raise ValueError("Installed dependency file set differs")
    for name, meta in receipt["installed_files"].items():
        path = inside(prefix, name)
        if path.stat().st_size != meta["bytes"] or sha(path) != meta["sha256"] or path.suffix == ".so":
            raise ValueError("Installed dependency input changed: " + name)
    ar = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe"
    readobj = ar.with_name("llvm-readobj.exe")
    built = {}
    for package, source_id, target, archive, _ in RECIPES:
        for abi in ABIS:
            key = package + "/" + abi
            row = receipt["builds"][key]
            relative = key + "/lib/" + archive
            path = inside(prefix, relative)
            if (row["archive"] != relative or row["build_passed"] is not True or
                    row["source_id"] != source_id or row["target"] != target or
                    row["elf_machine"] != MACHINES[abi] or row["sha256"] != sha(path) or
                    row["bytes"] != path.stat().st_size):
                raise ValueError("Dependency archive receipt differs: " + key)
            members = subprocess.check_output([str(ar), "t", str(path)]).decode().splitlines()
            headers = subprocess.check_output([str(readobj), "--file-headers", str(path)]).decode()
            machines = re.findall(r"Machine: (\w+)", headers)
            if (not members or len(members) != row["members"] or len(machines) != len(members) or
                    set(machines) != {MACHINES[abi]}):
                raise ValueError("Actual dependency object architecture differs: " + key)
            commands = build / "build" / package / abi / "compile_commands.json"
            if sha(commands) != row["compile_commands_sha256"]:
                raise ValueError("Compile command evidence changed: " + key)
            built[key] = {"sha256": row["sha256"], "members": len(members), "machine": MACHINES[abi]}
    return {"passed": True, "receipt_sha256": sha(receipt_path), "source_lock_sha256": sha(lock_path),
            "actual_archives_verified": len(built), "installed_files_verified": len(actual),
            "all_upstream_native_sources_unchanged": True, "builds": built,
            "runtime_verified": False, "historical_distribution_review_complete": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build", "lock", "sdk", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    result = verify(args.build, args.lock, args.sdk, args.receipt)
    if args.output.exists():
        raise ValueError("Preserve prior verification evidence")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", "utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "builds"}))
