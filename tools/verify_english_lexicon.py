"""Independently decode every mapped word, tonal sense, source ordinal and reverse token."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import re


def sha(path):
    with path.open("rb") as stream:return hashlib.file_digest(stream,"sha256").hexdigest()


def verify(args):
    manifest=json.loads((args.lexicon/"manifest.json").read_bytes())
    for name,h in manifest["files"].items():
        if name not in ("english.lex","levels.json") or sha(args.lexicon/name)!=h:raise ValueError("Asset checksum mismatch")
    canonical=json.dumps(manifest["files"],sort_keys=True,separators=(',',':')).encode()
    if hashlib.sha256(canonical).hexdigest()!=manifest["dataset_id"]:raise ValueError("Dataset identity differs")
    path=args.prepared/"data/english.json"
    if sha(path)!=manifest["source_english_sha256"]:raise ValueError("Source English differs")
    source=json.loads(path.read_bytes())["rows"];raw=(args.lexicon/"english.lex").read_bytes()
    header=struct.unpack_from('<8s22I',raw)
    magic,version,count,heads,groups,reverse,reverse_data,records,pool_index,pool_data,end,tokens,strings,blocks,maxword,labelled,graded,tonal,senses,reserved,blocksize,maxstring,last=header
    if magic!=b'VIMEENG2' or version!=2 or groups!=96 or end!=len(raw) or count!=len(source) or blocks!=(strings+63)//64 or blocksize!=64 or reserved or last:
        raise ValueError("Mapped header differs")
    if reverse!=96+count*4 or reverse_data!=reverse+tokens*4:raise ValueError("Index bounds differ")
    pool=raw[pool_data:];values=[v.decode('utf-8') for v in pool.split(b'\0')[:-1]]
    if not pool.endswith(b'\0') or len(values)!=strings or values!=sorted(set(values)) or values[0]!='':raise ValueError("String pool is incomplete")
    offsets=[];pos=0
    for value in values:offsets.append(pos);pos+=len(value.encode())+1
    for block in range(blocks):
        cursor=struct.unpack_from('<I',raw,pool_index+block*4)[0]
        relative=struct.unpack_from('<I',raw,cursor)[0];cursor+=4
        if relative!=offsets[block*64]:raise ValueError("Pool block offset differs")
        for i in range(1,min(64,strings-block*64)):
            delta=shift=0
            while True:
                part=raw[cursor];cursor+=1;delta|=(part&127)<<shift
                if part<128:break
                shift+=7
                if shift>28:raise ValueError("Invalid pool delta")
            relative+=delta
            if relative!=offsets[block*64+i]:raise ValueError("Missing pooled text")
    expected_reverse={};observed_tonal=observed_senses=0;cursor=records
    for i,row in enumerate(source):
        at=struct.unpack_from('<I',raw,groups+i*4)[0]
        if at!=cursor:raise ValueError("Missing or duplicated source group")
        word_size,code_size,label,payload,forms=struct.unpack_from('<HHIIH',raw,at);cursor=at+14
        word=raw[cursor:cursor+word_size].decode();cursor+=word_size
        code=raw[cursor:cursor+code_size].decode('ascii');cursor+=code_size
        if (word,code,values[label])!=(row[0],row[1],row[3]):raise ValueError("Word/reading/label changed")
        if forms!=len(row[7]):raise ValueError("Tonal form omitted")
        start=cursor
        for expected in row[7]:
            reading,n,ordinals=struct.unpack_from('<IHH',raw,cursor);cursor+=8
            ids=struct.unpack_from('<'+str(n)+'I',raw,cursor);cursor+=n*4
            provenance=list(struct.unpack_from('<'+str(ordinals)+'I',raw,cursor));cursor+=ordinals*4
            if values[reading]!=expected['reading'] or [values[v] for v in ids]!=expected['senses'] or provenance!=expected['source_rows']:
                raise ValueError("Meaning/phonetics/provenance changed")
            observed_tonal+=1;observed_senses+=n
        if cursor-start!=payload:raise ValueError("Payload bounds differ")
        for token in re.findall(r'[A-Za-z][A-Za-z-]+',row[3]):
            token=token.lower().strip('-')
            if len(token)>=2:expected_reverse.setdefault(token,i)
    if cursor!=pool_index or observed_tonal!=tonal or observed_senses!=senses:raise ValueError("Total senses differ")
    observed=[]
    for i in range(tokens):
        at=struct.unpack_from('<I',raw,reverse+i*4)[0];length,target=struct.unpack_from('<HI',raw,at)
        token=raw[at+6:at+6+length].decode('ascii');observed.append(token)
        if expected_reverse.get(token)!=target:raise ValueError("Reverse token target differs")
    if observed!=sorted(expected_reverse):raise ValueError("Reverse token missing")
    result={'passed':True,'groups':count,'headwords':heads,'tonal_forms':tonal,'senses':senses,'pooled_strings':strings,
            'all_source_records_and_ordinals_equal':True,'all_reverse_tokens_equal':True,'dataset_id':manifest['dataset_id']}
    args.report.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('lexicon','prepared','report'):parser.add_argument('--'+name,required=True,type=Path)
    verify(parser.parse_args())
