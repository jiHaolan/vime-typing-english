# 手机离线 X-ASR 原生探针

这是独立的 Android 原生测试程序，不进入当前 APK，也不是已完成的语音功能。它只读取本地模型与16kHz、单声道、16位有符号小端PCM文件，不使用麦克风或网络。Android API24及以上，提供ARM64与x86_64构建；目前仅x86_64做过实际识别。

固定来源：

- [CrispASR](https://github.com/CrispStrobe/CrispASR/tree/1dd0bf6fb9e7a8bf5849cb7a14605b2234e6ee93)，MIT。
- [其GGML子模块](https://github.com/CrispStrobe/ggml/tree/e2ae679a563014f46c546ce6c6448e8601e4dea6)，保留上游许可。只构建X-ASR、GGUF读取、Kaldi特征与GGML CPU；没有编译其他语音/TTS后端。
- [X-ASR中英文Q4模型](https://huggingface.co/cstr/x-asr-zh-en-GGUF/blob/acb1a95eac809719a2c86d1048471f96fc6444ad/x-asr-zh-en-q4_k.gguf)，Apache-2.0；92,665,824字节，SHA256为`3300f743cf34f686004cf600a1ea206c7eee50ecaa01e95b4aff418876f613c8`。模型不放进Git或主APK。

在独立目录取得上述固定源码和GGML子模块，保留源代码与许可。使用NDK28.0.13004108及SDK CMake3.31.6；构建目录应在工程之外。当前脚本支持Windows和Linux x86_64宿主：

```text
python tools/offline-asr-probe/build.py --sdk <Android-SDK> --source <CrispASR固定源码> --build <独立构建目录> --abi arm64-v8a
```

`--abi x86_64`构建模拟器版本。脚本核对两份源码提交、拒绝改过的已跟踪源码，保留编译日志、命令、程序摘要及16KB ELF加载段检查。构建时不自动下载其他依赖，也不安装或访问设备。

运行格式为`jianyi-xasr-probe model.gguf speech.pcm16 1`；最后的`1`是线程数。PCM最长30秒。测试时需自行在专用设备断网，逐一校验传输后的文件摘要，并在结束后恢复网络与清理仅本次创建的文件。不要把实际用户录音放进仓库。

首轮x86_64四线程未在240秒内返回，随后45秒限时复查仍未完成；单线程中英文均返回，中文专名“键忆”被识别成“建议”。这些有限结果不能推断根因、真实手机速度或识别准确率。原始结果见[离线语音记录](../../docs/verification/offline-asr-probe-20261101.json)。

下一步需完成可取消的手机端调用、模型导入/校验/移除、长按空格与麦克风实际流程、Android权限和生命周期检查、最终APK大小与来源核对，再对最终同版包做云真机验收。当前0.25.1尚不支持导入此模型。
