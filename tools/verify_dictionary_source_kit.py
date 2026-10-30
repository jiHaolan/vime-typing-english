"""Authenticate a known source kit, then rebuild using only its bundled converter."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import subprocess
import sys
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(args):
    # The caller must trust a specific archive hash before its build code runs.
    if digest(args.archive) != args.expected_sha256:
        raise ValueError("Source kit differs from the explicitly expected SHA-256")
    output = args.output.resolve()
    if output.exists(): raise ValueError("Existing roundtrip evidence is preserved")
    with zipfile.ZipFile(args.archive) as archive:
        entries = archive.infolist()
        if len(entries) > 300 or sum(entry.file_size for entry in entries) > 512 * 1024**2:
            raise ValueError("Unexpected source kit size")
        index = json.loads(archive.read("MANIFEST.json"))
        if index["format"] != "vime-dictionary-source-kit-1": raise ValueError("Wrong source kit format")
        if len({entry.filename for entry in entries}) != len(entries): raise ValueError("Duplicate ZIP entries")
        if {entry.filename for entry in entries} != set(index["files"]) | {"MANIFEST.json"}:
            raise ValueError("Unexpected source kit files")
        for entry in entries:
            name = entry.filename
            relative = PurePosixPath(name)
            if relative.is_absolute() or ".." in relative.parts or "\\" in name or ":" in name:
                raise ValueError("Unsafe source kit path")
            if stat.S_ISLNK(entry.external_attr >> 16) or entry.is_dir(): raise ValueError("Only plain source files are allowed")
            if not (output / name).resolve().is_relative_to(output): raise ValueError("Source kit path leaves workspace")
            if name != "MANIFEST.json":
                data = archive.read(name)
                proof = index["files"][name]
                if hashlib.sha256(data).hexdigest() != proof["sha256"] or len(data) != proof["bytes"]:
                    raise ValueError("Source kit member hash mismatch")
        archive.extractall(output)
    environment = dict(os.environ, PYTHONPATH=str(output / "converter"))
    commands = [
        ([sys.executable, "-S", "tools/prepare_clean_dictionary.py", "--lock", "notices/dictionary-sources.lock.json",
          "--luna", "sources/luna", "--essay", "sources/essay", "--cedict", "sources/cedict/cedict.txt.gz",
          "--ecdict", "sources/ecdict/ecdict.csv", "--output", "rebuilt"], "roundtrip-build.log"),
        ([sys.executable, "-S", "-m", "unittest", "discover", "-s", "tools", "-p", "test_clean_dictionary.py", "-v"], "roundtrip-tests.log")]
    for command, log in commands:
        process = subprocess.run(command, cwd=output, env=environment, capture_output=True)
        (output / log).write_bytes(process.stdout + process.stderr)
        if process.returncode: raise RuntimeError("Source kit reconstruction failed; inspect " + str(output / log))
    def hashes(folder):
        return {path.relative_to(folder).as_posix(): digest(path) for path in sorted(folder.rglob("*")) if path.is_file()}
    expected = hashes(output / "generated/plaintext")
    rebuilt = hashes(output / "rebuilt")
    if expected != rebuilt: raise RuntimeError("Rebuilt data differs from delivered data")
    result = {"passed": True, "archive_sha256": args.expected_sha256, "manifest_files_checked": len(index["files"]),
              "rebuilt_files": len(rebuilt), "all_rebuilt_bytes_equal": True, "python_no_site_packages": True,
              "bundled_converter_only": True, "network_required": False, "git_history_required": False,
              "native_compiler_rebuilt_by_this_roundtrip": False}
    if index.get("mapped_input_dataset_id"):
        commands = [
            ([sys.executable, "-S", "tools/build_input_lexicon.py", "--compiled", "generated/compiled-rime",
              "--export", "public-export", "--prepared", "generated/plaintext", "--lock", "notices/dictionary-sources.lock.json",
              "--output", "rebuilt-input"], "mapped-input-build.log"),
            ([sys.executable, "-S", "tools/verify_input_lexicon.py", "--lexicon", "rebuilt-input",
              "--vocabulary", "public-export/run-a.tsv", "--report", "input-rows-verified.json"], "mapped-input-verify.log")]
        for command, log in commands:
            process = subprocess.run(command, cwd=output, env=environment, capture_output=True)
            (output / log).write_bytes(process.stdout + process.stderr)
            if process.returncode: raise RuntimeError("Mapped input roundtrip failed; inspect " + str(output / log))
        for name in ("input.lex", "associations.json", "manifest.json"):
            if digest(output / "generated/input" / name) != digest(output / "rebuilt-input" / name):
                raise RuntimeError("Mapped input differs from delivered data")
        result.update(mapped_input_dataset_id=index["mapped_input_dataset_id"], mapped_input_rebuilt_files=3,
                      all_mapped_input_bytes_equal=True,
                      mapped_input_source_coverage=json.loads((output / "input-rows-verified.json").read_bytes()))
    (output / "roundtrip-result.json").write_text(json.dumps(result, indent=2), "utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    verify(parser.parse_args())
