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
English cards retain every tonal form, all senses and source ordinals. Older
two-sense preview columns remain for compatibility; the full original dictionary
is also supplied under sources/cedict.

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
    mapped_id = None
    if any((args.input_lexicon, args.public_export, args.exporter_tool)):
        if not all((args.input_lexicon, args.public_export, args.exporter_tool)):
            raise ValueError("Mapped data, public export and exporter sources are supplied together")
        mapped = json.loads((args.input_lexicon / "manifest.json").read_bytes())
        export = json.loads((args.public_export / "receipt.json").read_bytes())
        build = json.loads((args.exporter_tool / "receipt.json").read_bytes())
        if mapped["rime_dataset_id"] != receipt["dataset_id"] or mapped["source_lock_sha256"] != sha(lock_bytes):
            raise ValueError("Mapped vocabulary differs from the accepted public source")
        if mapped["table_sha256"] != export["table_sha256"] or mapped["vocabulary_sha256"] != export["vocabulary_sha256"]:
            raise ValueError("Mapped vocabulary does not match the public export")
        if not export["passed"] or not build["passed"] or build["tool_source_modified"]:
            raise ValueError("An authenticated unmodified public exporter is required")
        for name, expected in mapped["files"].items():
            if name not in ("input.lex", "associations.json"):
                raise ValueError("Unexpected mapped data file")
            add("generated/input/" + name, (args.input_lexicon / name).read_bytes(), expected)
        add("generated/input/manifest.json", (args.input_lexicon / "manifest.json").read_bytes())
        add("public-export/run-a.tsv", (args.public_export / "run-a.tsv").read_bytes(), mapped["vocabulary_sha256"])
        add("public-export/receipt.json", (args.public_export / "receipt.json").read_bytes())
        add("public-export/exporter-build-receipt.json", (args.exporter_tool / "receipt.json").read_bytes())
        add("public-export/rime_table_decompiler.cc", (args.exporter_tool / "rime/tools/rime_table_decompiler.cc").read_bytes(), build["upstream_tool_sha256"])
        for name in ("build_input_lexicon.py", "verify_input_lexicon.py", "build_dictionary_exporter.py", "export_rime_vocabulary.py"):
            add("tools/" + name, (repo / "tools" / name).read_bytes())
        add("sources/input-data-NOTICE.txt", (repo / "app/src/main/assets/licenses/input-data-NOTICE.txt").read_bytes())
        mapped_id = mapped["dataset_id"]
        files["README.md"] += b'''\nComplete mapped fallback vocabulary (no native runtime required to rebuild):
python -S tools/build_input_lexicon.py --compiled generated/compiled-rime --export public-export --prepared generated/plaintext --lock notices/dictionary-sources.lock.json --output rebuilt-input
python -S tools/verify_input_lexicon.py --lexicon rebuilt-input --vocabulary public-export/run-a.tsv --report input-rows-verified.json
Compare input.lex, associations.json and manifest.json with generated/input.
Every public source row and all three sorted indexes must match; do not replace
the complete vocabulary with a smaller selection to satisfy APK/memory checks.
The recorded export has its original older source lock; its exact table bytes
are identical to the current dataset. Schema-only abbreviation changes do not
change that public table. Both source identities remain explicitly recorded.
'''
    index = {"format": "vime-dictionary-source-kit-1", "data_source_lock_sha256": sha(lock_bytes),
             "dataset_id": receipt["dataset_id"], "native_review_complete": False,
             "files": {name: {"bytes": len(content), "sha256": sha(content)} for name, content in sorted(files.items())}}
    if mapped_id:
        index["mapped_input_dataset_id"] = mapped_id
    english_id = runtime_id = None
    if args.english_lexicon or args.runtime_rime:
        if not (args.english_lexicon and args.runtime_rime):
            raise ValueError("Complete English data and runtime projection are supplied together")
        english=json.loads((args.english_lexicon/"manifest.json").read_bytes())
        runtime=json.loads((args.runtime_rime/"manifest.json").read_bytes())
        if english["source_lock_sha256"]!=sha(lock_bytes) or runtime["source_dataset_id"]!=receipt["dataset_id"]:
            raise ValueError("English/runtime source lineage differs")
        for name,expected in english["files"].items():
            if name not in ("english.lex","levels.json"):raise ValueError("Unexpected English file")
            add("generated/english/"+name,(args.english_lexicon/name).read_bytes(),expected)
        add("generated/english/manifest.json",(args.english_lexicon/"manifest.json").read_bytes())
        for name,expected in runtime["files"].items():
            if expected!=compiled_manifest["files"].get(name) or sha((args.runtime_rime/name).read_bytes())!=expected:
                raise ValueError("Runtime file differs from compiled source")
        add("generated/runtime-rime/manifest.json",(args.runtime_rime/"manifest.json").read_bytes())
        for name in ("build_english_lexicon.py","verify_english_lexicon.py","project_rime_runtime.py"):
            add("tools/"+name,(repo/"tools"/name).read_bytes())
        for name in ("MappedInputIndex.kt","MappedEnglishIndex.kt"):
            add("readers/"+name,(repo/"app/src/main/java/com/vime/ime/data"/name).read_bytes())
        add("sources/cedict-NOTICE.txt",(repo/"app/src/main/assets/licenses/cedict-NOTICE.txt").read_bytes())
        english_id=english["dataset_id"];runtime_id=runtime["dataset_id"]
        files["README.md"]+=b'''\nComplete tonal English and precompiled runtime projection:
python -S tools/build_english_lexicon.py --prepared generated/plaintext --lock notices/dictionary-sources.lock.json --output rebuilt-english
python -S tools/verify_english_lexicon.py --lexicon rebuilt-english --prepared generated/plaintext --report english-rows-verified.json
python -S tools/project_rime_runtime.py --compiled generated/compiled-rime --output rebuilt-runtime
Compare all three English files and the runtime manifest. Runtime binary/schema
files are byte-identical to generated/compiled-rime and are not duplicated in
this archive. Full dictionary/Essay build sources remain in this source kit.
'''
        index["english_dataset_id"]=english_id;index["runtime_dataset_id"]=runtime_id
        index["files"]={name:{"bytes":len(content),"sha256":sha(content)} for name,content in sorted(files.items())}
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
    if mapped_id:
        result["mapped_input_dataset_id"] = mapped_id
    if english_id:
        result["english_dataset_id"]=english_id;result["runtime_dataset_id"]=runtime_id
    (output / "receipt.json").write_text(json.dumps(result, indent=2), "utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lock", "luna", "essay", "cedict", "cedict-license", "ecdict", "ecdict-license", "ecdict-readme", "prepared", "compiled", "receipt", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    for name in ("input-lexicon", "public-export", "exporter-tool", "english-lexicon", "runtime-rime"):
        parser.add_argument("--" + name, type=Path)
    package(parser.parse_args())
