# 构建

## 常规 APK

- JDK 17；Android SDK Platform 34、Build Tools 34.0.0。
- Android Gradle Plugin 8.5.2、Kotlin 1.9.24、Gradle 8.9。
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
