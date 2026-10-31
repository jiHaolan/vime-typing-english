# 0.24.1 键盘操作与候选排序

- 长按空格约0.5秒开始语音，松手结束；普通空格仍即时输入。此次缩短触发时间，沿用现有手机/电脑语音接入，不把手势回调测试当作真实语音识别验收。
- 删除键按住约0.3秒后向上划至少36dp，看到“松手清空”再松手。回滑到原位取消清空；侧向滑出、取消事件、第二根手指或布局切换都撤销清空动作。点击删除一个字符，单纯长按不清空。清空仍核对当前输入连接，并通过编辑器全选后删除。
- 键盘及词卡、工具面板纳入系统导航、IME caption、可点击系统控件及底部强制手势区域；Android10及以上至少留48dp的控制行，用户自定义底距在其上叠加。原因见Android的[IME导航实现](https://android.googlesource.com/platform/frameworks/base.git/+/master/core/java/android/inputmethodservice/NavigationBarController.java)与[窗口区域类型](https://developer.android.com/reference/android/view/WindowInsets.Type)。这修正了0.24.0仅按navigationBars留白不足的情况。
- “本地排版”标题与说明简化为实际操作：选中文本，整理空格、分段、列表，预览后替换。

候选排序增加固定jieba语料的词频补充。[源提交](https://github.com/fxsjy/jieba/tree/67fa2e36e72f69d9134b8a1037b83fbb070b9775)为MIT许可，仅使用word/frequency，不接入分词引擎或新增释义。保留频次至少5、2—16个基本汉字的126733个词；二进制表约2.3MB，随APK压缩。许可原文可从应用“开源与数据来源”查看。

重排限制在消耗拼音长度和字数相同的候选组，保留单字原有读音权重与整句分段，生僻词不删除。重复主动选择两次以上可保留个人偏好；显式自定义短语仍优先。扩展词包不再整批抢占默认首选。前64个原生候选使用固定排序窗口，翻页请求改变limit不改变该窗口的顺序；后续仍可翻选原生候选。

这是通用词频补充，不是实时热词榜或聊天语境模型。已观察到旧版shihao首选“谥号”，本版按补充词频将“嗜好”提前；其他词继续由读音、基础词频和个人选择共同决定。完整拼音词库、原生库与原有释义字节不变。

生成入口为`tools/build_common_frequency.py`，源文件与输出哈希在`app/src/main/assets/common-frequency.json`，独立离线来源包保存在本轮E盘证据目录。验证范围和最终安装包摘要见`verification/keyboard-usability-0.24.1.json`。专用模拟器检查实际View触摸、窗口几何和固定公共候选；未操作个人手机，未重新执行真机触感或语音识别测试。原生依赖接入工作仍保持暂停。
