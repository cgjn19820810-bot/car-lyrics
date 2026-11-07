# 参与贡献指南

欢迎 Issue 和 PR。动手之前花三分钟读完这篇，能避开本项目里一批**用真机和通宵实测换来的坑**。

## 开发流程

1. Fork 本仓库，开分支改动。
2. 改完先跑回归测试（[test/README.md](test/README.md)），全绿再打包。
3. `bash build.sh` 出 APK（构建环境准备见 [README](README.md#从源码构建)），**真机装上新包实测**：
   车里场景（外放 + 噪声 + 切歌）才是本项目的靶子，模拟器给不了结论。
4. 递增 `build.sh` 里的 `--version-code` / `--version-name`，更新 `CHANGELOG.md`。
5. 提 PR，描述里写清楚：改了什么、为什么、真机怎么测的。有识别日志/录屏更好。

## 硬性纪律

- **不要换 `debug.keystore`**。发布的 APK 由它签名；换了签名，所有人的已装 App 都无法覆盖升级你的构建。
- **不要引入 Gradle / 外部 maven 依赖**。整个 App 零第三方库（连 JSON 都是 `org.json` 自带），这是刻意的：
  国内网络 + 无 Google 服务的机器也要能一键复现构建。
- 代码注释保持现有风格：**关键决策旁边写清楚「为什么」**（包括当年踩过的坑），别只留「做了什么」。

## 别踩清单（都是真金白银的实测结论）

| # | 约束 | 怎么来的 |
|---|---|---|
| 1 | **Shazam 必须喂满 12 秒完整音频**，且不许裁留白。实测 4/8/10 秒全部 0 匹配、12 秒才命中。 | `AudioBuffer.takeFull(12)`，与 ACRCloud 那路的「裁尾部留白」是两条独立路径 |
| 2 | **Android 上别手动设 `Accept-Encoding: gzip`**。设了之后 HttpURLConnection 不再自动解压，Shazam 返回的 gzip JSON 会被当乱码字符串解析，全线 `JSONException`。 | 当年「电脑能识别、手机一首都不行」的真凶，`Shazam.java` 里有注释 |
| 3 | **歌词进度校准的锚点必须一致**：`baseLocalMs`（本地时刻）与 `baseOffsetMs`（歌曲位置）必须指向同一点。历史上锚点一个取片段中点、一个取片段末端，歌词系统性慢半段时长（Shazam 路 = 慢 6 秒），±3 秒微调都救不回来。 | `LyricService.applyResult` 注释 |
| 4 | **Hanning 窗常量必须逐位照抄 f32 字面量**（`Hanning.java`，源自 `shazam-ref/rs_hanning.rs`）。用公式重算会得到差几个 ulp 的 f64 舍入值，指纹就对不上 Shazam 服务器了。 | `test/ShazamFpTest` 对 `golden.raw` 校验 |
| 5 | **静音阈值 `SILENT_RMS` 不能定高**：车机音量 70% 时满电平音乐 RMS 也只有 ~83。改这个值前先跑 `test/C.java`。 | 曾有阈值 110 导致「音量稍小就装死」 |
| 6 | **取窗不许跨歌曲谷底**：车里切歌瞬间如果把上一首的尾巴裁进新样本，必输。`WindowPicker.trimValley` 的护栏逻辑有 39 项回归（`test/WP.java`），改动必跑。 | v1.5/v1.6 迭代 |
| 7 | **ACRCloud 只识别送进去音频的前 12 秒**，送更长纯浪费上行。 | 官方 API 文档 |
| 8 | **AudD `timecode` 带小数秒**，`"00:56.5"` 用 `Math.floor` 会丢 500ms。 | `test/T.java` |
| 9 | **验证以产物自身为凭**：改完构建，用 `aapt2 dump badging`（版本号）、`apksigner verify --print-certs`（签名）、直查 dex 内字符串确认新代码入包 —— 别信「命令跑过就算成」。 | 曾有构建被静默废掉一次 |
| 10 | **构建脚本别用「先删 build/ 再构建」**：部分环境对批量删除有保护且退出码为 0，会静默留下旧 class 编出旧包。`build.sh` 用时间戳新目录就是为了绕开这个。 | build.sh 注释 |

## 加新识曲引擎 / 新歌词源

- 引擎：照 `Shazam.java` / `AudD.java` 的样子写一个 `identify()`，返回统一的
  `AcrCloud.Result`（`ok / engine / title / artist / playOffsetMs / message`），
  在 `Recognizer.java` 的串行链里插位。记得给 `playOffsetMs` 想清楚语义（片段起点秒 × 1000）。
- 歌词源：`LyricsFetcher.java` 是「搜索 → 候选打分 → 歌手强匹配 → 拉同步歌词」的结构，
  新源照 `qq` 的样子加。歌词三源全部是非官方或社区接口，**每个源都要能单独失效不拖垮整体**。

## 提 Issue 的最小有用信息

- App 版本号（主界面底部）与手机型号/系统；
- **长按状态框 → 复制全部** 的识别日志（音量、送检秒数、每个引擎的报错都在里面）；
- 认不出的歌：歌名 + 歌手 + 是原唱/翻唱/现场；
- 「识别得到但歌词不同步」：差几秒、歌词在前还是在后。
