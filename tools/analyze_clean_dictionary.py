"""Read-only coverage comparison; never feed legacy records into rebuilt assets."""
import argparse
import json
from pathlib import Path

from prepare_clean_dictionary import sha


def analyze(prepared, legacy):
    metadata = json.loads((prepared / "data/manifest.json").read_bytes())
    for name, expected in metadata["files"].items():
        if sha((prepared / name).read_bytes()) != expected:
            raise ValueError("Prepared data hash differs: " + name)
    phonetics = json.loads((prepared / "data/pinyin.json").read_bytes())["rows"]
    forms = json.loads((prepared / "data/english.json").read_bytes())["rows"]
    old = json.loads(legacy.read_bytes())
    new_pairs = {tuple(row) for row in phonetics}
    new_words = {row[1] for row in phonetics}
    old_pairs = {(code, row[0]) for code, rows in old["pw"].items() for row in rows}
    old_words = {word for _, word in old_pairs}
    labels = {row[0] for row in forms if row[3]}
    cards = {row[0] for row in forms}
    missing = {}
    for code, rows in old["pw"].items():
        for row in rows:
            word, frequency = row[:2]
            if word in new_words:
                continue
            previous = missing.get(word)
            if previous is None or (frequency, len(code)) > (previous["legacy_frequency"], len(previous["code"])):
                missing[word] = {"word": word, "code": code, "legacy_frequency": frequency}
    return {"format": "vime-clean-data-coverage-1", "comparison_only": True,
            "legacy_file_sha256": sha(legacy.read_bytes()), "source_lock_sha256": metadata["source_lock_sha256"],
            "counts": metadata["counts"], "coverage": {
                "legacy_fallback_words": len(old_words), "new_fallback_words": len(new_words),
                "legacy_fallback_words_retained": len(old_words & new_words),
                "legacy_fallback_pairs": len(old_pairs), "new_fallback_pairs": len(new_pairs),
                "legacy_fallback_pairs_retained": len(old_pairs & new_pairs),
                "legacy_english_headwords": len(old["en"]), "new_nonempty_english_headwords": len(labels),
                "legacy_english_headwords_retained": len(set(old["en"]) & labels),
                "legacy_card_headwords": len(old["card"]), "new_card_headwords": len(cards),
                "legacy_card_headwords_retained": len(set(old["card"]) & cards)},
            "missing_english_headwords": sorted(set(old["en"]) - labels),
            "missing_card_headwords": sorted(set(old["card"]) - cards),
            "most_frequent_missing_fallback_headwords": sorted(missing.values(), key=lambda row: (-row["legacy_frequency"], row["word"]))[:100],
            "limits": [
                "Headword overlap is not a correctness or ranking pass. Legacy rows may themselves be wrong.",
                "CC-CEDICT is a dictionary, not a conversational phrase lexicon. Many frequent legacy phrases are absent as headwords.",
                "The new native Rime compiler expands the clean Essay vocabulary and composes sentences. Separate real native probes are required.",
                "Fallback-only conversation coverage needs improvement before replacing the shipped aggregate data; do not hide this gap with larger overall counts.",
                "No legacy pinyin, definitions, cards or frequency are incorporated into the generated clean dictionary by this analysis."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--legacy", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists(): raise ValueError("Preserve existing coverage reports")
    report = analyze(args.prepared, args.legacy)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    print(json.dumps(report["coverage"]))
