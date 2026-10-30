# HaoLive / X-ASR 电脑语音

0.17.4 提供“电脑语音 · X-ASR · 无需密钥”模式。手机使用自身麦克风，长按空格两秒后收音，松手上传到配置的 HaoLive 服务，将返回文字直接输入当前编辑框。支持中文、英文；模型不随 APK 分发。

这是自建服务模式，需要服务地址和正在运行的电脑。它不会自动获得一个公共免费语音后台。真机 USB 测试已经完成中文和英文识别，尚未验证手机中英混说准确率。

## 服务

本次使用 [HaoLive.cpp v0.1.1](https://github.com/minghe36/HaoLive.cpp/releases/tag/v0.1.1) 的 Windows CPU 单文件程序，以及 [X-ASR Q8 模型](https://huggingface.co/cstr/x-asr-zh-en-GGUF)。模型 168,189,920 字节，仅保留在电脑上。

```powershell
.\haolive-server.exe --model .\x-asr-zh-en-q8_0.gguf --backend cpu --host 127.0.0.1 --port 18781 --threads 4
adb -s YOUR_DEVICE_SERIAL reverse tcp:18781 tcp:18781
```

调试版在语音设置中预填 `http://127.0.0.1:18781/v1/transcribe`。保存并选择电脑语音，返回试打框即可测试。该 HTTP 地址仅用于调试版的 USB 回环连接；正式版需要 HTTPS 地址。

USB 断开、电脑关机或服务停止后，该测试连接不可用。结束测试时移除这一个映射，停止对应服务进程：

```powershell
adb -s YOUR_DEVICE_SERIAL reverse --remove tcp:18781
```

## 协议与限制

- `POST /v1/transcribe`，原始 WAV 请求体，`Content-Type: audio/wav`；不发送 API Key 或 Authorization。
- 16kHz、单声道、PCM16，每次最多30秒。达到上限停止录音，等待松手后识别。
- 只读取 JSON `text`，拒绝重定向，限制响应长度。不把 HaoLive 的原始 WAV 接口当成 OpenAI multipart 接口使用。
- 短按空格仍输入空格。滑出、取消、切换编辑框或隐藏键盘会取消本次输入；晚到的结果不会提交。
- 手机临时 WAV 用后删除。语音故障信息只保存模式、语言和状态，不保存录音、识别文字、接口地址或密钥。

公开部署需另行处理 HTTPS、服务端访问控制、限流、容量和模型许可。此次未部署公共服务，未发布 GitHub Release。

## 键盘触感

0.17.4 在输入法根视图接收按下事件，为候选词、工具栏、表情、符号、浮层和空白区域提供统一触感。每根手指按下触发一次，滑动和抬起不重复触发；按键原先的按下触感已移除，长按达到阈值仍有提示。沿用关闭、轻、中、强和自定义档位，并遵循系统关闭设置。
