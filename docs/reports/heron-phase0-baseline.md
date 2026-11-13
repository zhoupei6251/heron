# Heron Phase 0 基线报告

**执行日期：** 2026-11-13
**对应任务书：** `docs/plans/plan.md`
**阶段约束：** Phase 0 不修改任何业务代码。本报告只记录实测事实。

---

## 一、仓库基线

### 1.1 后端 `heron`

| 项 | 值 |
|---|---|
| 分支 | `dev` |
| HEAD | `dd3a06a4b3b8cd1272e89166318a7b6b6b928abe` |
| 与任务书基线 | **完全一致** |
| 工作区 | 不干净，见下 |

未提交内容（本次未触碰，保持原样）：

| 状态 | 文件 | 内容 |
|---|---|---|
| `M` | `ruoyi-admin/src/main/resources/application-prod.yml` | 数据源 URL 由 `localhost_prod:3306/heron` 改为 `localhost:3306/heron_prod` |
| `??` | `docs/plans/plan.md` | 本任务书 |

该改动属于本地数据库连接配置，与本次改造无关。**Phase 1 起所有改动都不得包含这两项。**

### 1.2 前端 `heron-ui`

| 项 | 值 |
|---|---|
| 分支 | `dev` |
| HEAD | `1deb386b0fd7c830a06ab1c1b1114e7b71f757b5` |
| 与任务书基线 | **完全一致** |
| 工作区 | 干净（0 项） |

### 1.3 结论

两个仓库都在任务书声明的审查基线上，且基线之后没有新增提交。**任务书中的代码审查结论可以直接用于当前代码，但仍按任务书要求逐条复核**（见第五节）。

---

## 二、代码规模与结构

| 项 | 数量 |
|---|---|
| 后端主代码 `ruoyi-heron` | 212 个 Java 文件 |
| 后端测试代码 | 82 个文件（71 个测试类 + 11 个 support 基类/夹具） |
| 测试方法注解 | 363 个 `@Test` |
| 视频模板 | 13 套（`data/templates/*`） |
| 前端 Heron 页面 | 13 个 `.vue` |
| 前端 Heron API 模块 | 20 个 `.ts` |

### 2.1 真实生产调用链（数据看板线，当前主线）

```
EditorialDecisionService        总编辑：选生产线
  → EditorialLineEditor         线内编辑：选源 + 选模板
    → DatasetRouter.pick        source_key（冻结进 selection）
    → NewsTemplatePicker.pick   模板（只按标题+摘要）
  → NewsWorkServiceImpl.claimForAuto
  → ProductionServiceImpl.submit
    → FrozenContextService.freeze     冻结输入快照（含 editorial / selection / assets）
  → ProductionExecutionServiceImpl.execute  租约 + attempt
    → NewsProductionPipeline.execute
      1. FrozenContextService.verify  输入必须与冻结 hash 一致
      2. DatasetFetchService.fetch    ★ 每次 attempt 重新抓取
      3. DatasetScriptAuthor.author   写稿（v1 契约）
      4. EditorialReviewer            审稿（一次定向修复 + 复审）
      5. contractGate                 DatasetScriptContract
      6. copyTemplate + templateVariables
      7. sceneTypeGate                模板能力闸（只在稿子带 scene_type 时生效）
      8. TTS → timeComposition → hyperframes → ffmpeg
      9. 十道闸 + 六类产物
```

### 2.2 解说线（`kind=article`）

调用链同上，差异是：

- 正文由 `FrozenArticleService` **按 taskId 冻结**（首写者胜），重试读同一份。
- 图片由 `ArticleImages.extract` 从正文 HTML 抽取，**每次 attempt 重新下载**。
- 契约走 `NewsScriptContract`（支持 v1/v2 混合）。

> **基线偏差：** `CLAUDE.md` 写「`article-enabled: false`，不参与发版」，但 `HeronProductionProperties.articleEnabled` 默认值为 `true`，`application-dev.yml` 与 `application-prod.yml` **均未设置** `heron.production.article-enabled`。即当前两个 profile 下解说线都是**开启**的。这是文档与配置的漂移，Phase 0 只记录、不修改。

---

## 三、测试基线（实测）

### 3.1 后端

**命令（任务书指定，默认排除组）：**

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
```

**结果（2026-11-13 10:08，本次实跑）：**

| 项 | 值 |
|---|---|
| BUILD | **SUCCESS** |
| 耗时 | 55.5 s |
| 执行测试类 | 44 |
| Tests run | **251** |
| Failures / Errors | **0 / 0** |
| Skipped | 5（`ProductionProcessTest` 4 条 + `ProtoDeliveryTest` 1 条，均为原型线） |

**重要：默认命令并不跑全量测试。**

`ruoyi-modules/ruoyi-heron/pom.xml:16` 定义了：

```xml
<heron.test.excludedGroups>exclude,mysql,render</heron.test.excludedGroups>
```

所以 `test` 默认**排除了 MySQL 集成测试与真渲染测试**。据此：

| 分组 | 类数 | 本次是否运行 | 说明 |
|---|---|---|---|
| 默认组 | 44 | ✅ 已运行，251 项全绿 | 纯单元测试 |
| `mysql` | 24 | ❌ **未运行** | 需要 `HERON_TEST_MYSQL_URL/USER/PASSWORD` 指向 `heron_it_*` 隔离库；当前环境变量不存在 |
| `render` | 3 | 见 3.2 | 需要 Node ≥22 + hyperframes + ffmpeg |

> `target/surefire-reports/` 里残留 2026-11-10 / 11-12 的旧 XML（含 1 个 failure、81 个 error 的历史记录）。这些**不是本次结果**，已按文件时间戳剔除；本报告数字只取本次 10:08 写入的 44 份报告。

### 3.2 渲染环境与真渲染测试

环境实测：

| 依赖 | 状态 |
|---|---|
| Node | v22.23.4 ✅ |
| `data/tools/node_modules/hyperframes/bin/hyperframes.mjs` | 存在 ✅ |
| ffmpeg | 存在（`/d/ffmpeg/.../bin/ffmpeg`）✅ |

运行命令（显式放开 render 组）：

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql
```

**结果（2026-11-13 10:1x，本次实跑）：**

| 项 | 值 |
|---|---|
| Tests run | **255** |
| Failures | 0 |
| **Errors** | **2** |
| Skipped | 5 |

| 测试类 | 结果 | 说明 |
|---|---|---|
| `NewsProductionPipelineRenderTest` | ✅ 通过（2 项，153.7 s） | 解说线真渲染可用 |
| `DataBoardPipelineRenderTest` | ❌ **ERROR** | `渲染失败：✗ Variable validation failed (--strict-variables)` |
| `DataBoardRealChainTest` | ❌ **ERROR** | `缺少 HERON_REAL_MODEL_KEY`——环境缺失，非代码缺陷 |

**第一个 error 是真实缺陷，已在 4.10 定位。第二个 error 属于环境未就绪（需要真实模型密钥），标记为未验证。**

涉及 3 个类：

- `NewsProductionPipelineRenderTest`（2 项：六类产物 + 图片场景落地）
- `DataBoardPipelineRenderTest`（1 项）
- `DataBoardRealChainTest`（1 项：数据看板真实全链路）

### 3.3 前端

项目没有 `test` / `typecheck` 脚本，按实际可用工具执行：

| 检查 | 命令 | 结果 |
|---|---|---|
| TypeScript | `npx vue-tsc --noEmit` | ✅ 0 error |
| Lint | `npm run lint`（oxlint src） | ✅ 0 error |
| 生产构建 | `npm run build`（vite build） | ✅ SUCCESS |

前端**没有单元测试框架**（无 vitest / jest 依赖）。Phase 6 若要给场景编辑加测试，需要先决定是否引入测试框架——这是一项需要人工确认的决策，不在 Phase 0 范围。

---

## 四、任务书 2.3 问题清单：逐条核实

图例：✅ 已解决 / ⚠️ 部分解决 / ❌ 未解决

| # | 优先级 | 任务书问题 | 核实结论 | 证据 |
|---|---|---|---|---|
| 1 | P0 | 写稿提示词与 Java 场景契约不一致 | ❌ **未解决，且存在直接矛盾** | 见 4.1 |
| 2 | P0 | 模板能力与场景类型没有形成统一约束 | ⚠️ **闸已存在，但生产中不触发** | 见 4.2 |
| 3 | P0 | 新闻视频时长仍受 75 秒限制 | ❌ **未解决** | 见 4.3 |
| 4 | P1 | 图片素材没有任务级冻结 | ❌ **未解决** | 见 4.4 |
| 5 | P1 | 数据集响应没有完全冻结 | ❌ **未解决** | 见 4.5 |
| 6 | P1 | 视频场景表达能力不足 | ❌ **未解决** | 见 4.6 |
| 7 | P1 | 观众反馈数据不完整 | ❌ **未解决** | 见 4.7 |
| 8 | P1 | 前端缺少场景级编辑能力 | ❌ **未解决** | 见 4.8 |
| 9 | P1 | 决策追溯信息没有完整进入生产上下文 | ⚠️ **部分解决** | 见 4.9 |
| **10** | **P0（任务书未列）** | **12/13 模板在 HEAD 上无法渲染** | ❌ **新增 P0** | 见 4.10 |

### 4.1 写稿提示词与 Java 场景契约不一致（P0，未解决）

**Java 侧（已支持 v2）：**
`service/impl/NewsScriptContract.java`
- `SCENE_TYPES`（32–34 行）：`headline, text, image, chart, quote, list, compare, number, qa, timeline, rank`，共 11 种。
- `SCENE_V2_EXTRA = {scene_type, visual}`、`VISUAL_FIELDS = {layout, motion, assets}`、`ASSET_FIELDS = {asset_id, role, source_url}`。
- `visual()`（111–165 行）：`image`/`chart` 场景缺 `visual` 或 `assets` 直接判违例。
- 已有测试 `NewsScriptContractV2Test`（8 项，全绿）证明 Java 侧行为正确。

**提示词侧（只输出 v1）：**
- `prompt/news-explainer/script-contract.md:15`：`scenes` 每段 `{lead, body, duration_s}`。
- `prompt/news-explainer/script-contract.md:20`：「**只允许上述字段。出现未知字段直接退回。**」
- `prompt/data-board/script-contract.md:15,20`：同上，且 `DatasetScriptContract.SCENE_FIELDS` **只有** `lead/body/duration_s`，连 v2 都不支持。

**直接矛盾：**
`service/impl/ArticleImages.java:108` 的 `promptHint()` 会把这一行塞进写稿 user prompt：

```
原文可用图片（scene_type=image 时从这些 asset_id 里选，不要自造）：
```

即：同一个 prompt 里，一处要求「只能用 lead/body/duration_s」，另一处要求「用 scene_type=image 选图」。模型按前者输出，则 v2 永远不激活；按后者输出，则被契约退回。

**影响：** 生产中的稿件实际上**永远是 v1**。`SCENE_TYPES` 的 11 种类型、`visual.layout`、`visual.motion` 全部是死代码路径。这也是问题 2、6 的根因。

### 4.2 模板能力与场景类型没有形成统一约束（P0，部分解决）

**已具备：**
- 13 个 `template.json` 都声明了 `supportedSceneTypes`。
- `NewsProductionPipeline.sceneTypeGate()`（224–249 行）读 `template.json`，未声明的类型抛 `409 scene_type_unsupported`，错误消息里列出实际支持项。
- `SceneTypeGateTest`（5 项，全绿）覆盖：声明过放行 / 未声明拦截 / v1 稿跳过 / 老模板放行 / 缺文件不阻断。

**未解决：**
1. **闸在生产中不触发**——因为 4.1，稿件从不带 `scene_type`。
2. **能力声明分布极窄**（实测 13 套）：

| 模板 | 声明支持类型 | 推荐时长 |
|---|---|---|
| `news-9x16`（快报） | headline, text, **image** | 40–90 |
| `news-data-9x16`（数据看板） | headline, **chart** | 30–60 |
| `news-quote-9x16`（引语） | headline, text, **quote** | 30–60 |
| `news-rank-9x16`（榜单） | headline, text, **rank** | 30–60 |
| 其余 9 套 | **仅 headline, text** | 30–90 |

   即 11 种场景类型中，`list / compare / number / qa / timeline` 在**任何模板里都没有渲染能力**。

   注意 `news-data-9x16` 声明的 `chart` 同样是空转：数据看板线的 `DatasetScriptContract` 根本不产 `scene_type`，`index.html` 也不读 `scene{N}_type`——图表是靠全局 `chart` 变量（`line/bars/race/stacked/map`）渲染的，与场景类型无关。
3. **模板选择不看场景**：`NewsTemplatePicker.pickFrom()`（44–70 行）只用「标题 + 摘要 + 最近已用」，prompt 见 `prompt()`（113–128 行）。不读 `supportedSceneTypes`、不读预期场景结构、不读可用素材、不读时长。
4. `NewsProductionDomain.TemplateInfo.supportsScenes()`（456 行）**定义了但没有任何调用方**（grep 无结果）。
5. **能力声明本身已经暴露到位**（不是缺口）：`VideoTemplateVo.supportedSceneTypes` 已输出，前端模板中心与工作室都已展示。缺的不是「看得见」，而是「选择时用得上、生产时拦得住」。

### 4.3 新闻视频时长仍受 75 秒限制（P0，未解决）

三处数字互相冲突：

| 位置 | 值 | 是否强制执行 |
|---|---|---|
| `NewsScriptContract.MAX_TOTAL_SECONDS`（39 行） | **75** | ✅ 硬闸 |
| `DatasetScriptContract.MAX_TOTAL_SECONDS`（22 行） | **75** | ✅ 硬闸 |
| `prompt/news-explainer/script-contract.md:19` | 30–75 | 提示词 |
| `prompt/data-board/script-contract.md:19` | 30–75 | 提示词 |
| 模板 `recommendedDuration.max` | 45 / 60 / 90 | ❌ **只用于展示** |

- `NewsProductionDomain` 470–492 行把 `recommendedDuration` 读成 `minDuration/maxDuration`，仅经 `TemplateCatalogService` 输出到 `VideoTemplateVo` 给前端展示。
- 生产闸**从不读取**模板时长区间。
- 结果：`news-magazine-9x16`、`news-minimal-9x16`、`news-rail-9x16` 推荐 60–90 秒，但 90 秒稿件必被 `NewsScriptContract` 拒绝。

**另外：** 时长校验只看 `sum(duration_s)`，与 TTS 实测音频、字幕时间轴无关。`timeComposition` 会按实测音频**缩放**各段时长（`timeCompositionString`，858 行起），所以「声明 75 秒」与「实际成片时长」本来就允许偏离——当前的时长闸并不能保证成片接近目标。

### 4.4 图片素材没有任务级冻结（P1，未解决）

| 要求 | 现状 | 证据 |
|---|---|---|
| 提取候选图 | ✅ | `ArticleImages.extract()` |
| 记录原始 URL | ✅ | `asset.source_url` / `article_url` |
| 校验类型/大小 | ⚠️ 仅 content-type `image/*` + `MAX_IMAGE_BYTES` | `NewsSourceFetcher.fetchImage()` 96,106 行 |
| 下载到任务级路径 | ❌ 落在 **attempt 级**目录 | `ProductionArtifactService.workDirectory()` 335–341 行：`…/{taskId}/a{attemptNo}-{runToken}` |
| 计算 SHA-256 | ❌ 图片不落 artifact，无哈希 | `materializeImages()` 788–818 行只写文件 |
| 保存图片元数据 | ❌ 无 width/height/mime/fileSize | `HeronProductionArtifact` 无对应列 |
| 重试复用已有图片 | ❌ 每次 attempt 重新下载 | 同上 |
| 失败必须明确报错 | ❌ **静默退化** | `materializeImages()` 807–815 行：取不到就 `continue`，日志 `log.warn("退化成纯文字")` |

`materializeImages()` 的注释明确写着「一张配图失败不该让整条片子报废」，这是有意的静默降级。Phase 3 需要把「必需素材失败」与「可选素材降级」分开——当前没有区分机制。

### 4.5 数据集响应没有完全冻结（P1，未解决）

`DatasetFetchService.fetch()`（65–98 行）在**每次 attempt** 都执行 `adapter.fetch(limit)` 出网抓取，然后落 `dataset.json` 到 attempt 工作目录并 `finalizeFile`。

已冻结 vs 未冻结：

| 项 | 状态 |
|---|---|
| 数据源**选择**（`source_key`） | ✅ 冻结进 `selection`，`frozenRoute()` 优先读冻结值（350–357 行） |
| 数据集**响应内容** | ❌ 每次 attempt 重新抓取 |
| 原始响应 / 请求参数 / 响应时间 | ❌ 未记录 |
| 内容 SHA-256 | ⚠️ 快照内有 `data_sha256`，但重抓后哈希会变 |
| 规范化快照复现 | ❌ 无 |
| 首次请求 / 缓存复用 / 人工重抓 的区分 | ❌ 无 |

`contractGate` 会校验 `dataset_sha256` 与脚本一致，所以同一 attempt 内部一致；但**跨 attempt 不一致**——重试可能拿到新数据，写稿输入随之改变。

### 4.6 视频场景表达能力不足（P1，未解决）

实测 13 套模板的 `index.html` 对 `scene{N}_type` 的使用：

| 模板 | 是否消费 `scene{N}_type` | 实际版式能力 |
|---|---|---|
| `news-quote-9x16` | ✅ 映射到 `data-scene-type` → CSS 引语版式 | 引语 |
| `news-rank-9x16` | ✅ 映射到 `data-scene-type` → CSS 榜单版式 | 榜单 |
| `news-9x16` | ❌ 只用 `scene{N}_image` 的 `has-image` | 文字 / 文字+图 |
| `news-data-9x16` | ❌ | 文字 + SVG 图表（`chart` 变量，支持 line/bars/race/stacked/map） |
| 其余 9 套 | ❌ | **纯文字卡片** |

**结论：11/13 套模板的 `scene{N}_type` 变量是白填的**——`templateVariables()`（762–780 行）照样写入，但模板不读。这正是任务书说的「视频容易变成文字卡片轮播」。

另外：
- `visual.layout` 与 `visual.motion` 被 `NewsScriptContract` 校验，但**没有任何模板消费**（`template.json` 里也没有这两个字段）。属于「有校验、无消费者」的字段。
- 动画由各模板内联的 GSAP 时间轴写死，`NewsScriptContract` 不校验、模型也无法指定。

### 4.7 观众反馈数据不完整（P1，未解决）

`domain/HeronNewsMetric.java` + `script/sql/heron/007-news-metric.sql`：

| 任务书要求指标 | 现状 |
|---|---|
| 播放量 | ✅ `plays` |
| 完播率 | ✅ `completion_rate` |
| 点赞 / 评论 / 分享 | ✅ `likes` / `comments` / `shares` |
| 2 秒留存 / 5 秒留存 | ❌ 无列 |
| 平均观看时长 | ❌ 无列 |
| 收藏数 | ❌ 无列 |
| 关注转化 | ❌ 无列 |
| 发布时间 / 采集时间 | ⚠️ 只有 `fetched_at`（采集时间），无发布时间 |
| 数据来源区分（平台/人工/派生） | ❌ 无来源列，无派生标记 |

消费方：
- `NewsCandidateScreener`（52、61–66 行）只读 `recentPerformance`（`title, plays, completionRate`）。
- `performanceBreakdown`（template / line / angle 三维）只被 `NewsMetricController` 暴露给前端，**未进入总编辑决策**。
- `prompt/editorial/daily-editor.md` 内**没有任何**表现数据引用。

### 4.8 前端缺少场景级编辑能力（P1，未解决）

- `src/views/heron` 13 个页面中，grep `scene_type|sceneType|visual` **0 命中**。
- `src/api/heron/production/types.ts` 无 `script` / `scene` 字段；`newsWork/types.ts` 无场景结构。
- 人工编辑入口 `NewsWorkServiceImpl.saveDraft()`（308–327 行）只改 `title / draft / templateKey / voiceKey`，**不触碰场景**。
- 保存后走 `revision + 1`，脚本只能在下次 `generate` 时由模型**整篇重写**——不存在「人工改一屏」的路径。
- 前端无单屏 / 多屏 / 整片预览对比能力；现有预览是成片 MP4 与九宫格联系表。

**版本语义缺口：** `EditorialReviewer` 产出的审稿记录（`record.put(...)` 51–61 行）包含 `schemaVersion / result / issues / minorSuggestions / repairAttempts / promptVersion / reviewedAt`，**没有脚本哈希或 revision**。因此无法回答「这条审稿通过结论对应哪一版脚本」——Phase 6 要求的「修改脚本后不能继续用旧审稿结果」目前只有部分实现（靠 work revision + 重新 generate），缺少可校验的绑定。

### 4.9 决策追溯信息没有完整进入生产上下文（P1，部分解决）

`NewsProductionDomain` 105–125 行构造的冻结快照包含：

| 追溯项 | 状态 |
|---|---|
| 选题来源 | ⚠️ `candidateId` + `evidence`，无 `PoolDigest` 身份 |
| 候选池身份 | ⚠️ 同上，`PoolDigest` 未进快照 |
| 原始总编辑决策 | ✅ `editorial` 节点（`editorialNode`） |
| 最终线内选择 | ✅ `selection` 节点（`selectionNode`） |
| fallback 信息 | ⚠️ `frozenRoute()` 有回退逻辑但只 `log.warn`，不落快照 |
| 文章正文版本 | ✅ `FrozenArticleService` 按 taskId 冻结，有 `content_sha256` |
| 图片素材引用 | ❌ 无 |
| 数据集快照引用 | ❌ 无（`source_key` 有，响应内容与哈希无） |
| 脚本版本 | ⚠️ 脚本作为 artifact 落盘，但上下文里无引用 |
| 模板版本 | ✅ `assets`（`templateKey` / `templateVersion` / `fontSha256` / `voiceVersion` …） |

`FrozenContext` 的不可变设计是可靠的（`FrozenContextService` 刻意不提供 update/delete，只写一次 + hash 校验）。

### 4.10 【新增 P0】12/13 模板在 HEAD 上无法渲染

这是本次 Phase 0 最重要的发现，任务书未列出，且会阻断 Phase 1 的运行时验收。

**现象：** `DataBoardPipelineRenderTest` 在 HEAD 上失败：

```
HeronBusinessException: 渲染失败：✗ Variable validation failed
Aborting render due to variable issues (--strict-variables mode).
  at NewsProductionPipeline.render(NewsProductionPipeline.java:614)
```

**机制（已读 HyperFrames 源码确认）：**

`hyperframes/dist/chunk-6JMNJYYR.js:4943` 的 `validateVariables()` 对**每一个传入变量**检查：

```js
const decl = decls.get(id);
if (!decl) { issues.push({ kind: "undeclared", variableId: id }); continue; }
```

即：**传入了模板 schema 里没有的变量 = issue**。在 `--strict-variables` 下直接 abort。

而 `NewsProductionPipeline.templateVariables()`（762–780 行）**无条件**为每段写入六个图片变量：

```java
vars.put("scene" + (index + 1) + "_image", images.getOrDefault(..., ""));
```

`NewsProductionPipeline.render()`（609 行）**始终**传 `--strict-variables`。

**实测影响面（13 套模板逐个核对 `"id": "sceneN_image"` 是否存在）：**

| 模板 | 声明 `_image` | 能否渲染 |
|---|---|---|
| `news-9x16` | ✅ | ✅ |
| 其余 **12 套**（含 `news-data-9x16`、`news-quote-9x16`、`news-rank-9x16`） | ❌ | ❌ **abort** |

**成因是两次提交叠加：**

- `c497b9745`（2026-11-12）"场景契约升到 v2，正文配图与背景音乐进成片"——给 `templateVariables()` 加了无条件 `sceneN_image`。
- `e370cf90a`（2026-11-12）"场景类型成为渲染信号，声明收缩到真实能力"——把不支持图片的模板的 `sceneN_image` 声明删掉了。

两次改动都合理，叠加后产生了「后端总是多给一个模板不认识的变量」。

**后果：**

- 数据看板线（当前产品形态）**唯一**可用模板是 `news-data-9x16`，它也在这 12 套里 → **数据看板线在 HEAD 上无法出片**。
- 只有解说线的 `news-9x16` 能渲染，这也解释了为什么 `NewsProductionPipelineRenderTest` 通过而数据看板测试失败。
- `target/surefire-reports/` 里 2026-11-10 的「全绿」记录早于这两次改动，不能作为当前状态。

**归属：** 属于任务书 Phase 1 验收清单中的「模板渲染变量缺失」与「模板不支持场景」。建议在 Phase 1 一并修复，方案见 6.3 第 11 项。

> 本报告只记录与定位，不修改代码。

---

## 五、Phase 0 结论

1. 两个仓库都在任务书基线上，无需重新审查。
2. 现有 251 项默认单元测试全绿，前端类型检查 / lint / 构建全绿。
3. **9 项问题中 6 项未解决、3 项部分解决**，与任务书判断基本一致；其中 4.1（提示词与契约矛盾）是根因性问题，直接导致 4.2、4.6 的「闸存在但不生效」。
4. **新增一项任务书未列的 P0：12/13 模板在 HEAD 上因 `--strict-variables` 变量不匹配而无法渲染，数据看板线当前无法出片（见 4.10）。**
5. 需要特别记录的两项**任务书未提及**的发现：
   - `article-enabled` 文档与配置漂移（代码默认 `true`，`CLAUDE.md` 写 `false`）。
   - 默认 `mvn test` 排除 `mysql` 与 `render` 两组，Phase 1 验收若只跑默认命令会漏掉真渲染。

### 5.1 未运行 / 未验证项（明确标记）

| 项 | 原因 |
|---|---|
| `mysql` 组 24 个测试类 | 缺 `HERON_TEST_MYSQL_URL/USER/PASSWORD`，且需 `heron_it_*` 隔离库 |
| `DataBoardRealChainTest`（真数据 + 真模型全链路） | 缺 `HERON_REAL_MODEL_KEY`；测试显式拒绝静默改用固定稿 |
| 前端单元测试 | 项目未引入测试框架 |
| 浏览器人工验收 | Phase 0 不涉及 UI 改动 |

---

## 六、Phase 1 实施计划（待人工确认后执行）

**范围：只统一场景契约。不做时长（Phase 2）、不做冻结（Phase 3）、不做视觉升级（Phase 4）、不做前端（Phase 6）。**

### 6.1 设计原则

1. **不新建第二套 Schema。** 在 `NewsScriptContract` 的 v2 上补齐，让它成为唯一契约；`DatasetScriptContract` 复用同一套场景校验。
2. **只加有消费者的字段。** `layout` / `motion` 当前无模板消费——Phase 1 **不扩大**这两个字段的语义，只决定「保留校验」还是「降级为可选提示」。建议保留校验但标注为 Phase 4 才启用，避免又一次「有校验无消费者」。
3. **向后兼容。** v1 稿件继续合法；历史 JSON 不改写；老的 `dataset` 脚本继续可读。
4. **不做静默降级。** 模板不支持场景时，Phase 1 选择「明确拒绝 + 记录原因」，不自动改型。

### 6.2 建议的统一 Schema（v2 final）

```json
{
  "schema_version": "2",
  "title": "…",
  "scenes": [
    {
      "scene_id": "scene_001",
      "scene_type": "image",
      "lead": "…",
      "body": "…",
      "duration_s": 8,
      "visual": { "assets": [{ "asset_id": "img_0", "role": "hero" }] }
    }
  ],
  "closing": "…",
  "source_name": "…"
}
```

**明确不在 Phase 1 引入：** `narration.text` / `subtitle.text` 独立字段。现有 `lead + body` 已经是口播 + 字幕的唯一来源，拆成两个字段会同时改动 TTS、模板变量和字幕闸三处，且当前没有「口播≠字幕」的业务需求。等 Phase 4 确有需要再加。

### 6.3 修改文件清单

| # | 文件 | 改动 | 理由 |
|---|---|---|---|
| 1 | `service/impl/NewsScriptContract.java` | 新增 `scene_id` 必填校验（v2 段落）；`scene_id` 唯一性；`SCENE_TYPES` 与模板能力解耦（契约只判「已知类型」，不判「模板是否支持」） | 统一契约，补稳定标识 |
| 2 | `service/impl/DatasetScriptContract.java` | 抽出/复用 v2 场景校验：`scenes` 段允许 `scene_id` / `scene_type` / `visual`，行为与 `NewsScriptContract` 一致 | 消除两份契约漂移 |
| 3 | 新增 `service/impl/SceneContract.java`（若 2 的复用超过约 40 行才抽） | 场景段校验的唯一实现，两个契约委托调用 | 避免复制粘贴两套逻辑 |
| 4 | `prompt/news-explainer/script-contract.md` | 重写「场景」一节：11 种类型 → **只保留模板真正能渲染的 5 种**（headline/text/image/quote/rank）+ 每种的使用条件 + `scene_id` 规则 | 消除与 Java 的矛盾 |
| 5 | `prompt/data-board/script-contract.md` | **不引入 `scene_type`**。数据看板的图表是全局 `chart` 变量，与场景无关；此阶段只补 `scenes` 段的形状说明与 `scene_id` 规则（若与解说线共用） | 不制造又一个无消费者的字段 |
| 6 | `service/impl/ArticleImages.java` | 修正 `promptHint()` 措辞，与契约对齐（`asset_id` 列表格式不变） | 消除同一 prompt 内的自相矛盾 |
| 7 | `service/impl/NewsProductionPipeline.java` | `sceneTypeGate` 保持；补一条闸：稿件声明了类型但模板未声明能力时**拒绝并落 gate 记录**（当前是静默放行 `declared=0`） | 拒绝静默降级 |
| 8 | `service/impl/NewsProductionDomain.java` | 删除无调用方的 `TemplateInfo.supportsScenes()`，或让它被 `NewsTemplatePicker` 真正使用 | 清理死代码 / 接线 |
| 9 | `service/impl/NewsTemplatePicker.java` | 候选收窄时叠加 `supportedSceneTypes` 过滤（模板声明为空则不过滤，保持老模板兼容） | 模板选择不再只看标题摘要 |
| 10 | `service/impl/NewsProductionPipeline.java` | **修 4.10**：`templateVariables()` 只输出目标模板真正声明的变量（从模板 `index.html` 的变量 schema 读取，与 `sceneTypeGate` 读 `template.json` 同一模式）。当前无条件写 `sceneN_image`，导致 12 套模板 abort | 让「后端给的变量」与「模板要的变量」由同一份声明驱动 |
| 11 | 测试：`NewsScriptContractV2Test` / `SceneTypeGateTest` / `DatasetScriptContractTest` / 新增变量映射测试 | 扩充：缺 `scene_id`、重复 `scene_id`、模板未声明能力时拒绝、v1 兼容、旧 JSON 原样可读、**非图片模板不注入 `_image` 变量**、13 套模板逐个可渲染 | 验收 |

**不修改：** `NewsProductionPipeline` 的渲染主流程、TTS、ffmpeg、`timeComposition`、artifact 服务、租约/attempt 逻辑。

**已具备、无需改动：** 模板场景能力已经端到端暴露——`VideoTemplateVo` 含 `supportedSceneTypes`，`TemplateCatalogService` 已填值，前端 `asset/template/index.vue` 与 `news/work/studio.vue` 都已展示。Phase 1 不需要新增该链路。

### 6.4 影响面

**API：** **无破坏性变更，也无新增字段。** 模板场景能力已在 `VideoTemplateVo` 中暴露，Phase 1 不动 API 契约。

**数据库：** **无迁移**。脚本以 JSON 存在 artifact / version 里，新字段是 JSON 内部结构，不改列。

**历史数据：** 旧稿件（v1、无 `scene_id`）继续通过校验与渲染。读取路径不变。

**提示词版本：** `script-contract.md` 与 `data-board/script-contract.md` 改动需在报告中记录版本号（若项目有 promptVersion 机制，否则在 commit message 里写明）。

### 6.5 Phase 1 验收测试

| # | 用例 | 期望 |
|---|---|---|
| 1 | v1 稿件（无 scene_type） | 通过，渲染不受影响 |
| 2 | v2 稿件，`scene_type` 已知且模板声明支持 | 通过 |
| 3 | v2 稿件，`scene_type` 未知 | 415/409 拒绝，错误含类型名 |
| 4 | v2 稿件，缺 `scene_id` | 拒绝 |
| 5 | v2 稿件，`scene_id` 重复 | 拒绝 |
| 6 | v2 稿件，模板不支持该类型 | **拒绝**（不再是静默放行） |
| 7 | 数据集线 v2 稿件 | 与解说线同一套场景校验 |
| 8 | 模型输出旧版 Schema | 兼容读取，不报「未知字段」 |
| 9 | 模型输出残缺 JSON | 退回写稿，不崩 |
| 10 | 模板能力声明为空（老模板） | 不过滤，行为不变 |
| 11 | 用 `news-data-9x16` 真渲染 | ✅ 通过（当前 ❌，见 4.10） |
| 12 | 用非图片模板（如 `news-poster-9x16`）真渲染 | ✅ 通过，且不注入 `_image` 变量 |

**运行时验收（人工确认）：**

- `render` 组必须全绿——当前有 2 个 error，Phase 1 结束时 `DataBoardPipelineRenderTest` 必须转为通过（`DataBoardRealChainTest` 仍需真实模型密钥，可继续标记未验证）。
- 至少 1 条数据看板视频走完整链路并人工看片。
- 若解说线实际启用，再补 1 条解说线视频。

**命令：**

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql
```

（`mysql` 组是否纳入 Phase 1 验收，取决于能否提供 `heron_it_*` 隔离库——需要人工决定。）

### 6.6 需要人工决定的三件事

1. **场景类型收敛范围**：契约是否直接收缩到「模板真正能渲染的 5 种」，还是保留 11 种声明但由模板闸拒绝？前者更诚实、模型更少试错；后者为 Phase 4 预留空间。**建议前者**——Phase 4 加类型时同步加渲染能力。
2. **`layout` / `motion` 的去留**：当前有校验、无消费者。建议 Phase 1 **保留字段但不写入提示词**，等 Phase 4 有模板消费后再启用；或直接删除。**建议保留不启用**，避免破坏已存在的 v2 稿件。
3. **4.10 的修复怎么排**：它是 P0 且阻断数据看板出片，但严格说属于「变量映射」而非「场景契约」。两个选项：
   - **并入 Phase 1**（推荐）：Phase 1 本来就要统一「模型 → Java → 模板 → 渲染」四层的一致性，变量映射是同一件事的最后一公里。
   - **先单独修一个 hotfix 提交**，再进 Phase 1：适合希望尽快恢复数据看板出片、且不想让 Phase 1 提交变大。

---

## 七、风险

| 风险 | 说明 |
|---|---|
| **数据看板线当前不可出片** | 4.10 的 P0，任何在此之前的「生产可用」结论都不成立 |
| 提示词改动影响产出质量 | 收缩场景类型后，模型可用表达变少；需要看片确认不是「更单调」 |
| `article-enabled` 实际为开启 | 若解说线本不该跑，Phase 1 的解说线验收可能是在验证一条已下线的线——需人工确认预期状态 |
| 默认测试命令会漏渲染 | 后续每个阶段的验收都必须显式跑 render 组 |
| `mysql` 组长期未跑 | 24 个测试类自 2026-11-12 起未在本机执行，可能已有漂移 |
