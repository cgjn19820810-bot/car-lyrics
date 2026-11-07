# shazam-ref —— 移植蓝本声明

本目录是从两个 MIT 开源项目摘录的**参考源码片段**，作为
`java/com/wbhome/carlyrics/ShazamFingerprint.java` / `Hanning.java` / `Shazam.java`
移植时的事实依据。它们**不参与编译**，仅存档备查。

| 文件 | 来源 | 说明 |
|---|---|---|
| `rs_algorithm.rs` | [SongRec](https://github.com/marin-m/SongRec)（marin-m，MIT） | Shazam 指纹算法 Rust 实现（峰值扩展/识别/编码） |
| `rs_signature_format.rs` | SongRec | 指纹二进制格式（header + TLV）编解码 |
| `rs_hanning.rs` | SongRec | 2048 点 Hanning 窗 f32 常量表（`Hanning.java` 逐位照抄的来源） |
| `py_algorithm.py` | [shazamio](https://github.com/shazamio/shazamio)（MIT，早期 Python 版） | 老版 Python 算法，参数与 Rust 版有差异，**不可当移植蓝本**，留作对照 |
| `py_signature_format.py` | shazamio | 同上 |

原始项目版权与许可证：

- SongRec — Copyright (c) marin-m contributors, MIT License
  <https://github.com/marin-m/SongRec>
- shazamio — MIT License
  <https://github.com/shazamio/shazamio>

Shazam 识别接口（`Shazam.java` 的 endpoint / headers / body 结构）的公开逆向参考：
shazamio-core <https://github.com/marin-m/shazamio-core>（MIT）。

算法学术出处：Wang, Avery. *An Industrial-Strength Audio Search Algorithm.*
Proc. 4th International Conference on Music Information Retrieval (ISMIR), 2003.
