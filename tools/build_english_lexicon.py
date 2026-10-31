"""Encode all source-locked CC-CEDICT tonal senses using a mapped string pool.

No legacy/hand definitions are read. Repeated text is interned, not discarded;
ECDICT word/tag labels remain a separate MIT file. Every source ordinal remains.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
from build_input_lexicon import encoded, sha, varint

HEADER = struct.Struct("<8s22I")
RECORD = struct.Struct("<HHIIH")
FORM = struct.Struct("<IHH")
REVERSE = struct.Struct("<HI")
TOKENS = re.compile(r"[A-Za-z][A-Za-z-]+")
SUFFIXES = ("ing", "ed", "es", "s", "ly", "er", "est")


def grade(label, levels):
    best = 0
    ranks = {"CET4": 1, "CET6": 2, "TOEFL": 3}
    for match in TOKENS.finditer(label):
        token = match[0].lower().strip("-")
        best = max(best, ranks.get(levels.get(token), 0))
        for suffix in SUFFIXES:
            if len(token) > len(suffix)+2 and token.endswith(suffix):
                best = max(best, ranks.get(levels.get(token[:-len(suffix)]), 0))
    return best


def integers(values):
    return struct.pack("<"+str(len(values))+"I",*values)


def build(args):
    if args.output.exists(): raise ValueError("Preserve previous builds")
    meta = json.loads((args.prepared / "data/manifest.json").read_bytes())
    if meta["source_lock_sha256"] != sha(args.lock): raise ValueError("Source lock mismatch")
    for name in ("english", "levels"):
        if sha(args.prepared / ("data/"+name+".json")) != meta["files"]["data/"+name+".json"]:
            raise ValueError("Source data hash mismatch")
    source = json.loads((args.prepared / "data/english.json").read_bytes())
    if source["format"]!="vime-english-2":raise ValueError("Versioned complete tonal source required")
    rows = source["rows"]
    levels = json.loads((args.prepared / "data/levels.json").read_bytes())["entries"]
    if len(rows) != meta["counts"]["senses"] or len(rows) != 121814:
        raise ValueError("Complete locked CC-CEDICT groups required")
    if rows != sorted(rows,key=lambda r:(r[0],r[1])) or len({(r[0],r[1]) for r in rows})!=len(rows):
        raise ValueError("Noncanonical source groups")
    text = {""}
    for row in rows:
        if len(row)!=8 or not row[7]: raise ValueError("All tonal forms are required")
        text.add(row[3])
        for form in row[7]:
            if not form["reading"] or not form["senses"] or not form["source_rows"]:
                raise ValueError("Missing tonal provenance")
            text.add(form["reading"]);text.update(form["senses"])
    strings=sorted(text);ids={value:i for i,value in enumerate(strings)}
    if strings[0]!="" or any("\0" in s or len(s.encode())>65535 for s in strings):
        raise ValueError("String pool bounds exceeded")
    pool=bytearray();pool_relative=[]
    for value in strings:pool_relative.append(len(pool));pool+=value.encode()+b"\0"
    blocks=[];pool_index=bytearray()
    for start in range(0,len(strings),64):
        blocks.append(len(pool_index));pool_index+=struct.pack("<I",pool_relative[start])
        for i in range(start+1,min(start+64,len(strings))):pool_index+=varint(pool_relative[i]-pool_relative[i-1])
    body=bytearray();relative=[];reverse={};labelled=set();graded=set();tonal=senses=0
    for ordinal,row in enumerate(rows):
        word,code,_,label,_,_,_,forms=row
        word_bytes=word.encode();code_bytes=code.encode("ascii")
        if len(word_bytes)>65535 or len(code_bytes)>128:raise ValueError("Word/code bounds exceeded")
        if label:labelled.add(word)
        if grade(label,levels):graded.add(word)
        for token in TOKENS.findall(label):
            token=token.lower().strip("-")
            if len(token)>=2:reverse.setdefault(token,ordinal)
        payload=bytearray()
        for form in forms:
            values=form["senses"];sources=form["source_rows"]
            if not 1<=len(values)<=65535 or not 1<=len(sources)<=65535:raise ValueError("Form bounds exceeded")
            payload+=FORM.pack(ids[form["reading"]],len(values),len(sources))
            payload+=integers([ids[v] for v in values])+integers(sources)
        if len(payload)>1024*1024 or len(forms)>65535:raise ValueError("Payload bounds exceeded")
        relative.append(len(body));body+=RECORD.pack(len(word_bytes),len(code_bytes),ids[label],len(payload),len(forms))
        body+=word_bytes+code_bytes+payload;tonal+=len(forms);senses+=sum(len(v["senses"]) for v in forms)
    tokens=sorted(reverse);reverse_body=bytearray();reverse_relative=[]
    for token in tokens:
        raw=token.encode("ascii");reverse_relative.append(len(reverse_body))
        reverse_body+=REVERSE.pack(len(raw),reverse[token])+raw
    group_start=HEADER.size;reverse_start=group_start+len(rows)*4
    reverse_data=reverse_start+len(tokens)*4;data_start=reverse_data+len(reverse_body)
    pool_start=data_start+len(body);pool_blocks=pool_start+len(blocks)*4
    pool_data=pool_blocks+len(pool_index);end=pool_data+len(pool)
    if end>=0x7fffffff:raise ValueError("Mapped size exceeds signed offsets")
    args.output.mkdir(parents=True)
    counts={"groups":len(rows),"headwords":len({r[0] for r in rows}),"labelled_headwords":len(labelled),
            "graded_headwords":len(graded),"tonal_forms":tonal,"senses":senses,"reverse_tokens":len(tokens),"level_words":len(levels),"pooled_strings":len(strings)}
    path=args.output/"english.lex"
    with path.open("wb") as stream:
        stream.write(HEADER.pack(b"VIMEENG2",2,len(rows),counts["headwords"],group_start,reverse_start,reverse_data,data_start,pool_start,pool_data,end,
                                 len(tokens),len(strings),len(blocks),max(len(r[0].encode()) for r in rows),len(labelled),len(graded),tonal,senses,0,64,max(len(s.encode()) for s in strings),0))
        stream.write(integers([data_start+i for i in relative]));stream.write(integers([reverse_data+i for i in reverse_relative]))
        stream.write(reverse_body);stream.write(body);stream.write(integers([pool_blocks+i for i in blocks]));stream.write(pool_index);stream.write(pool)
    if path.stat().st_size!=end:raise ValueError("Encoded size differs")
    (args.output/"levels.json").write_bytes((args.prepared/"data/levels.json").read_bytes())
    manifest={"format":"vime-english-lexicon-1","version":2,"source_lock_sha256":sha(args.lock),
              "source_english_sha256":meta["files"]["data/english.json"],"counts":counts,
              "sources":{"english":meta["sources"]["cedict"],"levels":meta["sources"]["ecdict"]},
              "licenses":{"english.lex":"CC-BY-SA-4.0","levels.json":"MIT"},
              "files":{name:sha(args.output/name) for name in ("english.lex","levels.json")},
              "legacy_english_read":False,"hand_overrides_read":False,
              "policy":"Keep all normalized groups, every tonal form/sense and source ordinals. Interned text is not removed. Default label is source-first common reading; different spellings require unique input match. Reverse English is a definition-token clue, not an independent English-Chinese dictionary."}
    manifest["dataset_id"]=hashlib.sha256(encoded(manifest["files"])).hexdigest()
    (args.output/"manifest.json").write_bytes(encoded(manifest))
    with zipfile.ZipFile(args.output/"size-probe.zip","w",zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for name in manifest["files"]:archive.write(args.output/name,name)
    result={"passed":True,"counts":counts,"mapped_bytes":end,"zip_bytes":(args.output/"size-probe.zip").stat().st_size,
            "dataset_id":manifest["dataset_id"],"sha256":manifest["files"]["english.lex"]}
    (args.output/"receipt.json").write_bytes(encoded(result));print(json.dumps(result),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("prepared","lock","output"):parser.add_argument("--"+name,required=True,type=Path)
    build(parser.parse_args())
