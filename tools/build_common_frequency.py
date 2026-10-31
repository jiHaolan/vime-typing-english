"""Build the supplemental word-frequency table from a hash-pinned jieba dict.txt (MIT)."""
from pathlib import Path
import argparse, hashlib, json, re, struct

def sha(data): return hashlib.sha256(data).hexdigest()

def build(source):
    rows = {}
    for line in source.decode('utf-8').splitlines():
        parts = line.split()
        if len(parts) != 3: raise ValueError('Invalid source row')
        word, count, _ = parts
        frequency = int(count)
        if re.fullmatch(r'[\u4e00-\u9fff]{2,16}', word) and frequency >= 5:
            rows[word] = max(frequency, rows.get(word, 0))
    words = sorted(rows, key=lambda word: word.encode('utf-8'))
    body = bytearray(); offsets = []
    for word in words:
        raw = word.encode('utf-8'); offsets.append(8 + 4 * len(words) + len(body))
        body.extend(struct.pack('<HI',len(raw),rows[word]) + raw)
    return b'CFQ1' + struct.pack('<I',len(words)) + struct.pack('<'+'I'*len(words),*offsets) + body, len(words)

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--dictionary',required=True,type=Path)
    p.add_argument('--assets',required=True,type=Path)
    args = p.parse_args()
    manifest = json.loads((args.assets/'common-frequency.json').read_bytes())
    source = args.dictionary.read_bytes()
    if sha(source) != manifest['source_sha256']: raise ValueError('Source differs from pinned bytes')
    data,count = build(source)
    if sha(data) != manifest['table_sha256'] or count != manifest['entries']: raise ValueError('Rebuild differs from locked table')
    (args.assets/'common-frequency.bin').write_bytes(data)
    print(json.dumps({'entries':count,'bytes':len(data),'sha256':sha(data)}))
