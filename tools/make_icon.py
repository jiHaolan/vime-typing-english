#!/usr/bin/env python3
"""Jianyi: original keycap/open-book vectors, with Pillow exports for legacy PNGs.

Run: python tools/make_icon.py --preview E:/path/to/icon-previews
Pillow is a build tool only; no dependency is added to the Android application.
"""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw, ImageOps

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'app/src/main/res'
SIZES={'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}
TOP='#21384F';BOTTOM='#0B1322';FACE='#F5F6F2';INK='#182638';GOLD='#E4B863';BASE='#9FAFBE'
OUTER='M41,29 H67 Q79,29 79,41 V69 Q79,81 67,81 H41 Q29,81 29,69 V41 Q29,29 41,29 Z'
CAP='M41,29 H67 Q79,29 79,41 V65 Q79,77 67,77 H41 Q29,77 29,65 V41 Q29,29 41,29 Z'
LEFT='M38,44 Q46,40 53,45 V65 Q46,60 38,63 Z'
RIGHT='M55,45 Q63,40 71,44 V63 Q63,60 55,65 Z'
BOOKMARK='M62,43 H66 V57 L64,55 L62,57 Z'

def vector(paths):
    return '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'+paths+'\n</vector>\n'

def write_vectors():
    foreground='\n'.join(f'    <path android:fillColor="{color}" android:pathData="{path}" />' for color,path in [(BASE,OUTER),(FACE,CAP),(INK,LEFT),(INK,RIGHT),(GOLD,BOOKMARK)])
    (RES/'drawable/ic_launcher_fg.xml').write_text(vector(foreground),encoding='utf-8')
    mono=f'    <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="{OUTER} {LEFT} {RIGHT} {BOOKMARK}" />'
    (RES/'drawable/ic_launcher_mono.xml').write_text(vector(mono),encoding='utf-8')
    background=f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr name="android:fillColor"><gradient android:startX="0" android:startY="0" android:endX="0" android:endY="108" android:startColor="{TOP}" android:endColor="{BOTTOM}" android:type="linear" /></aapt:attr>
    </path>
</vector>
'''
    (RES/'drawable/ic_launcher_background.xml').write_text(background,encoding='utf-8')
    for api in [26,33]:
        folder=RES/f'mipmap-anydpi-v{api}';folder.mkdir(exist_ok=True)
        mono_tag='\n    <monochrome android:drawable="@drawable/ic_launcher_mono" />' if api>=33 else ''
        (folder/'ic_launcher.xml').write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />{mono_tag}
</adaptive-icon>
''',encoding='utf-8')

def q(a,b,c):
    return [((1-t)**2*a[0]+2*(1-t)*t*b[0]+t*t*c[0],(1-t)**2*a[1]+2*(1-t)*t*b[1]+t*t*c[1]) for t in [i/32 for i in range(33)]]

def render(size=512,shape='squircle',themed=False):
    # Adaptive layers are108dp; launcher masks show the middle72dp. Raster
    # fallbacks use the same viewport instead of shrinking the brand twice.
    ss=4;n=size*ss;scale=n/72
    pos=lambda p:((p[0]-18)*scale,(p[1]-18)*scale)
    box=lambda x0,y0,x1,y1:(*pos((x0,y0)),*pos((x1,y1)))
    gradient=Image.linear_gradient('L').resize((n,int(n*108/72))).crop((0,int(n*18/72),n,int(n*90/72)))
    image=ImageOps.colorize(gradient,TOP,BOTTOM).convert('RGBA')
    if themed:image=Image.new('RGBA',(n,n),'#DCE5E9')
    draw=ImageDraw.Draw(image)
    draw.rounded_rectangle(box(29,29,79,81),radius=12*scale,fill=INK if themed else BASE)
    if not themed:draw.rounded_rectangle(box(29,29,79,77),radius=12*scale,fill=FACE)
    left=q((38,44),(46,40),(53,45))+[(53,65)]+q((53,65),(46,60),(38,63))
    right=q((55,45),(63,40),(71,44))+[(71,63)]+q((71,63),(63,60),(55,65))
    for page in [left,right]:draw.polygon([pos(point) for point in page],fill='#DCE5E9' if themed else INK)
    draw.polygon([pos(p) for p in [(62,43),(66,43),(66,57),(64,55),(62,57)]],fill=INK if themed else GOLD)
    mask=Image.new('L',(n,n),0);m=ImageDraw.Draw(mask)
    if shape=='circle':m.ellipse((0,0,n-1,n-1),fill=255)
    elif shape=='square':m.rectangle((0,0,n,n),fill=255)
    else:m.rounded_rectangle((0,0,n-1,n-1),radius=n*.235,fill=255)
    image.putalpha(mask)
    return image.resize((size,size),Image.Resampling.LANCZOS)

def svg():
    paths='\n'.join(f'<path fill="{color}" d="{path}"/>' for color,path in [(BASE,OUTER),(FACE,CAP),(INK,LEFT),(INK,RIGHT),(GOLD,BOOKMARK)])
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">
<defs><linearGradient id="navy" x1="0" y1="0" x2="0" y2="1"><stop stop-color="{TOP}"/><stop offset="1" stop-color="{BOTTOM}"/></linearGradient></defs>
<rect x="0" y="0" width="108" height="108" fill="url(#navy)"/>
{paths}
</svg>
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--preview',type=Path);args=parser.parse_args()
    write_vectors()
    for bucket,size in SIZES.items():render(size).save(RES/f'mipmap-{bucket}/ic_launcher.png',optimize=True)
    brand=ROOT/'design-system/jianyi';brand.mkdir(parents=True,exist_ok=True)
    (brand/'icon.svg').write_text(svg(),encoding='utf-8')
    if args.preview:
        args.preview.mkdir(exist_ok=True,parents=True)
        render().save(args.preview/'jianyi-icon.png')
        render(shape='circle').save(args.preview/'jianyi-icon-round.png')
        render(themed=True).save(args.preview/'jianyi-icon-themed.png')
        render(48).resize((384,384),Image.Resampling.NEAREST).save(args.preview/'jianyi-icon-48px-check.png')
        (args.preview/'jianyi-icon.svg').write_text(svg(),encoding='utf-8')
    print('Generated Jianyi adaptive, themed and five legacy-density icons.')

if __name__=='__main__':main()
