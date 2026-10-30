"""Build the pinned upstream public Rime table exporter for the owned emulator.

Uses the actual pinned C++ headers and Boost dependencies, not reconstructed ABI
declarations. The main product's native libraries are only read and hash-checked.
All temporary/compiler output goes to the explicitly selected output directory.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile

RIME = "de4700e9f6b75b109910613df907965e3cbe0567"
BOOST_SHA = "aca59f889f0f32028ad88ba6764582b63c916ce5f77b31289ad19421a96c555f"
BOOST_URL = "https://github.com/boostorg/boost/releases/download/boost-1.90.0/boost-1.90.0-cmake.tar.xz"
PREBUILDER = "d7774b67a46237ed53aae913c7f02ddb367f647c"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def build(args):
    repo = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.exists(): raise ValueError("Preserve earlier builds; use a fresh output")
    if digest(args.boost_archive) != BOOST_SHA: raise ValueError("Boost archive differs from pinned prebuilder recipe")
    provenance = json.loads((repo / "native/provenance.json").read_bytes())
    if provenance["librime"]["commit"] != RIME: raise ValueError("Review exporter ABI for the new librime version")
    libraries = repo / "app/src/main/jniLibs/x86_64"
    for name in ("libvime_rime.so", "libc++_shared.so"):
        key = "app/src/main/jniLibs/x86_64/" + name
        if digest(libraries / name) != provenance["files"][key]: raise ValueError("Product library hash mismatch")
    source = subprocess.check_output(["git", "-C", str(args.librime), "archive", "--format=tar", RIME,
                                      "src/rime", "tools/rime_table_decompiler.cc", "tools/codepage.h", "LICENSE"])
    output.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(source)) as archive:
        archive.extractall(output / "rime", filter="data")
    with tarfile.open(args.boost_archive) as archive:
        members = [m for m in archive.getmembers() if m.isfile() and
                   ("/include/" in m.name or m.name == "boost-1.90.0/LICENSE_1_0.txt")]
        archive.extractall(output / "boost", members=members, filter="data")
    boost = output / "boost/boost-1.90.0"
    includes = sorted((boost / "libs").glob("*/include"))
    if len(includes) < 100: raise ValueError("Incomplete Boost header archive")
    generated = output / "generated/rime"
    generated.mkdir(parents=True)
    (generated / "build_config.h").write_bytes(b"#pragma once\n#define RIME_ENABLE_LOGGING\n")
    ndk = args.sdk / "ndk/28.0.13004108"
    toolchain = ndk / "toolchains/llvm/prebuilt/windows-x86_64"
    compiler = toolchain / "bin/clang++.exe"
    binary = output / "rime_table_decompiler"
    include_dirs = [output / "generated", output / "rime/src", args.prebuilt / "librime/x86_64/include",
                    args.prebuilt / "marisa/x86_64/include", args.prebuilt / "glog/x86_64/include", *includes]
    flags = ["--target=x86_64-linux-android24", "--sysroot=" + (toolchain / "sysroot").as_posix(), "-std=c++17",
             "-O2", "-fPIE", "-pie", "-DBOOST_DISABLE_CURRENT_LOCATION", "-DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS",
             "-DGLOG_USE_GLOG_EXPORT"]
    flags += ["-I" + p.as_posix() for p in include_dirs]
    flags += [(output / "rime/tools/rime_table_decompiler.cc").as_posix(), "-L" + libraries.as_posix(),
              "-lvime_rime", "-nostdlib++", (toolchain / "sysroot/usr/lib/x86_64-linux-android/libc++_shared.so").as_posix(),
              "-llog", "-landroid", "-lm", "-Wl,-z,max-page-size=16384", "-Wl,-rpath,$ORIGIN",
              "-o", binary.as_posix()]
    # Clang response-file quoting, never shell interpolation.
    response = output / "compiler.rsp"
    response.write_text("\n".join('"' + flag.replace('"', '\\"') + '"' for flag in flags), "utf-8")
    temp = output / "tmp"
    temp.mkdir()
    environment = dict(os.environ, TEMP=str(temp), TMP=str(temp), TMPDIR=str(temp))
    process = subprocess.run([str(compiler), "@" + str(response)], capture_output=True, env=environment)
    (output / "build.log").write_bytes(process.stdout + process.stderr)
    if process.returncode: raise RuntimeError("Exporter compilation failed; inspect " + str(output / "build.log"))
    receipt = {"format": "vime-public-table-exporter-build-1", "passed": True, "rime_commit": RIME,
               "rime_canonical_archive_sha256": hashlib.sha256(source).hexdigest(),
               "upstream_tool_sha256": digest(output / "rime/tools/rime_table_decompiler.cc"),
               "tool_source_modified": False, "abi": "x86_64", "ndk": "28.0.13004108", "max_page_size": 16384,
               "boost_version": "1.90.0", "boost_archive_sha256": BOOST_SHA, "boost_url": BOOST_URL,
               "prebuilder_commit": PREBUILDER, "binary_sha256": digest(binary),
               "libraries_sha256": {name: digest(libraries / name) for name in ("libvime_rime.so", "libc++_shared.so")}}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2), "utf-8")
    print(json.dumps({"passed": True, "binary": str(binary), "binary_sha256": receipt["binary_sha256"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sdk", "librime", "prebuilt", "boost-archive", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    build(parser.parse_args())
