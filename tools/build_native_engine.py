"""Build the pinned Rime engine with an explicit plugin set, without provider librime.a.

The six dependency archives remain pinned to the provider's exact Git blobs.
This build is evidence of module selection, not a blanket licence approval.
Outputs are external, fresh directories; product binaries are never overwritten.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile


ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
LUA_BUILD = '''# Vime build adapter; upstream C/C++ sources are unchanged.
aux_source_directory(src RIME_LUA_SRC)
aux_source_directory(src/lib RIME_LUA_LIB_SRC)
add_library(rime-lua-objs OBJECT ${RIME_LUA_SRC} ${RIME_LUA_LIB_SRC})
target_include_directories(rime-lua-objs PRIVATE "${VIME_LUA_INCLUDE}")
set(plugin_name "rime-lua" PARENT_SCOPE)
set(plugin_objs $<TARGET_OBJECTS:rime-lua-objs> PARENT_SCOPE)
set(plugin_deps "${VIME_LUA_ARCHIVE}" ${rime_library} PARENT_SCOPE)
set(plugin_modules "lua" PARENT_SCOPE)
'''


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def extract_source(archive, destination):
    destination.mkdir()
    with tarfile.open(archive) as source:
        members = source.getmembers()
        roots = {PurePosixPath(m.name).parts[0] for m in members}
        if len(roots) != 1:
            raise ValueError("A single archive root is required")
        source.extractall(destination, filter="data")
    return destination / roots.pop()


def build(args):
    lock_bytes = args.lock.read_bytes()
    lock = json.loads(lock_bytes)
    if lock["format"] != "vime-native-sources-1" or lock["engine_plugins"] != ["lua", "predict"]:
        raise ValueError("Explicit reviewed Lua/Predict selection required")
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Use a fresh output directory; preserve previous evidence")
    sources = {row["id"]: row for row in lock["sources"]}
    for name in ("librime", "librime-lua", "librime-predict"):
        if sha(args.archives / (name + ".tar.gz")) != sources[name]["archive_sha256"]:
            raise ValueError("Pinned source archive differs: " + name)
    if sha(args.boost_archive) != lock["boost"]["sha256"]:
        raise ValueError("Pinned Boost source archive differs")
    for relative, expected in lock["dependency_archives"].items():
        path = args.prebuilt / relative
        if path.stat().st_size != expected["bytes"] or sha(path) != expected["sha256"]:
            raise ValueError("Pinned dependency differs: " + relative)
    for relative, expected in lock["dependency_headers"].items():
        if sha(args.prebuilt / relative) != expected["sha256"]:
            raise ValueError("Pinned dependency header differs: " + relative)
    output.mkdir(parents=True)
    source_root = extract_source(args.archives / "librime.tar.gz", output / "core-source")
    lua = extract_source(args.archives / "librime-lua.tar.gz", output / "lua-source")
    predict = extract_source(args.archives / "librime-predict.tar.gz", output / "predict-source")
    shutil.copytree(lua, source_root / "plugins/lua")
    shutil.copytree(predict, source_root / "plugins/predict")
    (source_root / "plugins/lua/CMakeLists.txt").write_text(LUA_BUILD, "utf-8")
    include = output / "boost-include"
    with tarfile.open(args.boost_archive) as source:
        for member in source:
            if not member.isfile() or "/include/boost/" not in member.name:
                continue
            relative = PurePosixPath("boost/" + member.name.split("/include/boost/", 1)[1])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Unsafe Boost header")
            path = include.joinpath(*relative.parts)
            content = source.extractfile(member).read()
            if path.exists() and path.read_bytes() != content:
                raise ValueError("Conflicting Boost header")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if not (include / "boost/version.hpp").is_file():
        raise ValueError("Missing complete Boost headers")
    print("Pinned source extraction and dependency authentication completed", flush=True)
    sdk = args.sdk.resolve()
    ndk = sdk / "ndk/28.0.13004108"
    cmake = sdk / "cmake/3.31.6/bin/cmake.exe"
    ninja = sdk / "cmake/3.31.6/bin/ninja.exe"
    ar = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe"
    environment = os.environ | {"RIME_PLUGINS": "lua predict", "TEMP": str(output / "tmp"), "TMP": str(output / "tmp")}
    (output / "tmp").mkdir()
    receipt = {"format": "vime-native-engine-build-1", "passed": False,
               "source_lock_sha256": hashlib.sha256(lock_bytes).hexdigest(),
               "rime_commit": sources["librime"]["commit"], "plugins": lock["engine_plugins"],
               "octagram_included": False, "upstream_cpp_sources_changed": False,
               "lua_cmake_adapter_sha256": hashlib.sha256(LUA_BUILD.encode()).hexdigest(),
               "dependency_archives": lock["dependency_archives"], "abis": {},
               "commercial_source_audit_complete": False}

    def run(command, log):
        with log.open("wb") as stream:
            process = subprocess.run([str(value) for value in command], env=environment, stdout=stream, stderr=subprocess.STDOUT)
        if process.returncode:
            raise RuntimeError("Build failed; inspect " + str(log))

    try:
        for abi in ABIS:
            work = output / abi
            prefix = args.prebuilt.resolve()
            flags = ["-DBUILD_SHARED_LIBS=OFF", "-DBUILD_STATIC=ON", "-DBUILD_TEST=OFF", "-DBUILD_TOOLS=OFF",
                     "-DBUILD_DATA=OFF", "-DENABLE_EXTERNAL_PLUGINS=OFF", "-DBUILD_MERGED_PLUGINS=ON",
                     "-DALSO_LOG_TO_STDERR=ON", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                     "-DBoost_NO_BOOST_CMAKE=ON", "-DBoost_INCLUDE_DIR=" + include.as_posix(),
                     "-DBoost_NO_SYSTEM_PATHS=ON", "-DCMAKE_BUILD_TYPE=Release",
                     "-DCMAKE_CXX_FLAGS=-DBOOST_DISABLE_CURRENT_LOCATION -DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS -ffile-prefix-map=" + source_root.as_posix() + "=.",
                     "-DCMAKE_C_FLAGS=-ffile-prefix-map=" + source_root.as_posix() + "=.",
                     "-DCMAKE_MAKE_PROGRAM=" + ninja.as_posix(),
                     "-DCMAKE_TOOLCHAIN_FILE=" + (ndk / "build/cmake/android.toolchain.cmake").as_posix(),
                     "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_shared",
                     "-DCMAKE_FIND_ROOT_PATH=" + ";".join((prefix / name / abi).as_posix() for name in ("glog", "leveldb", "lua", "marisa", "opencc", "yaml-cpp")),
                     "-DVIME_LUA_INCLUDE=" + (prefix / "lua" / abi / "include").as_posix(),
                     "-DVIME_LUA_ARCHIVE=" + (prefix / "lua" / abi / "lib/liblua_static.a").as_posix()]
            for package, variable, archive in (("glog", "Glog", "glog"), ("leveldb", "LevelDb", "leveldb"),
                                               ("marisa", "Marisa", "marisa"), ("opencc", "Opencc", "opencc"),
                                               ("yaml-cpp", "YamlCpp", "yaml-cpp")):
                flags += ["-D" + variable + "_INCLUDE_PATH=" + (prefix / package / abi / "include").as_posix(),
                          "-D" + variable + "_LIBRARY=" + (prefix / package / abi / ("lib/lib" + archive + ".a")).as_posix()]
            run([cmake, "-S", source_root, "-B", work, "-G", "Ninja", *flags], output / (abi + "-configure.log"))
            run([cmake, "--build", work, "--target", "rime-static", "-j", "4"], output / (abi + "-build.log"))
            archive = work / "lib/librime.a"
            members = subprocess.check_output([str(ar), "t", str(archive)], env=environment).decode().splitlines()
            if any(name in members for name in ("octagram.cc.o", "gram_db.cc.o", "gram_encoding.cc.o", "grammar_module.cc.o")):
                raise ValueError("Unexpected Octagram object in source-built archive")
            if not {"lua_gears.cc.o", "predict_engine.cc.o", "rime_api.cc.o"} <= set(members):
                raise ValueError("Missing intended core/plugin objects")
            receipt["abis"][abi] = {"archive": str(archive), "sha256": sha(archive), "bytes": archive.stat().st_size,
                                    "members": len(members), "source_build_passed": True}
            print(abi + " source engine built with Lua/Predict and no Octagram", flush=True)
        receipt["passed"] = True
    finally:
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", "utf-8")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lock", "archives", "boost-archive", "prebuilt", "sdk", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    result = build(parser.parse_args())
    print(json.dumps({"passed": result["passed"], "abis": list(result["abis"])}))
