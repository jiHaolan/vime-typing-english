"""Replace legacy exam tags using only word/tag fields from a pinned ECDICT CSV.

No translations, pronunciations, word frequencies or commercial dictionary text
are imported from ECDICT. Other built-in dictionary sections stay byte-equivalent
as JSON values. Original upstream notices are kept separately.
"""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path
import re

COMMIT = "ab22ad36302cafc6349a050e2b60894855208055"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def levels(source):
    result = {}
    with source.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if not {"word", "tag"} <= set(reader.fieldnames or []):
            raise ValueError("Missing ECDICT word/tag columns")
        for row in reader:
            word = row["word"].lower()
            if not re.fullmatch(r"[a-z][a-z-]{1,63}", word):
                continue
            tags = row["tag"].split()
            for accepted, label in (({"zk", "gk", "cet4"}, "CET4"), ({"cet6"}, "CET6"), ({"toefl"}, "TOEFL")):
                if accepted.intersection(tags):
                    # The CET4 learning tier includes explicitly marked school basics.
                    priority = {"CET4": 0, "CET6": 1, "TOEFL": 2}
                    if word not in result or priority[label] < priority[result[word]]:
                        result[word] = label
                    break
    if not all(sum(v == key for v in result.values()) > 1000 for key in ("CET4", "CET6", "TOEFL")):
        raise ValueError("Unexpectedly incomplete exam metadata")
    return dict(sorted(result.items()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--csv", required=True, type=Path)
    p.add_argument("--sha256", required=True)
    p.add_argument("--levels", required=True, type=Path)
    p.add_argument("--receipt", required=True, type=Path)
    a = p.parse_args()
    source_hash = digest(a.csv.read_bytes())
    if source_hash != a.sha256:
        raise ValueError("ECDICT source checksum mismatch")
    data = {"format":"vime-levels-1","entries":levels(a.csv)}
    output = encode(data)
    receipt = {"format": "vime-level-source-2", "source": "https://github.com/skywind3000/ECDICT",
               "commit": COMMIT, "csv_sha256": source_hash, "license": "MIT",
               "license_file": "licenses/ecdict-MIT.txt", "fields_used": ["word", "tag"],
               "transformation": "ASCII lowercase words; CET4 learning tier includes zk/gk/cet4 basics, then cet6, then toefl; lowest tier wins; untagged words remain unclassified",
               "tag_policy": {"CET4": ["zk", "gk", "cet4"], "CET6": ["cet6"], "TOEFL": ["toefl"]},
               "counts": dict(sorted(collections.Counter(data["entries"].values()).items())),
               "levels_sha256": digest(encode(data["entries"])), "levels_file_sha256": digest(output),
               "legacy_dictionary_asset_present":False,"english_replacement_manifest":"english/manifest.json"}
    a.levels.write_bytes(output)
    a.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(json.dumps({"count": len(data["entries"]), "counts": receipt["counts"]}))


if __name__ == "__main__":
    main()
