"""Bundle exact native sources, pinned dependency inputs and notices for review.

This is a native rebuild kit, not the whole application source or a declaration
that historical GPL distribution and every dependency audit are resolved.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def package(args):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version):
        raise ValueError("Numeric package version required")
    if args.output.exists():
        raise ValueError("Preserve earlier source kits")
    lock = json.loads(args.lock.read_bytes())
    engine = json.loads((args.engine / "receipt.json").read_bytes())
    if not engine["passed"] or engine["source_lock_sha256"] != digest(args.lock) or engine["octagram_included"]:
        raise ValueError("Matching successful source-engine build required")
    files = {}

    def add(name, path, expected=None):
        if name in files or name.startswith("/") or ".." in Path(name).parts:
            raise ValueError("Duplicate or unsafe source-kit path")
        if expected and digest(path) != expected:
            raise ValueError("Source input differs: " + name)
        files[name] = path

    for row in lock["sources"]:
        add("upstream/" + row["id"] + ".tar.gz", args.archives / (row["id"] + ".tar.gz"), row["archive_sha256"])
    add("upstream/boost-1.90.0-cmake.tar.xz", args.boost_archive, lock["boost"]["sha256"])
    for table in ("dependency_archives", "dependency_headers"):
        for name, meta in lock[table].items():
            add("dependency-blobs/" + name, args.prebuilt / name, meta["sha256"])
    for name in ("Build-Native.ps1", "app/build.gradle.kts", "native/sources.lock.json", "native/provenance.json",
                 "native/CMakeLists.txt", "native/rime_bridge.cpp", "native/README.md", "notices/native-notice-sources.json",
                 "tools/build_native_engine.py", "tools/prepare_native_notices.py", "tools/package_native_sources.py",
                 "tools/verify_native_source_kit.py"):
        add(name, ROOT / name)
    for path in (ROOT / "native/include").rglob("*"):
        if path.is_file():
            add(path.relative_to(ROOT).as_posix(), path)
    for name in ("tools/verify_native_userdb.py", "tools/native-leveldb-reader/main.cpp", "tools/native-leveldb-reader/CMakeLists.txt"):
        add(name, ROOT / name)
    for path in (ROOT / "app/src/main/assets/licenses").glob("*.txt"):
        add("notices/documents/" + path.name, path)
    add("upstream/rapidjson-upstream-license.txt", args.rapidjson_license,
        "a140e5d46fe734a1c78f1a3c3ef207871dd75648be71fdda8e309b23ab8b1f32")
    ndk = args.sdk / "ndk/28.0.13004108"
    for name in ("source.properties", "NOTICE", "NOTICE.toolchain"):
        add("sdk/" + name, ndk / name)
    add("sdk/llvm-NOTICE", ndk / "toolchains/llvm/prebuilt/windows-x86_64/NOTICE")
    add("evidence/source-engine-build.json", args.engine / "receipt.json")
    add("evidence/native-runtime-receipt.json", args.runtime_receipt)
    runtime = json.loads(args.runtime_receipt.read_bytes())
    if not runtime["passed"] or runtime["result"]["passed"] != 9:
        raise ValueError("Actual native runtime evidence required")
    provenance = json.loads((ROOT / "native/provenance.json").read_bytes())
    for name, sha in provenance["files"].items():
        add("current-binaries/" + name.removeprefix("app/src/main/jniLibs/"), ROOT / name, sha)
    manifest = {"format": "vime-native-source-kit-1", "version": args.version,
                "source_lock_sha256": digest(args.lock), "plugins": engine["plugins"],
                "provider_core_archive_included": False, "independent_dependency_rebuild_complete": False,
                "historical_distribution_review_complete": False,
                "files": {name: {"bytes": path.stat().st_size, "sha256": digest(path)} for name, path in sorted(files.items())}}
    readme = b'''# Vime native source/rebuild kit

This kit covers the native engine/JNI, exact upstream inputs and pinned binary
dependencies. It is not the whole Android application or a blanket commercial
licence approval. Dependency binaries are fixed provider blobs, not independently
rebuilt here. Historical GPL distribution review remains outstanding.

Install Android NDK28.0.13004108 and CMake3.31.6 with Ninja in your SDK.
From this extracted directory (Python3.12, no network/site packages required):

python -S tools/build_native_engine.py --lock native/sources.lock.json --archives upstream --boost-archive upstream/boost-1.90.0-cmake.tar.xz --prebuilt dependency-blobs --sdk <SDK> --output <NEW-ENGINE-OUTPUT>
powershell -File Build-Native.ps1 -Prebuilt dependency-blobs -EngineBuild <NEW-ENGINE-OUTPUT> -Sdk <SDK> -BuildRoot <NEW-JNI-OUTPUT>

The build explicitly includes Lua/Predict and rejects the provider's librime.a.
The Octagram source archive is retained separately for historical review; it
is GPL3, is not compiled into this engine, and does not relicense Vime code.
Existing input, English and user-learning capabilities remain unchanged.

Original notices are preserved under notices/documents. The complete upstream
RapidJSON notice includes additional test-data terms for files not imported in
OpenCC's runtime headers; SDK notices may cover additional SDK tools. The full
Boost source archive contains its own per-file notices. Source URLs/commits and
hashes are in native/sources.lock.json. The paired dictionary source kit covers
the independently licensed generated dictionary data.

MANIFEST.json authenticates all payload files. A successful archive check does
not claim a second native compilation or prove byte-reproducible compiler output.
'''
    args.output.mkdir(parents=True)
    archive = args.output / ("Vime-native-sources-" + args.version + ".zip")
    manifest["files"]["README.md"] = {"bytes": len(readme), "sha256": hashlib.sha256(readme).hexdigest()}
    with zipfile.ZipFile(archive, "w") as dest:
        for name, path in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if name.endswith((".xz", ".gz")) else zipfile.ZIP_DEFLATED
            dest.writestr(info, path.read_bytes(), compresslevel=9)
        for name, content in (("README.md", readme), ("MANIFEST.json", json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode())):
            info = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            dest.writestr(info, content, compresslevel=9)
    receipt = {"format": manifest["format"], "archive": archive.name, "bytes": archive.stat().st_size,
               "sha256": digest(archive), "payload_files": len(manifest["files"]),
               "native_dependencies_independently_rebuilt": False, "historical_distribution_review_complete": False}
    (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", "utf-8")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lock", "archives", "boost-archive", "prebuilt", "sdk", "engine", "runtime-receipt", "rapidjson-license", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--version", required=True)
    print(json.dumps(package(parser.parse_args())))
