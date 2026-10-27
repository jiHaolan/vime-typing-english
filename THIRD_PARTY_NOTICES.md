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
| 可选 CC-CEDICT 词包 | CC-BY-SA-4.0；https://www.mdbg.net/chinese/dictionary?page=cc-cedict ，完整来源/许可随词包提供 |

完整许可证原文位于 app/src/main/assets/licenses/；librime 头文件许可证也位于 native/include/RIME-LICENSE。引擎与依赖精确提交、三架构原生库 SHA-256 和重链接入口见 native/provenance.json、native/README.md。公共字典明文、变换说明和哈希见 app/src/main/assets/rime/。

本项目自行编写 Android 输入法界面与 JNI 适配层；仓库没有收录 Trime 的 GPL Android 前端或历史参考仓库。可选词典需要用户主动导入。发布 APK 内保留第三方许可证和来源说明。
