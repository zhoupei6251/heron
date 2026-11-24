# 爆款学习闭环 · 设计（2026-11-24）

> 目标不是再加一套视频引擎，而是让 Heron **知道市面上的爆款是怎么做的**，并把结论沉淀成可对照的技法卡。
> 这是 `2026-11-24-big-tasks-brief.md` 里 T1 的最小可用切面：先跑通「选样 → 下载 → 拆解 → 归纳」，**不建配方表、不接生产线**。

## 判断口径

参考的九份文档（`docs/reference/root/`）指向同一结论：现有 20 套模板、三套原创风格、StoryPlan、音画与关键帧门禁已经够用，**缺的不是渲染能力，而是「什么样的开场 / 节奏 / 画面真的留得住人」**。这只能从真实爆款里学，不能读仓库文档拍脑袋。

因此本设计**不引入** HyperFrames registry 之外的新引擎、不引入 Remotion / Motion Canvas / Manim、不做原片再创作。

## 闭环形态

```
trend.py 采集（已有）
   ↓
heron_trend_video（已有，实跑 30 条 / 51 热词 / 11.4 秒）
   ↓ ① 选样：Java 硬筛 + 一次 LLM 排序
   ↓ ② 下载：复用 ApacheNewsTransport（带 Referer，已验证 200 / 真 mp4）
   ↓ ③ 拆解：rea-video decompose
   ↓ ④ 归纳：ReActAgent 对照 20 套模板，出技法卡
   ↓
docs/learned/YYYY-MM-DD/
```

## 状态机（存在 `heron_trend_video`，不建新表）

```
SEEN → SELECTED → DOWNLOADED → DECOMPOSED → LEARNED
                 ↘ DOWNLOAD_FAILED
                               ↘ DECOMPOSE_FAILED
                               ↘ LEARN_FAILED
```

`LEARNED` 为不可逆终态，选样时直接排除——**选过且成功的永不再选**。`*_FAILED` 可重试，重试次数记在 `learn_note`。

## ① 选样

**Java 硬筛**（无模型）：`duration_ms ≤ 90000` ∧ `learn_state ∈ {SEEN, *_FAILED}` ∧ 互动量非零 ∧ 不与已 `LEARNED` 的记录重复。

**一次 LLM 排序**：输入硬筛后的候选摘要（`description`、时长、`digg/comment/share/collect`、`rank_no`、作者），输出 `{awemeId, relevance, reason}`，按相关度取前 N（N ≤ 10，**允许 0 条**，不硬凑）。

判据只有一条硬要求：跟 Heron 栏目相关（数据排行 / 趋势解读 / 行业洞察 / AI 科技 / 知识解释）。播放量不可用（抖音网页端 `play_count` 恒为 0，实体注释已写明）。

## ② 下载

复用 `ApacheNewsTransport`：它已设 `User-Agent` + 同源 `Referer`，实测同一 CDN 直链带 Referer → 200 / 真 mp4，去掉 → 403。另带 DNS pinning、逐跳重定向校验、体积上限。

- 视频体积上限**单独配**，不复用 `MAX_RAW_BYTES`（那是给新闻正文的几十 KB 级）。
- 文件进 OSS（复用 `sys_oss` / `OssService`），`oss_id` 回填到 `heron_trend_video`。
- 直链形状坑已在 `trend.py` 的 `pick_download()` 修过；URL 里带 `watermark=1` 直接判降级。
- **不引** yt-dlp / douyin-downloader / 同类库：实测都卡在 `aweme/detail` 反爬（详见 brief T1.1）。

## ③ 拆解

`rea-video decompose <video> -o <out>` 子进程调用，超时可配、输出目录隔离、失败不静默。产物落 OSS：`breakdown.json`、`report.html`、`storyboard.jpg`、`VIDEO.md`。

前置：`rea-video` 未安装；`data/tools/social-auto-upload/.venv` 没有 pip，**单独建 venv**，别往那个里塞。

## ④ 归纳（用 ReActAgent，不用单次调用）

技法必须对照 Heron 现有模板才说得清「该落到哪」，而 `_shared/styles.json` + `tokens/` + 布局 + 样例图全塞进 prompt 是每片几万 token 且每片重复。让 agent 按需读才省。

复用现成的 L2 执行器 `ResearchAgentFactory.createReAct(...)`，配 `ResearchBudget` + `ToolCallLedger`（不写新循环）：

| 工具 | 作用 |
| --- | --- |
| `read_breakdown` | 读 `breakdown.json` |
| `list_templates` | 列 20 套模板及 `styles.json` 能力声明 |
| `read_template` | 读某套模板的 tokens / 布局 |
| `view_frame` | 看 `storyboard.jpg` 抽帧 |

输出固定字段技法卡：

```
hookType / firstShotSeconds / avgShotSeconds / cutRhythm
textStyle / motionTypes / infoDensity
reusablePoints / heronTemplateFit
```

两处模型调用都走现有封装，**不引入 JEV**：这轮没有「对错标准」，Judge 无处校准；等攒够片子要判「某技法是否真让成片变好」时才需要对照（属 T6 阶段 0）。

## 数据变更

`heron_trend_video` 加 3 列（Flyway `V{序号}__trend_learn_state.sql`）：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `learn_state` | varchar(24) | 状态机取值，默认 `SEEN` |
| `learn_note` | varchar(500) | 失败原因 / 重试次数，人可读 |
| `oss_id` | bigint | 复用 `sys_oss`，原片指针 |

不建 `heron_reference_asset`、不建配方表。等真出现「同一片子要挂多份资产」时才需要。

## 产出物

```
docs/learned/2026-11-24/
  7707974930184594651.md     # 技法卡（人读）
  7707974930184594651.jpg    # storyboard.jpg
  techniques.md              # 汇总：这批片子共同的技法 + 各自差异
  _raw/                      # breakdown.json 等原始产物（OSS 指针见库）
```

## 不做什么

- 不建配方表、不做配方评分排序（没有样本，权重全是拍的）
- 不接生产线、不改模板（学完先出「改造点清单」，下一轮才动）
- 不做原片画面再创作（只学结构，不复用画面）
- 不引入 JEV、不新增渲染引擎
- 不把学习任务挂进 `heron_research_task`（`decisionType` 是选题语义，塞进去是污染）

## 验收

1. 跑一轮，能自动选出 N 条（N ≤ 10，可为 0）并走到 `LEARNED`
2. 再跑一轮，`LEARNED` 的不再出现
3. 某条下载/拆解失败停在 `*_FAILED` 且 `learn_note` 有可读原因，不阻塞同批其他条
4. 每片产出技法卡 + `storyboard.jpg`，`techniques.md` 能横向对比出差异（不是 N 条一样）

## 下一步（不在本轮）

技法卡对照 20 套模板，出「哪套缺什么、哪套风格该调什么」的改造点清单 → 再决定补哪些组件。十片片子不足以支撑模板改造。
