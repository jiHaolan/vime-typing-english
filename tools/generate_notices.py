"""Generate a local license catalog from curated sources and resolved runtime POMs.

Offline only. Missing licenses, POMs, unsupported declarations or ambiguous cached
POMs stop generation. Does not infer licenses from project popularity.
"""
import argparse
import hashlib
import json
from pathlib import Path
from xml.etree import ElementTree as ET

NS = {"p": "http://maven.apache.org/POM/4.0.0"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pom_metadata(cache, coordinate, seen=()):
    if coordinate in seen or len(seen) > 8:
        raise ValueError("Invalid POM inheritance")
    group, artifact, version = coordinate.split(":")
    candidates = list((cache / group / artifact / version).glob("*/*.pom"))
    if not candidates or len({sha(p.read_bytes()) for p in candidates}) != 1:
        raise ValueError("Missing or ambiguous cached POM: " + coordinate)
    data = candidates[0].read_bytes()
    root = ET.fromstring(data)
    licenses = [{"name": p.findtext("p:name", namespaces=NS), "url": p.findtext("p:url", namespaces=NS)}
                for p in root.findall("p:licenses/p:license", NS)]
    evidence = [{"coordinate": coordinate, "pom_sha256": sha(data)}]
    developers = [p.findtext("p:name", namespaces=NS) for p in root.findall("p:developers/p:developer", NS)]
    source = root.findtext("p:url", namespaces=NS)
    if not licenses:
        parent = root.find("p:parent", NS)
        if parent is None:
            raise ValueError("No declared license: " + coordinate)
        parent_id = ":".join(parent.findtext("p:" + key, namespaces=NS) for key in ("groupId", "artifactId", "version"))
        inherited = pom_metadata(cache, parent_id, (*seen, coordinate))
        licenses = inherited["licenses"]
        evidence += inherited["pom_evidence"]
        developers = developers or inherited["developers"]
        source = source or inherited["source"]
    if not all(p["url"] and "apache.org/licenses/LICENSE-2.0" in p["url"] for p in licenses):
        raise ValueError("Review new runtime license before catalog generation: " + coordinate)
    return {"coordinate": coordinate, "name": root.findtext("p:name", namespaces=NS) or artifact,
            "source": source or "https://repo.maven.apache.org/maven2/" + group.replace(".", "/") + "/" + artifact,
            "developers": [d for d in developers if d], "licenses": licenses, "pom_evidence": evidence}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--inventory", required=True, type=Path)
    p.add_argument("--cache", required=True, type=Path)
    p.add_argument("--assets", required=True, type=Path)
    p.add_argument("--components", required=True, type=Path)
    a = p.parse_args()
    inventory = json.loads(a.inventory.read_text("utf-8"))
    assert inventory["format"] == "vime-runtime-inventory-1"
    modules = sorted(inventory["modules"])
    assert len(set(modules)) == len(modules) and modules
    metadata = [pom_metadata(a.cache, c) for c in modules]
    notice = "Resolved production runtime libraries. POM declarations are reproduced below.\n\n"
    for row in metadata:
        notice += row["coordinate"] + "\n" + row["name"] + "\n" + row["source"] + "\n"
        notice += "Declared developers: " + "; ".join(row["developers"]) + "\n"
        for license in row["licenses"]:
            notice += "License: " + license["name"] + "\n" + license["url"] + "\n"
        notice += "\n"
    (a.assets / "licenses/runtime-NOTICE.txt").write_text(notice, "utf-8")
    curated = json.loads(a.components.read_text("utf-8"))
    runtime = {"id": "android-runtime", "title": "AndroidX、Kotlin 与协程", "category": "应用基础组件",
               "license": "Apache-2.0", "version": f"{len(modules)} 个解析模块",
               "source": "https://developer.android.com/jetpack/androidx",
               "summary": "本版实际解析的运行依赖；具体版本、项目来源和许可声明见清单。构建插件和测试库未计入。",
               "documents": ["licenses/runtime-NOTICE.txt", "licenses/Apache-2.0.txt"]}
    components = [runtime] + curated["components"]
    paths = sorted({file for row in components for file in row["documents"]})
    files = {}
    for name in paths:
        if not name.startswith("licenses/") or "/" in name.removeprefix("licenses/") or ".." in name:
            raise ValueError("Unsafe catalog path")
        data = (a.assets / name).read_bytes()
        if not 100 <= len(data) <= 256 * 1024:
            raise ValueError("Missing or oversized license: " + name)
        files[name] = sha(data)
    catalog = {"format": "vime-notices-1", "components": components, "files": files,
               "runtime_modules": modules, "runtime_metadata": metadata,
               "runtime_artifacts": [{k: row[k] for k in ("coordinate", "sha256")} for row in inventory["artifacts"]],
               "release_blockers": curated["release_blockers"]}
    (a.assets / "notices.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(json.dumps({"components": len(components), "runtime_modules": len(modules), "documents": len(files),
                      "release_blockers": len(catalog["release_blockers"])}))


if __name__ == "__main__":
    main()
