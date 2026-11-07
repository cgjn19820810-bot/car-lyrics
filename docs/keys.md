# 可选识别引擎的 key 怎么申请

**先说结论：什么都不填也能用。** 主引擎 Shazam 完全免费、不限次数、无需注册。
这篇只面向「想让识别多一层保险」的人 —— ACRCloud 和 AudD 是可选兜底，
Shazam 没听出来时才会轮到它们。

---

## ACRCloud（推荐作为兜底，华语曲库覆盖好）

### 申请步骤

1. **注册账号**
   - 国际站：<https://console.acrcloud.com>（acrcloud.com，海外区域）
   - 中文站：<https://www.acrcloud.com.cn>（acrcloud.cn，国内区域，网络更稳）
   - 两边账号体系**不通用**，选哪边就用哪边的控制台，注册只需邮箱。
2. **创建项目**
   - 登录后进控制台 → `Audio Recognition`（音频识别）→ `Add Project` / 新建项目；
   - 项目名随意，Ecosystem（平台）选 `Other` 即可。
3. **复制三项凭据**
   项目创建后，项目详情页会显示：
   - **Host**（形如 `identify-cn-north-1.acrcloud.cn` 或 `identify-us-west-2.acrcloud.com`，**区域不同值不同**）
   - **Access Key**
   - **Access Secret**
4. **填进 App**
   打开 App 设置页 → 「ACRCloud 识别引擎」一节 → 把三项分别粘进去 → 保存。
   > App 故意不给 Host 预填默认值：填错区域会报「Access Key 不对」，照控制台原样抄最稳。
   > 粘贴时带上 `https://` 前缀也没关系，App 会自动去掉。

### 额度与报错

- 免费额度有限（各时期政策以官网/控制台为准），**超限时接口返回 `code=3003 requests limit exceeded`**
  —— 这时识别并不会失败，只是 ACRCloud 这条兜底歇菜，等下一周期或升级付费。
- 一次识别消耗的音频约为你设置页「送检长度」的秒数，别把它当无限刷的接口。
- 常见错误码对照（App 日志里会显示）：
  | code | 含义 |
  |---|---|
  | 3001 | Access Key 不对，或 Host 填了别的区域 |
  | 3002 | Access Secret 不对 |
  | 3003 | 免费额度用完 |

---

## AudD（纯兜底，白嫖额度更小）

### 申请步骤

1. 打开 <https://dashboard.audd.io> → `Sign Up`（邮箱注册，很快）。
2. 登录后在 dashboard 里找到自己的 **API Token**。
3. 填进 App 设置页「兜底引擎 AudD（可选）」→ 保存。

### 额度与报错

- 不填 token 时 App 会借用官方示例 token（`test`），**所有白嫖者共享一份额度，很容易见底**。
- 填了自己的 token 后，免费额度按官方政策（注册后 dashboard 上可见），
  超限时返回 `code=901`。
- `code=900` = token 无效，检查是不是复制错了。

---

## Shazam 主引擎（无需申请，供了解）

- App 在**本地**计算 Shazam 兼容指纹（`ShazamFingerprint.java`，Wang 2003 算法的 Java 移植，
  蓝本为 MIT 开源项目 [SongRec](https://github.com/marin-m/SongRec)），
  然后按 iOS 客户端的协议直接向 `amp.shazam.com` 查询（`Shazam.java`）。
- 不需要账号、不需要 key、没有可知的次数限制。
- 它是逆向出来的非官方用法：如果哪天 Apple 改了接口，App 日志里会显示 Shazam 的 HTTP 状态码 ——
  欢迎提 issue，改起来不难。
