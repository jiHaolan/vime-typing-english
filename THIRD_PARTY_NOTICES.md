# 第三方来源与许可

本仓库没有给所有文件附加统一许可证。自有应用代码尚未选择额外开源许可证；以下第三方文件始终保留原作者与原许可证，不能被这一说明覆盖。

| 组件/数据 | 许可与来源 |
| --- | --- |
| librime | BSD-3-Clause；https://github.com/rime/librime |
| Luna Pinyin / Rime Essay 派生公共数据 | LGPL-3.0；https://github.com/rime/rime-luna-pinyin 、 https://github.com/rime/rime-essay |
| Boost | BSL-1.0 |
| glog / LevelDB | BSD-3-Clause |
| Lua / yaml-cpp | MIT |
| marisa-trie | BSD 许可证，保留完整原文 |
| OpenCC | Apache-2.0 |
| Android C++ runtime | Apache-2.0 with LLVM exceptions |
| AndroidX / Kotlin 等 Gradle 依赖 | 各自上游许可证；未将依赖重授予其他许可 |
| 内置 CC-CEDICT 派生释义 | 历史源文件标明 CC-BY-SA-3.0、2018-10-31；不同于下方可选扩展包 |
| MakeMeAHanzi 字义兜底 | dictionary.txt 的上游 COPYING 声明 LGPL-3.0-or-later；不使用其笔画 graphics.txt |
| ECDICT 考试标签（0.22.0） | 上游 MIT；固定提交 ab22ad36302cafc6349a050e2b60894855208055，仅提取 word/tag，不导入释义、音标或词频 |
| jieba 候选词频（0.24.1） | MIT；固定提交67fa2e36e72f69d9134b8a1037b83fbb070b9775，仅提取dict.txt的word/frequency；许可原文与生成摘要随包提供 |
| 少量补充词条 | 原代码记录 Wiktionary 与本地 CC-CEDICT 参考；补充说明与 CC-BY-SA-4.0 原文已附带，确切历史修订仍待核对 |
| 可选 CC-CEDICT 词包 | CC-BY-SA-4.0；https://www.mdbg.net/chinese/dictionary?page=cc-cedict ，完整来源/许可随词包提供 |

完整许可证原文位于 app/src/main/assets/licenses/；librime 头文件许可证也位于 native/include/RIME-LICENSE。引擎与依赖精确提交、三架构原生库 SHA-256 和重链接入口见 native/provenance.json、native/README.md。公共字典明文、变换说明和哈希见 app/src/main/assets/rime/。

本项目自行编写 Android 输入法界面与 JNI 适配层；仓库没有收录 Trime 的 GPL Android 前端或历史参考仓库。可选词典需要用户主动导入。发布 APK 内保留第三方许可证和来源说明。

0.22.0可从输入设置、离线词典或隐私说明打开「开源与数据来源」，离线查看许可原文。`app/src/main/assets/notices.json`列出13组组件、47个解析的生产运行模块和21份文档哈希。运行依赖来自实际Gradle解析及其POM（含Guava父POM继承），而非手工猜测版本；构建插件与测试依赖未列为APK运行库。GNU GPL 3.0伴随文本与LGPL 3.0文本一并保留。

当前清单并不证明全部商用权利已完成。历史拼音/词频与联想表的精确源快照和再分发许可、混合释义的逐词来源/清洁重建，以及原生依赖内嵌的第三方说明仍在核对。`notices/data-source-audit.json`保存已追溯生成脚本、输入文件和原始导出一致性证据。普通正式候选构建会拒绝这三项未解决状态；明确的validation-only及本地debug预览继续可用于开发，不会被标为已获商用发布许可。详见[来源生成与检查](docs/NOTICES.md)。
