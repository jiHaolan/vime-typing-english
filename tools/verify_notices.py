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


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def inspect(read, inventory=None, commercial=False):
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
        dictionary_bytes = read("dict.android.json")
        dictionary = json.loads(dictionary_bytes)
        source = json.loads(read("levels-source.json"))
        check("exam-tag-source-and-fields", source["format"] == "vime-level-source-1"
              and source["commit"] == "ab22ad36302cafc6349a050e2b60894855208055"
              and source["license"] == "MIT" and source["fields_used"] == ["word", "tag"]
              and source["tag_policy"] == {"CET4": ["zk", "gk", "cet4"], "CET6": ["cet6"], "TOEFL": ["toefl"]})
        check("exam-tag-and-dictionary-hashes", sha(encoded(dictionary["lvl"])) == source["levels_sha256"]
              and sha(dictionary_bytes) == source["dictionary_sha256"])
        check("unrelated-dictionary-sections-preserved", all(sha(encoded(dictionary[k])) == expected
              for k, expected in source["preserved_sections_sha256"].items())
              and set(source["preserved_sections_sha256"]) == {"pw", "en", "card"})
        check("three-explicit-exam-tiers", set(dictionary["lvl"].values()) == {"CET4", "CET6", "TOEFL"}
              and all(sum(v == key for v in dictionary["lvl"].values()) == count for key, count in source["counts"].items()))
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
        return inspect(lambda name: apk.read("assets/" + name), inventory, commercial)


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
