# Rime 原生模块重建与替换

Vime 自行编写 JNI 适配层；使用 Rime BSD 引擎，不包含 Trime 的 GPL Android 前端。所有版本和二进制 SHA-256 见 provenance.json，词典来源与变换见 app/src/main/assets/rime/manifest.json。

重建准备：NDK 28.0.13004108、CMake 3.31.6；取 https://github.com/fcitx5-android/prebuilt 到任意目录，切换 provenance.json 的 static_provider.commit。该仓库使用 Git LFS，需拉取真实的静态库文件，并包含三种 ABI 的 librime、glog、leveldb、lua、marisa、opencc、yaml-cpp 子目录。

在工程目录执行 `./Build-Native.ps1 -Prebuilt <绝对路径>`，再执行 `./Build-Verified.ps1`。适配层链接产物写入 app/src/main/jniLibs，允许替换修改后的原生模块并重新打包。provider 对应的构建配方在 https://github.com/fcitx5-android/prebuilder 的 prebuilder_commit；librime 源码来源与确切提交也在 provenance.json。需要修改静态引擎时按该配方及其固定依赖重建静态库，随后重新链接适配层。

词典为 Luna Pinyin 和 Rime Essay 的简体转换/合并版本，原始 LGPL 文本随 assets/licenses 提供。可修改明文 .dict.yaml、essay.txt 和 schema；删除 rime/build 并相应更新 manifest（每文件 SHA-256）后，引擎会后台重编译。预编译 build 文件只含公共词典，不含用户词库。数据转换脚本另随构建材料交付。

第三方组件许可见 assets/licenses 和 NOTICE-rime.txt；引擎 BSD 原文见 include/RIME-LICENSE。分架构 APK 版本号、包名及签名一致，覆盖升级时保留原应用学习数据。

0.20.0新增shutdown JNI入口供用户重置选词习惯。调用方必须与所有查询/学习串行化；先销毁会话并finalize以关闭用户库，再清理应用私有Rime用户目录。重新initialize后恢复默认全拼状态，不能沿用旧九键标志。不要只删除仍被映射的LevelDB文件。修改本接口后重新链接三个ABI并更新provenance.json；输入数据原生检查会验证实际学到的记录消失、反复重开及并发查询，构建入口见docs/BUILD.md。

生命周期依据为[锁定版本的Rime实现](https://github.com/rime/librime/blob/de4700e9f6b75b109910613df907965e3cbe0567/src/rime_api_impl.h#L25-L63)，实际释放/重开结果仍以本项目的设备检查为准。
