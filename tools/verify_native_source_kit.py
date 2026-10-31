"""Authenticate/extract a native rebuild kit; never executes bundled code."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import zipfile


def verify(args):
    with args.archive.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != args.sha256 or args.output.exists():
        raise ValueError("Archive hash differs or extraction directory exists")
    with zipfile.ZipFile(args.archive) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or sum(row.file_size for row in archive.infolist()) > 768 * 1024 * 1024:
            raise ValueError("Duplicate files or oversized source kit")
        for row in archive.infolist():
            name = PurePosixPath(row.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in row.filename or stat.S_ISLNK(row.external_attr >> 16):
                raise ValueError("Unsafe source-kit entry")
        manifest = json.loads(archive.read("MANIFEST.json"))
        if manifest["format"] != "vime-native-source-kit-1" or manifest["plugins"] != ["lua", "predict"]:
            raise ValueError("Unexpected source-kit policy")
        if manifest["provider_core_archive_included"] or set(names) != set(manifest["files"]) | {"MANIFEST.json"}:
            raise ValueError("Source-kit payload differs")
        for name, meta in manifest["files"].items():
            with archive.open(name) as source:
                value = hashlib.file_digest(source, "sha256").hexdigest()
            if value != meta["sha256"] or archive.getinfo(name).file_size != meta["bytes"]:
                raise ValueError("Source-kit file checksum differs: " + name)
        archive.extractall(args.output)
    lock = json.loads((args.output / "native/sources.lock.json").read_bytes())
    checked = 0
    def check(path, expected):
        nonlocal checked
        with path.open("rb") as source:
            value = hashlib.file_digest(source, "sha256").hexdigest()
        if value != expected:
            raise ValueError("Pinned material differs: " + str(path))
        checked += 1
    for row in lock["sources"]:
        check(args.output / "upstream" / (row["id"] + ".tar.gz"), row["archive_sha256"])
    check(args.output / "upstream/boost-1.90.0-cmake.tar.xz", lock["boost"]["sha256"])
    for table in ("dependency_archives", "dependency_headers"):
        for name, meta in lock[table].items():
            check(args.output / "dependency-blobs" / name, meta["sha256"])
    check(args.output / "native/sources.lock.json", manifest["source_lock_sha256"])
    provenance = json.loads((args.output / "native/provenance.json").read_bytes())
    for name, expected in provenance["files"].items():
        check(args.output / "current-binaries" / name.removeprefix("app/src/main/jniLibs/"), expected)
    result = {"passed": True, "archive_sha256": actual, "payload_files_checked": len(manifest["files"]),
              "pinned_material_files_checked": checked, "native_compiler_executed_by_verifier": False,
              "independent_dependency_rebuild_complete": False, "historical_distribution_review_complete": False}
    (args.output / "verification-receipt.json").write_text(json.dumps(result, indent=2) + "\n", "utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path); parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    print(json.dumps(verify(parser.parse_args())))
