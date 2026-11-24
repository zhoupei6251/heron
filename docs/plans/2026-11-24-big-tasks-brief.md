# Heron 剩余大任务清单（2026-11-24 按代码与库核实）

> 给「另一个 agent 接手」用的需求草案。每条都标了现状证据位置，接手的人不用先猜哪些已经做了。
> 与 `docs/plans/2026-11-23-remaining-work.md` 是两份：那份记零散欠账，这份只记**够大、值得单独立项**的。

## 判断口径

「大任务」= 同时满足三条：

1. 要新建表或新建一条数据链路，不是改几行；
2. 有明确的上下游依赖，做完了别的任务才能开始；
3. 现在这一整段是零（不是「有雏形待打磨」）。

按这个口径，真正的大任务有 6 个。其余（冻结帧阈值、compare 两栏、黑场闸）都已在 `2026-11-23-remaining-work.md` 里记过，不重复。

---

## T1. 爆款链路后半段：获取 → 拆解 → 视觉配方（最大，优先）

### 现状（已核实）

| 环节 | 状态 | 证据 |
| --- | --- | --- |
| 发现（抖音热榜 + 热视频） | **已做** | `data/tools/douyin-trend/trend.py`；表 `heron_trend_video`；`TrendCollectService` |
| 采集实跑 | **已验证** | 2026-11-24 实跑：30 条视频 / 51 热词 / 11.4 秒，≤90 秒的 12 条 |
| 下载 | **零** | `TrendCollectService.java:82` 只把 `downloadUrl` 当字符串存进列，全表无下载代码 |
| 下载直链可用性 | **已验证** | 带 `Referer: https://www.douyin.com/` + 浏览器 UA 拉同一 URL → 200 / 3.96MB / video/mp4；无 Referer → 403 |
| 无水印地址挑选 | **已修（2026-11-24）** | `trend.py` 的 `pick_download()` 跳过 `aweme/v1` 形式，带 `--self-check` 离线校验 |
| rea-video | **未安装** | `data/tools/rea-video` 不存在 |
| 拆解 | **零** | 全仓 grep `decompose` / `VisualRecipe` 零命中 |
| 配方入库 | **零** | 库里无任何 reference / recipe / asset 表 |
| 接入生产 | **零** | `heron_trend_video` 的消费方只有 `TrendVideoController`（列表接口），生产线不读它 |

### 要做什么（建议拆成四个可独立交付的子任务）

**T1.1 素材获取与治理**

- 新表 `heron_reference_asset`：按 `(platform, aweme_id)` 唯一，记原片 OSS 路径、文件大小、"内容 hash、下载状态、失败原因、来源许可判定、获取时间。
- 状态机沿用需求文档 `reference_trend-intelligence.md` §3.7 的七个状态（DISCOVERED → CHECKING → RESTRICTED / PENDING_AUTH / ACQUIRING → ACQUIRED / FAILED）。
- 只对「已入库且够短」的候选发起下载；并发、单文件大小、总容量都要有上限，不能一次把热榜全拉下来。
- 文件进 OSS（复用现有 `sys_oss` 与 `OssService`），**不写 MySQL**。

验收：跑一次采集后，够短的候选在表里能走到 `ACQUIRED`，OSS 里拿得到真 mp4，失败的留在 `FAILED` 且有可读原因。

**下载怎么实现（已实测，别再重新调研）**

| 结论 | 证据 |
| --- | --- |
| **抖音不要引这些下载库**（yt-dlp / douyin-dl / 同类） | 都卡在同一处：抖音 `aweme/detail` 接口的反爬。见下面三家的实测 |
| **用已有的 `ApacheNewsTransport`** | 它在 `ApacheNewsTransport.java:70-72` 已经设了 `User-Agent` + 同源 `Referer`，与抖音 CDN 的要求完全一致；另带 DNS pinning、逐跳重定向校验、体积上限 |
| **`Referer` 是硬要求** | 同一个 CDN 直链：带 `Referer: https://www.douyin.com/` → 200 / 3.96MB；去掉 → **403**。裸请求也 403 |
| **直链不会很快过期** | 13:19 采到的直链，13:47 再拉仍 200 / 全量字节。但别因此不做重试 |
| **上限要单独配** | `MAX_RAW_BYTES` 是给新闻正文用的（几十 KB 级）。视频另设上限，与生产线的 `news-max-bytes: 209715200` 对齐，**不要复用同一个常量** |
| **`play_addr` 不能用** | 抖音的 `play_addr.url_list` 里是裸 hex 哈希（`203fe7f13f6ec...`），不是 URL。yt-dlp 在 TikTok 那边把它当首选格式，抖音这边形状不同。我们只用 `download_addr`，但这是运气，接手方别改 |
| **`has_watermark` 字段不存在** | 热点接口响应里没有这个键（实测 `None`）。只能靠 URL 形状判水印，不能靠标志位 |

**三个现成库的实测（2026-11-24，都别浪费时间重跑）**

| 库 | 结果 |
| --- | --- |
| **yt-dlp**（1800+ 站） | 抖音 `403`。源码里明写 `# TODO: Run verification challenge code to generate signature cookies`——签名没实现。把真实浏览器抠出的 32 个 cookie（含它点名要的 `s_v_web_id`）喂进去仍 `403` |
| **jiji262/douyin-downloader**（12.6k★，MIT，`douyin-dl-cli`，2026-11-24 还在更新） | **它自己签了 `a_bogus`/`X-Bogus`**（`core/api_client.py:394,489`），`--hot-board` 零 cookie 可用、还带我们采不到的 `view_count`；但**单条下载同样失败**：`抖音未返回作品详情（可能触发了风控验证）`。给真实 cookie、开 `browser_fallback` 都不行 |
| **Mintnoii/douyin-downloader**（4★） | 是上者的 fork（README 里 socialify 图还指向 jiji262），不单独评估 |

**为什么我们能下、它们不能**

我们不调 `aweme/detail` 接口，改用 patchright 加载 `/hot` 页面、**拦截页面自己发出的、已签名的响应**，从响应里直接拿 `download_addr.url_list`。签名由抖音自己的 JS 算，我们不碰。

同一个视频（`7707974930184594651`，30.8s）对照：我们的路径 `200` / **10.2MB** 拿到；`douyin-dl` 失败。这是决定性证据。

**代价**：占一个浏览器进程、单次约 11 秒拿 10 条。对「一天扫几次」完全够用，不要为了省这点去换库。

**唯一的例外**：将来加 YouTube / TikTok 时确实该用 yt-dlp——那两个站它零 cookie 零配置可用（实测 B站 212s、YouTube 213s 都成功）。抖音不通不代表它整体不行。

**水印坑（已修，别改回去）**

`download_addr.url_list` 不保证顺序，`[0]` 偶尔是 `aweme/v1/play?watermark=1` 这种带水印的接口形式。带水印的片子拆解时字幕区被遮挡。

`trend.py` 的 `pick_download()` 已按「优先取非 `aweme/v1` 的地址」挑选，并带 `--self-check` 离线校验（6 例，不联网）。2026-11-24 实跑确认：10 条里 9 条拿到 CDN 直链，1 条退回接口形式——**那条是整条 `url_list` 里就没有 CDN 直链，不是挑选失败**。下载环节可以从 URL 里有没有 `watermark=1` 直接判出这种降级。

**未来加 YouTube / TikTok 时用 yt-dlp**

到那一步 yt-dlp 是对的：它在这两个站上零 cookie 零配置就能跑（实测 B站 212s、YouTube 213s 都成功，拿到完整元信息）。注意它警告缺 JS runtime（默认只认 `deno`），装一个再跑。抖音这条不通不代表它整体不行——是字节的签名没被它实现。

**T1.2 rea-video 拆解引擎**

- 装 `rea-video`（Apache-2.0，Python ≥3.10，需 ffmpeg，无 GPU）。注意：`data/tools/social-auto-upload/.venv` **没有 pip**，要么先 `ensurepip`，要么单独建 venv，不要往那个 venv 里硬塞。
- 写一个 Java 侧的服务，按 `rea-video decompose <video> -o <out>` 调用，超时可配、输出目录隔离、失败不静默。
- 产出落 OSS：`breakdown.json`（结构化，后续要解析）、`report.html`、`storyboard.jpg`、`VIDEO.md`。

验收：对一条真实下载的片子跑通，`breakdown.json` 能解析出镜头数与分镜头寸，整条链路有超时与失败路径。

**T1.3 Visual Recipe 提取**

- 从 `breakdown.json` 抽出一份「可被 Heron 复用」的视觉配方：镜头节奏、开场 Hook 类型、字幕样式、动效类型、配色倾向、信息密度。
- 新表存配方，按 `(platform, aweme_id)` 挂到资产上，同时保留原始 breakdown 的 OSS 指针。
- **不要在这一步就做「评分排序」**：现在没有拆解样本，任何权重都是拍的。先攒够样本再谈权重。

验收：拆 10 条片子，配方字段能横向对比出差异（不是 10 条都长一样）。

**T1.4 接入现有生产**

- 配方怎么被生产线用：最小可行是「配方 → 模板选择偏好」，再往上是「配方 → 分镜节奏约束」。
- 现状参考：`StoryStyleMap` 是从 `data/templates/_shared/styles.json` 读「哪套风格能承载哪些叙事」，配方应该走同一类登记，不要另起一套。

边界：**不要做「原片再创作」（直接改别人的画面重发）**。需求文档 §3.6 明确把下载许可和再创作许可分开判，Heron 只学结构不复用画面。

**多平台扩展（T1 之后，不是现在）**

当前发现源只有抖音。要加 YouTube / TikTok 时，下载用 **yt-dlp**——实测这两个站零 cookie 零配置可用，元信息、格式选择、字幕都能拿到。别为它们手写 extractor。前提是装一个 JS runtime（yt-dlp 默认只认 `deno`，缺了会警告部分格式缺失）。

另一个可考虑的点：`jiji262/douyin-downloader` 的 `--hot-board` **零 cookie 能用**，且响应里带 `view_count`（实测 6568 万这种量级），这是我们自己采不到的字段——抖音 web 端的 `play_count` 恒为 0。如果以后需要播放量做热度判断，可以只借它这个热榜接口，下载仍然走我们自己的链路。

---

## T2. 自动线开闸灰度

### 现状

三处开关全关，能力建好了没启：

| 位置 | 键 | 值 |
| --- | --- | --- |
| `application-dev.yml:393` | `heron.news.auto-enabled` | `false` |
| `ruoyi-modules/ruoyi-heron/src/main/resources/heron-production.yml:58` | `pipeline-enabled` | `false` |
| `heron-production.yml:60` | `auto-enabled` | `false` |

`heron_auto_slot` 里历史槽位全部 `manual-*`，没有一条是 cron 产出的。这说明自动线**从没真正跑过**。

### 要做什么

- 先开一条/天，出口固定 `publish_source = "sandbox"`，跑满一周看槽位结果分布（produced / skipped / failed）。
- `heron_auto_slot.retry_count`（V55 已加）要真的生效一次：故意造一个 failed 槽位，确认 30 分钟后会补跑。
- 灰度矩阵：逐源开启（不是全局一个开关），并保留随时下架单个源的能力。

不做：`auto-confirm-enabled` 在拿到开放平台凭据前不开。

---

## T3. 文稿解说线启用

### 现状

- `heron-production.yml:10` 写 `article-enabled: true`，但 `CLAUDE.md` 记的是 `article-enabled: false`，**文档与配置不一致**，得先确认哪个是真的。
- 20 套模板里只有 5 套开了 `storyboard`，14 套文稿/知识模板没开。
- `2026-11-23-remaining-work.md` 第 8 条已说明卡点：文稿线不产 beat 与上屏文字，开了 headline 屏会藏正文、藏标题卡。

### 要做什么

- 先修文档与配置的不一致（这本身零代码）。
- 给文稿模板补「beat + 上屏文字」产出，再做一轮渲染验证，确认开 storyboard 后正文与标题卡仍然可见。
- 一次只开一类模板，别 14 套一起开。

---

## T4. 3D 资产库扩容

### 现状

- 只有 1 套 3D 模板：`data/templates/news-data-3d-9x16`（声明 globe）。
- `data/templates/_gen/behaviors/globe.js:13` 只认 `dataset.shape === "geo"`——也就是说 3D 只能画地球，别的空间形式全画不了。
- `docs/plans/2026-11-23-data-visual-mode-3d-design.md` §6 已提出要第二套（terrain / 剖面 / 柱阵），但没定做哪种。

### 要做什么

- 先定第二套做哪种空间形式（这一条需要人拍，不是 agent 能定的）。
- 抽 `behaviors/` 下 3D 行为，让 shape 不再写死 geo。
- 3D 线已有真片（`docs/acceptance-2026-11-23/geo-acceptance-summary.md`），第二套应该复用同一套门禁。

---

## T5. 前端缺失页面

### 现状

- `heron-ui/src/views/heron` 下 16 个 `.vue`，**没有爆款/趋势页面**。
- `heron_trend_video` 后端已有列表接口（`GET /heron/trend/videos`，已验证返回 12 条数据），但**没有菜单、没有页面**（`sys_menu` 里无 trend 相关条目）。
- `api/heron/` 下 12 个目录，无 trend。

### 要做什么

- 爆款候选页：按热榜位次排、显示时长/点赞/作者、标记「够短」、能触发一次采集。
- 配方页（依赖 T1.3）：拆解结果横向对比。
- JEV SHADOW 对照页（依赖 T6）：本地判断与外部判断的一致率。

不做：不要做成大屏或仪表盘。这些是运营用的列表页，走 plus-ui 标准 CRUD 形态。

---

## T6. JEV 阶段 0：离线评估与金标集

### 现状

- `heron_jev_shadow` 表已建（V54），`JevShadowRecorder` 已挂在 `EditorialDecisionService.decide` 上，异步对照、不进任何分支。
- 但**表里 0 行**——自动线没开，`.decide` 从没被真正调用过。
- 真实外呼已冒烟（2026-11-24，Command Code `typesafe/jev` 返回 ChoiceAnswer）。

### 要做什么

- 先攒够对照样本（依赖 T2 开闸）。
- 用现有账本 + 报告做离线评估，建金标集。
- 评估标准要事先定死，不能看完数据再调口径。

---

## 交付顺序建议

```
T1.1 素材获取 ─┐
T1.2 拆解     ─┼→ T1.3 配方 ─→ T1.4 接入生产
               │
T2 自动线开闸 ─┴→ T6 JEV 阶段 0

T3 文稿线启用   （独立，与 T1 无依赖）
T4 3D 扩容      （独立，需要人先定空间形式）
T5 前端页面     （T1.3、T2、T6 的下游，可以边有边做）
```

T1 与 T2 可以并行，互不依赖。T3、T4 完全独立。T5 依赖前面几个出数据。

---

## 不要做（设计上的决定，不是欠账）

- 案例拆解栏目不单独建第五条生产线（`docs/plans/reference_github_4.md:399`）。
- 多租户、套餐、配额、计费、自助注册、第二套 RBAC（`CLAUDE.md` 边界）。
- 「一个全局开关就全线直发」（`CLAUDE.md` 边界）。
- 原片画面再创作（见 T1 边界）。
- 按 Heron 自己的历史表现优化选题——方向已改成按市面爆款优化（2026-11-24 用户明确要求）。
