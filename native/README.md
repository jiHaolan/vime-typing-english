# 原生引擎源码构建与范围（0.23.1）

当前构建从固定Rime源码生成引擎，显式合并BSD许可的Lua、Predict插件；保留原全拼、九键、词典和用户学习接口。应用schema没有配置Octagram的grammar/language，也没有打包.gram模型，故新构建排除该插件。新版本不接收旧provider的librime.a。

发现的问题：0.23.0三个ABI的实际动态符号均包含Octagram、Lua和Predict，旧说明漏列插件。Octagram固定快照的LICENSE为GPL3；主项目的BSD许可不能代替插件许可。该旧版本问题、原二进制哈希和许可证保留作历史记录；其他旧包尚未逐一审查。本次不对应用自有代码擅自改许可证，也不宣称历史发布事项已经解决。

## 重建

安装NDK28.0.13004108、CMake3.31.6与Ninja。原生来源包中包含固定的上游源码压缩包、Boost完整源包、六类依赖的18份预编译静态库与306个实际头文件；它们分别由native/sources.lock.json锁定。依赖静态库和头文件已核对固定provider的Git对象（或LFS指针）；六个底层依赖尚未全部独立重建。

在来源包根目录运行（Python3.12；不要求第三方Python包或联网）：

```powershell
python -S tools/build_native_engine.py --lock native/sources.lock.json `
  --archives upstream --boost-archive upstream/boost-1.90.0-cmake.tar.xz `
  --prebuilt dependency-blobs --sdk <SDK> --output <新的引擎输出目录>
.\Build-Native.ps1 -Prebuilt dependency-blobs -EngineBuild <引擎输出目录> `
  -Sdk <SDK> -BuildRoot <新的JNI输出目录>
```

在应用完整工程中同样可用本地上游档案与依赖目录代替upstream/dependency-blobs。Python构建器拒绝覆盖旧输出，核对源码、静态依赖和头文件哈希；Rime及插件C/C++源码不变，只有Lua的CMake查找适配由本项目提供。Core、Lua和Predict均从源码编译；CMake明确禁止外部动态插件加载。

JNI链接入口核对引擎收据、来源锁、ABI对应静态库与六个依赖的SHA，拒绝缺少源码构建收据、Octagram选择、错误引擎摘要或错误依赖。distribution_version取应用Gradle版本，移除旧的0.14常量。构建后按三个ABI更新provenance.json和打包的native-sources.json，运行实际APK与原生检查。

新引擎的模块选择核对不等于所有依赖许可的最终商业结论。内嵌UTF8/Darts/X11、RapidJSON、Lua兼容层、glog和SDK LLVM的原始声明随许可阅读器提供；原有许可文件不改写。SDK原始NOTICE可能包含未链接的工具，RapidJSON原始文本也列出未引入的测试数据条款，不能据此误判为这些测试工具都进入了APK。

## 公共词典与用户数据

固定Luna/Essay公共词典运行文件与0.23.0逐字节相同。APK分发10个预编译文件和清单，不在手机上重编译词典；完整.dict.yaml、essay.txt、变换脚本与数据编译收据在独立词典来源包。修改数据后先离线编译，再执行project_rime_runtime.py生成运行目录并更新清单，不能仅删除build目录。

JNI调用与关闭/重开仍由Kotlin串行化。shutdown先销毁会话、finalize释放映射用户库，再清理应用生成目录；重新初始化恢复默认全拼。公共词典、Rime用户根目录、收藏/复习分别保存。原生库升级后的真实重置与持续学习开关回归需单独验证，不能只以成功链接作证。

原生运行与新旧候选对比、用户数据保留、完整来源包校验和当前未完成项见docs/verification/native-source-0.23.1.json。实体ARM64/16KB、多机型长期使用和历史发布审核不由模拟器检查代替。
