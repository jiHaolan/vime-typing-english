"""Static source-resource checks. These do not run Android views or prove device compatibility."""
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
RES = ROOT / "app/src/main/res"
A = "{http://schemas.android.com/apk/res/android}"
checks = []
def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)

for file in RES.rglob("*.xml"):
    ET.parse(file)
check("全部Android资源XML可解析", True)
row_results = {}
for filename in ["keyboard_qwerty.xml", "keyboard_number_row.xml", "keyboard_numpad.xml"]:
    root = ET.parse(RES / "xml" / filename).getroot()
    widths = []
    codes = []
    for row in root.findall("Row"):
        total = 0
        for key in row.findall("Key"):
            width = key.get(A + "keyWidth", row.get(A + "keyWidth", root.get(A + "keyWidth")))
            assert width.endswith("%p"), width
            total += float(width[:-2])
            codes.append(int(key.get(A + "codes")))
        widths.append(round(total, 3))
    check(filename + "每行键宽总和为100%且不越界", all(99.9 <= x <= 100.1 for x in widths))
    row_results[filename] = {"row_width_percent": widths, "key_count": len(codes)}
    if filename != "keyboard_qwerty.xml":
        check(filename + "含完整0—9和返回/退格/空格/动作键", all(c in codes for c in [*range(48, 58), -20006, -5, 32, -4]))
    else:
        check("全键盘保留符号/数字/语言/空格/退格/动作键", all(c in codes for c in [-20003, -2, -20001, 32, -5, -4]))

layout = ET.parse(RES / "layout/ime_input.xml").getroot()
buttons = [v for v in layout.iter() if v.get("style") == "@style/ToolbarTile"]
check("五个工具按钮均有可访问名称且没有语音工具栏", len(buttons) == 5 and all(v.get(A + "contentDescription") for v in buttons) and not any(v.get(A+"id") == "@+id/tbVoice" for v in layout.iter()))
check("五个工具均有图标与短文字", all(v.find("ImageView") is not None and v.find("TextView") is not None for v in buttons))
service = (ROOT / "app/src/main/java/com/vime/ime/VimeImeService.kt").read_text(encoding="utf-8")
main = (ROOT / "app/src/main/java/com/vime/ime/MainActivity.kt").read_text(encoding="utf-8")
check("无通用输入法切换弹窗，外部回退仅列出已启用语音子类型",
      all(x not in service + main for x in ["showImeSwitcher", "showInputMethodPicker"])
      and 'inputManager.enabledInputMethodList' in service
      and 'it.second.mode.equals("voice",true)' in service)
check("后台候选与旧结果版本检查已接线", "candidateWorker.submit" in service and "queryGate.accepts(token)" in service)
check("候选栏有独立显隐容器", any(v.get(A + "id") == "@+id/candidateBar" for v in layout.iter()))

manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
permissions = [v.get(A + "name") for v in manifest.findall("uses-permission")]
check("仅申请用户要求的录音、振动、联网权限", set(permissions) == {"android.permission.RECORD_AUDIO", "android.permission.VIBRATE", "android.permission.INTERNET"})
voice = manifest.find("application").findall("activity")
check("语音中转Activity不向其他应用导出", any(v.get(A + "name") == ".VoiceInputActivity" and v.get(A + "exported") == "false" for v in voice))
permission_host = (ROOT / "app/src/main/java/com/vime/ime/VoiceInputActivity.kt").read_text(encoding="utf-8")
check("语音权限宿主无独立识别页面", "setContentView" not in permission_host and "requestPermissions" in permission_host)
check("符号浮层不决定键盘窗口高度", layout.tag == "com.vime.ime.ImeRootLayout" and any(v.get(A+"id") == "@+id/toolbarGuard" and v.get(A+"layout_height") == "12dp" for v in layout.iter()))
check("空格语音与取消生命周期已接线", "setVoiceGestureListener" in service and "voice.cancel(); editorSerial++" in service and "b.voiceStop.setOnClickListener" in service)

def luminance(value):
    rgb = [int(value[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    rgb = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in rgb]
    return sum(v * w for v, w in zip(rgb, [.2126, .7152, .0722]))
def contrast(a, b):
    hi, lo = sorted([luminance(a), luminance(b)], reverse=True)
    return (hi + .05) / (lo + .05)

ratios = {}
for directory in ["values", "values-night"]:
    colours = {v.get("name"): v.text for v in ET.parse(RES / directory / "colors.xml").getroot().findall("color")}
    ratios[directory] = {"key_text": contrast(colours["ink"], colours["key"]), "key_hint": contrast(colours["ink_3"], colours["key"]), "action_text": contrast("#FFFFFF", colours["acc"])}
    check(directory + "常态主字/副字/动作字颜色对比>=4.5", all(v >= 4.5 for v in ratios[directory].values()))
    attr = {v.get("name"): colours[v.text.removeprefix("@color/")] for v in ET.parse(RES/directory/"themes.xml").getroot().find("style").findall("item") if v.get("name", "").startswith("vime") and v.text.startswith("@color/")}
    for style in ET.parse(RES/directory/"skin_themes.xml").getroot().findall("style"):
        skin = attr | {v.get("name"): v.text for v in style.findall("item")}
        data = {"main":contrast(skin["vimeInk"], skin["vimeKey"]), "hint":contrast(skin["vimeInk3"], skin["vimeKey"]), "action":contrast(skin["vimeOnAcc"], skin["vimeAcc"]), "accent_label":contrast(skin["vimeAccInk"], skin["vimeAccSoft"])}
        ratios[directory + "/" + style.get("name")] = data
        check(directory + style.get("name") + "皮肤文本对比>=4.5", all(v >= 4.5 for v in data.values()))

report = {"kind": "静态资源/几何/隐私声明检查，不是Android运行", "passed": len(checks), "checks": checks, "keyboard_rows": row_results, "contrast_ratios": ratios, "phone_tested": False}
if len(sys.argv) > 1:
    Path(sys.argv[1]).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
