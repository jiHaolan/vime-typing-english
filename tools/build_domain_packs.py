"""Export independently importable, deterministic CC-CEDICT domain packs.

Words are selected by an explicit seed list or explicit parenthesized subject
markers. Missing seeds are reported, never assigned invented source meanings.
"""
import argparse,hashlib,json,re,zipfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(raw):return hashlib.sha256(raw).hexdigest()

def make_pack(path,id,name,entries,notice,license_text):
    raw=''.join('\t'.join(row)+'\n' for row in sorted(set(entries))).encode()
    count=len(set(entries))
    assert count>0 and count<=180000
    manifest=f'format=vimelex-1\nid={id}\nname={name}\nversion=2026.11.01\nsource=https://www.mdbg.net/chinese/dictionary?page=cc-cedict\nlicense=CC-BY-SA-4.0\nentries={count}\nsha256={sha(raw)}\n'
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for filename,data in [('manifest.properties',manifest.encode()),('entries.tsv',raw),('NOTICE.txt',notice.encode()),('LICENSE.txt',license_text)]:
            info=zipfile.ZipInfo(filename,(2026,11,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            z.writestr(info,data,compresslevel=9)
    return {'file':path.name,'entries':count,'headwords':len({r[1] for r in entries}),'bytes':path.stat().st_size,'sha256':sha(path.read_bytes()),'entries_sha256':sha(raw)}

def build(args):
    if args.output.exists():raise ValueError('Use a fresh output directory')
    raw=(args.prepared/'data/english.json').read_bytes()
    manifest=json.loads((ROOT/'app/src/main/assets/english/manifest.json').read_bytes())
    assert sha(raw)==manifest['source_english_sha256']
    source=json.loads(raw)['rows'];spec=json.loads((ROOT/'data/domain-seeds.json').read_bytes())
    sys.path.insert(0,str(args.pypinyin_path))
    from pypinyin import lazy_pinyin,Style
    authored={}
    for line in (ROOT/'data/domain-extra.tsv').read_text('utf-8').splitlines():
        if line and not line.startswith('#'):
            domain,word,gloss=line.split('\t');assert domain in spec and re.search('[A-Za-z]',gloss)
            assert (domain,word) not in authored;authored[domain,word]=gloss
    license_text=(ROOT/'app/src/main/assets/licenses/CC-BY-SA-4.0.txt').read_bytes()
    license_text+=b'\n\nPinyin generation: pypinyin0.55.0 (MIT)\n'+(ROOT/'app/src/main/assets/licenses/pypinyin-MIT.txt').read_bytes()
    upstream=manifest['sources']['english'];args.output.mkdir(parents=True)
    report={'format':'jianyi-domain-packs-1','version':'2026.11.01','source':upstream,'source_english_sha256':sha(raw),'domains':{}}
    provenance={};all_rows=[]
    for domain,config in spec.items():
        seeds=set(config['words'].split());marker=re.compile(r'\([^)]*\b(?:'+config['tags']+r')\b[^)]*\)',re.I)
        entries=[];found=set();proof=[]
        for row in source:
            word,code,reading,label=row[:4]
            senses=[v for form in row[7] for v in form['senses']]
            if not re.fullmatch('[\u3400-\u9fff]{1,20}',word) or (len(word)<2 and word not in seeds) or not label or not re.search('[A-Za-z]',label):continue
            selected=word in seeds or any(marker.search(s) for s in senses)
            if not selected:continue
            # Keep spelling and tonal labels together, including source ordinals.
            value=re.sub(r'\s+',' ',label).strip()
            if len(value)>1000:continue
            entries.append((code,word,value,reading));found.add(word)
            proof.append({'word':word,'code':code,'source_rows':row[6],'selection':'seed' if word in seeds else 'source-subject-marker'})
        for (category,word),gloss in authored.items():
            if category!=domain:continue
            assert word not in found,('Authored term already exists in source',domain,word)
            reading=' '.join(lazy_pinyin(word,style=Style.TONE3,neutral_tone_with_five=True))
            code=re.sub('[1-5 ]','',reading)
            assert re.fullmatch('[a-z]{1,64}',code)
            entries.append((code,word,gloss,reading));found.add(word)
            proof.append({'word':word,'code':code,'source_rows':[],'selection':'authored-domain-extra','gloss':gloss})
        assert len(found)>=30,(domain,len(found))
        notice=f'''键忆 · {config['name']} · 2026.11.01\nData derived from CC-CEDICT, MDBG and the CC-CEDICT contributors.\nSource: {upstream['url']}\nSnapshot: {upstream['version']}\nCompressed source SHA-256: {upstream['sha256']}\nPrepared source SHA-256: {sha(raw)}\nLicense: Creative Commons Attribution-ShareAlike 4.0 International\nhttps://creativecommons.org/licenses/by-sa/4.0/\nChanges: simplified headwords grouped by input reading; source reference resolution follows the shipped builder; short first senses retained; selected domain seeds or explicit source subject markers.\nNo claim that this is an exhaustive professional terminology standard. Full source ordinals and missing seed report accompany the release.\n可完全离线使用。多个领域包可共存；新导入包在同词同读音冲突时优先，同标识的新版本更新旧包。\n'''
        notice+='Additional authored terms: data/domain-extra.tsv (CC-BY-SA-4.0), with pypinyin0.55.0 (MIT) spellings; separately identified in provenance.\n'
        meta=make_pack(args.output/f'Jianyi-{domain}-2026.11.01.vimelex','jianyi-domain-'+domain,config['name'],entries,notice,license_text)
        meta.update(name=config['name'],missing_seeds=sorted(seeds-found));report['domains'][domain]=meta
        provenance[domain]=proof;all_rows.extend(entries)
    notice='Jianyi combined domain pack. Derived from CC-CEDICT / MDBG, snapshot '+upstream['version']+'. CC-BY-SA-4.0.\nSource: '+upstream['url']+'\nCombines all eight subject selections; see domain-provenance.json. Simplified headwords grouped by input readings; full senses remain in built-in dictionary.\n'
    notice+='Authored additions: data/domain-extra.tsv (CC-BY-SA-4.0); pypinyin0.55.0 spellings (MIT), separate from CC-CEDICT source records.\n'
    report['combined']=make_pack(args.output/'Jianyi-all-domains-2026.11.01.vimelex','jianyi-domain-all','全部专业领域',all_rows,notice,license_text)
    (args.output/'domain-packs.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    (args.output/'domain-provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,separators=(',',':')),'utf-8')
    (args.output/'SHA256SUMS-packs.txt').write_text(''.join(row['sha256']+'  '+row['file']+'\n' for row in list(report['domains'].values())+[report['combined']]),'utf-8')
    print(json.dumps({k:{'headwords':v['headwords'],'entries':v['entries'],'bytes':v['bytes'],'missing_seeds':len(v['missing_seeds'])} for k,v in report['domains'].items()},ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--prepared',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--pypinyin-path',type=Path,required=True)
    build(p.parse_args())
