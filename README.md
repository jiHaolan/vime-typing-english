# 边打字边背单词 · Vime

Android 中文输入法 + 英语提示 + 生词复习。先把打字做好，再利用日常输入认识和记住英文。

**当前版本：v0.16.0 预览版。** 欢迎安装试用，通过 [Issues](https://github.com/fasdwertioklopmnbnbw3456-del/vime-typing-english/issues) 反馈。仍在迭代，尚未上架手机应用商店。

## 下载安装

| 安装包 | 适用设备 |
| --- | --- |
| [arm64-v8a · 大多数安卓手机](https://github.com/fasdwertioklopmnbnbw3456-del/vime-typing-english/releases/download/v0.16.0/vime-v0.16.0-arm64-v8a.apk) | 约 24.7 MB，推荐先选这个 |
| [armeabi-v7a · 32 位安卓手机](https://github.com/fasdwertioklopmnbnbw3456-del/vime-typing-english/releases/download/v0.16.0/vime-v0.16.0-armeabi-v7a.apk) | 较老的 32 位设备 |
| [x86_64 · 模拟器](https://github.com/fasdwertioklopmnbnbw3456-del/vime-typing-english/releases/download/v0.16.0/vime-v0.16.0-x86_64.apk) | Android x86_64 模拟器 |

[完整发布页](https://github.com/fasdwertioklopmnbnbw3456-del/vime-typing-english/releases/tag/v0.16.0) · [可选 CC-CEDICT 词包](https://github.com/fasdwertioklopmnbnbw3456-del/vime-typing-english/releases/download/v0.16.0/CC-CEDICT-2026-10-19.vimelex) · [更新记录](CHANGELOG.md)

Android 7.0 及以上。安装后在系统设置中启用并选择本输入法。首次切换请在普通记事本中测试；现有项目签名的旧版可覆盖升级。

## 已有功能

- Rime 全拼与九键、整句候选、候选展开和本地用户词频学习。
- 英文按键直接上屏，不需要额外确认；主动点击候选可补全单词。
- 中文候选上方显示一个简短英文义项；长按候选查看完整词卡、收藏或播放英文发音。
- 生词本、间隔复习、学习数据和估算遗忘曲线。
- 主题配色、按键触感、横竖屏独立键盘尺寸与自定义短语。
- 长按空格 2 秒调用手机语音服务，松手结束；默认无需填写 API 密钥。
- 可选离线词包、词典查询、剪贴板、表情和符号工具。

语音识别和英文发音复用手机现有服务；手机可能需要英语语音包或网络。不随 APK 捆绑大型语音模型。没有可用服务时，使用应用内的语音/发音设置入口检查。

## 这版修正

英文单字母直接提交，修复连续英文超过 128 字符后的丢字；增加轻微滑动容错、填补按键间隙；缩短候选释义；补全词卡、生词本、复习答案与查词发音；修正自定义配色下候选过淡，以及大字体词卡标题裁切。

## 反馈问题

请在 [新建 Issue](https://github.com/fasdwertioklopmnbnbw3456-del/vime-typing-english/issues/new/choose) 中说明手机型号、Android 版本、应用版本、键盘布局、复现步骤和预期结果。截图请先遮住聊天、账号和其他私人信息，不要提交密码、API 密钥或个人词库。

## 验证范围

100 项 JVM 测试、43 项静态检查、46 项 Android 场景检查分组通过；最终仅修订词卡对齐属性后的 APK，DEX 与功能测试包一致，并单独复测显示配置。包含真实系统 TTS 完成回调与受控语音生命周期测试。**模拟器通过不代表所有手机的误触率、识别准确率或实际听感。** 见 [验证说明](docs/VERIFICATION.md)。

## 构建与隐私

- [构建说明](docs/BUILD.md)：JDK 17、Android SDK 34、Gradle 8.9。仓库包含三种 ABI 的 Rime 原生库与公共词典，通常无需重新编译原生引擎。
- [隐私说明](docs/PRIVACY.md)：候选查询和学习记录以本机处理为主，主动语音功能由所选服务处理。
- [第三方来源与许可证](THIRD_PARTY_NOTICES.md)：Rime、词典数据及依赖保留各自许可证。自有应用代码暂未选择额外的开源许可证；公开仓库不等于给所有文件统一授予 MIT 许可。

目前是免费预览版本，尚未接入套餐计费或支付。
