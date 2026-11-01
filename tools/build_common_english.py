"""Build a separately attributed supplement; refuse uncovered high-frequency words.

CC-CEDICT remains unchanged. The audit baseline is the first 10,000 multi-character
words of the pinned shipped frequency table, not a claim about all Chinese text.
"""
import argparse,collections,hashlib,json,re,struct,sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def sha(b):return hashlib.sha256(b).hexdigest()
def authored():
    result={}
    for line in (ROOT/'data/common-glosses.tsv').read_text('utf-8').splitlines():
        if not line or line.startswith('#'):continue
        word,label=line.split('\t')
        if word in result or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9 ,\x27()./-]{0,149}',label) or not re.search('[A-Za-z]',label):raise ValueError('Invalid/duplicate authored label: '+word)
        result[word]=label
    return result

def baseline():
    raw=(ROOT/'app/src/main/assets/common-frequency.bin').read_bytes()
    manifest=json.loads((ROOT/'app/src/main/assets/common-frequency.json').read_bytes())
    assert sha(raw)==manifest['table_sha256'] and raw[:4]==b'CFQ1'
    rows=[]
    for i in range(struct.unpack_from('<I',raw,4)[0]):
        at=struct.unpack_from('<I',raw,8+4*i)[0];size,freq=struct.unpack_from('<HI',raw,at)
        rows.append((raw[at+6:at+6+size].decode(),freq))
    return sorted(rows,key=lambda r:(-r[1],r[0]))[:10000]

def cardinal(n):
    small='zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen'.split()
    if n<20:return small[n]
    if n<100:return ['','','twenty','thirty','forty','fifty','sixty','seventy','eighty','ninety'][n//10]+('-'+small[n%10] if n%10 else '')
    for size,name in [(1000000000,'billion'),(1000000,'million'),(1000,'thousand'),(100,'hundred')]:
        if n>=size:return cardinal(n//size)+' '+name+(' '+cardinal(n%size) if n%size else '')
    raise ValueError(n)

def number(raw):
    digits=dict(zip('零一二三四五六七八九两',range(10)))
    digits['两']=2
    if not raw or any(c not in digits and c not in '十百千万亿' for c in raw):return None
    total=part=next_digit=0
    for c in raw:
        if c in digits:next_digit=digits[c]
        elif c in '十百千':part+=(next_digit or 1)*{'十':10,'百':100,'千':1000}[c];next_digit=0
        else:total+=(part+next_digit or 1)*{'万':10000,'亿':100000000}[c];part=next_digit=0
    return total+part+next_digit

UNITS={'平方米':('square meter','square meters'),'平方公里':('square kilometer','square kilometers'),
 '立方米':('cubic meter','cubic meters'),'公顷':('hectare','hectares'),'公斤':('kilogram','kilograms'),
 '美元':('US dollar','US dollars'),'千瓦':('kilowatt','kilowatts'),'分钟':('minute','minutes'),
 '人次':('person-visit','person-visits'),'元':('yuan','yuan'),'吨':('tonne','tonnes'),'亩':('mu of land','mu of land'),
 '年':('year','years'),'天':('day','days'),'日':('day','days'),'月':('month','months'),'个':('item','items'),
 '位':('person','people'),'名':('person','people'),'人':('person','people'),'只':('animal','animals'),
 '种':('kind','kinds'),'件':('item','items'),'批':('batch','batches'),'片':('piece','pieces'),
 '座':('building','buildings'),'把':('handful','handfuls'),'段':('section','sections'),'支':('unit','units'),
 '页':('page','pages'),'份':('portion','portions'),'番':('time','times'),'股':('share','shares'),
 '部':('work','works'),'项':('item','items'),'招':('move','moves'),'根':('stem','stems'),'处':('place','places'),
 '顿':('meal','meals'),'条':('strip','strips'),'门':('subject','subjects'),'群':('group','groups'),
 '双':('pair','pairs'),'代':('generation','generations'),'刀':('cut','cuts'),'层':('level','levels'),
 '类':('category','categories'),'封':('letter','letters'),'分':('point','points'),'声':('sound','sounds'),
 '步':('step','steps'),'杯':('cup','cups'),'本':('book','books'),'幅':('picture','pictures'),
 '间':('room','rooms'),'剑':('sword stroke','sword strokes'),'丝':('trace','traces'),'笔':('sum','sums'),
 '辆':('vehicle','vehicles'),'篇':('article','articles'),'团':('clump','clumps'),'碗':('bowl','bowls'),
 '脚':('kick','kicks'),'枚':('piece','pieces'),'趟':('trip','trips'),'架':('machine','machines'),
 '倍':('times as much','times as much'),'出':('act','acts'),'艘':('ship','ships'),'拳':('punch','punches'),
 '首':('poem','poems'),'圈':('circle','circles'),'所':('institution','institutions'),'队':('team','teams'),
 '响':('sound','sounds'),'株':('plant','plants'),'斤':('jin in weight','jin in weight'),'组':('group','groups'),
 '串':('string','strings'),'棵':('tree','trees'),'道':('line','lines'),'块':('piece','pieces'),
 '枝':('branch','branches'),'匹':('horse','horses'),'事':('matter','matters'),'粒':('grain','grains'),
 '幕':('scene','scenes'),'路':('route','routes'),'里':('li in distance','li in distance'),'户':('household','households'),
 '尾':('fish','fish'),'朵':('flower','flowers'),'伙':('group','groups'),'角':('corner','corners'),
 '尺':('chi in length','chi in length'),'届':('session','sessions'),'口':('mouthful','mouthfuls'),'米':('meter','meters')}

def measured(word):
    for unit in sorted(UNITS,key=len,reverse=True):
        if word.endswith(unit):
            raw=word[:-len(unit)];n=number(raw)
            if n is not None and n>0:
                # Short lexical facts; never concatenate arbitrary translations.
                if raw=='两':n=2
                return cardinal(n)+'fold' if unit=='倍' else cardinal(n)+' '+UNITS[unit][n!=1]
            if raw in ('几','多','数'):return 'several '+UNITS[unit][1]
            if raw in ('十几','几十'):return ('a dozen or more ' if raw=='十几' else 'dozens of ')+UNITS[unit][1]
    if word.startswith('第'):
        n=number(word[1:]);ordinals={1:'first',2:'second',3:'third',4:'fourth',5:'fifth',6:'sixth',7:'seventh',8:'eighth',9:'ninth',10:'tenth'}
        if n in ordinals:return ordinals[n]
    return None

def build(args):
    english_manifest=json.loads((ROOT/'app/src/main/assets/english/manifest.json').read_bytes())
    if sha((args.prepared/'data/english.json').read_bytes())!=english_manifest['source_english_sha256']:
        raise ValueError('Prepared source must match the English dictionary actually shipped')
    sys.path.insert(0,str(args.pypinyin_path))
    import pypinyin
    assert pypinyin.__version__=='0.55.0'
    from pypinyin import lazy_pinyin,Style
    forms=collections.defaultdict(list)
    for row in json.loads((args.prepared/'data/english.json').read_bytes())['rows']:forms[row[0]].append(row)
    manual=authored();names=set((ROOT/'data/common-proper-names.txt').read_text('utf-8').split())
    jieba=args.jieba_source.read_bytes()
    assert sha(jieba)==json.loads((ROOT/'app/src/main/assets/common-frequency.json').read_bytes())['source_sha256']
    characters=[]
    for line in jieba.decode().splitlines():
        word,freq,pos=line.split()
        if len(word)==1 and '\u4e00'<=word<='\u9fff':characters.append((word,int(freq)))
    characters=sorted(characters,key=lambda r:(-r[1],r[0]))[:500]
    character_raw=''.join(w+'\t'+str(f)+'\n' for w,f in characters).encode()
    assert character_raw==(ROOT/'data/common-characters.tsv').read_bytes()
    result=[];missing=[];counts=collections.Counter()
    common=baseline()+characters;known={w for w,f in common}
    for word,freq in common+[(w,0) for w in sorted(manual) if w not in known]:
        if len(forms[word])==1 and re.search('[A-Za-z]',forms[word][0][3]):continue
        kind='authored';label=manual.get(word)
        if not label:label=measured(word);kind='measure'
        py=lazy_pinyin(word,style=Style.NORMAL)
        if not label and word in names:
            compound=word[:2] in ['欧阳','慕容','司马','令狐','上官','尉迟','耶律']
            surname=2 if compound else 1
            label=''.join(py[:surname]).capitalize()+' '+''.join(py[surname:]).capitalize();kind='proper-name'
        if not label:missing.append(word);continue
        reading=' '.join(lazy_pinyin(word,style=Style.TONE3,neutral_tone_with_five=True))
        overrides={'大夫':'dai4 fu5','大都':'da4 du1','琢磨':'zuo2 mo5','同行':'tong2 hang2','朝阳':'zhao1 yang2','重重':'chong2 chong2'}
        overrides.update({'了':'le5','和':'he2','的':'de5','这':'zhe4','说':'shuo1','都':'dou1','着':'zhe5','地':'di4','还':'hai2','大':'da4','得':'de2','个':'ge4','将':'jiang1','那':'na4','会':'hui4','给':'gei3','见':'jian4','便':'bian4','没':'mei2','听':'ting1','无':'wu2','长':'chang2','省':'sheng3','万':'wan4','曾':'ceng2','呢':'ne5','女':'nv3','约':'yue1','称':'cheng1','属':'shu3','吧':'ba5','区':'qu1','南':'nan2','行':'xing2','派':'pai4','强':'qiang2','若':'ruo4','重':'zhong4','儿':'er2','红':'hong2','朝':'chao2','哪':'na3','紝':'ren4','转':'zhuan3','么':'me5','石':'shi2','数':'shu4','亲':'qin1','传':'chuan2','提':'ti2','系':'xi4','车':'che1','离':'li2','足':'zu2','合':'he2','居':'ju1','藏':'cang2','落':'luo4','率':'lv4','六':'liu4','似':'si4','调':'tiao2','差':'cha1'})
        reading=overrides.get(word,reading);code=re.sub('[1-5 ]','',reading).replace('u:','v')
        assert re.fullmatch('[a-z]{1,64}',code),(word,reading)
        result.append((code,word,label,reading,kind));counts[kind]+=1
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'unresolved-common.json').write_text(json.dumps(missing,ensure_ascii=False,indent=2),'utf-8')
    if missing:print('Unresolved',len(missing),':',' '.join(missing));return
    raw=''.join('\t'.join(row)+'\n' for row in sorted(result,key=lambda r:r[1])).encode()
    baseline_raw=''.join(w+'\t'+str(f)+'\n' for w,f in baseline()).encode()
    (args.output/'common-english.tsv').write_bytes(raw)
    (args.output/'common-baseline.tsv').write_bytes(baseline_raw)
    report={'format':'jianyi-common-english-1','baseline':10000,'common_characters':500,'character_baseline_sha256':sha(character_raw),'supplement':len(result),'kinds':dict(counts),'sha256':sha(raw),
      'baseline_sha256':sha(baseline_raw),'gloss_source':'data/common-glosses.tsv (authored, CC-BY-SA-4.0); explicit numeric measure rules; proper names are transliterations, not English senses',
      'pinyin_source':'pypinyin 0.55.0 (MIT); explicit common reading corrections','source_english_sha256':sha((args.prepared/'data/english.json').read_bytes()),
      'policy':'Use only when the matched built-in label contains no Latin letters. Match reading hints; keep unknowns empty. Existing CC-CEDICT files remain unchanged.'}
    (args.output/'common-english.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['prepared','pypinyin-path','jieba-source','output']:p.add_argument('--'+key,type=Path,required=True)
    build(p.parse_args())
