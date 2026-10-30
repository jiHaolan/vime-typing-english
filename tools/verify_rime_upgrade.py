"""Compare app-owned emulator data after an upgrade, without erasing it or installing APKs."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tarfile
import xml.etree.ElementTree as ET


def sha(data): return hashlib.sha256(data).hexdigest()


def databases(folder):
    result = {}
    for path in (folder / "databases").iterdir():
        if not path.is_file() or path.read_bytes()[:16] != b"SQLite format 3\0": continue
        connection = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
        tables = {}
        try:
            for (name,) in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
                identifier = '"' + name.replace('"', '""') + '"'
                rows = [list(row) for row in connection.execute("SELECT * FROM " + identifier)]
                canonical = sorted(json.dumps(row, ensure_ascii=False, default=lambda value: value.hex(), separators=(",", ":")) for row in rows)
                tables[name] = {"rows": len(rows), "records_sha256": sha("\n".join(canonical).encode())}
        finally:
            connection.close()
        result[path.name] = tables
    return result


def preferences(folder):
    def value(node):
        return [node.tag, {key: v for key, v in node.attrib.items() if key != "name"},
                (node.text or "").strip(), sorted(json.dumps(value(child), ensure_ascii=False, sort_keys=True) for child in node)]
    return {path.name: {node.attrib["name"]: value(node) for node in ET.parse(path).getroot()}
            for path in (folder / "shared_prefs").glob("*.xml")}


def verify(args):
    output = args.output.resolve()
    if output.exists(): raise ValueError("Preserve earlier upgrade evidence")
    before = args.before.resolve()
    baseline_tables = databases(before)
    saved = sum(tables.get("words", {}).get("rows", 0) for tables in baseline_tables.values())
    reviewed = sum(tables.get("review_events", {}).get("rows", 0) for tables in baseline_tables.values())
    if saved < 2 or reviewed < 1:
        raise ValueError("Populate at least two saved words and one real review in the baseline")
    private = Path("files/rime/user/private-upgrade-sentinel.txt")
    obsolete = Path("files/rime/user/build/obsolete-cache-sentinel.txt")
    if not (before / private).is_file() or not (before / obsolete).is_file():
        raise ValueError("Both preservation/cache fixtures must exist in the baseline")
    manifest = json.loads(args.manifest.read_bytes())
    base = [str(args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")), "-s", args.device]
    def adb(*command): return subprocess.check_output(base + list(command))
    if args.device != "emulator-5580" or adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise ValueError("Only the owned emulator is allowed")
    version = adb("shell", "dumpsys", "package", "com.vime.ime").decode()
    if "versionName=" + args.version + "\n" not in version.replace("\r", ""):
        raise ValueError("Unexpected installed version")
    output.mkdir(parents=True)
    adb("shell", "am", "force-stop", "com.vime.ime")
    data = adb("exec-out", "run-as", "com.vime.ime", "tar", "-cf", "-", "databases", "shared_prefs", "files/rime/user")
    (output / "private-snapshot.tar").write_bytes(data)
    after = output / "after"
    with tarfile.open(fileobj=io.BytesIO(data)) as archive: archive.extractall(after, filter="data")
    old_tables, new_tables = baseline_tables, databases(after)
    old_preferences, new_preferences = preferences(before), preferences(after)
    changed = {name: sorted(key for key in set(old_preferences.get(name, {})) | set(new_preferences.get(name, {}))
               if old_preferences.get(name, {}).get(key) != new_preferences.get(name, {}).get(key))
               for name in set(old_preferences) | set(new_preferences)}
    changed = {name: keys for name, keys in changed.items() if keys}
    old_sst = {path.relative_to(before).as_posix(): sha(path.read_bytes()) for path in (before / "files/rime/user").rglob("*.ldb")}
    retained_sst = all((after / name).is_file() and sha((after / name).read_bytes()) == digest for name, digest in old_sst.items())
    marker = adb("exec-out", "run-as", "com.vime.ime", "cat", "files/rime/active-dataset.txt").decode().strip()
    public_roots = adb("shell", "run-as", "com.vime.ime", "ls", "files/rime").decode().split()
    preserved = (after / private).read_bytes() == (before / private).read_bytes()
    cache_removed = not (after / obsolete).exists()
    result = {"version": args.version, "dataset_id": marker, "sqlite_before": old_tables, "sqlite_after": new_tables,
              "baseline_saved_words": saved, "baseline_review_events": reviewed,
              "sqlite_logical_records_equal": old_tables == new_tables, "changed_preferences": changed,
              "private_user_fixture_preserved": preserved, "obsolete_generated_cache_removed": cache_removed,
              "old_public_shared_removed": "shared" not in public_roots, "baseline_native_sst_files": len(old_sst),
              "baseline_native_sst_bytes_retained": retained_sst, "private_snapshot_sha256": sha(data),
              "limits": "Native manifest/log counters may change when the engine opens and tests learn a word; this compares existing immutable SST bytes, not every internal native counter."}
    result["passed"] = bool(old_tables) and old_tables == new_tables and not changed and preserved and cache_removed and bool(old_sst) and retained_sst and marker == manifest["dataset_id"] and "shared" not in public_roots
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), "utf-8")
    print(json.dumps({key: result[key] for key in ("passed", "sqlite_logical_records_equal", "changed_preferences", "baseline_native_sst_bytes_retained", "obsolete_generated_cache_removed")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sdk", "before", "manifest", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--version", required=True)
    raise SystemExit(verify(parser.parse_args()))
