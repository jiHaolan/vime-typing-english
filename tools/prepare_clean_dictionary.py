"""Build separated, source-locked dictionaries without reading any legacy Vime data.

CC-CEDICT supplies phonetics and senses; Essay supplies separate frequency and
prefix-association data; ECDICT supplies word/tag metadata. Rime uses only the
pinned Luna/Essay inputs. Conversion is offline and refuses an existing output.
"""
import argparse
from collections import defaultdict
from decimal import Decimal
import gzip
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import subprocess

from prepare_levels import levels

VERSION = "0.23.0"
# Rime's pinned filesystem clock conversion samples two clocks. A half-second
# offset keeps that tiny rounding error away from an integer-second boundary.
# Preserve real compiler bytes rather than editing generated checksums/headers.
EPOCH_MS = 946684800500
REF = re.compile(r"^(?:see |(?:old |archaic |unofficial )?variant of |same as |dialectal equivalent of )", re.I)
NON_GLOSS = re.compile(r"^(?:CL:|surname |also written|Taiwan pr\.|used in |also pr\.|old name for )", re.I)
TARGET = re.compile(r"(?:[\u3400-\u9fff]+\|)?([\u3400-\u9fff]+)(?:\[([^]]+)])?")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def normalized(reading):
    code = re.sub(r"[0-5\s'·-]", "", reading.lower().replace("u:", "v").replace("ü", "v"))
    return code if re.fullmatch(r"[a-z]{1,64}", code) else None


def primary(sense):
    text = re.split(r"\s*[/;；\n]\s*|,\s*|，", sense.strip(), maxsplit=1)[0]
    text = re.sub(r"\([^)]*\)|（[^）]*）|\[[^]]*]", "", text)
    return re.sub(r"\s+", " ", text).strip()


def load_cedict(data, declared_count):
    text = gzip.decompress(data).decode("utf-8")
    if "Attribution-ShareAlike 4.0" not in text[:3000]:
        raise ValueError("Unexpected CC-CEDICT license")
    records = defaultdict(list)
    ordinal = 0
    skipped = defaultdict(int)
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        ordinal += 1
        match = re.fullmatch(r"(\S+) (\S+) \[(.*?)] /(.+)/", line)
        if not match:
            raise ValueError("Malformed source record " + str(ordinal))
        traditional, word, reading, glosses = match.groups()
        code = normalized(reading)
        if code is None or not 1 <= len(word.encode("utf-16-le")) // 2 <= 64:
            skipped["unsupported-phonetics-or-headword-length"] += 1
            continue
        if any(ord(c) < 32 or ord(c) == 127 for c in word + reading + glosses):
            raise ValueError("Control character in source record")
        records[word].append({"code": code, "reading": reading, "senses": glosses.split("/"), "row": ordinal})
    if ordinal != declared_count:
        raise ValueError("Source record count differs from locked snapshot")
    return dict(records), dict(skipped)


def derive_senses(records):
    def resolve(word, row, seen=()):
        direct = [s for s in row["senses"] if s and not REF.search(s) and not NON_GLOSS.search(s)]
        if direct:
            return direct, [row["row"]]
        if word in seen or len(seen) >= 3:
            return [], [row["row"]]
        for sense in row["senses"]:
            if not REF.search(sense):
                continue
            match = TARGET.search(sense)
            if not match:
                continue
            target, reading = match.groups()
            for other in records.get(target, []):
                if reading and normalized(reading) != other["code"]:
                    continue
                values, source_rows = resolve(target, other, (*seen, word))
                if values:
                    return values, [row["row"], *source_rows]
        return [], [row["row"]]

    result = []
    for word, rows in sorted(records.items()):
        by_code = defaultdict(list)
        for row in rows:
            by_code[row["code"]].append(row)
        for code, forms in sorted(by_code.items()):
            senses = []
            source_rows = []
            # Lowercase common readings precede same-spelling surname entries.
            forms.sort(key=lambda row: (row["reading"][:1].isupper(), row["row"]))
            for row in forms:
                values, provenance = resolve(word, row)
                source_rows += provenance
                for value in values:
                    if value not in senses:
                        senses.append(value)
            label = primary(senses[0]) if senses else ""
            original = senses or [s for row in forms for s in row["senses"] if s]
            result.append([word, code, forms[0]["reading"], label,
                           original[0] if original else "", original[1] if len(original) > 1 else "",
                           sorted(set(source_rows))])
    return result


def source_blob(folder, spec, name):
    # A delivered source kit contains the exact canonical blobs, without needing
    # Git history. Both paths must match the same independently locked hash.
    data = (subprocess.check_output(["git", "-C", str(folder), "show", spec["commit"] + ":" + name])
            if (folder / ".git").exists() else (folder / name).read_bytes())
    if sha(data) != spec["files"][name]:
        raise ValueError("Pinned upstream blob mismatch: " + name)
    return data


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def converter_package(spec):
    if importlib.metadata.version(spec["package"]) != spec["version"]:
        raise ValueError("Wrong OpenCC converter version")
    import opencc
    package = Path(opencc.__file__).parent
    for group in ("data_sha256", "code_sha256"):
        for name, expected in spec[group].items():
            if sha((package / name).read_bytes()) != expected:
                raise ValueError("OpenCC converter file changed: " + name)
    for name, expected in spec["metadata_sha256"].items():
        if sha((package.parent / spec["dist_info"] / name).read_bytes()) != expected:
            raise ValueError("OpenCC converter metadata changed: " + name)
    return package


def reading_weight(raw, frequency):
    if not raw:
        return Decimal(frequency)
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?%?", raw):
        raise ValueError("Unsupported Luna reading weight")
    return Decimal(raw[:-1]) * Decimal(frequency) / 100 if raw.endswith("%") else Decimal(raw)


def luna_entries(luna, frequencies):
    rows = {}
    body = luna.decode("utf-8").partition("...\n")[2]
    from opencc import OpenCC
    converter = OpenCC("t2s")
    for line in body.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        word, code = converter.convert(parts[0]), parts[1]
        if 1 <= len(word) <= 64 and re.fullmatch(r"[a-z]+(?: [a-z]+)*", code):
            weight = reading_weight(parts[2] if len(parts) > 2 else "", frequencies.get(word, 100))
            # t2s can collapse duplicate variants. Preserve the strongest stated
            # reading instead of inflating it by counting the same code twice.
            rows[word, code] = max(weight, rows.get((word, code), Decimal(0)))
    return rows


def rime_sources(luna, frequencies):
    rows = luna_entries(luna, frequencies)
    if len(rows) < 60000:
        raise ValueError("Incomplete Luna dictionary")
    header = f'''# Derived only from pinned Luna Pinyin and Rime Essay. See NOTICE-rime.txt.
---
name: vime_pinyin
version: "{VERSION}"
sort: by_weight
use_preset_vocabulary: true
...
'''
    dictionary = header + "".join(f"{word}\t{code}\t{format(weight, 'f')}\n" for (word, code), weight in sorted(rows.items()))
    schema = f'''schema:
  schema_id: vime_pinyin
  name: 智能拼音
  version: "{VERSION}"
switches:
  - name: ascii_mode
    reset: 0
    states: [中文, English]
engine:
  processors: [ascii_composer, recognizer, key_binder, speller, punctuator, selector, navigator, express_editor]
  segmentors: [ascii_segmentor, matcher, abc_segmentor, punct_segmentor, fallback_segmentor]
  translators: [punct_translator, script_translator]
  filters: [uniquifier]
speller:
  alphabet: zyxwvutsrqponmlkjihgfedcba
  delimiter: " '"
  algebra:
    - abbrev/^([a-z]).+$/$1/
    - abbrev/^([zcs]h).+$/$1/
translator:
  dictionary: vime_pinyin
  enable_user_dict: true
  enable_sentence: true
  enable_completion: true
  contextual_suggestions: true
  max_sentence_length: 0
menu:
  page_size: 10
'''
    nine = schema.replace("schema_id: vime_pinyin", "schema_id: vime_nine").replace("name: 智能拼音", "name: 智能九键")
    nine = nine.replace("alphabet: zyxwvutsrqponmlkjihgfedcba", "alphabet: 23456789")
    nine = nine.replace("    - abbrev/^([a-z]).+$/$1/\n    - abbrev/^([zcs]h).+$/$1/", "    - xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/")
    nine = nine.replace("  dictionary: vime_pinyin", "  dictionary: vime_pinyin\n  prism: vime_nine")
    return {"vime_pinyin.dict.yaml": dictionary, "vime_pinyin.schema.yaml": schema, "vime_nine.schema.yaml": nine,
            "default.yaml": f'config_version: "{VERSION}"\nschema_list:\n  - schema: vime_pinyin\n  - schema: vime_nine\nmenu:\n  page_size: 10\n'}, len(rows)


def build(args):
    lock = json.loads(args.lock.read_text("utf-8"))
    spec = lock["sources"]
    if args.output.exists():
        raise ValueError("Use a new output directory; existing data is preserved")
    converter_package(spec["conversion"])
    import opencc
    raw = args.cedict.read_bytes()
    if sha(raw) != spec["cedict"]["sha256"] or sha(args.ecdict.read_bytes()) != spec["ecdict"]["csv_sha256"]:
        raise ValueError("Source checksum mismatch")
    luna = source_blob(args.luna, spec["luna"], "luna_pinyin.dict.yaml")
    pinyin_module = source_blob(args.luna, spec["luna"], "pinyin.yaml").decode("utf-8")
    if not all(rule in pinyin_module for rule in ("abbrev/^([a-z]).+$/$1/", "abbrev/^([zcs]h).+$/$1/")):
        raise ValueError("Review abbreviation semantics for the new Luna module")
    essay = source_blob(args.essay, spec["essay"], "essay.txt")
    converter = opencc.OpenCC("t2s")
    frequencies = {}
    for line in essay.decode("utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and parts[1].isdigit():
            word = converter.convert(parts[0])
            frequencies[word] = frequencies.get(word, 0) + int(parts[1])
    records, skipped = load_cedict(raw, spec["cedict"]["source_records"])
    english = derive_senses(records)
    phonetics = sorted({(row["code"], word) for word, rows in records.items() for row in rows})
    weights = sorted((word, frequencies[word]) for word in records if word in frequencies)
    associations = defaultdict(dict)
    for word, count in frequencies.items():
        if count < 1000 or not 2 <= len(word) <= 8 or not all("\u3400" <= c <= "\u9fff" for c in word):
            continue
        for end in range(1, min(4, len(word) - 1) + 1):
            prefix, suffix = word[:end], word[end:]
            if prefix in records and len(suffix) <= 4:
                associations[prefix][suffix] = max(count, associations[prefix].get(suffix, 0))
    associations = {key: sorted(values, key=lambda v: (-values[v], v))[:12] for key, values in sorted(associations.items())}
    exam = levels(args.ecdict)
    output = args.output
    write(output / "data/pinyin.json", encoded({"format": "vime-pinyin-1", "rows": phonetics}))
    write(output / "data/english.json", encoded({"format": "vime-english-1", "rows": english}))
    write(output / "data/frequencies.json", encoded({"format": "vime-frequencies-1", "rows": weights}))
    write(output / "data/levels.json", encoded({"format": "vime-levels-1", "entries": exam}))
    write(output / "data/associations.json", encoded(associations))
    source_files, count = rime_sources(luna, frequencies)
    source_files["essay.txt"] = "".join(f"{word}\t{value}\n" for word, value in sorted(frequencies.items()))
    for name, text in source_files.items():
        write(output / "rime" / name, text.encode("utf-8"))
    rime_hashes = {name: sha((output / "rime" / name).read_bytes()) for name in sorted(source_files)}
    source_id = sha(encoded(rime_hashes))
    manifest = {"format": "vime-rime-2", "version": VERSION, "source_id": source_id, "dataset_id": source_id,
                "dictionary_entries": count, "vocabulary_entries": len(frequencies), "compiled": False,
                "reading_weight_policy": "Preserve Luna percentage/numeric reading weights with Decimal; unstated uses Essay count or100; t2s duplicate word/code keeps maximum. Do not promote0%/rare readings to common frequency.",
                "abbreviation_policy": "Use Luna pinyin.yaml abbrev rules for initials/zh-ch-sh; never promote abbreviations to ordinary full-code derivations.",
                "source_lock_sha256": sha(args.lock.read_bytes()), "files": rime_hashes,
                "luna_source": spec["luna"], "essay_source": spec["essay"], "compiler_source_mtime_ms": EPOCH_MS}
    write(output / "rime/manifest.json", encoded(manifest))
    files = {p.relative_to(output).as_posix(): sha(p.read_bytes()) for p in sorted((output / "data").glob("*.json"))}
    meta = {"format": "vime-dictionary-data-1", "version": VERSION, "source_lock_sha256": sha(args.lock.read_bytes()),
            "sources": spec, "files": files, "licenses": {"data/pinyin.json": "CC-BY-SA-4.0",
            "data/english.json": "CC-BY-SA-4.0", "data/frequencies.json": "LGPL-3.0",
            "data/associations.json": "LGPL-3.0", "data/levels.json": "MIT"},
            "counts": {"phonetic_pairs": len(phonetics), "headwords": len(records), "senses": len(english),
                       "english_labels": sum(bool(row[3]) for row in english), "weight_rows": len(weights),
                       "association_prefixes": len(associations), "level_rows": len(exam), "unsupported": skipped},
            "transformation": "CC-CEDICT simplified headwords and tone-free readings; group equal word/readings; first two source senses; resolve equivalent references at most3 hops; source ordinals retained; no gloss invented. Essay weights and prefix associations stored separately; ECDICT word/tag policy unchanged. No legacy Vime input is read."}
    write(output / "data/manifest.json", encoded(meta))
    print(json.dumps(meta["counts"], ensure_ascii=False))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for key in ("lock", "luna", "essay", "cedict", "ecdict", "output"):
        p.add_argument("--" + key, required=True, type=Path)
    build(p.parse_args())
