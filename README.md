# 车机歌词 CarLyrics

**把手机扔在车里，它听歌、认歌、滚屏显示同步歌词 —— 车机一切歌，它自动跟下一首。**

一个为「开车听歌」做的安卓 App：用麦克风「听」车机外放的歌，自动识别出歌名，
然后全屏大字滚动这首歌的同步歌词。你只管切歌，剩下的它全包。

- 一个 APK 装完就能用：**不需要注册、不需要填任何 key**
- 无账号、无广告、无统计 SDK，只申请它要用到的权限
- 开源（MIT），欢迎拿去做任何改造

## 为什么做这个

车里听歌想跟歌词，QQ 音乐 / 网易云的「听歌识曲」体验最好，但它们的识曲接口**不对个人开放**。
市面第三方识曲要么收费要么限额，很难长期白嫖。

本项目走的路子是：**在手机上本地计算 Shazam 兼容的音频指纹**（Wang 2003 算法，
参考 [SongRec](https://github.com/marin-m/SongRec) 的实现移植成 Java），直接请求 Shazam 的识别接口。
这条通道**免费、不限次数**，识别成功后再去拿歌词（LRCLIB / 网易云 / QQ音乐 三源），
全程不需要你注册任何账号。

## 功能

| | |
|---|---|
| 全自动跟歌 | 常驻预录音缓冲（环形 40 秒），切歌后秒级出结果，不用每次都干等录音 |
| 免费识曲 | 主引擎 Shazam 指纹识别，本地算指纹、免费接口，无 key 即用 |
| 同步歌词 | 全屏大字横向滚动 + 逐句高亮，歌词进度按识别结果自动校准 |
| 歌词三源 | LRCLIB → 网易云 → QQ音乐 依次兜底，歌手名强匹配防同名假命中 |
| 抗车内噪声 | 峰值归一增益（小声歌自动抬）、RMS 真实判静、取窗避开谷底留白 |
| 识别日志 | 长按状态框查看每一轮的音量/时长/引擎报错，支持一键复制（提 bug 神器） |
| 手动微调 | 歌词与声音有偏差时用 ±3 秒滑条校正 |
| 省电模式 | 觉得识别太频繁费电/费额度，一键切保守节奏 |

## 快速上手（普通用户）

1. **下载 APK**：到 **[Releases 页面](https://github.com/cgjn19820810-bot/car-lyrics/releases)** 下载 `CarLyrics.apk`
   （仓库根目录也放了一份同签名的最新 APK；国内网络直连 GitHub 慢时，可在下载链接前加 `ghproxy` 类加速前缀）。
2. **安装**：传到手机上打开安装，允许「安装未知来源应用」。
   从旧版本升级**直接覆盖安装**即可（签名一致，数据不丢）。
3. **首次使用**：
   - 授予**麦克风**权限（必须，它就是靠听的）；
   - 系统问「忽略电池优化」时选允许（或手动把本应用设为无限制）—— 后台服务被系统杀掉是「识别突然不干了」的头号原因；
   - 车机放歌 → 打开 App → 点「开始」。第一次识别要等约 12 秒攒音频（这是 Shazam 的硬性要求），之后切歌会快很多。
4. **识别出来后**自动进入全屏歌词。音量、识别引擎、日志随时长按主界面状态框查看。

## 识别引擎与 key（可选）

| 引擎 | 角色 | 费用 | 要配置吗 |
|---|---|---|---|
| **Shazam** | 主力 | 免费、不限次 | **不用，开箱即用** |
| ACRCloud | 可选兜底 | 免费额度有限 | 想填看 [docs/keys.md](docs/keys.md) |
| AudD | 可选兜底 | 免费额度有限 | 想填看 [docs/keys.md](docs/keys.md) |

识别链是**串行**的：Shazam 先探，没听出来才轮到 ACRCloud、AudD（仅当你填了它们的 key）。
对识别率的要求不苛刻的话，什么都不用填。

歌词来源（LRCLIB / 网易云 / QQ音乐）全部免费、无需申请，其中网易云和 QQ 是非官方接口，
万一哪天失效，提 issue 或按 `LyricsFetcher.java` 的注释自行调整。

## 已知边界（诚实清单）

- **Shazam 严格要 12 秒**完整音频才肯匹配（实测 10 秒 0 命中），所以每轮识别固定送 12 秒、不参与「裁留白」优化。
- **翻唱 / 现场版 / 喊麦改编**，Shazam 曲库没有就是没有（这不是本 App 能解决的）。
- 识别请求发往 Apple 的 Shazam 服务器（`amp.shazam.com`），国内一般可直连；个别运营商/网络不通时日志会明确报网络错。
- Shazam 接口是逆向自 iOS 客户端的**非官方用法**，Apple 哪天改掉它就得跟着改（见 `Shazam.java` / `ShazamFingerprint.java` 注释，改动点都写在明处）。
- 识别是「听」出来的：车机音量太小、风噪太大都会降低成功率——先确保音量够，再看日志里的「音量/峰值」两项。

## 从源码构建

不需要 Gradle、不需要翻墙拉 maven。整个构建就是四步命令行：
`aapt2`（编译/链接资源）→ `javac` → `d8`（转 dex）→ `zipalign` + `apksigner`（签名），
全在 [`build.sh`](build.sh) 里。

依赖三样工具（免安装解压即用）：

| 工具 | 用途 |
|---|---|
| JDK 17 | javac / keytool / 跑 d8 和 apksigner |
| Android SDK build-tools 34.0.0 | aapt2 / zipalign / apksigner / d8.jar |
| Android SDK platform android-35 的 `android.jar` | 编译目标与 bootclasspath |

在 **Git Bash**（Windows）或 Linux shell 里：

```bash
# 路径默认值按作者机器写的，别的机器用环境变量覆盖：
export JDK_HOME=/path/to/jdk-17
export BUILD_TOOLS=/path/to/android-sdk/build-tools/34.0.0
export ANDROID_JAR=/path/to/android-sdk/platforms/android-35/android.jar

bash build.sh
# 产物：CarLyrics.apk（脚本最后会自动做签名/对齐/版本号验证）
```

> minSdk 26（Android 8.0）/ targetSdk 35。

### 关于签名（重要）

仓库里带着 [`debug.keystore`](debug.keystore)（口令 `android`，别名 `androiddebugkey`），
发布的 `CarLyrics.apk` 就是它签的。**请不要换掉它** —— 保持同一签名，
你自编译的包才能直接覆盖安装官方包（Android 签名不同就只能卸载重装）。
想发自己的版本，就换 keystore、改包名，各玩各的互不影响。

### 版本号纪律

每次发布在 `build.sh` 里递增 `--version-code` / `--version-name` 再构建，
并更新 [CHANGELOG.md](CHANGELOG.md)。

## 回归测试

`test/` 目录是不用装手机就能跑的纯逻辑测试（AudD timecode 解析、环形缓冲、静音阈值、
取窗策略、**Java 版 Shazam 指纹对黄金基准**等），改代码前先跑一遍，改完再跑一遍。
怎么跑、哪些样本文件因版权需要自备，都写在 [test/README.md](test/README.md)。

## 目录结构

```
├── CarLyrics.apk          最新成品（当前 v2.1 / versionCode 12）
├── build.sh               手工构建脚本（无 Gradle）
├── debug.keystore         发布签名（口令 android/android，勿换）
├── AndroidManifest.xml
├── java/com/wbhome/carlyrics/
│   ├── LyricService.java        主状态机：循环取音频→识别→拉歌词→滚动+进度校准
│   ├── Recognizer.java          多引擎串行链：Shazam → ACRCloud → AudD
│   ├── ShazamFingerprint.java   ★ Shazam 指纹算法 Java 移植（Wang 2003）
│   ├── Hanning.java             指纹算法用的 2048 点 Hanning 窗常量
│   ├── Shazam.java              Shazam 识别接口客户端（逆向自 iOS 客户端）
│   ├── AcrCloud.java            ACRCloud 接口（HMAC-SHA1 签名）
│   ├── AudD.java                AudD 接口
│   ├── LyricsFetcher.java       歌词三源：LRCLIB / 网易云 / QQ音乐
│   ├── AudioBuffer.java         常驻预录音环形缓冲（40 秒，可倒带）
│   ├── AudioRecorder.java       录音与取样本（判静、增益、四道关口）
│   ├── WindowPicker.java        取窗策略（裁尾部留白、不跨谷底）
│   ├── LyricActivity/LyricView  全屏歌词渲染
│   ├── MainActivity/Config      设置界面与偏好
│   └── Lrc/Net/PlayerState/TrackInfo
├── res/                     布局与图标
├── test/                    PC 回归测试（见 test/README.md）
└── shazam-ref/              移植蓝本：SongRec / shazamio 源码摘录（MIT，见 NOTICE）
```

## 参与改进

- 遇到 bug / 识别不出 / 想要新功能：直接开 **Issue**，能附上 App 内长按复制的识别日志最好（日志里有音量、时长、每个引擎的报错，定位极快）。
- 提 **PR**：先读 [CONTRIBUTING.md](CONTRIBUTING.md)，里面有构建方法、跑测要求，以及一批**用真机/实测换来的设计约束**（别踩清单）。
- 中/英文 issue、PR 都欢迎。

## 致谢

- [SongRec](https://github.com/marin-m/SongRec)（marin-m）与 [shazamio](https://github.com/shazamio/shazamio) —— Shazam 指纹算法与接口协议的公开实现，本项目 Java 移植的蓝本
- [ACRCloud](https://docs.acrcloud.com) / [AudD](https://audd.io) —— 可选兜底识曲引擎
- [LRCLIB](https://lrclib.net) —— 开源同步歌词库
- 网易云音乐 / QQ音乐 —— 歌词兜底来源（非官方接口）

## License

[MIT](LICENSE)。`shazam-ref/` 内的参考源码片段版权归其原作者，均为 MIT，详见 [shazam-ref/NOTICE.md](shazam-ref/NOTICE.md)。

---

*本项目与 Apple / Shazam 无隶属关系；Shazam 接口调用方式为逆向研究所得。*
