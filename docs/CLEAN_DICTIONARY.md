# 0.23 词库重建：第一阶段历史记录

当前集成及完整声调义项见[0.23.0记录](COMPLETE_ENGLISH_LEXICON.md)。

此页保留 bc62964 阶段的原始数据和证据。当时应用为 0.22.0，重建数据尚未接入；该阶段仍丢失了原始读音比例，并把简拼写为普通派生。两项问题已在 [0.22.1 读音修复](RIME_READING_FIX.md)修正，Rime 部分已接入应用。以下旧数据标识、来源包哈希与测试记录不代表当前 APK，商用来源阻断仍保留。

## 固定来源与物理分离

`notices/dictionary-sources.lock.json`固定原始文件、转换程序和许可原文的哈希。Git 工作区通过 `git show <commit>:<path>`读取规范 blob；来源包中的非 Git 目录必须满足完全相同的文件哈希。转换不读取旧 Vime 数据，旧文件只由独立分析器作覆盖比较。

| 输出 | 来源 | 当前数量 |
| --- | --- | --- |
| `data/pinyin.json` | CC-CEDICT 2026-10-19，CC-BY-SA-4.0 | 121814 拼音/词条对，121093 词头 |
| `data/english.json` | 同一 CC-CEDICT 快照 | 121814 读音记录；120194 个词头有非空英文提示 |
| `data/frequencies.json` | 固定 Rime Essay，经固定 OpenCC t2s 转换 | 110721 个匹配词头 |
| `data/associations.json` | Essay 中频率至少 1000 的词，确定性前缀/后缀分拆 | 5937 个前缀，最多各 12 项 |
| `data/levels.json` | 固定 ECDICT，仅 `word/tag` | 10514 项，沿用已核对的基础/CET6/TOEFL 策略 |
| `rime/*` | Luna `46acf031...`、Essay `054920de...` | 67299 明文读音行、437796 词汇项 |

完整提交与转换表哈希见锁文件。CC-CEDICT 声明 125303 条原始记录；447 条读音/长度不支持的记录计入报告，没有猜测拼音。释义按词与读音分组，保留所选前两个义项和源记录序号。序号不计注释及空行；`used in` 不会把整词意思冒充成单字释义，等价引用最多解析三跳。多音字数据已分开，应用的候选与词卡仍需接入相应读音上下文。

## 隔离编译与实测

`tools/dictionary-compiler`是单独的 test-only x86_64 工程，不属于主应用 Gradle settings。它只使用与主应用完全相同哈希的 JNI 库，包含五个固定明文输入，没有主应用 Application、用户文件导入、网络或麦克风权限。每轮在新私有目录编译，只导出七个固定的公共编译文件及测试报告。

`tools/compile_clean_dictionary.py`在任何安装前检查包名、源文件字节、原生库字节、指定专用模拟器 `emulator-5580`、真实 16KB 页面及启动状态。兼容回退关闭并读回，结束后恢复并读回。旧证据、现有运行目录和未列出的文件不会被当作新编译输出。

Rime 固定版本的时钟转换会在整秒边界产生一秒差异。最初两轮确实产生不同 schema 时间戳及 prism 校验值，该失败保留在 `compiler-16k-a`。现在源 mtime 使用 `2000-01-01T00:00:00.500Z`，不修改任何编译后的头或校验值；最终两轮的七个文件完全一致。大候选报告改为私有文件，Bundle 仅携带长度与 SHA-256，主机读取后再次核对，避免大报告在系统结果传输中截断。

最终证据为 `compiler-16k-quality-final`：Rime 1.16.1，两个新进程和独立目录，各 34 组输入均返回消耗全部输入的预期首候选，含 `zhendejiade`、`xuexiyingyu`、九键 `64426` 及 30 个常用口语短语。它证明这些样例及本次编译复现，不代表全词库排名、全设备或长期性能已通过。

编译数据标识：`d15199f066ca229dd5a450162c574859d939558bad22c2107b7564aa38f1c1c0`。

## 复建检查

```powershell
python -m unittest discover -s tools -p test_clean_dictionary.py -v
python tools/verify_dictionary_source_kit.py --archive <来源包路径> `
  --expected-sha256 7c3f359522e981dc3ab7c5cf18284a03d59b204cb3f2cc07e9e41530a03eb92d `
  --output <新的验证目录>
```

来源包有 82 个文件（含索引），原始许可字节逐一保留；ZIP 为 52011000 字节。验证器先核对明确的 ZIP 哈希，限制文件集合/路径/尺寸，之后以 Python `-S`和包内转换程序构建，不需要 Git 历史或网络。最终验证核对 81 个被索引文件，重建的 12 个明文文件全部一致，包内 13 项测试通过。这次来源包往返验证没有再次编译 Android 二进制；二进制的实际编译证据独立保存。

该包是数据来源材料，不是整套原生库已完成对应源代码与内嵌许可审核的结论。

## 接入前仍需解决

覆盖比较见 `tools/analyze_clean_dictionary.py`及验证摘要。新数据包含旧版 14041 个英文提示词头中的 13772 个、5928 个词卡词头中的 5923 个，但基础拼音仅覆盖旧版 41189 个不同词中的 22392 个。缺失多为“我的、给我、这是”等口语组合；总词头数量增加不能掩盖这个差距。

新 Rime 已利用 Essay 扩展并正确处理本次 30 个口语样例，但基础模式不能依赖原生引擎永远可用。下一步应从这次干净的 Rime 编译词表导出常用词读音，与 CC-CEDICT 文件保持来源分离，补齐基础候选，并实际比较排名；不要把旧词库重新合并回来。固定 librime 源码的 `tools/rime_table_decompiler.cc`可作为导出入口，需使用精确配对的头文件和本次表数据，避免手工猜多音字读音。

之后还需流式装载/内存与延迟验证、多音字候选到词卡贯通、按数据标识部署新 Rime 公共目录并清理仅生成的旧 `user/build`缓存、保留用户学习数据库与用户词典，最后执行升级和实际 APK 三 ABI 验证。现有商用来源阻断继续保留，原生内嵌第三方 NOTICE 审核也未完成。

## 本机构建位置

本次 C 盘曾耗尽。清理临时目录被自动审批拒绝后，仅复制并逐文件校验到 E 盘，C 盘原文件保留。后续活动工作副本为 `E:/Vime-build-work/android-ime-clean-dictionary`，数据工作目录为 `E:/Vime-build-work/clean-data-v0.23.0`。该副本的 `origin`是原 C 盘本地仓库，没有公共推送。

最终原始编译/来源包证据仍在项目 `outputs/clean-data-v0.23.0/`；最终往返结果位于 E 盘数据工作目录的 `source-kit-final-verified/roundtrip-result.json`。精确绝对路径与当前状态见验证摘要和 E 盘 checkpoint。
