"""Exhaustively authenticate every public mapped row/index against its source export."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import struct

from build_input_lexicon import HEADER, MAGIC, PREFIX, RECORD, DIGITS, encoded, sha


def verify(args):
    manifest = json.loads((args.lexicon / "manifest.json").read_bytes())
    if hashlib.sha256(encoded(manifest["files"])).hexdigest() != manifest["dataset_id"]:
        raise ValueError("Input dataset identity mismatch")
    for name, expected in manifest["files"].items():
        if name not in ("input.lex", "associations.json") or sha(args.lexicon / name) != expected:
            raise ValueError("Input data checksum mismatch")
    if sha(args.vocabulary) != manifest["vocabulary_sha256"]:
        raise ValueError("Source vocabulary checksum mismatch")
    raw = (args.lexicon / "input.lex").read_bytes()
    magic, version, count, full, nine, initials, prefixes, data, end, prefix_count, max_code, cap, *reserved = HEADER.unpack_from(raw)
    if magic!=MAGIC or full!=64 or end!=len(raw) or cap!=160 or version not in (1,2):
        raise ValueError("Mapped header differs")
    if version==1:
        if (nine,initials,prefixes,reserved)!=(64+count*4,64+count*8,64+count*12,[0,0,0]):raise ValueError("V1 index bounds differ")
    elif reserved!=[64,(count+63)//64,0] or initials!=nine+count*4 or prefixes!=initials+count*4:
        raise ValueError("V2 block bounds differ")
    records = {}
    position = data
    while position < end:
        offset = position
        syllable_length, code_length, word_length, weight = RECORD.unpack_from(raw, position)
        position += RECORD.size
        syllables = raw[position:position+syllable_length].decode("ascii"); position += syllable_length
        word = raw[position:position+word_length].decode("utf-8"); position += word_length
        code = syllables.replace(" ", "")
        if len(code) != code_length or not word or not code:
            raise ValueError("Malformed mapped record")
        records[offset] = code, word, code.translate(DIGITS), "".join(s[0] for s in syllables.split()), weight
    if position != end or len(records) != count or max(len(r[0]) for r in records.values()) != max_code:
        raise ValueError("Mapped row count/bounds differ")
    source = {}
    for line in args.vocabulary.read_text("utf-8").partition("...\n")[2].splitlines():
        if not line: continue
        parts = line.split("\t")
        key = parts[1].replace(" ", ""), parts[0]
        if key in source: raise ValueError("Duplicate source row")
        source[key] = int(parts[2]) if len(parts) == 3 else 0
    actual = {(r[0], r[1]): r[4] for r in records.values()}
    if source != actual or count != manifest["vocabulary_export_rows"]:
        raise ValueError("Mapped content differs from the complete public export")
    for start, column in ((full,0),(nine,2),(initials,3)):
        if version==1 or start!=full:
            offsets = struct.unpack_from("<"+str(count)+"I",raw,start)
        else:
            offsets=[]
            for block in range((count+63)//64):
                cursor=struct.unpack_from('<I',raw,64+block*4)[0]
                relative=struct.unpack_from('<I',raw,cursor)[0];cursor+=4;offsets.append(data+relative)
                for i in range(min(63,count-block*64-1)):
                    delta=shift=0
                    while True:
                        value=raw[cursor];cursor+=1;delta|=(value&127)<<shift
                        if value<128:break
                        shift+=7
                        if shift>28:raise ValueError('Invalid variable offset')
                    if delta<=0:raise ValueError('Nonpositive delta')
                    relative+=delta;offsets.append(data+relative)
        expected = sorted(records,key=lambda i:(records[i][column],-records[i][4],records[i][1],records[i][0]))
        if list(offsets) != expected:
            raise ValueError("Missing/duplicated/unsorted mapped index")
    expected = defaultdict(list)
    for offset in sorted(records,key=lambda i:(-records[i][4],len(records[i][0]),records[i][1],records[i][0])):
        row = records[offset]
        for mode,column,depth in ((0,0,3),(1,2,4)):
            for size in range(1,min(depth,len(row[column]))+1):
                key = mode,row[column][:size]
                if len(expected[key])<160: expected[key].append(offset)
    if len(expected) != prefix_count:raise ValueError("Missing prefix keys")
    seen = []
    for index in range(prefix_count):
        offset = struct.unpack_from("<I",raw,prefixes+index*4)[0]
        mode,size,entries = PREFIX.unpack_from(raw,offset)
        key = mode,raw[offset+4:offset+4+size].decode("ascii")
        values = list(struct.unpack_from("<"+str(entries)+"I",raw,offset+4+size))
        if expected[key] != values:raise ValueError("Incomplete/incorrect weighted prefix index")
        seen.append(key)
    if seen != sorted(expected):raise ValueError("Prefix keys are not canonical")
    result={"passed":True,"entries_authenticated":count,"index_offsets_authenticated":count*3,
            "prefix_keys_authenticated":prefix_count,"prefix_offsets_authenticated":sum(map(len,expected.values())),
            "all_source_rows_retained":True,"mapped_sha256":manifest["files"]["input.lex"],"dataset_id":manifest["dataset_id"]}
    args.report.write_bytes(encoded(result))
    print(json.dumps(result),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("lexicon","vocabulary","report"):parser.add_argument("--"+name,required=True,type=Path)
    verify(parser.parse_args())
