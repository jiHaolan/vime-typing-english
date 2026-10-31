"""Verify actual notice bytes, resolved dependency coverage and exam-tag provenance.

Integrity checks are separate from the outstanding commercial source audit.
--release refuses unresolved source items; preview checks report them explicitly.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

REQUIRED = {"licenses/GNU-GPL-3.0.txt", "licenses/Apache-2.0.txt", "licenses/ecdict-MIT.txt",
            "licenses/librime-BSD-3-Clause.txt", "licenses/luna-pinyin-LGPL-3.0.txt",
            "licenses/rime-essay-LGPL-3.0.txt", "licenses/makemeahanzi-COPYING.txt",
            "licenses/CC-BY-SA-3.0.txt", "licenses/legacy-data-NOTICE.txt", "licenses/runtime-NOTICE.txt"}
REQUIRED |= {"licenses/CC-BY-SA-4.0.txt", "licenses/supplement-NOTICE.txt"}
REQUIRED.add("licenses/input-data-NOTICE.txt")
REQUIRED.add("licenses/cedict-NOTICE.txt")
REQUIRED |= {"licenses/" + name for name in ("librime-lua-BSD-3-Clause.txt", "librime-predict-BSD-3-Clause.txt",
              "legacy-octagram-GPL-3.0.txt", "darts-clone-BSD-3-Clause.txt", "utf8cpp-embedded-NOTICE.txt",
              "x11-keysyms-NOTICE.txt", "lua-compat-NOTICE.txt", "rapidjson-upstream-NOTICE.txt",
              "ndk-llvm-full-NOTICE.txt", "glog-embedded-NOTICE.txt", "native-rebuild-NOTICE.txt")}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def inspect(read, inventory=None, commercial=False, native_libraries=None):
    result = {"passed": False, "commercial_distribution_ready": False, "checks": []}
    def check(name, okay):
        result["checks"].append({"name": name, "pass": bool(okay)})
    try:
        raw = read("notices.json")
        if len(raw) > 128 * 1024:
            raise ValueError("Oversized notice catalog")
        data = json.loads(raw)
        check("catalog-format", data["format"] == "vime-notices-1")
        files = data["files"]
        check("required-license-documents", REQUIRED <= files.keys())
        for name, expected in files.items():
            if not re.fullmatch(r"licenses/[A-Za-z0-9_.-]+", name) or ".." in name:
                raise ValueError("Unsafe license path")
            content = read(name)
            check("document-bytes:" + name, 100 <= len(content) <= 256 * 1024
                  and re.fullmatch("[a-f0-9]{64}", expected) and sha(content) == expected)
        check("gpl-companion-is-complete", len(read("licenses/GNU-GPL-3.0.txt")) > 30000
              and b"END OF TERMS AND CONDITIONS" in read("licenses/GNU-GPL-3.0.txt"))
        check("lua-compat-notices-are-complete", read("licenses/lua-compat-NOTICE.txt").count(b"Permission is hereby granted") == 2
              and b"Kepler Project" in read("licenses/lua-compat-NOTICE.txt")
              and b"Lua.org" in read("licenses/lua-compat-NOTICE.txt"))
        native = json.loads(read("native-sources.json"))
        expected_native = {abi + "/" + name for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")
                           for name in ("libvime_rime.so", "libc++_shared.so")}
        check("native-source-and-plugin-policy", native["format"] == "vime-native-runtime-1"
              and native["core_from_source"] and not native["octagram_included"]
              and native["engine_plugins"] == ["lua", "predict"]
              and re.fullmatch("[a-f0-9]{64}", native["source_lock_sha256"]))
        check("native-declared-library-set", set(native["libraries"]) == expected_native
              and all(re.fullmatch("[a-f0-9]{64}", value) for value in native["libraries"].values()))
        if native_libraries is not None:
            present = set(native_libraries)
            abis = {name.split("/")[0] for name in present}
            check("actual-native-library-set", bool(abis) and abis <= {"arm64-v8a", "armeabi-v7a", "x86_64"}
                  and present == {abi + "/" + name for abi in abis for name in ("libvime_rime.so", "libc++_shared.so")})
            for name, content in native_libraries.items():
                check("native-binary-bytes:" + name, name in native["libraries"] and sha(content) == native["libraries"][name])
        components = data["components"]
        check("unique-component-ids", 1 <= len(components) <= 100
              and len({row["id"] for row in components}) == len(components))
        references = {name for row in components for name in row["documents"]}
        check("all-documents-reachable", references == files.keys())
        check("component-metadata-present", all(all(isinstance(row.get(k), str) and row[k]
              for k in ("id", "title", "category", "license", "version", "source", "summary")) for row in components))
        modules = data["runtime_modules"]
        check("runtime-pom-coverage", sorted(modules) == sorted(row["coordinate"] for row in data["runtime_metadata"])
              and len(set(modules)) == len(modules) and all(row["licenses"] and row["pom_evidence"] for row in data["runtime_metadata"]))
        if inventory:
            expected = sorted(inventory["modules"])
            artifacts = sorted((row["coordinate"], row["sha256"]) for row in inventory["artifacts"])
            actual = sorted((row["coordinate"], row["sha256"]) for row in data["runtime_artifacts"])
            check("actual-runtime-module-coverage", sorted(modules) == expected)
            check("actual-runtime-artifact-hashes", actual == artifacts)
        levels_bytes=read("english/levels.json")
        levels=json.loads(levels_bytes)["entries"]
        source = json.loads(read("levels-source.json"))
        check("exam-tag-source-and-fields", source["format"] == "vime-level-source-2"
              and source["commit"] == "ab22ad36302cafc6349a050e2b60894855208055"
              and source["license"] == "MIT" and source["fields_used"] == ["word", "tag"]
              and source["tag_policy"] == {"CET4": ["zk", "gk", "cet4"], "CET6": ["cet6"], "TOEFL": ["toefl"]})
        check("exam-tag-asset-hashes",sha(encoded(levels))==source["levels_sha256"] and sha(levels_bytes)==source["levels_file_sha256"])
        def absent(name):
            try:read(name);return False
            except (FileNotFoundError,KeyError):return True
        check("legacy-dictionary-asset-removed",absent("dict.android.json") and not source["legacy_dictionary_asset_present"])
        lexicon = json.loads(read("input/manifest.json"))
        check("complete-clean-input-source-counts", lexicon["format"] == "vime-input-lexicon-1"
              and lexicon["counts"]["entries"] == 569466 and lexicon["counts"]["words"] == 457884
              and lexicon["counts"]["codes"] == 393950 and not lexicon["legacy_vime_input_read"]
              and not lexicon["english_sources_merged"] and lexicon["license"] == "LGPL-3.0")
        public = lexicon["files"]
        check("input-asset-set-and-identity", set(public) == {"input.lex", "associations.json"}
              and sha(json.dumps(public,sort_keys=True,separators=(",", ":")).encode()) == lexicon["dataset_id"])
        for name, expected in public.items():
            if name not in ("input.lex", "associations.json"):
                raise ValueError("Unsafe input asset path")
            check("input-asset-bytes:" + name, sha(read("input/" + name)) == expected)
        try:
            read("associations.json")
            old_associations_absent = False
        except (FileNotFoundError, KeyError):
            old_associations_absent = True
        check("legacy-associations-asset-removed", old_associations_absent)
        check("three-explicit-exam-tiers", set(levels.values()) == {"CET4", "CET6", "TOEFL"}
              and all(sum(v == key for v in levels.values()) == count for key, count in source["counts"].items()))
        english=json.loads(read("english/manifest.json"))
        check("complete-english-and-tonal-forms",english["counts"]["groups"]==121814 and english["counts"]["headwords"]==121093
              and english["counts"]["tonal_forms"]==122472 and english["counts"]["senses"]==198019
              and not english["legacy_english_read"] and not english["hand_overrides_read"])
        check("english-file-set-and-identity",set(english["files"])=={"english.lex","levels.json"}
              and sha(json.dumps(english["files"],sort_keys=True,separators=(",",":")).encode())==english["dataset_id"])
        for name,expected in english["files"].items():
            if name not in ("english.lex","levels.json"):raise ValueError("Unsafe English asset path")
            check("english-asset-bytes:"+name,sha(read("english/"+name))==expected)
        runtime=json.loads(read("rime/manifest.json"))
        check("precompiled-runtime-source-lineage",runtime["format"]=="vime-rime-runtime-1"
              and set(runtime["build_sources"])=={"essay.txt","vime_pinyin.dict.yaml"}
              and sha(json.dumps(runtime["files"]|runtime["build_sources"],sort_keys=True,separators=(",",":")).encode())==runtime["source_dataset_id"]
              and runtime["source_dataset_id"]==lexicon["rime_dataset_id"]
              and runtime["source_lock_sha256"]==lexicon["source_lock_sha256"]==english["source_lock_sha256"])
        runtime_names={"default.yaml","vime_nine.schema.yaml","vime_pinyin.schema.yaml","build/default.yaml",
                       "build/vime_nine.prism.bin","build/vime_nine.schema.yaml","build/vime_pinyin.prism.bin",
                       "build/vime_pinyin.reverse.bin","build/vime_pinyin.schema.yaml","build/vime_pinyin.table.bin"}
        check("runtime-file-set-and-identity",set(runtime["files"])==runtime_names
              and sha(json.dumps(runtime["files"],sort_keys=True,separators=(",",":")).encode())==runtime["dataset_id"])
        for name,expected in runtime["files"].items():
            if name not in runtime_names:raise ValueError("Unsafe runtime asset path")
            check("runtime-asset-bytes:"+name,sha(read("rime/"+name))==expected)
        check("raw-build-sources-not-packaged",absent("rime/essay.txt") and absent("rime/vime_pinyin.dict.yaml"))
        blockers = data["release_blockers"]
        check("source-review-status-is-explicit", isinstance(blockers, list)
              and all(row.get("id") and row.get("detail") for row in blockers))
        result.update(components=len(components), documents=len(files), runtime_modules=len(modules), release_blockers=blockers)
        integrity = all(row["pass"] for row in result["checks"])
        result["commercial_distribution_ready"] = integrity and not blockers
        if commercial:
            check("commercial-source-review-complete", not blockers)
        result["passed"] = all(row["pass"] for row in result["checks"])
    except Exception as error:
        result["error"] = str(error)
    result["failed_checks"] = [row["name"] for row in result["checks"] if not row["pass"]]
    return result


def verify(path, inventory=None, commercial=False):
    if path.is_dir():
        return inspect(lambda name: (path / name).read_bytes(), inventory, commercial)
    with zipfile.ZipFile(path) as apk:
        native = {name.removeprefix("lib/"): apk.read(name) for name in apk.namelist()
                  if name.startswith("lib/") and name.endswith(".so")}
        return inspect(lambda name: apk.read("assets/" + name), inventory, commercial, native)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("assets_or_apk", type=Path)
    p.add_argument("--inventory", type=Path)
    p.add_argument("--release", action="store_true")
    p.add_argument("--report", type=Path)
    a = p.parse_args()
    inventory = json.loads(a.inventory.read_text("utf-8")) if a.inventory else None
    result = verify(a.assets_or_apk, inventory, a.release)
    if a.report:
        a.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(json.dumps({k: v for k, v in result.items() if k not in ("checks", "release_blockers")}, ensure_ascii=False))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
