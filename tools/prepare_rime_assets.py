from pathlib import Path
import json,hashlib,shutil,subprocess,argparse
parser=argparse.ArgumentParser(description="Rebuild public Rime data from pinned upstream checkouts")
for name in ["luna","essay","librime","prebuilt"]:
    parser.add_argument("--"+name,required=True,type=Path)
args=parser.parse_args()
from opencc import OpenCC
root=Path(__file__).resolve().parent.parent
app=root
assets=app/"app/src/main/assets/rime"
assets.mkdir(parents=True,exist_ok=True)
conv=OpenCC("t2s")
base=args.luna
essay=args.essay
freq={}
for line in (essay/'essay.txt').read_text(encoding='utf-8').splitlines():
 parts=line.split('\t')
 if len(parts)==2 and parts[1].isdigit():
  word=conv.convert(parts[0]);freq[word]=freq.get(word,0)+int(parts[1])
(assets/'essay.txt').write_text(''.join(f'{w}\t{n}\n' for w,n in sorted(freq.items())),encoding='utf-8')
rows={};readings={};data=False
for line in (base/'luna_pinyin.dict.yaml').read_text(encoding='utf-8').splitlines():
 if line=='...':data=True;continue
 if not data or not line or line.startswith('#'):continue
 cols=line.split('\t')
 if len(cols)<2:continue
 word,code=conv.convert(cols[0]),cols[1]
 if not word or len(word)>64 or any(c<' ' for c in word):continue
 rows[word,code]=freq.get(word,100)
 if len(word)==1:readings.setdefault(word,set()).add(code)
dictionary=json.loads((app/'app/src/main/assets/dict.android.json').read_text(encoding='utf-8'))['pw']
def spell(word,py):
 memo={}
 def go(i,pos):
  if i==len(word):return [] if pos==len(py) else None
  if (i,pos) in memo:return memo[i,pos]
  for reading in sorted(readings.get(word[i],()),key=len,reverse=True):
   if py.startswith(reading,pos):
    rest=go(i+1,pos+len(reading))
    if rest is not None:return [reading]+rest
  memo[i,pos]=None;return None
 return go(0,0)
for py,items in dictionary.items():
 for item in items:
  word=item[0];syll=spell(word,py)
  if syll:rows.setdefault((word,' '.join(syll)),freq.get(word,max(1,int(item[1]))*10))
header='''# Derived simplified data for Vime. See NOTICE-rime.txt and upstream licenses.
---
name: vime_pinyin
version: "0.14.0"
sort: by_weight
use_preset_vocabulary: true
...
'''
(assets/'vime_pinyin.dict.yaml').write_text(header+''.join(f'{word}\t{py}\t{n}\n' for (word,py),n in sorted(rows.items())),encoding='utf-8')
schema='''schema:
  schema_id: vime_pinyin
  name: 智能拼音
  version: "0.14.0"
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
    - derive/^([a-z]).+$/$1/
    - derive/^([zcs]h).+$/$1/
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
(assets/'vime_pinyin.schema.yaml').write_text(schema,encoding='utf-8')
nine=schema.replace('schema_id: vime_pinyin','schema_id: vime_nine').replace('name: 智能拼音','name: 智能九键').replace('alphabet: zyxwvutsrqponmlkjihgfedcba','alphabet: 23456789').replace('    - derive/^([a-z]).+$/$1/\n    - derive/^([zcs]h).+$/$1/','    - xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/').replace('  dictionary: vime_pinyin','  dictionary: vime_pinyin\n  prism: vime_nine')
(assets/'vime_nine.schema.yaml').write_text(nine,encoding='utf-8')
(assets/'default.yaml').write_text('config_version: "0.14.0"\nschema_list:\n  - schema: vime_pinyin\n  - schema: vime_nine\nmenu:\n  page_size: 10\n',encoding='utf-8')
license_dir=app/'app/src/main/assets/licenses';license_dir.mkdir(exist_ok=True)
for source,name in [(base/'LICENSE','luna-pinyin-LGPL-3.0.txt'),(essay/'LICENSE','rime-essay-LGPL-3.0.txt'),(args.librime/'LICENSE','librime-BSD-3-Clause.txt')]:shutil.copy2(source,license_dir/name)
for filename in ['candidate.h','menu.h','common.h']:shutil.copy2(args.librime/'src/rime'/filename,app/'native/include/rime'/filename)
def git(path,*args):return subprocess.check_output(['git','-C',str(path),*args],text=True).strip()
meta={'format':'vime-rime-1','version':'0.14.0','native_prebuilt':{'url':'https://github.com/fcitx5-android/prebuilt','commit':git(args.prebuilt,'rev-parse','HEAD')},'librime_source':{'url':'https://github.com/rime/librime','commit':git(args.librime,'rev-parse','HEAD')},'luna_source':{'url':'https://github.com/rime/rime-luna-pinyin','commit':git(base,'rev-parse','HEAD')},'essay_source':{'url':'https://github.com/rime/rime-essay','commit':git(essay,'rev-parse','HEAD')},'transformation':'OpenCC-python t2s conversion; merge equal words and sum vocabulary counts; add spellable existing Vime entries','dictionary_entries':len(rows),'vocabulary_entries':len(freq),'files':{p.relative_to(assets).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in assets.rglob('*') if p.is_file() and p.name!='manifest.json'}}
(assets/'manifest.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
(app/'app/src/main/assets/NOTICE-rime.txt').write_text('Vime includes the BSD-licensed Rime engine and derived simplified-Chinese Luna Pinyin/Rime Essay data.\nData licensed LGPL-3.0; transformations and exact commits are in rime/manifest.json. Original license texts are in licenses/.\nNative dependencies: Boost (BSL-1.0), glog (BSD-3-Clause), LevelDB (BSD-3-Clause), Lua (MIT), marisa-trie (BSD), OpenCC (Apache-2.0), yaml-cpp (MIT), and the Android C++ runtime (Apache-2.0 with LLVM exceptions).\nNative static provider: https://github.com/fcitx5-android/prebuilt. Source and relinking build entry are shipped with the Vime source archive.\n',encoding='utf-8')
print(json.dumps({'dictionary_entries':len(rows),'vocabulary_entries':len(freq),'files':meta['files']},ensure_ascii=False))
