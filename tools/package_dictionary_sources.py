"""Package exact public data sources, conversion code, licences and build evidence.

This is a dictionary source kit, not a claim that native dependency review or
commercial distribution approval is complete. Never includes product user data.
"""
import argparse
import json
from pathlib import Path
import zipfile

from prepare_clean_dictionary import converter_package, encoded, sha, source_blob
from compile_clean_dictionary import INPUTS, OUTPUTS, check_source


README = """# Vime dictionary source kit 0.23.0

Public dictionary inputs and conversion sources only. No user dictionaries,
learning records, recordings, API keys or product signing material are included.
The source lock identifies upstream URLs, commits and exact original file hashes.
MANIFEST.json hashes every supplied file; original licence bytes are preserved.

Data reconstruction (Python 3.12; no network or pip install needed):

```powershell
$env:PYTHONPATH = Join-Path $PWD 'converter'
python tools/prepare_clean_dictionary.py --lock notices/dictionary-sources.lock.json `
  --luna sources/luna --essay sources/essay `
  --cedict sources/cedict/cedict.txt.gz --ecdict sources/ecdict/ecdict.csv `
  --output rebuilt
python -m unittest discover -s tools -p test_clean_dictionary.py -v
```

Compare every file under rebuilt/data and rebuilt/rime with generated/plaintext;
the delivered hashes are exact, including line endings. Outputs refuse an
existing directory. CC-CEDICT definitions/phonetics, LGPL Essay frequencies and
prefix associations, and MIT ECDICT word/tag labels are separate physical files.
CC-CEDICT source ordinals count non-comment, non-empty records, not text lines.
English cards retain the first two selected senses and their source ordinals;
the full original dictionary is supplied under sources/cedict.

For binary Rime compilation, use the matching Vime source checkout, the pinned
native libraries in native/provenance.json, Android SDK36, Gradle8.11.1 and JDK21.
The tools/dictionary-compiler project is a separate, test-only x86_64 APK. It has
no activity, network or microphone permission, no product Application, and never
imports the product's private files. Build with -PrimeSources=<rebuilt/rime>.
compile_clean_dictionary.py permits only the owned emulator-5580 with actual
16KB pages and disabled/read-back compatibility fallback. It builds in two fresh
private directories and exports exactly seven public outputs after input probes.
No generated binary headers/checksums are patched. Input mtimes are fixed at
2000-01-01T00:00:00.500Z to avoid the pinned Rime clock conversion's second-boundary
rounding. Both runs must produce identical bytes before a dataset is accepted.

generated/compiled-rime contains the exact accepted public binary outputs;
evidence/compiler-receipt.json records their hashes and the real input probes.
The native libraries are build prerequisites, not supplied as independently
audited complete corresponding source in this data kit. Native embedded third
party notices, safe product deployment, full APK integration/quality and final
commercial distribution review remain separate work. This kit does not remove
the current app's release blockers by itself.
"""


def package(args):
    repo = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Preserve existing source kits; select a fresh output")
    lock_bytes = args.lock.read_bytes()
    lock = json.loads(lock_bytes)
    spec = lock["sources"]
    receipt = json.loads(args.receipt.read_bytes())
    compiled_manifest = json.loads((args.compiled / "manifest.json").read_bytes())
    data_manifest = json.loads((args.prepared / "data/manifest.json").read_bytes())
    plain_manifest = check_source(args.prepared / "rime")
    expected_data = {"data/" + name + ".json" for name in ("pinyin", "english", "frequencies", "levels", "associations")}
    if set(data_manifest["files"]) != expected_data:
        raise ValueError("Only the five known public data files may enter the kit")
    if not receipt["passed"] or not receipt["public_outputs_byte_reproducible"] or not receipt["compat_restored"]:
        raise ValueError("Successful actual isolated compiler receipt required")
    if {data_manifest["source_lock_sha256"], plain_manifest["source_lock_sha256"], compiled_manifest["source_lock_sha256"]} != {sha(lock_bytes)}:
        raise ValueError("Prepared data/compiler source locks differ")
    if receipt["dataset_id"] != compiled_manifest["dataset_id"] or receipt["source_id"] != plain_manifest["source_id"]:
        raise ValueError("Compiler receipt does not belong to this data")
    if sha(encoded(dict(sorted(compiled_manifest["files"].items())))) != compiled_manifest["dataset_id"]:
        raise ValueError("Compiled dataset identity differs from its file hashes")
    files = {}

    def add(name, content, expected=None):
        if name in files or name.startswith("/") or ".." in Path(name).parts:
            raise ValueError("Invalid or duplicate source-kit path")
        if expected and sha(content) != expected:
            raise ValueError("Source hash mismatch: " + name)
        files[name] = content

    add("README.md", README.encode("utf-8"))
    add("notices/dictionary-sources.lock.json", lock_bytes)
    for key, folder in (("luna", args.luna), ("essay", args.essay)):
        for name in spec[key]["files"]:
            add("sources/" + key + "/" + name, source_blob(folder, spec[key], name), spec[key]["files"][name])
    add("sources/cedict/cedict.txt.gz", args.cedict.read_bytes(), spec["cedict"]["sha256"])
    add("sources/cedict/CC-BY-SA-4.0.txt", args.cedict_license.read_bytes(), spec["cedict"]["license_sha256"])
    add("sources/ecdict/ecdict.csv", args.ecdict.read_bytes(), spec["ecdict"]["csv_sha256"])
    for name, path in (("LICENSE", args.ecdict_license), ("README.md", args.ecdict_readme)):
        add("sources/ecdict/" + name, path.read_bytes(), spec["ecdict"]["files"][name])
    conversion = spec["conversion"]
    converter = converter_package(conversion)
    for group in ("data_sha256", "code_sha256"):
        for name, expected in conversion[group].items():
            add("converter/opencc/" + name, (converter / name).read_bytes(), expected)
    for name, expected in conversion["metadata_sha256"].items():
        relative = conversion["dist_info"] + "/" + name
        add("converter/" + relative, (converter.parent / relative).read_bytes(), expected)
    add("sources/GNU-GPL-3.0.txt", (repo / "app/src/main/assets/licenses/GNU-GPL-3.0.txt").read_bytes())
    for name in ("prepare_clean_dictionary.py", "prepare_levels.py", "compile_clean_dictionary.py", "test_clean_dictionary.py", "package_dictionary_sources.py"):
        add("tools/" + name, (repo / "tools" / name).read_bytes())
    compiler_dir = repo / "tools/dictionary-compiler"
    for name in ("build.gradle.kts", "settings.gradle.kts"):
        add("tools/dictionary-compiler/" + name, (compiler_dir / name).read_bytes())
    for path in sorted((compiler_dir / "src").rglob("*")):
        if path.is_file(): add("tools/dictionary-compiler/" + path.relative_to(compiler_dir).as_posix(), path.read_bytes())
    add("native/provenance.json", (repo / "native/provenance.json").read_bytes())
    for name, expected in data_manifest["files"].items():
        add("generated/plaintext/" + name, (args.prepared / name).read_bytes(), expected)
    for name in [*sorted(INPUTS), "manifest.json"]:
        add("generated/plaintext/rime/" + name, (args.prepared / "rime" / name).read_bytes(), plain_manifest["files"].get(name))
    add("generated/plaintext/data/manifest.json", (args.prepared / "data/manifest.json").read_bytes())
    if set(compiled_manifest["files"]) != INPUTS | {"build/" + name for name in OUTPUTS}:
        raise ValueError("Compiled dataset has an unexpected file set")
    for name, expected in compiled_manifest["files"].items():
        add("generated/compiled-rime/" + name, (args.compiled / name).read_bytes(), expected)
    add("generated/compiled-rime/manifest.json", (args.compiled / "manifest.json").read_bytes(), receipt["compiled_manifest_sha256"])
    add("evidence/compiler-receipt.json", args.receipt.read_bytes())
    index = {"format": "vime-dictionary-source-kit-1", "data_source_lock_sha256": sha(lock_bytes),
             "dataset_id": receipt["dataset_id"], "native_review_complete": False,
             "files": {name: {"bytes": len(content), "sha256": sha(content)} for name, content in sorted(files.items())}}
    files["MANIFEST.json"] = encoded(index)
    output.mkdir(parents=True)
    archive_path = output / "Vime-dictionary-sources-0.23.0.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(2000, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content, compresslevel=9)
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None: raise ValueError("Source kit ZIP CRC failed")
        if set(archive.namelist()) != set(files): raise ValueError("Source kit files differ")
        for name, content in files.items():
            if archive.read(name) != content: raise ValueError("Source kit byte verification failed")
    result = {"format": index["format"], "dataset_id": receipt["dataset_id"], "files": len(files),
              "bytes": archive_path.stat().st_size, "sha256": sha(archive_path.read_bytes()),
              "archive": archive_path.name, "native_review_complete": False}
    (output / "receipt.json").write_text(json.dumps(result, indent=2), "utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lock", "luna", "essay", "cedict", "cedict-license", "ecdict", "ecdict-license", "ecdict-readme", "prepared", "compiled", "receipt", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    package(parser.parse_args())
