# 回归测试

改代码后跑一遍，不用装到手机上就能验掉大部分低级错误。
全部是纯逻辑测试（`javac`/`java` 直接跑），不需要 Android 设备。

## 怎么跑

在 **Git Bash**（Linux/macOS 用普通 shell 也行）里，把 `JDK` 指向你机器上的 JDK 17：

```bash
JDK=<你的 JDK17>/bin          # 例如 /d/Apps/jdk17/bin 或 /usr/lib/jvm/java-17/bin
cd <仓库目录>/test

$JDK/javac.exe -encoding UTF-8 T.java && $JDK/java.exe -Dfile.encoding=UTF-8 T   # AudD timecode 解析
$JDK/javac.exe -encoding UTF-8 R.java && $JDK/java.exe -Dfile.encoding=UTF-8 R   # 环形缓冲取数
$JDK/javac.exe -encoding UTF-8 C.java && $JDK/java.exe -Dfile.encoding=UTF-8 C   # 静音阈值标定
$JDK/javac.exe -encoding UTF-8 -d out -sourcepath ../java ShazamFpTest.java && $JDK/java.exe -Dfile.encoding=UTF-8 -cp out ShazamFpTest  # Shazam 指纹对 golden.raw
```

（Linux/macOS 下去掉 `.exe` 后缀即可。）

## 各测什么

| 文件 | 覆盖 |
|---|---|
| `T.java` | AudD `timecode` 解析（"00:56" / "01:02:03" / 带小数秒 / 脏数据） |
| `R.java` | `AudioBuffer` 环形缓冲取数：缓冲不足、精确取最近 N 秒、回绕、超容量夹紧、空缓冲 |
| `C.java` | 静音阈值标定。**改 `SILENT_RMS` 之前先跑这个** |
| `B.java` | 端到端：用 App 一致的 WAV 封头 + multipart 真打一次 AudD（需要网络） |
| `A.java` | ACRCloud 签名算法（HMAC-SHA1 string_to_sign）校验 |
| `WP.java` | `WindowPicker` 取窗策略回归（39 项） |
| `L.java` | 用真实音乐对照「旧固定窗口 vs 新裁尾留白」策略（10 项，需要自备样本） |
| `ShazamFpTest.java` | Java 版 Shazam 指纹对 `golden.raw` 逐结构/峰值校验（**开箱即跑**，基准文件已入库） |
| `shazam_test.py` | shazamio 连通性 + 识别实测（`pip install shazamio`，需要网络和自备样本） |
| `dump_signature.py` | 用 shazamio-core 重新生成 `golden.raw`（`pip install shazamio-core`） |
| `send_mine.py` | 把 `ShazamFpTest` 落盘的 `mine.uri.txt` 真发给 Shazam 验证端到端 |

## 关于样本文件（版权）

**版权音乐样本没有随仓库分发**：`sample.wav`（12 秒 16kHz 单声道）、`ex.pcm` 等需要自备。

- `ShazamFpTest.java` 依赖的 `golden.raw` / `golden.uri.txt` 是**指纹数据**（3 KB，不含音频），已随仓库入库，clone 后直接能跑。
- 要跑 `L.java` / `shazam_test.py` / `dump_signature.py`，自己切一段喜欢的歌：

```bash
ffmpeg -v error -y -i <任意音乐> -ss 60 -t 12 -ac 1 -ar 16000 sample.wav
```

- `B.java` 用 AudD 官方示例（无版权问题）：

```bash
curl -s -o ex.mp3 https://audd.tech/example.mp3
ffmpeg -v error -y -i ex.mp3 -t 6 -ac 1 -ar 16000 -f s16le -acodec pcm_s16le ex.pcm
```

注意要导出**原始 PCM**（`-f s16le`）再由 `B.java` 自己封 WAV 头 ——
直接让 ffmpeg 出 `.wav` 的话，它会在 `fmt ` 后面插一个 `LIST` 元数据块，
跟 App 实际产出的字节不一样，就测不到真实情况了。

## 测出来的几条结论（别再踩）

1. **`timecode` 的小数秒不能 `Math.floor`**：`"00:56.5"` 会丢掉 500ms。
   末段要单独取小数部分转毫秒。
2. **静音阈值不能定高**：真实音乐满电平时 RMS 才 119，音量降到 70% 就只剩 83。
   原来定 110 → 车机音量稍小 App 就静默装死。现在定 40。
3. **Shazam 严格要求完整 12 秒**：实测 4/8/10 秒全部 0 匹配，12 秒才命中。
   给 Shazam 的音频永远走 `AudioBuffer.takeFull(12)`，不参与「裁尾部留白」优化。
