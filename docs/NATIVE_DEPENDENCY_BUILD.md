# 六个底层依赖的源码构建

2026-10-31完成后续构建基础：glog、LevelDB、Lua、Marisa、OpenCC、yaml-cpp均从`native/sources.lock.json`固定的源码压缩包编译，覆盖arm64-v8a、armeabi-v7a、x86_64。当前0.23.1安装包仍使用原来认证过的provider依赖；本次没有替换安装包、变更产品版本或上传发布。

## 实际结果

- 18份静态库全部构建通过，每个对象的ELF机器类型与目标ABI相符；408份实际安装文件逐一校验摘要。
- 六份源码快照的1129个C/C++源文件和头文件保持不变。OpenCC仅调整四处CMake胶水：使用本次编译的Marisa包，跳过构建主机不能运行的Android词典生成器和命令行程序，保留完整运行库。当前词典材料另有独立构建流程。
- 使用NDK28.0.13004108、CMake3.31.6，最低原生API24与共享C++运行时；关闭库自己的测试/基准/可执行工具，不以此声称它们的全部上游测试通过。Lua保留动态模块支持，OpenCC的Darts开关与原provider配方一致关闭，Marisa不使用构建主机的原生指令选项。
- 专用Android16/API36、x86_64、16KB页模拟器实际运行7项通过：进程页大小、日志初始化/输出/关闭、LevelDB同步批次/删除/关闭重开、Lua UTF8与标准库、Marisa前缀与保存/mmap、OpenCC文本字典转二进制后转换、YAML类型读取与畸形输入拒绝。实际链接映射包含六份新编译静态库。
- 6类反例全部被拒绝：少一个ABI依赖、provider输入标记、错误上游提交、改动安装头文件、改动原生源码、摘要正确但ELF机器类型错误的静态库。最后恢复副本再次验证，权威构建未改动。
- 新编译LevelDB读取之前保留的原生用户词库快照副本：升级前后各6条逻辑记录，所有原键保留；只有先前明确的学习计数/时间变化。原始快照未变，正在使用的应用数据库未打开。读取器CMake另拒绝旧provider库冒充新源码产物、错误来源锁两种反例。

逐项摘要和外部证据路径见[验证记录](verification/native-dependencies-20261031.json)。本次仅操作`emulator-5580`，运行程序和快照副本已从模拟器临时目录清理；没有安装APK、改输入法设置或操作个人手机。触感仍沿用0.17.4历史记录。

## Windows重建命令

准备锁定的六份压缩包：`glog.tar.gz`、`leveldb.tar.gz`、`Lua.tar.gz`、`marisa-trie.tar.gz`、`OpenCC.tar.gz`、`yaml-cpp.tar.gz`。这些材料也已保存在0.23.1原生来源包的`upstream`目录。以下输出目录必须是新目录；保留旧失败与成功证据。

```powershell
python -S tools/build_native_dependencies.py --lock native/sources.lock.json `
  --archives <固定源码压缩包目录> --sdk <SDK> --output <新的依赖构建目录>
python -S tools/verify_native_dependencies.py --build <依赖构建目录> `
  --lock native/sources.lock.json --sdk <SDK> --output <新的校验JSON>
python -S tools/test_native_dependency_guards.py --build <依赖构建目录> `
  --lock native/sources.lock.json --sdk <SDK> --output <新的反例目录>
python -S tools/run_native_dependency_probe.py --build <依赖构建目录> `
  --lock native/sources.lock.json --sdk <SDK> --device emulator-5580 `
  --output <新的运行检查目录>
```

构建器认证源码压缩包、记录SDK工具与编译命令摘要，所有输出在指定目录；校验器再次读取实际库、源文件与安装文件，不只相信收据里的成功字段。运行检查限定专用16KB x86_64模拟器，拒绝真机及其他架构。源码校验不等于第二台机器逐字节可复现编译；没有据此作这种声明。

读取器可以通过`-DVIME_DEPENDENCY_BUILD=<依赖构建目录>`配合`-DVIME_LEVELDB_ROOT=<依赖构建目录>/prefix/leveldb/<ABI>`选用新库；未指定时保留历史provider方式以复查既有收据。选用新库前应执行上面的完整校验，不可只改CMake里的摘要。

## 下一步与边界

下一步将这些依赖接入Rime/Lua/Predict、JNI和实际APK，检查二进制摘要、大小、16KB运行、新旧候选、已有学习记录及数据开关，再生成对应安装包和完整来源包。当前独立程序的7项不能代替这些应用检查，也没有证明ARM真机运行、长期输入质量或所有公开词条排序。

初次构建A在OpenCC安装后发现库文件被相对PATH参数放进工程目录；4个已完成构建及失败收据保留。生成的6个文件经路径和未跟踪状态检查后移至外部失败目录；构建B改为绝对安装路径、固定子进程工作目录，并核查每份安装清单都在专属前缀内。没有删除原有工程文件。

独立源码重建缩小了后续产物对provider二进制的依赖；它不解决历史GPL发布审核，也不为应用自有代码选择许可证。正式签名、独立语音、收费、多设备和长期学习质量等商用工作仍未完成。
