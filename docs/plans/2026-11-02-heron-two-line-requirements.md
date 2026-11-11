# Heron 两条业务线需求文档

**版本**：2026-11-02 · v1（待评审）
**适用基座**：RuoYi-Vue-Plus 6.0.0（Spring Boot 4.1.2 / JDK 21 / MyBatis-Plus / Sa-Token）+ plus-ui（Vue 3 + Element Plus）
**产品形态**：单租户、自部署、日更 3 条
**取代关系**：本文定义两条业务线的**外部效果落地形态**，取代 2026-10-29 规格的 §8 现状盘点、§10 未决决策（D1/D2/D3/D4/D5/D6 全部结案）与 §11 本轮不做中的渲染与发布部分。规格中的业务规则（状态机语义、闸的判定纪律、确认三样、失败分类、保留与鉴权）全部保留，不重述。

---

## 0. 一句话

骨架已经建完了。本轮要做的只有一件事：把四条**空接缝**接上真实外部效果——渲染出片、模型出稿、装配出 `.rp`、发布出回执。

**本轮的产物是「真效果」，不是「新框架」。** 任何以「先抽象再实现」为名新增的接口、配置项、后端开关，默认视为越界，除非本文某节明确点名。

---

## 1. 现状：已实现 / 空缺（实测核对）

### 1.1 已实现（不要重复实现）

| 范围 | 位置 |
|---|---|
| 共享生产核心 | `ruoyi-modules/ruoyi-heron/.../service/impl/ProductionServiceImpl`、`ProductionExecutionServiceImpl`（租约/认领/心跳）、`ProductionRunner`、`ProductionRecoveryService`、`ProductionArtifactService`、`ProductionUsageService`、`ProductionCleanupService` |
| 沙箱子进程 | `ProductionProcessService`：固定工具白名单 `{node, ffmpeg, ffprobe, python, zee-rp}`，由 `heron-production.yml` 解析，经 `src/main/resources/runtime/process_guard.py` 执行（内存/CPU/输出上限，**Linux only**） |
| 新闻线 | `HeronNewsSource` / `HeronNewsCandidate` / `HeronNewsWork` / `HeronVideoVersion` + `NewsSourceServiceImpl`、`NewsCandidateCollector`、`NewsSourceFetcher`、`NewsSourceRunner`、`NewsDedup`、`ApacheNewsTransport`、`NewsUrlPolicy`、`NewsAddressPolicy` |
| 原型线 | `HeronProtoProject` / `HeronProtoVersion` + `ProtoProjectServiceImpl`、`ProtoProductionDomain`、`ProtoController`（含 native-verifications / deliver / download） |
| 表 | `script/sql/heron/001-production.sql`、`002-news.sql`、`003-prototype.sql`、`004-menu.sql` |
| 前端 | `heron-ui/src/views/heron/{news/source,news/candidate,news/work,production,proto}` + `src/api/heron/*` |
| 闸 | 新闻线 10 道：`schema, evidence, audio, subtitles, render, decode, visual, aigc_metadata, aigc_visible, assets`；原型线：`check` 结构 + 视觉审计四项 |

抓取侧安全边界已到位，本轮直接复用、不重写：
- `NewsUrlPolicy.normalize` 强制 http/https、禁 userinfo、端口只允许 80/443、IDN 归一；
- `NewsAddressPolicy.requirePublic` 对**任一**非公网地址整次解析失败（私网、环回、链路本地、ULA、组播、保留段；IPv6 只收 `2000::/3`）；
- `ApacheNewsTransport` 用 `PINS` ThreadLocal + 自定义 `DnsResolver` 把已校验的 IP 钉成实际连接地址——**DNS 重绑定 TOCTOU 已封堵**。

### 1.2 空缺（本轮的全部工作量）

| # | 空缺 | 当前状态 |
|---|---|---|
| G1 | **渲染执行器** | 无 `IProductionPipeline` 生产实现；`newsProductionPipeline` 只存在于测试（`TestProductionPipeline`），原型侧的 `prototypeProductionPipeline` 见 G4。全仓库无 hyperframes / ffmpeg 调用点、无 composition 文件 |
| G2 | **模型调用** | 已接线（2026-11-02 TDD）：`OpenAiModelClient.chat(run, stage, system, user)` 发一次 `POST {base-url}/chat/completions`，用量经 `ProductionUsageService` 落 `heron_usage_record`（供应商没报的 token 保持空，不填 0）；`ProtoModelRp1Author` 以 `prompt/prototype/rp1-authoring-contract.md` 为 system、冻结需求为 user，模型原文直接交装配流水线。还缺的是新闻写稿步骤本身（属 G1） |
| G3 | **发布** | 无抖音适配器；`heron_news_candidate.published_at` 注释写明「N1 never writes this field」 |
| G4 | **原型引擎调用** | 已接线（2026-11-02 TDD）：`ProtoProductionPipeline` 注册为 `prototypeProductionPipeline`，写 `.rp1` → `check` → `generate` → 定稿 `5+页数` 个产物 → 出 `ManifestEvidence`。配置项 `heron.production.zee-rp` 的语义随之从「可执行文件名」改为**引擎根目录**（能力探测看 `<root>/scripts/proto.mjs`）。剩余缺口只有 Linux 真跑（`process_guard.py` 限制）与人在 Axure 9 打开那一步 |

**默认全关**：`heron.production.worker-enabled: false`、`heron.news.collection-enabled: false`、`heron.news.allowed-hosts: []`。开关的放开是 §7 各里程碑的**出口判据之一**，不是实现步骤。

---

## 2. 本轮拍板（决策表）

| # | 决策 | 定论 | 放弃的选项与理由 |
|---|---|---|---|
| D1 | composition 由谁产出 | **固定模板 + 变量填充**。HyperFrames 工程是人工一次性交付物；模型只产结构化稿件 JSON；Java 确定性映射为 `--variables-file` | 否决「每单让 agent 现写 HTML」：结构每次都变会让契约闸、`check` 基线、AIGC 角标位置全部失去稳定判定，且重渲无法保证指纹可复现 |
| D2 | 渲染后端 | **本地渲染**（`hyperframes render --docker`），AIGC 元数据由 ffmpeg 后置写入 | 否决 HeyGen `cloud render`：`check` 反正要本地浏览器（cloud.md 明确要求上传后仍跑 `hyperframes check`），云端只省 FFmpeg；且引入按 credit 计费、非幂等上传（盲重试=重复 asset+双倍扣费）、短时效签名 URL、国内出网可达性未知 |
| D3 | 配音与字幕 | **edge-tts**（`zh-CN-XiaoxiaoNeural`），一次调用同时给音频与词级时间戳；`hyperframes transcribe`（Whisper）不进链路 | 否决本地 Kokoro：中文口播质量弱，且还要额外一步 ASR 才能得到字幕时间戳；否决 HeyGen TTS：会把 D2 刚排除的外部 SaaS 约束（key、账单、出网）请回来 |
| D4 | 发布最后一公里 | **v1 = 人工发布 + 回执回填**；开放平台 `video.create` 为独立里程碑，不阻塞前序里程碑 | 否决 Playwright 网页上传：cookie 机器身份 + 账号风控 + 平台改版即失效，风险打在唯一发布出口上 |
| D5 | Axure 目标版本与模板 | **在 `.rp1` 头部钉 `rp/1 target=9`**（`generate` 没有 `--version` 参数，见 §4.1）；空白模板由本机已安装的 Axure 9 新建单页空白工程另存 | 否决「GitHub 找现成 .rp」：引擎对基底有硬校验（`scripts/create-blank-project.mjs:29` 要求单页、widget/panelState/diagram 对象全为 0），网上现成工程全部带内容、过不了；且 RP10 不在引擎支持列表 |
| D6 | RP/1 由谁写 | **模型自由写 `.rp1`**，靠 `check` + 视觉审计拦，契约失败不自动重试 | 否决「人工预制骨架库 + 映射」：原型的价值在结构多样，骨架库会把它变成新闻线那样只有版式没有内容；且需要额外造并维护一套骨架资产 |
| D7 | 内容品类 | **只做泛资讯，时政不进候选池**（源清单白名单 + 认领时人工判断） | 否决自动分类闸：关键词黑名单误判率高，人耗在申诉上的成本高于收益；认领这一步本来就是人在看 |
| D8 | 事实依据 | **抓原文正文喂模型**，稿件必填 `source_url` + 抓取时间 + 正文哈希，画面带出处 | 否决「只用标题 + 模型参数知识」：现有三道自动闸判的全是形状，没有一道判事实，纯标题必然靠模型编数字/日期/主体，虚假信息的处罚落在账号 |
| D9 | 模型接入 | **自建轻量 OpenAI-compatible client**（~100 行），单一 model 配置项，`sys_config` + 环境变量兜底 | 否决 SnailAI：`ruoyi-common-ai` 实为 snail-ai 三个 starter（chat/executor/openapi），接它必须多养一个常驻服务，而两条线的模型需求都只是「一段 prompt → 一段结构化文本」 |
| D10 | 规模 | **日更 3 条**，渲染并发写死 1 | 不做队列调优、不做多 worker：这个量级下排队压力不存在 |

---

## 3. 业务线 A：新闻视频生产线

### 3.1 流水（每步 = 真实外部效果）

```
候选认领 → [模型] 稿件 JSON → [契约闸] → [变量映射] → [hyperframes check] → [edge-tts]
        → [hyperframes render] → [ffprobe decode] → [ffmpeg AIGC 元数据] → [视觉闸]
        → 审片 → [人确认] → [人工发布 + 回执回填] → 已发布
```

- 每一步产出**可独立校验的证据文件**，落 `data/videos/<work-id>/v<version>/`，沿用现有 6 种产物类型（`mp4, contact_sheet, script, subtitles, checks, asset_manifest`），**不新增产物类型**。
- 渲染耗时目标 ≤ 60 秒，认领→可审 ≤ 5 分钟（单次串行，不含人工审片等待）。诚实口径：分钟级出片，不是实时。

### 3.2 稿件 JSON（模型唯一产物）

模型的输出物**不是 HTML、不是 vars**，是一份受契约闸校验的稿件 JSON：

```
{ source_url, fetched_at, body_sha256,        // D8 必填，缺失即契约闸不过
  title,                                      // ≤ 15 字
  scenes: [ { lead, body, duration_s } ],     // 3–6 段，总时长 30–90 s
  closing,                                    // 领句/结尾一句
  source_name                                 // 画面「来源：xxx」文案
}
```

- 字段形状、必填、时长合计、标题与屏句重复——由契约闸判；**版式相关字段（字号、填幅、领句位）由 `hyperframes check` 判**，Java 不重复实现一份几何校验。
- 正文抓取：候选 URL → 复用 `ApacheNewsTransport`（含 `allowed-hosts` 白名单 + IP 钉死）→ **截断后的 HTML 直喂模型抽取要点**。不引入 jsoup / readability 类正文抽取依赖。
  `shortcut: 截断 HTML 直喂模型，噪声大时改引正文抽取库，触发条件是稿件返工率实测偏高`。
- 抓取失败 = 任务失败并入库原因，**禁止静默退回「只用标题」**。
- **实现落地（2026-11-02 TDD，纯 Java 可验证段）**：契约闸 = `NewsScriptContract.violations(JsonNode)`，判必填、未知字段、`title ≤ 15 字`、`scenes 3–6`、`duration_s` 合计 30–90 秒、标题与屏句重复；返回**条目清单**，调用方原样退回，不改稿也不重试。它接在 `NewsProductionDomain.complete()` 里，所以**任何流水线结果过不了契约就不入库**——不是等流水线建好才生效的一道闸。
- 出处三字段（`source_url` / `fetched_at` / `body_sha256`）**由抓取覆盖，不信模型自述**：`NewsSourceFetcher.fetchArticle(URI)` 复用采集那套白名单→逐跳解析→地址闸（安全逻辑只在共享代码改一次），哈希取**整篇解码文档**、喂模型的是截断前缀（12 000 码点，见 `ARTICLE_CODE_POINTS` 的 shortcut 注释）。
- **本轮暴露一条文档没写的口径**：手工候选（`addManual`）证据里 `sourceUrl` 为 null，D8 的出处要求在它身上无解。现按 `news_script_source` **直接拒绝写稿**，而不是放宽契约；要让手工条目可生产，得先给 `NewsCandidateBo` 加出处字段并过同一道白名单——那是新需求，不在本轮。
- **本轮不接的三件，理由写死**：① `vars.json` 映射——变量名由人工模板的 `data-composition-variables` 决定，模板不存在时写映射就是照想象的契约编代码；② `newsProductionPipeline` 本体（hyperframes check/render、edge-tts、ffprobe、AIGC 元数据）——`process_guard.py` 是 Linux only，本机 Node 20.9 连 hyperframes/pnpm 都起不来，命令形状一次都没实测过；③ 视觉闸。已落码的 `NewsScriptAuthor` / `NewsModelScriptAuthor` 目前没有调用方，等的就是 ②。

### 3.3 模板（人工交付物，不是代码功能）

- 一套竖版 composition：`1080×1920`（9:16），30–90 秒，声明 `data-composition-variables`。
- 交付位置：`data/templates/news-9x16/`（或部署侧只读卷），**不进 Git、不进镜像**——它含字体与商业素材引用。
- 模板由人在本会话外用 hyperframes skills 做好、`hyperframes check` 通过、并在抖音实际看过成片；文档不承诺由程序生成模板。
- 变量映射是 Java 侧一个**确定性函数**（稿件 JSON → `vars.json`），不含模型、不含分支选择。多套模板/多风格**本轮不做**。

### 3.4 闸的归属（避免重复造）

| 闸 | 判定者 | 不通过 |
|---|---|---|
| 去重 | 现有 `NewsDedup` | 不生成候选 |
| 契约（形状） | Java，判稿件 JSON | 退回写稿，列条目，**不自动改稿** |
| 契约（版式） | `hyperframes check --strict`（含 WCAG 对比度、layout、运行时错误、motion 断言） | 失败，附 `checks` 产物原文 |
| 音频 | edge-tts 调用成功 + 时长 > 0 + **无自动兜底** | 失败入库，等人重跑 |
| 字幕 | edge-tts 词级时间戳 JSON 非空且时长覆盖 | 失败 |
| decode | `ffprobe` 校验容器/时长/fps | 失败，保留现场 |
| visual | `check` 快照 + 现有填幅/占位判定 | 失败，附未过项 |
| aigc_metadata | `ffprobe` 读 mp4 内 `AIGC` 元数据字段 | **拒绝进入发布，不可跳过** |
| aigc_visible | 画面含「内容由 AI 生成」声明（模板内固定元素） | 同上 |
| 人工 | 确认三样（确认人 + 时间 + 联络表指纹） | 未确认不能发布 |

- `--quality` 用**本地词表** `draft | looks | delivery`；`draft|standard|high` 是 cloud 路径的另一套，不得混用（写死，防止照 README 抄错）。
- 渲染必产生新版本与新指纹 → 原确认失效需重确认（沿用既有纪律）。

### 3.5 发布 v1 的真实语义

- 「已发布」状态由**人回填 + 系统校验回执**产生：操作人在抖音发布后，回填作品链接/发布时间与账号标识，系统校验必填项与格式后置状态、写 `published_at`（此字段在本轮由该路径写入，覆盖 §1.2 的注释）。
- 幂等键沿用 `taskId:v版本号`；同一版本重复回填不产生第二条发布记录。
- 界面必须区分 `人工已发布` 与 `API 已发布` 两种来源，不得混为一谈——否则未来接开放平台时无法判断历史数据口径。
- AIGC 声明与「来源」标注在人工发布时同样必须已在成片内，合规闸不因人工而放松。
- **实现落地（2026-11-02 TDD）**：回执落在版本行的 `publish_*` 六列（`source|url|account|at|by|video_sha`），不新建发布表——发布事实与版本同生命周期，幂等键就是 `uk_video_version_task` 那一行。判定顺序是先格式（400）、再未来时间（`news_publish_time`）、再过 `readyFiles()`（含 AIGC 两闸与文件在场）、再审片事实（`news_publish_unapproved`）、再回执指纹（`news_publish_hash`）；四条都过了才用 `isNull(publish_at)` 条件更新写版本行，再用 `isNull(published_at)` 条件更新写候选 `published_at`，两次 `updateCount()!=1` 都退化成 `news_publish_conflict`。
- 同一版本重复回填走「四条校验仍全过 + 四要素逐位相等」的回放分支，返回既有事实且不增行；**换版本不能覆盖已存在的候选发布事实**（候选已有 `published_at` 即 `news_publish_conflict`），这是「一个候选只有一条已发布记录」的落点。
- `publishedAt` 先截断到毫秒再落库：`datetime(3)` 会四舍五入，不截断的话回放比对永远不相等，重放就变成冲突。
- 缺 `heron:newsWork:publish` 与「不是你的作品」一样返回真实 404（沿用本模块反枚举口径，不引入 403 新分支）；来源标签只有 `manual`，`api` 留给 M2，界面按 `publish_source` 分开显示「人工已发布 / API 已发布」。

### 3.6 内容边界

- **允许品类**：科技 / 数码、财经 / 商业、汽车、游戏、体育、影视娱乐、生活方式。
- **禁止进入候选池**：时政类社会新闻、突发事件报道、涉政治人物与政策评判、灾难伤亡报道。
- 执行方式：`heron.news.allowed-hosts` 只配泛资讯源；**判定责任在认领这一步由人承担**，系统不做自动分类。文档不假装程序能识别时政。
- 待实测：抖音对无《互联网新闻信息服务许可证》账号发布时政的具体处置（限流 / 下架 / 处罚）需拿官方条文或实测确认；**本条边界不以该结果为前提**，排时政是主动选择。

---

## 4. 业务线 B：原型工坊

### 4.1 引擎调用契约（全部经 `ProductionProcessService`）

```
node <zee-rp>/scripts/proto.mjs check     --spec <project>.rp1
node <zee-rp>/scripts/proto.mjs generate  --spec <project>.rp1 --out-dir <dir> --out-rp <out.rp>
```

- **版本钉在 spec 头部，不是命令行**（2026-11-02 实测更正）：`generate` 的参数表里没有 `--version`（`scripts/proto.mjs:133-149` 的 help 全列可查），未知 flag 不会报错也不会生效。目标版本由 `.rp1` 首行的 `rp/1 target=9 ...` 声明，`--out-dir` 与 `--out-rp` 才是必填（缺 `--out-rp` 且没传 `--compile-only` 时直接打 help 退出 1，`proto.mjs:829-834`）。
  引擎 `DEFAULT_VERSION = 11`（`scripts/version.mjs:13`），`supportedVersions() = [9, 11]` —— 本机已装的 **RP10 不被支持**。原生装配后端（`--backend native`，RP/1 的默认）从空白页到面板填充、Repeater、交互写入全程硬钉 9（`proto.mjs:296/365/437/637`）。
- **RP11 出不了，两个后端都一样**（2026-11-02 实测，别再当备选路径写进设计）：把 spec 改成 `target=11` 时 `check` 直接给 `E_TARGET_UNSUPPORTED / "RP/1 v1 native backend supports target 9"`；`--backend gui` 也不是 RP11 路径——它同样调 `createBlankProject(..., {version: 9})`（`proto.mjs:296`），且粘贴器写死 `open -a "Axure RP 9"` 与 `com.axure.AxureRP9`（`tools/rp9_place.py:168`、`:23`）。gui 后端在 Windows 实跑失败为 `[ERR_GUI_PLACE] ... exit=1`，栈顶 `FileNotFoundError: [WinError 2]`（找不到 macOS 的 `open`），三次重试后退出 1。**结论：这条线的版本口径只有 RP9 一档，`version.mjs` 的 11 是引擎给旧 spec/自有工具链留的，不是本产品的能力。**
- **口径钉在我们自己的配置面，不改引擎**：`heron.prototype.target-axure-version`（`heron-production.yml`，值 `Axure RP 9`，与前端输入框示例串逐字相同）是原生闸的比对基准，由 `ProtoProjectServiceImpl:63` 读取；留空 = 永远判不过（刻意 fail-closed，`ProtoDeliveryTest:64` 锁住）。**不动 vendored 引擎的 `DEFAULT_VERSION`**：原生后端不读它，`gui` 后端也写死 RP9，改了既无效果又会在换 tag 时静默丢失。
- **模板路径必须走环境变量**：引擎默认模板路径是 macOS 形状（`<homedir>/Library/Application Support/zee-axure/templates/...`，`version.mjs:27`），Windows/Linux 容器里不可用。部署侧显式给 `AXURE_BLANK_TEMPLATE_RP9` 指向绝对路径。当前值：`D:\zhoupei\heron\axure\blank-rp9.rp`。
- **两个入口只走一个**：`heron-production.yml` 的 `prototype-template`（现为 `null`）与引擎的 `AXURE_BLANK_TEMPLATE_RP9` 是同一件事。实现只保留 yml 那一条路径——由 Java 把它注入子进程环境变量，**不做「yml 或 env 二者取其一」的兼容分支**。
- **实现落地（2026-11-02 TDD）**：流水线 bean 由 `heron.prototype.engine-enabled`（默认 `false`）控制注册；打开开关却没有 `ProtoRp1Author` bean（属 G2）时启动即失败，不留到运行期才报 503。逐页预览只取 `audit-report.json` 里 `scenes[kind=page-default].pngPath`，**不猜文件名**；联络表固定 `pages-sheet.png`。
- **原生格式自检由 Java 做，不信引擎自报**：读 `.rp` 头 4 字节 `ac ef` + 小端版本号，必须等于 spec 首行声明的 `target`，据此得出 `nativeFormatChecked`。所有产物先整体校验存在性再逐项原子搬移——半份定稿会被清单契约永久拒绝，并在产物卷留下无法回收的孤儿文件。
- **两处 SP1 契约与引擎事实冲突，已纠正**：`ProtoProductionDomain.mediaMatches` 原把 `rp1` 判成 `application/json`，而 `.rp1` 是文本 DSL → 改为 `text/plain`（同步改测试夹具）；`ProductionArtifactService.SAFE_KIND` 原不接受下划线，而契约要求的 `native_rp / page_preview / preview_summary / engine_check` 全带下划线 → 放开。此前没有流水线调用点，两处矛盾都藏着没暴露。
- **`Default.rplib` 的取径没有覆盖开关**：`installRoot()` 硬编码拼成 `<AXURE_APP_RP9>/Contents/Resources/DefaultSettings`（`version.mjs:50`），而 `native-repeater.mjs:13` 与 `proto.mjs:409` 直接用它拼 `Libraries/Default.rplib`，**不接受路径参数也没有环境变量**。
  对策是**让挂载树去适配引擎的硬编码**，不改引擎：在主机上按 macOS 形状放三个文件，再把 `AXURE_APP_RP9` 指向那棵树的根。
  ```
  <AXURE_APP_RP9>/Contents/Resources/DefaultSettings/Libraries/Default.rplib
  <AXURE_APP_RP9>/Contents/Resources/DefaultSettings/Training/RP9 Tour.rp
  ```
  这样 `panelTemplate()`（`AXURE_PANEL_TEMPLATE` 可覆盖）也用不上第二个开关，但两个环境变量仍建议显式设，作为对引擎后续变更的缓冲。Windows 本机开发用 `mklink /J` 建这棵树；Linux 容器用只读卷挂到同一路径。
  **已实测（2026-11-02）**：在 `D:\zhoupei\heron\axure\rp9-app` 下 `mkdir Contents/Resources` + 把 `DefaultSettings` 做成指向 `D:\Program Files (x86)\Axure\Axure RP 9\DefaultSettings` 的 junction，只设 `AXURE_APP_RP9` 与 `AXURE_BLANK_TEMPLATE_RP9` 两个变量（`AXURE_PANEL_TEMPLATE` 故意不设），完整 `generate` 退出 0 —— 证明这棵树同时喂到了 `installRoot(9)/Libraries/Default.rplib`（Repeater 壳样式）和 `installRoot(9)/Training/RP9 Tour.rp`（面板克隆源）。junction 不复制授权字节，Linux 侧等价物是 bind mount / 只读卷，形状一致。
- **产物 `.rp` 不做字节级复现**（实测）：同一份 spec 连跑两次，`out.rp` 的 sha256 不同（`4fe25b9d…` vs `2d23da02…`），因为 Repeater 克隆用 `randomBytes(16)` 生成 GUID（`native-repeater.mjs:2,35`）。而 `pages-sheet.png`、`states-sheet.png`、`01-preview.png`、`02-structure.png`、每页 `@2x.png` 两次运行 **逐字节相同**，`audit-report.json` 反而不同（内含路径与时间戳）。→ **人工确认的指纹锚点必须落在联络表 PNG 的 sha256**，不能落 `.rp` 的 sha，也不能落 audit JSON 全文。§3 表里那条「联络表指纹」按此定义。
- 引擎钉 tag（延续既有纪律：`v0.2.1` 起步 + 记录 commit），升级 = 换钉住的 tag + 跑固定回归样本；只通过 CLI 子进程调用，**不 import 其内部脚本**。
- 任一步非零退出即失败，stderr 原文进 `error_reason`。

### 4.2 三个模板件（缺一件就少一类能力）

| 文件 | 用途 | 本机实测路径 | 状态 |
|---|---|---|---|
| 单页空白 `.rp` | 装配基底（`AXURE_BLANK_TEMPLATE_RP9`） | `D:\zhoupei\heron\axure\blank-rp9.rp`（40020 字节，`ac ef 09 00`，两仓库之外） | **已实测通过**：`create-blank-project.mjs --version 9` 退出 0，`pages=1 widgets=0 masters=0 panelStates=0 problems=0`；移到新路径后重跑仍退出 0 |
| `RP9 Tour.rp` | 动态面板克隆源（`create-panel-clone.mjs:40`；RP9 取 `Discount Panel`） | `D:\Program Files (x86)\Axure\Axure RP 9\DefaultSettings\Training\RP9 Tour.rp` | **已实测通过**：经 §4.1 挂载树被引擎读到，装出的 `.rp` 含 1 个 `Axure:DiagramObject:DynamicPanel` + 2 条 `Axure:PanelState` |
| `Default.rplib` | 输入框 / Repeater 的条件表达式语法样本（`expr-schema.mjs:3`：样本在 `.rplib` 不在 `.rp`） | `D:\Program Files (x86)\Axure\Axure RP 9\DefaultSettings\Libraries\Default.rplib` | **已实测通过**：Repeater 克隆走通，装出的 `.rp` 含 `Axure:DiagramObject:Repeater` + `Axure:RepeaterStyle` + `Axure:RepeatableDiagram` |

- 后两件已在本机被引擎读到（2026-11-02 完整装配实测）→ **动态面板、输入框、Repeater 保留在 v1 范围内**，不缩减。
- 同目录还有 `Flow.rplib`、`Icons.rplib`、`Sample UI Patterns.rplib`、`GettingStarted.rp`，本文不使用，也不复制进挂载树。
- **文件头已实测**（2026-11-02）：`RP9 Tour.rp` 与 `Default.rplib` 均为 `ac ef 09 00`（RP9，通过 `detectFileVersion`），体积 3.1 MB / 476 KB；同目录 `GettingStarted.rp` 是 `50 4b 03 04`（zip 容器），**引擎读它会抛「不是可识别的 Axure 容器」——不能拿它当素材**。这证明 `version.mjs` 的魔数校验是承重的，别指望换个 `.rp` 就能用。
- 三个文件都是商业软件产物：不进仓库、不进镜像，部署者自备 + 按 §4.1 的挂载树形状放置（沿用既有纪律）。

### 4.3 生成与闸

- RP/1 由模型**自由写**（D6），提示词内嵌 zee-rp 的 `SKILL.md` 创作契约；首轮契约失败率预期偏高，**由人点重跑，不自动重试**（沿用既有失败语义）。
- 四道闸不变（契约 / 视觉 / 原生 / 人工）。**原生闸的验证条件已具备**：本机有 Axure 9，人能真打开。
- 系统仍不得伪造「已在 Axure 打开」；未勾选永久显示「未在 Axure 验证」。
- **2026-11-02 完整装配实测**（`examples/authoring/list-detail.rp1`：2 页、动态面板 2 态、Repeater 带 dataset/key/pageSize、9 条事件、4 个全局变量）：`generate` 退出 0、stderr 空，`backend=native`、`version=9`、`ok=true`、`structural_ok=true`、`aesthetic_ok=true`、`warnings=[]`；`counts` = `pages 2 / widgets 49 / dynamicPanels 1 / panelStates 2 / repeaters 1 / interactions 5 / actions 10 / globalVariables 4 / containedWidgets 18 / layers 16 / masters 0 / problems 0`；三零审计 `danglingRefs 0 / sentinelSlotsNonZero 0 / problems 0`；事件覆盖 `declared 9 → materialized 9`；`.rp` 71227 字节、文件头 `ac ef 09 00`；产物装配阶段 `elapsedMs=485`，整条命令含离线编译约 1.2 秒。
- **仍未闭合的只剩人的那一步**：这份 `.rp` 还没在 Axure 9 里打开过（本机装着 Axure，但打开是人的动作，系统不得代填）。P1 的验收以此为准。

### 4.4 与新闻线的共享边界

两线共用 `heron_production_lane / heron_production_task / heron_task_step / heron_usage_record`（`ProductionBizType = news_video | prototype`），**不新增表、不拆独立状态机**。此前「完全独立一套」的取向已在实现中收敛为「一套生产核心 + 两条 lane」，本文按代码事实记录，不再改回。

---

## 5. 模型 client（两线共用）

- 一个类：`POST {baseUrl}/chat/completions`，OpenAI 兼容。超时/HTTP 错误/空输出 = `model` 类失败，原因入库，**不自动重试**。
- 配置三项：`base-url`、`model`、`api-key`；读 `sys_config`，环境变量兜底。页面只回显「已配置」，不回显明文（沿用既有约束）。
- **只有单一 model 配置项**，不区分写稿模型与原型模型。`shortcut: 单模型档位，触发升级条件是原型线 RP/1 一次通过率实测不可接受`。
- 用量写 `heron_usage_record`：取响应 `usage` 字段；某供应商未上报时该栏显示「未上报」，**不填 0**。
- **实现落地（2026-11-02 TDD）**：三项配置的读取顺序是 `sys_config` → `Environment`（yml 与 `HERON_MODEL_*` 环境变量都走后者），空白值等同未配置；`model_not_configured` 在发出请求之前判定，因此不留用量行。
- 记账点在 client 内部：请求前 `start`，成功 `complete`（带 `id` 与 `prompt_tokens` / `completion_tokens`，缺字段保持 null），任何失败 `fail(model_failed)`；`stage` 由调用方给（原型 `rp1`，新闻写稿 `script`）。超时 300 秒是常量，**没有第四项配置**，守住「只有 base-url / model / api-key」。
- RP/1 创作契约在 `src/main/resources/prompt/prototype/rp1-authoring-contract.md`：zee-rp `SKILL.md` 第二、三节的摘录（MIT，文件头标了出处），外加 Heron 侧硬校验口径（首行 `rp/1 target=9`、页面 ID `[A-Za-z0-9_-]{1,80}`、全文 256 KB、语义化命名）。契约不从引擎目录读取，引擎口径变更时人工同步这份摘录。
- 模型需求本身仍只是「一段 prompt → 一段结构化文本」，不接 SnailAI（D9 结论不变）；`api-key` 不落业务表明文，版本快照里的 `modelConfigId` 是逻辑名不是密钥。基座「参数管理」页对管理员仍显示明文，这是选择 `sys_config` 存密钥的既定代价，不在本轮加固范围。

---

## 6. 部署与镜像新增依赖

| 新增 | 用途 | 说明 |
|---|---|---|
| Node.js **22+** | hyperframes CLI、zee-rp 装配 | hyperframes 明确要求 Node 22+ |
| hyperframes（钉版本） | composition 渲染 + `check` | 通过 `npx hyperframes@<pin>`，渲染版本进产物记录 |
| Chromium（由 hyperframes 驱动） | `check` 与 render 必需 | 本地渲染与云端渲染都躲不开，`check` 判源目录 |
| FFmpeg / FFprobe | 已有；AIGC 元数据后写 + decode 校验 | 已在白名单 |
| **edge-tts 运行时**（node-edge-tts） | 配音 + 词级字幕 | 无 key、无账单，但**需出网到微软**；`process_guard.py` 白名单需放行 `node`（已有） |
| 开源中文字体 | composition 中文渲染 | **思源黑体 / 阿里普惠体**；禁用微软雅黑（仓库要开源） |
| Python3 + zee-rp（钉 tag） | 原型装配 | `heron.production.zee-rp` 配的是**引擎根目录**（探测 `<root>/scripts/proto.mjs`），Java 用 `node <root>/scripts/proto.mjs` 调用，不走 `zee-rp` 可执行文件 |
| Axure 素材只读卷 | 装配基底 + 动态面板/Repeater 素材 | 按 §4.1 的 `Contents/Resources/DefaultSettings/{Libraries,Training}` 形状挂载；不进镜像。需 `AXURE_APP_RP9` 与 `AXURE_BLANK_TEMPLATE_RP9` 两个环境变量 |

`process_guard.py` 是 Linux only：**Windows 开发机不能直跑渲染**，本地开发用 `render --docker` 或 Linux 环境验证。此约束写进开发者须知，不要出现「本机能跑」的错误结论。

### 6.1 产物与素材不得进 Git（2026-11-02 已闭合）

发现时的事实：`.gitignore` 对 `data/` 与 `*.rp` **一条规则都没有**，`git check-ignore` 双双未命中；照那状态任何一次 `git add .` 会把 `heron/data/`（`data-root: ./data`）里的成片、联络表、字幕、原型 `.rp`、预览图、审计原文，连同 Axure 商业素材一起永久写进历史——与 §4.2「不进仓库、不进镜像」直接冲突。

已做的三件事：

1. `.gitignore` 增 `/data/`、`*.rp`、`*.rplib` 三段。用**根锚定的 `/data/`** 而非 `data/`，避免误伤 `src/**/data/` 这类源码目录（已实测 `src/main/java/data/Foo.java` 不被忽略）。
2. 空白工程移到两仓库之外的 `D:\zhoupei\heron\axure\blank-rp9.rp`。移出仓库比加忽略规则更稳——`.gitignore` 挡不住 `git add -f`，也挡不住下一个换目录的人。
3. 该文件此前曾被暂存过一次（索引状态 `A`），但**未进入任何提交**（`HEAD` 无此路径、全分支 `git log` 无记录），已用 `git restore --staged` 撤下；残留只是一个未引用的 blob 对象，`git gc` 自然回收。若日后发现素材真进了提交，清历史是单独授权的操作，不在本轮范围。

---

## 7. 里程碑与出口判据

| 里程碑 | 内容 | 出口判据（人可见的真效果，不是命令返回 0） | 依赖 |
|---|---|---|---|
| N1 渲染真出片 | composition 模板 + 变量映射 + 本地 render + decode + AIGC 元数据 | 一份稿件 JSON 渲成一条 9:16 mp4，抖音手机端看过、口播听得清、画面有 AIGC 声明与来源；重渲产生新版本与新指纹 | 无（模板为人工交付物） |
| N2 稿件真生成 | OpenAI-compatible client + 正文抓取 + 稿件 JSON + 契约闸 | 给一条真实热榜候选 → 抓到白名单正文 → 稿件 JSON 过契约闸；`source_url`/正文哈希入库；抓取失败任务失败且**无兜底** | N1 |
| N3 闭环与开关 | `worker-enabled` / `collection-enabled` / `allowed-hosts` 放开 + 热榜采集 + 认领 | 泛资讯源自动出候选 → 人认领 → 自动走到审片 → 确认 → 人工发布并回填回执 → 状态 `人工已发布`，全链路无占位 | N1 N2 |
| P1 原型装配 | zee-rp 调用 + RP9 版本钉 + 模板卷挂载 | 录需求 → 模型写 RP/1 → `check` 过 → 装配出 `.rp` → **人在本机 Axure 9 打开、编辑、保存、重开**才算过 | 引擎侧已实测通过（§4.3），只差人打开那一步 |
| P2 交付面 | 下载鉴权 + 用量 + 版本快照 | 越权下载 404；两版互不覆盖；用量页有记录 | P1 |
| M2 真实发布（独立） | 抖音开放平台 `video.create` | 测试号跑通、断网重试不发两遍、回执丢失以外部状态为准 | §8-U2 |

> N1–N3 是新闻线的必需路径；P1/P2 是原型线；M2 不与 N/P 互相阻塞。

---

## 8. 未决与待实测（不得写成已定）

| # | 事项 | 谁负责 | 判据 | 未定期间 |
|---|---|---|---|---|
| U1 | **已闭合**（2026-11-02）：§4.1 挂载树用 junction 建成并跑通完整 `generate`，含动态面板与 Repeater，退出 0、三零审计全 0、`repeaters=1 panelStates=2`。引擎确实读到了 `RP9 Tour.rp` 与 `Default.rplib`，且 `AXURE_PANEL_TEMPLATE` 无需设置。顺带纠正两处本文早先的错误：`generate` 根本没有 `--version` 参数，版本钉在 `.rp1` 头部；`.rp` 字节不可复现（GUID 随机），联络表 PNG 才可复现 | —（本轮已跑完） | 见 §4.2 / §4.3 实测记录 | 无遗留；「人在 Axure 9 打开」属 P1 验收动作，不是引擎待验证项 |
| U2 | 抖音开放平台主体资质与 `video.create` 准入 | 用户确认资质 | 控制台申请成功 + 拿到凭据 | 发布保持人工回填，不阻塞 N1–N3 |
| U3 | edge-tts 中文口播质量 + 断句/多音字 + 接口可用性 | 实现者真机跑一次 | 三条真实稿件的配音人听合格；接口失效时报错为 `audio` 类失败而非静默降级 | 不合格则回到「引入本地引擎」作为**新需求**讨论 |
| U4 | `hyperframes check` 在无头容器内可跑 | 实现者 | 容器内 `check` 正常出 findings 并门控退出码 | 若不行，`check` 移到宿主执行，容器只做 encode |
| U5 | 本地渲染单次耗时（60 秒目标） | 实现者 | 实测 3 条 30–90 秒成片的 P50/P95 | 超时则调 quality/fps，不放松 5 分钟口径的诚实性 |
| U6 | RP/1 自由生成的一次通过率 | 实现者 | 10 个真实需求样本的 `check` 通过率 | 通过率过低才讨论 D6 的骨架库升级路径 |

---

## 9. 本轮不做（写死，防顺手加回）

- HeyGen 云渲染 / Lambda / Cloud Run 三条后端；多渲染后端选择逻辑。
- Kokoro 本地 TTS、Whisper 转写、edge-tts 自动兜底。
- Playwright / cookie 自动化发布。
- 时政内容、自动品类分类器。
- 多套版式、多风格、在线编辑 composition、模板管理界面。
- SnailAI / Spring AI / Agent / RAG；多模型分档配置。
- 正文抽取库（jsoup / readability）。
- 原型骨架库、在线编辑 RP/1、`.rp` 回写、版本合并、Figma/Sketch 互转。
- 多租户、套餐、配额、计费；微服务拆分；队列中间件。

---

## 10. 端到端验收

**新闻线**
1. 加热榜源 → 下一轮出候选；同标题跨源合并；同候选只一条在途。
2. 人认领 → 抓正文 → 稿件 JSON → `check` → 配音 → 渲染 → 审片看到 mp4 + 联络表 + 出处 + 时长，指纹与版本一致 → 确认。
3. 人工发布 + 回填 → 发布记录来源标 `人工已发布`，`published_at` 有值。
4. 抖音手机端看成片：口播清晰、字幕同步、有 AIGC 声明与来源标注。
5. 故意让 edge-tts 失败一次 → `audio` 闸失败、原因入库、**无本地音色兜底**。
6. 故意让正文抓取失败一次 → 任务失败并指出 URL 与失败类，不退回「只用标题」。

**原型线**
7. 录需求 → 模型写 RP/1 → `check` 过 → 装配 `.rp` → 人在本机 Axure 9 打开/编辑/保存/重开 → 勾原生验证 → 交付下载带 sha256。
8. 故意写错一个组件类型 → 失败原因指向 `check` 诊断原文；重跑出新版本，旧版本仍在。
9. 不勾原生验证也能交付，但详情页与下载记录永久显示「未在 Axure 验证」。
10. 换项目 id 或越权下载 → 404。

任一步靠占位放行即不算完成。

---

**评审要点**：§2 决策表（尤其 D2 本地渲染、D3 edge-tts、D4 人工发布、D6 模型自由写 RP/1）、§4.1 挂载树对策（Repeater 素材路径无覆盖开关，已用 junction 实测通过）、§4.3 完整装配实测记录（原型线引擎侧可行性已定，只缺人在 Axure 9 打开那一步）。其余可按本文排期。
