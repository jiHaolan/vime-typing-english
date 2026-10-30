# 开源说明与词库来源

0.22.0增加离线原文阅读器，从输入设置、离线词典和隐私说明进入。读取只允许清单中固定的assets文件，后台核对SHA-256，再展示可选择文字的原文。返回恢复来源列表位置，横屏重建保留当前文档；没有WebView或自动打开外部网页。

`notices/components.json`是已核对来源与待解决项的维护入口。生成的`app/src/main/assets/notices.json`包含固定原生组件、数据说明、实际生产运行模块、POM证据及许可文件哈希；不将应用自有代码附加为MIT或其他统一开源许可。

## 生成依赖说明

先解析本工程当前生产运行依赖，再使用本机Gradle缓存生成说明；全部步骤可离线执行：

```powershell
.\gradlew.bat :app:writeRuntimeInventory --offline
python tools/generate_notices.py --inventory app/build/reports/runtime-components.json `
  --cache "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1" `
  --assets app/src/main/assets --components notices/components.json
python tools/verify_notices.py app/src/main/assets --inventory app/build/reports/runtime-components.json
python tools/test_notices.py -v
```

生成器保留POM的开发者/许可声明及父POM继承证据。缺失POM、无许可、缓存不一致或新增许可类型会停止生成，要求先核对。清单的47项是解析模块，包含平台/别名模块，并非47份独立二进制。构建插件、JVM测试库及电脑侧ASR服务不属于APK运行依赖。

`Build-Verified.ps1`支持`-Python`参数并执行来源完整性检查。发布工具在任何签名操作前检查来源审核状态；随后核对新解析的运行依赖，并对每个实际APK再次检查文件字节。手动检查安装包可执行：

```powershell
python tools/verify_notices.py app.apk --inventory app/build/reports/runtime-components.json --report notices-apk.json
python tools/verify_notices.py app/src/main/assets --release
```

完整性通过和商用来源审核通过是两个字段。目前`--release`必须失败：旧词频/联想来源、旧释义来源映射/清洁重建及原生内嵌第三方代码仍未完成审核。不得删除待解决项来把检查变绿。普通正式候选构建会在需要签名凭据之前拒绝；validation-only仅验证工程和打包流程，不改变来源审核结论。

## 考试分级数据

旧`mahavivo/english-wordlists`生成的分级映射已替换。新数据只提取[ECDICT固定版本](https://github.com/skywind3000/ECDICT/tree/ab22ad36302cafc6349a050e2b60894855208055)的`word`和`tag`字段，其[MIT文本](https://github.com/skywind3000/ECDICT/blob/ab22ad36302cafc6349a050e2b60894855208055/LICENSE)随APK保留。原始CSV约66MB只用于构建，不打包入APK；未导入其释义、音标、语料词频或音频。

学习档位规则：明确的`zk`/`gk`/`cet4`标签进入CET4基础档，`cet6`进入CET6，`toefl`进入TOEFL；多标签/重复记录选较低档，未标记词不强行分类。它是学习筛选，不声称等于官方最新考纲。CET4为5351条、CET6为1755条、TOEFL为3408条，共10514条。hello/study/love在基础档，contemplate在CET6，abdicate在TOEFL。

```powershell
python tools/prepare_levels.py --csv ecdict.csv --sha256 <已核对的原始CSV哈希> `
  --dictionary app/src/main/assets/dict.android.json --receipt app/src/main/assets/levels-source.json
```

生成器校验原始CSV哈希，并逐字段核对`pw`、`en`、`card`的规范JSON哈希保持相同。来源记录包括精确提交、原始CSV哈希、转换规则、分级结果哈希及整体字典哈希。更新数据后重新生成来源清单并执行检查，不手改词库标签而保留旧收据。

## Android运行检查

在专用模拟器构建主包及`assembleDebugAndroidTest -PvimeNoticeChecks=true`，再执行：

```powershell
adb -s emulator-5580 shell am instrument -w com.vime.ime.test/com.vime.ime.NoticesInstrumentation
```

该入口与其他测试入口互斥，拒绝实体手机；5项检查实际AssetManager中的21份许可文档及真实DictRepository加载、基础/CET6/TOEFL标签、英文补全与完整中文候选。它不修改学习数据库，不等同于长期性能或词库版权全部完成。记录中的单次后台加载耗时只用于定位，不作为性能通过指标。
