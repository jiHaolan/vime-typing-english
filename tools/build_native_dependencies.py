"""Build all six pinned Android dependencies from authenticated source archives.

Requires Python3.12 and the pinned SDK tools; no provider archives, downloads,
host package discovery or runtime dictionary compilation are used. Output must
be a fresh directory. This receipt proves a build, not distribution compliance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from build_native_engine import ABIS, extract_source, sha


# OpenCC's upstream build tries to run target-Android dictionary generators on
# the build host and links an unqualified marisa target. Change only CMake glue:
# use our source-built Marisa package, omit host data/CLI targets, retain the
# complete runtime library. Existing dictionary assets are built separately.
OPENCC_CMAKE_EDITS = {
    "CMakeLists.txt": [
        ('''  find_library(LIBMARISA NAMES marisa)
  if (LIBMARISA)
    message(STATUS "libmarisa found: ${LIBMARISA}")
  else()
    message(FATAL_ERROR "libmarisa not found.")
  endif()''', "  find_package(Marisa CONFIG REQUIRED)"),
        ("add_subdirectory(data)", "# Runtime-only cross build: dictionaries are separate inputs."),
    ],
    "src/CMakeLists.txt": [
        ("target_link_libraries(libopencc marisa)", "target_link_libraries(libopencc Marisa::marisa)"),
        ("add_subdirectory(tools)", "# Runtime-only cross build: do not install target CLI tools."),
    ],
}

RECIPES = (
    ("glog", "glog", "glog", "libglog.a", [
        "-DWITH_GFLAGS=OFF", "-DWITH_GTEST=OFF", "-DWITH_UNWIND=none"]),
    ("leveldb", "leveldb", "leveldb", "libleveldb.a", [
        "-DLEVELDB_BUILD_TESTS=OFF", "-DLEVELDB_BUILD_BENCHMARKS=OFF",
        "-DHAVE_CRC32C=0", "-DHAVE_SNAPPY=0", "-DHAVE_TCMALLOC=0"]),
    ("lua", "Lua", "lua_static", "liblua_static.a", [
        "-DLUA_BUILD_BINARY=OFF", "-DLUA_BUILD_COMPILER=OFF",
        "-DLUA_ENABLE_SHARED=OFF", "-DLUA_ENABLE_TESTING=OFF", "-DLUA_SUPPORT_DL=ON"]),
    ("marisa", "marisa-trie", "marisa", "libmarisa.a", [
        "-DENABLE_TOOLS=OFF", "-DENABLE_NATIVE_CODE=OFF"]),
    ("opencc", "OpenCC", "libopencc", "libopencc.a", [
        "-DBUILD_DOCUMENTATION=OFF", "-DBUILD_PYTHON=OFF", "-DENABLE_GTEST=OFF",
        "-DENABLE_BENCHMARK=OFF", "-DENABLE_DARTS=OFF", "-DUSE_SYSTEM_MARISA=ON",
        "-DSHARE_INSTALL_PREFIX=share"]),
    ("yaml-cpp", "yaml-cpp", "yaml-cpp", "libyaml-cpp.a", [
        "-DYAML_CPP_BUILD_CONTRIB=OFF", "-DYAML_CPP_BUILD_TESTS=OFF",
        "-DYAML_CPP_BUILD_TOOLS=OFF", "-DYAML_CPP_FORMAT_SOURCE=OFF"]),
)
MACHINES = {"arm64-v8a": "EM_AARCH64", "armeabi-v7a": "EM_ARM", "x86_64": "EM_X86_64"}
NATIVE_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx"}


def native_files(root):
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(root.rglob("*"))
            if p.is_file() and p.suffix in NATIVE_SUFFIXES}


def build(args):
    lock_bytes = args.lock.read_bytes()
    lock = json.loads(lock_bytes)
    if lock["format"] != "vime-native-sources-1":
        raise ValueError("Unsupported source lock")
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Use a fresh output directory; preserve earlier evidence")
    sources = {row["id"]: row for row in lock["sources"]}
    for _, source_id, *_ in RECIPES:
        path = args.archives / (source_id + ".tar.gz")
        expected = sources[source_id]
        if path.stat().st_size != expected["archive_bytes"] or sha(path) != expected["archive_sha256"]:
            raise ValueError("Pinned source archive differs: " + source_id)
    sdk = args.sdk.resolve()
    ndk = sdk / "ndk/28.0.13004108"
    cmake = sdk / "cmake/3.31.6/bin/cmake.exe"
    ninja = sdk / "cmake/3.31.6/bin/ninja.exe"
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    ar = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe"
    readobj = ar.with_name("llvm-readobj.exe")
    tool_files = [cmake, ninja, toolchain, ar, readobj, ndk / "source.properties"]
    for p in tool_files:
        if not p.is_file():
            raise ValueError("Required pinned SDK tool missing: " + str(p))
    if "Pkg.Revision = 28.0.13004108" not in (ndk / "source.properties").read_text("utf-8"):
        raise ValueError("NDK revision differs")
    output.mkdir(parents=True)
    (output / "sources").mkdir()
    (output / "tmp").mkdir()
    environment = os.environ | {"TEMP": str(output / "tmp"), "TMP": str(output / "tmp")}
    receipt = {"format": "vime-native-dependency-build-1", "passed": False,
               "source_lock_sha256": hashlib.sha256(lock_bytes).hexdigest(),
               "builder_sha256": sha(Path(__file__)), "provider_binary_inputs_used": False,
               "upstream_cpp_sources_changed": False, "sources": {}, "cmake_adaptations": {},
               "toolchain_files": {str(p): sha(p) for p in tool_files},
               "android_api": 24, "stl": "c++_shared", "abis": list(ABIS), "builds": {},
               "installed_files": {}, "runtime_verified": False,
               "historical_distribution_review_complete": False}

    def run(command, log):
        with log.open("wb") as stream:
            process = subprocess.run([str(v) for v in command], env=environment, cwd=output,
                                     stdout=stream, stderr=subprocess.STDOUT)
        if process.returncode:
            raise RuntimeError("Build failed; inspect " + str(log))

    try:
        roots = {}
        for package, source_id, *_ in RECIPES:
            source = extract_source(args.archives / (source_id + ".tar.gz"), output / "sources" / package)
            roots[package] = source
            receipt["sources"][package] = {"source_id": source_id, **sources[source_id],
                                              "native_files_before": native_files(source)}
        for name, edits in OPENCC_CMAKE_EDITS.items():
            path = roots["opencc"] / name
            before = sha(path)
            text = path.read_text("utf-8")
            for old, new in edits:
                if text.count(old) != 1:
                    raise ValueError("Pinned OpenCC CMake context differs: " + name)
                text = text.replace(old, new)
            path.write_text(text, encoding="utf-8", newline="\n")
            receipt["cmake_adaptations"][name] = {"before_sha256": before, "after_sha256": sha(path)}
        for abi in ABIS:
            for package, source_id, target, archive_name, recipe_flags in RECIPES:
                source = roots[package]
                work = output / "build" / package / abi
                prefix = output / "prefix" / package / abi
                log_root = output / "logs" / package / abi
                log_root.mkdir(parents=True)
                flags = ["-DCMAKE_MAKE_PROGRAM=" + ninja.as_posix(),
                         "-DCMAKE_TOOLCHAIN_FILE=" + toolchain.as_posix(),
                         "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_shared",
                         "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF", "-DBUILD_TESTING=OFF",
                         "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DCMAKE_INSTALL_LIBDIR=lib",
                         "-DCMAKE_INSTALL_PREFIX=" + prefix.as_posix(), "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                         "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF", "-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF",
                         "-DCMAKE_FIND_USE_PACKAGE_ROOT_PATH=OFF", "-DFETCHCONTENT_FULLY_DISCONNECTED=ON",
                         "-DCMAKE_C_FLAGS=-ffile-prefix-map=" + source.as_posix() + "=.",
                         "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=" + source.as_posix() + "=.", *recipe_flags]
                if package == "opencc":
                    marisa = output / "prefix/marisa" / abi
                    flags += ["-DCMAKE_FIND_ROOT_PATH=" + marisa.as_posix(),
                              "-DLIB_INSTALL_DIR=" + (prefix / "lib").as_posix(),
                              "-DMarisa_DIR=" + (marisa / "lib/cmake/Marisa").as_posix()]
                run([cmake, "-S", source, "-B", work, "-G", "Ninja", *flags], log_root / "configure.log")
                run([cmake, "--build", work, "--target", target, "-j", "4"], log_root / "build.log")
                run([cmake, "--install", work], log_root / "install.log")
                installed = (work / "install_manifest.txt").read_text("utf-8").splitlines()
                if not installed or not all(Path(name).resolve().is_relative_to(prefix) for name in installed):
                    raise ValueError("Dependency installed outside its dedicated prefix")
                archive = prefix / "lib" / archive_name
                members = subprocess.check_output([str(ar), "t", str(archive)], env=environment).decode().splitlines()
                headers_log = log_root / "archive-headers.txt"
                run([readobj, "--file-headers", archive], headers_log)
                machines = re.findall(r"Machine: (\w+)", headers_log.read_text("utf-8"))
                if not members or len(machines) != len(members) or set(machines) != {MACHINES[abi]}:
                    raise ValueError("Wrong or incomplete archive object architecture: " + str(archive))
                commands = json.loads((work / "compile_commands.json").read_bytes())
                if not commands or not all(Path(cmd["file"]).resolve().is_relative_to(source) or
                                           Path(cmd["file"]).resolve().is_relative_to(work) for cmd in commands):
                    raise ValueError("Compilation source outside authenticated source/build directory")
                key = package + "/" + abi
                receipt["builds"][key] = {"source_id": source_id, "target": target,
                    "archive": archive.relative_to(output / "prefix").as_posix(),
                    "sha256": sha(archive), "bytes": archive.stat().st_size, "members": len(members),
                    "elf_machine": MACHINES[abi], "compile_commands": len(commands),
                    "compile_commands_sha256": sha(work / "compile_commands.json"),
                    "cmake_flags": flags, "build_passed": True}
                print(key + " built from source (" + str(len(members)) + " objects)", flush=True)
        for package in roots:
            unchanged = receipt["sources"][package]["native_files_before"] == native_files(roots[package])
            receipt["sources"][package]["native_sources_unchanged"] = unchanged
            if not unchanged:
                raise ValueError("Upstream native source changed: " + package)
        prefix_root = output / "prefix"
        receipt["installed_files"] = {p.relative_to(prefix_root).as_posix():
            {"bytes": p.stat().st_size, "sha256": sha(p)} for p in sorted(prefix_root.rglob("*")) if p.is_file()}
        if len(receipt["builds"]) != 18 or any(p.endswith(".so") for p in receipt["installed_files"]):
            raise ValueError("All18 static dependency builds required")
        receipt["passed"] = True
    finally:
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", "utf-8")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lock", "archives", "sdk", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    result = build(parser.parse_args())
    print(json.dumps({"passed": result["passed"], "builds": len(result["builds"]),
                      "installed_files": len(result["installed_files"]), "runtime_verified": False}))
