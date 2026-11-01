# 固定源码的离线识别JNI

本目录提供JNI和取消适配，编译固定、未修改的CrispASR与GGML源码。提交、配方摘要、最终库摘要及依赖见应用资源`offline-native-sources.json`，不等于实际麦克风或商用验收。

使用Android NDK28.0.13004108、CMake3.31.6和Ninja。工具支持当前Windows/Linux主机布局；输出目录须独立于应用与上游源码，建议使用ASCII路径。

```text
python tools/build_offline_voice.py --sdk <Android-SDK> --source <CrispASR检出目录> --output <独立输出目录>
```

CrispASR必须位于`1dd0bf6fb9e7a8bf5849cb7a14605b2234e6ee93`，ggml子模块必须位于`e2ae679a563014f46c546ce6c6448e8601e4dea6`，两者已跟踪源码均不得修改。不初始化其他未使用子模块。脚本检查源码、构建ARM64与x86_64、裁剪符号，并核对ELF加载段至少16KB、只导出六个JNI入口、只依赖Android平台库。

识别库采用单线程CPU、体积优化和链接时优化，私有静态链接C++，隐藏非JNI符号。不替换Rime的`libc++_shared.so`。库与构建记录须一并更新，并通过`tools/verify_notices.py`及最终APK验收。不同构建路径可能影响Build ID；当前没有声明字节级可复现构建。

主APK不含模型。固定模型来自Apache-2.0许可的`cstr/x-asr-zh-en-GGUF`指定版本，导入时检查92,665,824字节和固定摘要。内部工具独立于产品，见[云测样本工具](../cloud-voice-fixture/README.md)。
