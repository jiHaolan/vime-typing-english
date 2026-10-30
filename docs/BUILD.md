# 构建

## 常规 APK

- JDK 17及以上（本机使用JBR 21）；Android SDK Platform 36、Build Tools 36.0.0。
- Android Gradle Plugin 8.10.1、Kotlin 2.2.21、Gradle 8.11.1。
- Python 3.11及以上用于发布检查；Gradle Wrapper固定官方分发包SHA-256。
- 设置 ANDROID_HOME，或在未纳入 Git 的 local.properties 中设置 sdk.dir。

Linux/macOS：

```sh
chmod +x gradlew
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 Verify-Reference.py
```

Windows：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
python Verify-Reference.py
```

也可使用 Build-Verified.ps1，并显式传入 -JavaHome、-AndroidSdk 和 -Gradle。产物位于 app/build/outputs/apk/debug/，按 arm64-v8a、armeabi-v7a、x86_64 分包。

离线数据集集成测试需要另行下载发布页的 CC-CEDICT-2026-10-19.vimelex，并将 VIME_PACK_PATH 指向其绝对路径；不设置时只有这项数据集集成检查跳过。完整测试曾在提供该数据集的环境中执行。

## 原生引擎与词典

仓库已包含原生库和公共预编译词典，可直接构建。原生替换及精确上游版本见 native/README.md、native/provenance.json。修改字典的数据源与 SHA-256 见 app/src/main/assets/rime/manifest.json。

重新链接需要 NDK 28.0.13004108、CMake 3.31.6，以及 provenance.json 固定的 fcitx5-android/prebuilt 静态库；执行 Build-Native.ps1 -Prebuilt <绝对路径> -Sdk <SDK路径>。修改引擎需按对应 prebuilder 配方重建静态库。

tools/prepare_rime_assets.py 可从独立克隆的 Luna Pinyin、Rime Essay 和 librime 源码重新生成公共数据。运行 --help 查看路径参数；需要 Python 的 opencc 模块。生成后需重新部署预编译词典或删除预编译目录让引擎首次启动时编译，并同步更新清单哈希。

## 签名

发布页预览 APK 沿用项目现有测试签名。仓库不包含任何签名私钥。其他机器自行生成的 debug APK 通常签名不同，不能直接覆盖现有安装；正式产品需另行管理发布签名。不要把 keystore、API 密钥或用户词库提交到仓库。

0.19.0增加`Build-Release.ps1`与`tools/build_release.py`，构建并校验候选包，不执行上传。由操作者或CI提供以下环境变量，密码不要写入命令行、脚本或Git：

```text
VIME_RELEASE_STORE_FILE
VIME_RELEASE_KEY_ALIAS
VIME_RELEASE_STORE_PASSWORD
VIME_RELEASE_KEY_PASSWORD
VIME_RELEASE_CERT_SHA256
```

最后一项为独立配置的预期证书SHA-256，用于核对实际产物；工具拒绝现有预览证书及Android Debug证书。缺少私密参数时不回退到debug签名。直接执行Gradle可能生成未签名release中间产物，不应据此交付。

```powershell
.\Build-Release.ps1 -Output 'E:\Vime-deliveries\0.19.0' `
  -VersionName '0.19.0' -VersionCode 28 `
  -PreviousApk 'E:\Vime-deliveries\previous-release.apk'
```

需已设置`JAVA_HOME`、`ANDROID_HOME`，也可显式传入对应参数。`-Python`可指定Python可执行文件。默认使用本仓库Wrapper；`-Gradle`可指定已校验的Gradle 8.11.1。输出必须为工作树外的新目录，既有交付不会被覆盖。

普通候选构建要求干净Git状态、相同证书的旧APK和递增versionCode，并核对构建前后的源码指纹。工具会执行单元测试、release lint和静态资源检查，再检查实际三个ABI安装包的应用ID、版本、权限、HTTPS配置、签名、库完整性与对齐。发布资源可能被重命名，HTTPS配置通过编译后的资源表解析，不依赖源码路径。输出包括APK、SHA256SUMS、逐包检查、脱敏日志和交付收据。

`-Validation`仅供临时证书验证流程：允许未提交源码、不要求旧包，产物明确命名为`Vime-validation-*`，收据标记为`validation-only`。这些文件不应作为正式产品发布。本阶段验证证书的私钥已销毁，没有据此创建永久商业签名。

64位arm64-v8a/x86_64库检查16KB ELF与ZIP对齐；独立的32位armeabi-v7a包按其4KB ELF ABI校验，ZIP仍按16KB对齐。依据[Android官方检查范围](https://developer.android.com/guide/practices/page-sizes)。静态对齐通过不替代真实16KB系统运行检查。

现有公开预览使用测试证书。正式身份的安全保管、证书轮换、原有安装的连续升级和真实设备安装仍需完成；当前脚本仅支持相同证书更新，证书不同时会停止，不能用卸载旧应用来代替数据升级验收。

## 学习存储原生检查（0.18.0）

在独立测试模拟器安装主调试APK与`assembleDebugAndroidTest`生成的测试APK，再执行：

```powershell
adb -s emulator-5576 shell am instrument -w com.vime.ime.test/com.vime.ime.data.LearningStorageInstrumentation
```

15项检查使用独立临时数据库，验证真实SQLite事务与迁移，不使用内存假数据库代替磁盘持久化。临时数据库和旧偏好测试数据会在结束时清理。`seedUi`为专用模拟器UI准备选项，会替换模拟器中的学习记录；日常存储测试不传此选项，也不要在个人真机使用它。

## 键盘与窗口检查（0.19.0）

窗口检查使用单独的测试入口；默认测试入口仍为上面的学习存储检查。先构建主调试包及窗口测试APK：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PvimeWindowChecks=true
python tools/run_window_checks.py --sdk "$env:ANDROID_HOME" --device emulator-5578 `
  --app-apk app/build/outputs/apk/debug/app-x86_64-debug.apk `
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk `
  --output 'E:\Vime-checks\window-019-run1'
```

APK名称以构建产物为准。使用已经完成系统初始化的专用API30+模拟器，且主包targetSdk至少36；0.19.0已在API36、1080×2400、标准4KB页系统上执行。工具在安装或更改设置前拒绝物理手机，输出目录必须是新目录。主机先设置硬件键盘下仍显示软键盘并启用Vime；原生测试在首页窗口就绪后才选中Vime，避免Instrumentation重启同包输入法进程后遗留失效绑定。结束时恢复并回读核对默认输入法、Vime启用状态及硬件键盘显示设置。测试期间不要同时运行android-cli layout或其他UiAutomation检查进程。

7项检查通过真实触摸、窗口Insets与Back键验证：首页前台焦点、点击弹出Vime键盘、完整输入框避让、三行输入框避让、返回收起键盘并保留首页、首页打开生词本、生词本返回首页。记录输入框屏幕坐标、实际可见矩形、窗口边界和IME高度；未执行的检查计入not_run，全部7项通过才成功。只临时修改试打框文字，结束后恢复，不替换学习数据。

收据保存实际主包与测试包SHA-256、Instrumentation返回结果及设置回读。单独保存instrumentation.log、window-results.json和receipt.json。调用Back键不能代替屏幕边缘手势验证；此次另在API36手势导航下通过实际边缘滑动验证了收起键盘和生词本返回首页，未据此验收动画帧率或取消手势。上述窗口检查也不能替代16KB页系统、横屏、分屏或不同厂商真机检查。
