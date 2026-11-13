# Heron Phase 0 基线报告（新版任务书）

**执行日期：** 2026-11-13
**对应任务书：** `docs/plans/plan.md`（新版：数据驱动的自动化短视频生产平台）
**阶段约束：** 本报告不修改任何业务代码、数据库结构、生产配置或任务执行逻辑。

---

## 一、仓库与工作区

### 1.1 后端 `heron`

| 项 | 值 |
|---|---|
| 分支 | `dev` |
| HEAD | `dd3a06a4b3b8cd1272e89166318a7b6b6b928abe` |
| 与新版任务书审查基线 | **一致** |
| 工作区 | **不干净**，见下 |

工作区内容分三类：

| 类别 | 文件 | 说明 |
|---|---|---|
| 任务书 | `docs/plans/plan.md`（新增） | 本次换上的新版任务书 |
| **上一版任务书的 Phase 1 成果（未提交）** | `NewsProductionPipeline` / `NewsScriptContract` / `NewsModelScriptAuthor` / `NewsTemplatePicker` / `NewsProductionDomain` / `news-explainer/script-contract.md` + 5 个测试文件 | 场景契约统一与渲染阻塞修复，默认组 258 项已验证通过 |
| 与本任务无关的本地改动 | `ruoyi-admin/src/main/resources/application-prod.yml` | 数据源 URL 指向本地库，Phase 0 前即存在 |

> **必须先决定的一件事：** 这三类改动混在工作区里。如果新版任务书要按「干净基线」推进，需要先把上一版 Phase 1 提交或暂存，否则后续每次 diff 都会混进它。

### 1.2 前端 `heron-ui`

| 项 | 值 |
|---|---|
| 分支 | `dev` |
| HEAD | `1deb386b0fd7c830a06ab1c1b1114e7b71f757b5` |
| 与审查基线 | **一致** |
| 工作区 | 干净（0 项） |

---

## 二、核心结论（先回答问题）

你让我重点核查的两件事，结论如下。

### 2.1 现有数据看板的数据来源是什么

**是 119 个外部公开 API，不是「自身采集的抖音数据」。**

- 注册表 `heron_data_source` 里 119 个 `source_key`，分 10 类：finance 37、macro 33、weather 21、platform 14、boxoffice 3、entertainment 3、gaming 3、news 3、ai 2、dbnomics 1。
- 由 23 个 `*Adapter.java` 实现（新浪期货、东方财富指数/宏观/市值、Frankfurter 汇率、MetNo 天气、USGS 地震、World Bank、OWID、HackerNews、GitHub Trending、HuggingFace、Jikan、CheapShark、StackExchange、DevTo、VegaMovies、Gapminder…）。
- **`source_key` 中含 douyin 的：0 个。** 后端所有「抖音」字样只出现在发布适配器（`DouyinCliPublishAdapter`）与指标回填相关文件，没有任何采集。

新版任务书开篇写「以**自身采集的抖音数据**为基础」。这句话在当前代码里**没有对应实现**，是全篇最大的前提偏差。

### 2.2 现有「数据分析链路」如何进入内容生产链路

**当前不存在数据分析链路。** 存在的是一条「取数 + 描述统计」链路，而且它**不产生选题**。

真实链路（自动路径）：

```
NewsSourceRunner / hotlist(微博、百度)
  → NewsCandidateCollector        候选入库，heatScore=重复出现次数，bestRank=热榜名次
  → candidatePool()               ★ 先筛新闻候选；为空则整槽直接返回空池
  → EditorialDecisionService     只看「走哪条线」，看的是候选标题/数据源名称
  → EditorialLineEditor.editDataBoard()
      → DatasetRouter.pick(…, decisionContext())
           ★ decisionContext 是硬编码 {"limit":30,"title":"数据看板"}，没有真实题材
      → 模型回 "none"（因为题材无法与源相关）→ digest() 按栏目周期轮换选源
  → 冻结 selection(source_key, digest)
  → DatasetFetchService.fetch()  取数
  → DatasetFacts.compute()       first/last/min/max/change/change_pct（单源描述统计）
  → DatasetTitleAuthor           取数之后才生成标题
  → DatasetScriptAuthor → 渲染 → 发布
```

三个结构性事实：

1. **选源没有题材输入。** `EditorialLineEditor.decisionContext()`（第 152–154 行）返回的是 `{"limit":30,"title":"数据看板"}`。而选源契约 `prompt/data-board/routing.md` 第 2 条要求「题材必须与源的名称、覆盖范围直接相关；只是『可能有关』就输出 `none`」。用写死的「数据看板」去匹配，规则 2 几乎必然触发 `none`，于是落到 `DatasetRouter.digest()`——**按 20 期栏目周期 + cadence 排名轮换**（TECH 6 / MARKET 4 / BUSINESS 4 / TREND 3 / ATLAS 3）。

   也就是说：**今天做什么数据视频，实际由「轮换到了哪个源」决定，不是由「哪个数据值得做」决定。**

2. **数据看板线被新闻候选卡死。** `NewsAutoService.candidatePool()` 里，新闻候选为空时直接 `return EditorialCandidatePool.empty()`，**在此之前不会去查数据源**（第 245–247 行）。`tick()` 拿到空池后 `finish(slot, "skipped", "no_candidate")` 直接返回（第 155–158 行）。

   后果有两层：
   - 某天没有白名单新闻源的新候选，那天连数据看板视频也一条都不产。
   - 失败原因记成 `no_candidate`，看着像「没有素材」，实际是「没有新闻」——排查时会往错误方向找。

   这与新版任务书「数据看板占 80%，是核心生产线」的定位正好相反。

3. **数据看板只能由自动槽位产出。** 手动认领 `claimAs()` 不写 `selection`，而 `NewsProductionDomain` 第 118 行按 `selection != null && selection.dataBoard()` 决定 `kind`。`selection` 为空 → `kind="article"`。所以人工在工作台上生成的永远是解说线，**没有任何人工入口能直接做一条数据看板**。

### 2.3 「能不能自主发现值得做的视频」

**不能。** 现有系统能自动把「轮到的那个数据源」做成视频，但没有任何一步在回答「这个数据今天有什么值得讲的」。

判断依据：

| 需要的动作 | 现状 |
|---|---|
| 拉取数据并跨期/跨源比较 | ❌ 只算单源的 first/last/min/max/change |
| 发现趋势、异常、拐点、排名变化 | ❌ 无 |
| 生成「内容机会」候选 | ❌ 无 |
| 对机会打分、排序、淘汰 | ❌ 无（唯一的排序依据是热榜名次与重复出现次数） |
| 把机会送进生产队列 | ❌ 生产入口只有 work/candidate，数据不能自己发起 |
| 用真实发布表现反过来选机会 | ⚠️ 只有 `NewsCandidateScreener` 读 plays/完播率，且只作用于新闻候选 |

---

## 三、数据来源实测明细

| 项 | 实测 |
|---|---|
| 注册源 | 119 |
| 适配器实现 | 23 个 `*Adapter.java` |
| 数据形态 `shape` | `wide`（时间序列）/ `long`（多实体序列）/ `rows`（单期排行）/ `geo`（地理） |
| 图表类型 | `chartFor(shape)`：wide→line、long→race、rows→bars、geo→map |
| 自有账号数据 | **无** |
| 抖音公开/开放平台数据 | **无** |
| 数据源健康探测 | ⚠️ `probe_status` 只由迁移 SQL 写入，**运行期没有任何探测任务**更新它 |
| 数据源失败计数 | ⚠️ `heron_data_source.failure_count` 列存在，**无任何代码写入**（新闻源表才有写入） |
| 选源去重 | 7 天内用过的源不再参与（`DatasetRouter.DEDUP_DAYS`） |
| 每槽候选上限 | 20（`DATASET_POOL_LIMIT`），按 `source_key` 字典序取前 20 |

`probe_status` 静态这一点影响很大：`eligible()` 只用 `enabled='0' AND probe_status='ok'` 过滤，而 `ok` 是迁移时人工探一次的结论。源失效后系统不会自动剔除，只会在取数时失败并占掉一个槽位。

---

## 四、「分析」能力的真实边界

### 4.1 已有的确定性计算（可复用）

`DatasetFacts.compute()`：

- `wide`：`first / last / min / max / change / change_pct / period`
- `long`：多实体序列归约
- `rows` / `geo`：排行与地理分布归约

这是**单源、当期、描述性**的统计。它是脚本占位符 `{{facts.xxx}}` 的数据源，也是 data 闸重算的依据——质量上是可靠的，但对「发现」没有帮助。

### 4.2 模型侧看到的输入

| 环节 | 模型看到的 | 看不到的 |
|---|---|---|
| 总编辑 `EditorialDecisionService` | 热榜标题、带正文文章标题、**数据源名称（cadence，shapes）**、最近发布标题 | 任何数据数值 |
| 选源 `DatasetRouter` | 20 个源的 key/名称/栏目/cadence/shapes + 写死的「数据看板」 | 任何数据数值 |
| 取数后 `DatasetTitleAuthor` | 完整 dataset 快照 | —— |

关键矛盾：`prompt/editorial/daily-editor.md` 要求总编辑判断「数据侧今天确实没有能撑起一条片子的题材」，但 `PoolDigest.datasets()` 与 `NewsAutoService.datasetLabel()` 只提供 `名称（daily，line）`。**这个判断在现有输入下不可完成**，模型只能按「默认走 DATA_BOARD」的偏好行事。

### 4.3 没有的东西

- 没有跨源/跨主体对比。
- 没有异常检测、趋势显著性、拐点识别。
- 没有「内容机会」实体，也没有存储它的表。
- 没有选题评分（`EditorialCandidatePool` 里没有分值字段）。
- 没有「淘汰原因」「延迟观察」的结构化记录（`heron_auto_slot.decision_reason` 只记一行文本）。

---

## 五、价值信号实测

现有系统判断「值得做」的全部依据：

| 信号 | 来源 | 含义 |
|---|---|---|
| `heatScore` | `NewsCandidateCollector` | 该标题被**重复采集到的次数** |
| `bestRank` | 热榜 | 在微博/百度热榜里的最好名次 |
| `lastSeenAt` | 采集 | 24 小时内是否还在 |

没有播放、完播、分享等真实表现参与选稿主路径；完播率只在 `NewsCandidateScreener` 里作为参考项拼进提示词，读失败还会静默降级为「无数据」(`NewsCandidateScreener` 第 60–68 行)。

---

## 六、自动化程度与人工依赖

| 能力 | 现状 | 配置 |
|---|---|---|
| 新闻采集 | ✅ 自动（RSS 白名单 + 微博/百度热榜） | `collection-enabled: true` |
| 数据采集 | ✅ 自动（但只在生产时取，不预先分析） | — |
| 选题决策 | ⚠️ 只决定「走哪条线」 | `auto-enabled: false`（dev） |
| 自动生产 | ✅ 槽位驱动 | `auto-slots`：11:00 / 15:00 / 19:00 |
| 自动审稿 | ✅ 模型审稿 + 定向修复一次 | — |
| 自动发布 | ✅ douyin-cli（浏览器自动化，非官方 API） | `publish-adapter: douyin-cli` |
| 自动确认发布 | ✅ 默认开启 | `autoConfirmEnabled = true` |
| 效果回流 | ❌ **手工回填** | 只有 `POST /heron/metric` |
| 自动质量门禁 | ✅ 十道闸 + AIGC 角标 + 联络表 | — |
| 异常队列 | ❌ 无（失败只写槽位 detail） | — |

**运营前提：新闻源白名单不在迁移里。** `autoSources()` 取 `enabled='0' AND auto='0'` 的新闻源；迁移 SQL 里只有 `022-news-hotlist-sources.sql` 插入了微博/百度热榜，RSS 源要靠后台手工添加。全新部署若没人配 RSS 源，自动线会一直 `skipped / no_candidate`。

**当前自动化是「关着的」**：`application-dev.yml` 里 `auto-enabled: false`。任务书要求的「默认全自动」需要先打开，并补上异常队列。

**另一个配置地雷：** `CLAUDE.md` 写「`article-enabled: false`，不参与发版」，但 `HeronProductionProperties.articleEnabled` 默认 `true`，两个 profile 都没设置 `heron.production.article-enabled`。当前实际是**开启**的。如果按文档把它关掉，`NewsProductionPipeline` 第 121 行会直接抛 `article_disabled`，而手动认领只能产出解说线（见 2.2 第 3 点）——**等于关掉人工生产入口**。这个开关的实际含义和文档描述相反，需要先理清。

发布侧需要说明：`DouyinCliPublishAdapter` 调 `sau`（social-auto-upload）走浏览器自动化，**不是抖音开放平台 API**。任务书 §5.3 要求「必须优先使用平台官方或合规授权的发布能力」，这一条与现状不符，需要你确认是否接受「自用账号 + 已登录态」这条路径。

---

## 七、前端现状

13 个页面 / 20 个 API 模块，全部围绕**人工操作单条视频**设计：

| 页面 | 行数 | 用途 |
|---|---|---|
| `news/work/studio.vue` | 1099 | 视频工作室（人工改稿/预览） |
| `proto/index.vue` | 1052 | 原型工坊（另一条业务线） |
| `editorial/index.vue` | 563 | 编辑策划（决策回放） |
| `production/index.vue` | 497 | 生产任务列表 |
| `news/topic/index.vue` | 462 | 选题雷达（候选列表） |
| `review/index.vue` | 399 | 审片中心 |
| `analytics/index.vue` | 244 | 内容表现（按模板/线/角度归因） |
| 其余 | — | 来源、数据集、模板、发布、works 列表 |

与新版任务书 Phase 7 的差距：

- ❌ 没有总览驾驶舱（首页 `src/views/index.vue` 自己注明「没有聚合接口前不编造『今日』口径」）。
- ❌ 没有内容决策工作台（现有 `editorial` 页是“回放某次决策”，不是“看待评估的候选与评分”）。
- ❌ 没有异常中心。
- ⚠️ 有生产监控雏形（任务列表 + 阶段进度），但没有成功率/耗时聚合。
- ✅ 审片、九宫格、版本、发布记录可复用。

后端也没有聚合统计接口：`HeronHealthController /overview` 只报健康，没有任何业务聚合。

---

## 八、测试基线（实测）

### 8.1 默认组

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
```

| 项 | 值 |
|---|---|
| Tests run | **258** |
| Failures / Errors | 0 / 0 |
| Skipped | 5 |
| BUILD | SUCCESS |

（含上一版 Phase 1 新增的 7 项。默认组按 `ruoyi-heron/pom.xml:16` 排除 `exclude,mysql,render` 三组。）

### 8.2 render 组

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql
```

| 项 | 值 |
|---|---|
| Tests run | 262 |
| Failures | 0 |
| Errors | **1** |
| Skipped | 5 |
| BUILD | FAILURE（仅因下面这一个环境用例） |

| 测试类 | 结果 |
|---|---|
| `NewsProductionPipelineRenderTest`（解说线真渲染） | ✅ 2 项，182.6 s |
| `DataBoardPipelineRenderTest`（数据看板真渲染） | ✅ 1 项，116.9 s |
| `DataBoardRealChainTest`（真数据 + 真模型） | ❌ **环境未就绪**：`缺少 HERON_REAL_MODEL_KEY，真链路不静默改用固定稿` |

渲染依赖实测可用：Node v22.23.4、`data/tools/node_modules/hyperframes`、ffmpeg、edge-tts（Python `-m edge_tts`）均就位。**两条线都能真渲染出片**（数据看板 116.9 s、解说线 182.6 s，十道/十一道闸全过）。

### 8.3 mysql 组：**未运行**

26 个测试类继承 `HeronMysqlTestSupport` / `NewsMysqlTestSupport` / `ProtoMysqlTestSupport`，要求：

- `HERON_TEST_MYSQL_URL` 指向 `heron_it_*` 隔离库
- `HERON_TEST_MYSQL_USER` / `HERON_TEST_MYSQL_PASSWORD`

三个环境变量在本机均不存在，**这批测试本次一个都没跑**。任务书提到的两个已知问题，我做了静态核对：

**`HeronMenuSeedTest` 权限标识重复——确认存在。** `script/sql/heron/004-menu.sql` 的 48 行菜单种子里有 7 组重复 `perms`：

| perms | 重复条数 | 举例 |
|---|---|---|
| `heron:newsWork:list` | 4 | 视频作品 / 发布记录 / 内容表现 / 表现查询 |
| `heron:newsCandidate:query` | 2 | 选题查询 / 选题详情 |
| `heron:editorial:list` | 2 | 编辑策划 / 运行查询 |
| `heron:newsWork:query` | 2 | 作品查询 / 作品工作室 |
| `heron:newsWork:review` | 2 | 人工审片 / 审片中心 |
| `heron:dataSource:list` | 2 | 数据集 / 数据集查询 |
| `heron:template:list` | 2 | 视频模板 / 模板查询 |

测试第 59–60 行的 `GROUP BY perms HAVING COUNT(*) > 1` 断言会判失败。**但需要判断是哪一边错**：RuoYi 里「菜单页 + 查询按钮」共用同一个 `perms` 是常见做法，这条断言可能过于严格。实际运行前不应先改种子。

**MySQL 测试顺序依赖 / 锁等待——无法静态确认。** 26 个类共用一个隔离库，多个类内部用 `lockById` / 行锁，是否互相等待必须实跑才知道。标记为**未验证**。

---

## 九、新版任务书与实际代码的差异（必须先修订的部分）

| 任务书假设 | 实际 | 影响 |
|---|---|---|
| 以自身采集的抖音数据为基础 | 0 个抖音数据源，抖音只用于发布 | §1.1、§2.1 的整条主线需要重写 |
| 存在「数据分析链路」 | 只有取数 + 单源描述统计 | Phase 4 不是「升级分析」，是**从零建** |
| 数据看板是 80% 主力 | 数据看板只能自动产出，且被新闻候选卡死 | 优先级与依赖顺序要调整 |
| 总编辑能判断「数据侧有没有强题材」 | 总编辑看不到任何数据值 | 提示词要求超出输入能力 |
| 自动发布用官方/授权能力 | douyin-cli 浏览器自动化 | 需确认合规口径 |
| 模型负责发现洞察 | 模型只在固定 119 源里做选择题 | §2.2 第 3、4 项职责目前无承载 |
| 效果数据自动回流 | 纯手工回填 4 个字段 | Phase 6 需先解决采集 |

### 9.1 一个必须先定的口径问题

任务书说「数据看板内容线核心来源是 **Heron 自身采集的数据**」，并列举了「账号表现分析、视频表现分析、商品或行业数据对比、突发数据异常、近期热门趋势」。

这些数据在当前系统里分成两类，性质完全不同：

- **外部公开数据**（119 源）：现在就有，但没有分析。
- **自有账号/视频表现**：一条都没有，且必须走抖音开放平台或人工导出。

「自主发现值得做的视频」如果建立在「自身账号表现」上，先要有账号数据；如果建立在「外部公开数据」上，现在就能开始，只是缺分析层。**这两条路的 Phase 4 成本差一个数量级**，建议先明确以哪条为主。

---

## 十、可复用清单

| 现有能力 | 复用方式 |
|---|---|
| `heron_data_source` 注册表 + 23 个适配器 + `DatasetContract` | Phase 4 的分析输入层，无需重写 |
| `DatasetFacts` 确定性统计 | 继续做 facts/占位符；Phase 4 在其上叠加跨期/跨源分析 |
| `EditorialCandidatePool` + `PoolDigest` | 候选池身份与 hash 机制可承载「数据机会池」 |
| `heron_auto_slot` 决策留痕（`pool_json` / `decision_reason` / `editorial_intent`） | Phase 4 决策可追溯的落点，字段基本够用 |
| `NewsProductionPipeline` + 十道闸 + artifact/租约 | 生产执行层完全复用 |
| `NewsScriptContract` v2 + 模板能力声明（上一版 Phase 1） | 场景契约已统一，Phase 1 剩余工作量很小 |
| `heron_news_metric` + `performanceBreakdown(template/line/angle)` | Phase 6 分析骨架，字段需扩 |
| 审片 / 九宫格 / 版本 / 发布记录前端 | Phase 7 直接复用 |

## 十一、必须新建的

| 能力 | 现状缺口 |
|---|---|
| 数据机会实体 | 需要新表或扩 `heron_news_candidate`（当前无 kind / source_key / insight / 分值字段） |
| 分析层（趋势/异常/对比/排名变化） | 完全没有 |
| 选题评分与淘汰/延迟记录 | 完全没有 |
| 数据机会 → 生产的入口 | 生产入口只有 work/candidate，数据无法自发 |
| 账号表现采集 | 只有手工 POST |
| 聚合统计 API + 驾驶舱/异常中心 | 完全没有 |

---

## 十二、Phase 1 建议（等确认后实施，现在不动代码）

### 12.1 先确认顺序

新版任务书把「统一场景契约」放在 Phase 1。上一版任务书的 Phase 1 已经做完这件事的核心部分（未提交）：场景类型按模板能力收敛、`scene_id`、变量映射修复、渲染阻塞修复。

按任务书顺序推进仍然合理，但要明确：**Phase 1、2、3 都不会提升「自主发现」能力**。如果「能不能自己发现值得做的视频」是验收核心，建议在 Phase 1 收尾后直接进入分析层（任务书的 Phase 4），把 Phase 2（90 秒）与 Phase 3（冻结）排到其后——它们影响的是稳定性与观感，不是发现能力。

### 12.2 Phase 1 剩余工作（按新版任务书验收标准逐条对照）

| 验收标准 | 现状 |
|---|---|
| 模型能稳定生成合法场景结构 | ✅ 提示词已按模板能力收敛 |
| Java 能校验并拒绝不合法结构 | ✅ |
| 模板能声明支持的场景能力 | ✅（13 套已声明，能力闸生效） |
| 渲染器能按场景类型正确渲染 | ⚠️ 只有 `news-quote` / `news-rank` 真按类型切换版式；其余 11 套不消费 `scene_type` |
| **前端能读取并展示场景结构** | ❌ **完全缺失**（grep `scene_type` 在前端 0 命中） |
| 历史任务不因契约升级无法查看 | ✅ |
| 单元测试覆盖主要场景类型 | ⚠️ 契约层覆盖，渲染层只覆盖 2 套模板 |

**剩余 = 「前端展示场景结构」+「渲染层按类型表达」。** 前者是 Phase 1 内可完成的；后者本质属于视觉升级，与新任务书的 Phase 4/5 视觉要求重叠。

### 12.3 分析层（任务书 Phase 4）最小可行范围建议

如果决定提前做，建议的最小闭环：

1. 在现有 119 源上，按栏目每日取数（复用适配器与 `DatasetContract`）。
2. 确定性计算变化幅度、排名变动、异常偏离（这些必须是程序算，不能用模型）。
3. 把「变化显著」的项写成**数据机会候选**，落到与新闻候选同一张表（加 `kind` + `source_key` + `分值` + `依据`）。
4. 总编辑改成同时在「新闻候选」与「数据机会」里选，且**看得到机会的数值依据**。
5. 线内编辑不再 `digest()` 轮换，改为从选中机会直接锁定源。

这样「自主发现」才成立；在此之前系统只是自动轮播。

---

## 十三、风险

| 风险 | 说明 |
|---|---|
| 工作区混着上一版 Phase 1 | 不先处理，后续每个阶段的 diff 都不干净 |
| 任务书前提与代码不符 | 「自身采集的抖音数据」不存在，按原样实施会在 Phase 4 卡住 |
| 数据看板被新闻候选卡死 | 即使分析层做好了，没有新闻候选依然零产出——这是一个必须先拆的前置条件 |
| 数据源健康无人维护 | `probe_status` 静态、无失败计数，119 源会静默腐坏 |
| 发布合规口径未定 | douyin-cli 是浏览器自动化，与本任务书 §5.3 表述不一致 |
| mysql 组长期未跑 | 26 个类未验证，其中 `HeronMenuSeedTest` 已有静态证据会失败 |
