"""Build a complete mapped LGPL fallback lexicon; never read legacy/user data.

All public export rows are retained. Three sorted offset indexes support full
pinyin, nine-key and initials; bounded prefix lists avoid whole-vocabulary sorts
on each keystroke. Original English definitions remain a separate source.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import time
import zipfile

MAGIC = b"VIMELEX1"
HEADER = struct.Struct("<8s14I")
RECORD = struct.Struct("<BBHI")
PREFIX = struct.Struct("<BBH")
BLOCK = 64


def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128); value >>= 7
    result.append(value)
    return result
DIGITS = str.maketrans("abcdefghijklmnopqrstuvwxyz", "22233344455566677778889999")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def read_entries(path):
    rows = []
    body = False
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if not body:
                body = line.strip() == "..."
                continue
            if not line.strip():
                continue
            parts = line.rstrip("\n\r").split("\t")
            if len(parts) not in (2, 3):
                raise ValueError("Unexpected public export row")
            word, syllables = parts[:2]
            if not word or not re.fullmatch(r"[a-z]+(?: [a-z]+)*", syllables):
                raise ValueError("Unsupported public word/code")
            if len(parts) == 3 and not re.fullmatch(r"[0-9]+", parts[2]):
                raise ValueError("Expected fixed integer export weight")
            code = syllables.replace(" ", "")
            weight = int(parts[2]) if len(parts) == 3 else 0
            if len(syllables) > 255 or len(word.encode()) > 65535 or weight > 0x7fffffff:
                raise ValueError("Public row exceeds mapped format bounds")
            rows.append((code, word, code.translate(DIGITS), "".join(s[0] for s in syllables.split()), weight, syllables))
    if len({(r[0], r[1]) for r in rows}) != len(rows):
        raise ValueError("Duplicate public word/code requires explicit review")
    return sorted(rows, key=lambda r: (r[0], -r[4], r[1]))


def build(args):
    started = time.monotonic()
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Preserve earlier builds; select a fresh output")
    compiled = json.loads((args.compiled / "manifest.json").read_bytes())
    export = json.loads((args.export / "receipt.json").read_bytes())
    prepared = json.loads((args.prepared / "data/manifest.json").read_bytes())
    lock_hash = sha(args.lock)
    if not compiled["compiled"] or hashlib.sha256(encoded(compiled["files"])).hexdigest() != compiled["dataset_id"]:
        raise ValueError("Invalid compiled public dataset identity")
    for name, expected in compiled["files"].items():
        if sha(args.compiled / name) != expected:
            raise ValueError("Public dataset checksum mismatch")
    if not export["passed"] or not export["exports_byte_identical"] or not export["compat_restored"]:
        raise ValueError("Successful repeated public export required")
    if export["private_product_data_read"] or export["product_apk_installed"]:
        raise ValueError("Public-only export required")
    # The old export observation keeps its own source lock. A schema-only lock
    # update may leave its exact public table bytes unchanged; authenticate that
    # input, rather than relabelling the old observation as a new export.
    table_hash = compiled["files"]["build/vime_pinyin.table.bin"]
    if export["table_sha256"] != table_hash:
        raise ValueError("Export input table differs from current public data")
    if {compiled["source_lock_sha256"], prepared["source_lock_sha256"]} != {lock_hash}:
        raise ValueError("Current source locks differ")
    vocabulary = args.export / "run-a.tsv"
    if sha(vocabulary) != export["vocabulary_sha256"]:
        raise ValueError("Public vocabulary checksum mismatch")
    associations = args.prepared / "data/associations.json"
    if sha(associations) != prepared["files"]["data/associations.json"]:
        raise ValueError("Association checksum mismatch")
    rows = read_entries(vocabulary)
    if len(rows) != export["rows"]:
        raise ValueError("Incomplete public vocabulary")
    full = list(range(len(rows)))
    nine = sorted(full, key=lambda i: (rows[i][2], -rows[i][4], rows[i][1], rows[i][0]))
    initials = sorted(full, key=lambda i: (rows[i][3], -rows[i][4], rows[i][1], rows[i][0]))
    prefixes = {}
    ranked = sorted(full, key=lambda i: (-rows[i][4], len(rows[i][0]), rows[i][1], rows[i][0]))
    for i in ranked:
        row = rows[i]
        for mode, column, depth in ((0, 0, 3), (1, 2, 4)):
            typed = row[column]
            for length in range(1, min(depth, len(typed)) + 1):
                key = mode, typed[:length]
                group = prefixes.setdefault(key, [])
                if len(group) < 160:
                    group.append(i)
    prefix_keys = sorted(prefixes)
    prefix_size = len(prefix_keys)*4 + sum(PREFIX.size + len(key[1]) + len(prefixes[key])*4 for key in prefix_keys)
    # Full-order records are already contiguous. Bounded blocks retain random
    # access while encoding their positive record-size deltas once per block.
    relative = []; size = 0
    for code, word, _, _, weight, syllables in rows:
        relative.append(size); size += RECORD.size + len(syllables) + len(word.encode())
    block_count = (len(rows)+BLOCK-1)//BLOCK
    full_data = bytearray(); blocks = []
    for start in range(0,len(rows),BLOCK):
        blocks.append(len(full_data)); full_data += struct.pack("<I",relative[start])
        for i in range(start+1,min(start+BLOCK,len(rows))):
            full_data += varint(relative[i]-relative[i-1])
    full_start = HEADER.size
    full_data_start = full_start + block_count*4
    nine_start = full_data_start + len(full_data)
    initials_start = nine_start + len(rows)*4
    prefix_start = initials_start + len(rows)*4
    data_start = prefix_start + prefix_size
    offsets = []
    body = bytearray()
    for code, word, _, _, weight, syllables in rows:
        offsets.append(data_start + len(body))
        word_bytes = word.encode()
        body += RECORD.pack(len(syllables), len(code), len(word_bytes), weight) + syllables.encode() + word_bytes
    end = data_start + len(body)
    if end >= 0x7fffffff:
        raise ValueError("Mapped lexicon exceeds signed 32-bit address bounds")
    output.mkdir(parents=True)
    binary = output / "input.lex"
    with binary.open("wb") as stream:
        stream.write(HEADER.pack(MAGIC, 2, len(rows), full_start, nine_start, initials_start, prefix_start, data_start, end,
                                 len(prefix_keys), max(len(row[0]) for row in rows), 160, BLOCK, block_count, 0))
        stream.write(struct.pack("<"+str(block_count)+"I",*(full_data_start+i for i in blocks)))
        stream.write(full_data)
        for index in (nine, initials):
            stream.write(struct.pack("<" + str(len(index)) + "I", *(offsets[i] for i in index)))
        cursor = prefix_start + len(prefix_keys)*4
        for key in prefix_keys:
            stream.write(struct.pack("<I", cursor))
            cursor += PREFIX.size + len(key[1]) + len(prefixes[key])*4
        for mode, key in prefix_keys:
            group = prefixes[mode, key]
            stream.write(PREFIX.pack(mode, len(key), len(group)) + key.encode())
            stream.write(struct.pack("<" + str(len(group)) + "I", *(offsets[i] for i in group)))
        stream.write(body)
    if binary.stat().st_size != end:
        raise ValueError("Mapped format size mismatch")
    (output / "associations.json").write_bytes(associations.read_bytes())
    counts = {"entries": len(rows), "words": len({row[1] for row in rows}), "codes": len({row[0] for row in rows}),
              "subunit_weight_rows": sum(row[4] == 0 for row in rows), "prefix_keys": len(prefix_keys),
              "prefix_offsets": sum(map(len, prefixes.values())), "association_prefixes": len(json.loads(associations.read_bytes()))}
    result = {"format": "vime-input-lexicon-1", "version": 2, "source_lock_sha256": lock_hash,
              "index_encoding": "full-order monotone deltas in64-record blocks; complete nine/initials offsets unchanged",
              "rime_dataset_id": compiled["dataset_id"], "table_sha256": table_hash,
              "vocabulary_sha256": export["vocabulary_sha256"], "vocabulary_export_rows": export["rows"],
              "observed_export_source_lock_sha256": export["source_lock_sha256"], "license": "LGPL-3.0", "counts": counts,
              "weight_policy": "Keep all public word/code rows; emitted integer weights retained. Omitted subunit weights stay in tier0 below every emitted weight; no default frequency invented.",
              "files": {name: sha(output / name) for name in ("input.lex", "associations.json")},
              "legacy_vime_input_read": False, "english_sources_merged": False}
    result["dataset_id"] = hashlib.sha256(encoded(result["files"])).hexdigest()
    (output / "manifest.json").write_bytes(encoded(result))
    with zipfile.ZipFile(output / "size-probe.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in result["files"]:
            archive.write(output / name, name)
    report = {"passed": True, "counts": counts, "mapped_bytes": binary.stat().st_size,
              "zip_bytes": (output / "size-probe.zip").stat().st_size, "elapsed_seconds": round(time.monotonic()-started, 2),
              "dataset_id": result["dataset_id"], "sha256": result["files"]["input.lex"]}
    (output / "receipt.json").write_bytes(encoded(report))
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("compiled", "export", "prepared", "lock", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    build(parser.parse_args())
