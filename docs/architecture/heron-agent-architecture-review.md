# Heron Agent 架构审查（Phase 0）

**日期：** 2026-11-13
**阶段：** Phase 0 —— 只盘点、不改造（未修改任何业务代码、未改库结构、未动配置）
**依据：** `docs/plans/plan.md` §16 Phase 0
**配套：** `heron-agentscope-compatibility.md`（技术底座核实）、`heron-agent-implementation-plan.md`（后续实施）

---

## 一、这份文档要回答什么

plan.md §16 给的验收标准是四句话：

> 明确哪些能力可以复用 / 明确哪些能力需要新增 / 明确哪些能力不能迁移 / 明确现有业务边界

再加一句你自己提的：**现有任务体系能否与 Agent 会话正确衔接**。

所以下面按「有什么 → 能不能复用 → 缺口在哪」组织，每条尽量给出代码或表结构证据。

---

## 二、系统鸟瞰

```
┌──────────────────── 前端 heron-ui（Vue3 + Element Plus）────────────────────┐
│ views/heron/{analytics, asset, editorial, news, production, proto, publish, │
│              review}   +   views/ai/chat   +   views/monitor/snailai        │
└─────────────────────────────────┬──────────────────────────────────────────┘
                                  │ REST（Sa-Token）
┌─────────────────────────────────▼──────────────────────────────────────────┐
│ ruoyi-admin（Spring Boot 4.1.2 / Jetty / JDK 21）                           │
│  └ ruoyi-modules/ruoyi-heron  219 个 Java 文件                              │
│      controller 13 │ service 105 │ domain 79 │ mapper 17 │ config 4         │
│  └ ruoyi-modules/ruoyi-ai        1 个 controller（SnailAiController）        │
│  └ 公共：ruoyi-common-{ai,mcp,mybatis,web,oss,log,json,...}                  │
└─────────────────────────────────┬──────────────────────────────────────────┘
                                  │
        ┌─────────────┬───────────┴──────────┬──────────────┐
        ▼             ▼                      ▼              ▼
    MySQL(17 表)   Redis(暂停标记/锁)    MinIO/七牛(产物)  外部：模型 API / 107~119 数据源 / 抖音上传
                                                          / 渲染：HyperFrames+GSAP+FFmpeg+EdgeTTS
```

**关键判断：** 这个系统已经是一个**完整的内容生产执行系统**，缺的不是执行能力，是**在流水线前端「想清楚该做什么」的那一层**。Agent 要补的正是这一层，不是替换任何一段。

---

## 三、现有能力清单

### 3.1 统一生产执行底座 —— 最值钱的一块

**有什么：**

| 表 | 关键列 | 作用 |
|---|---|---|
| `heron_production_task` | `request_hash`、`input_hash`、`run_token`、`lease_until`、`heartbeat_at`、`attempt_no`、`cancel_requested_at` | 任务领取与租约 |
| `heron_task_step` | `stage`、`checkpoint json`、`runtime_identity json`、`uk_step(task_id,attempt_no,stage)` | 阶段轨迹与断点 |
| `heron_usage_record` | `input_tokens`、`output_tokens`、`elapsed_ms`、`error_code`、`uk_usage_call` | 模型用量账本 |
| `heron_production_artifact` | 产物清单 | 渲染产物 |

唯一约束：`uk_task_request(actor_id,biz_type,biz_id,request_key)` 与 `uk_task_version(biz_type,version_id)` —— **同一个版本不可能有第二个任务**。

`ProductionStatus` 只有六个值：`queued / running / cancelling / succeeded / failed / cancelled`。

`ProductionRunner` 的实际做法（`service/impl/ProductionRunner.java`）：

```java
execution.recoverExpired();           // 租约过期回收
recovery.recoverCancelling();         // 取消中的收尾
Optional<ProductionRunVo> claimed = execution.claim(type);
// 执行期间定时 heartbeat，失败则视为失去租约
ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(...);
```

`ProductionExecutionServiceImpl` 在 attempt 递增时，会把上一个 attempt 的 `checkpoint` 读出来传给新 attempt：

```java
checkpoint = previous == null ? null : previous.getCheckpoint();
```

**判定：✅ 完全可复用，且是 Agent 任务的天然宿主。**

理由：
- 租约 + 心跳 + 重试计数 + 取消，这四件事**Agent 研究任务一样需要**，而且 Agent 更需要（跑得久、容易卡住）。
- `checkpoint` 列已经存在、已经在用、已经有「跨 attempt 传递」语义 —— AgentScope 的会话状态不需要新表，塞进这一列即可。
- `heron_usage_record` 已经是 token 账本，Agent 的每一次模型调用可以走同一个 `ProductionUsageService`。

**缺口：** 这套底座是**串行单任务**语义（`biz_type` + `version_id` 唯一）。Agent 研究是「一次研究、多个子 Agent 并行、可能产出多个候选」，形状不同。**不是不能复用，是不能硬套**——见 §6。

### 3.2 Frozen Context / Frozen Article

**有什么：** `domain/FrozenContext.java`、`service/impl/FrozenContextService.java`、`service/impl/FrozenArticleService.java`。

`FrozenContext` 的设计要点（原文注释）：

> 同一份逻辑内容必须算出同一个 hash，与键序、缩进、写入次数无关。
> 为什么不直接对写库字符串取 hash：MySQL 的 `json` 列会重新规范化存储…所以 hash 只认「解析后再规范化」的结果。

实现：`canonicalJson()`（键名升序 + 递归排序）→ `sha256`。`SCHEMA = "1.0"`，读取方只认这个版本。

`FrozenArticle` 冻结的是新闻原文。

**判定：✅ 完全可复用。**

Agent 的研究依据必须能追溯到具体快照（plan.md §8）。现有机制已经解决了两件麻烦事：
1. **规范化**：同一个逻辑内容 hash 稳定，不因 MySQL json 列重排而假报警；
2. **版本化**：`context_no` + `parent_context_id` + `revision_reason`，天然支持「同一任务多次修订」。

**Agent 需要做的只是：把「用了哪些数据源、哪几行快照、哪些工具返回值」写进 `contentJson`，让它参与 hash。** 不需要新机制。

### 3.3 决策链（编辑层）

**有什么：**

```
数据采集 → 信号与合并 → heron_data_opportunity
                              ↓
                    EditorialCandidatePool（冻结候选池，带 PoolDigest sha256）
                              ↓
                    EditorialDecisionService（总编辑：选线 + 锁源）
                              ↓
                    EditorialLineEditor（线内编辑：只读冻结候选，回退留痕）
                              ↓
                    NewsWorkServiceImpl → ProductionSubmitBo
                              ↓
                    NewsProductionDomain（冻结输入，含 selection 节点）
                              ↓
                         生产执行底座
```

辅助：`heron_auto_slot`（槽位幂等）、`AutoPauseStore` / `RedisAutoPauseStore`（暂停）。

**判定：⚠️ 可复用但要接对位置。**

这是 Agent 最自然的落点：**`EditorialDecisionService` 现在做的是「从候选池里挑一条」，Agent 要做的是「判断候选值不值得做、给出角度和结构」**。两者不是替代关系，是上下游：

- Agent 的输出应该是**候选池的上游**（丰富候选、给出理由），或者**决策的补充证据**（为什么这条比那条好）；
- 一旦 Agent 直接接管「挑哪条」，就等于把 `EditorialDecisionService` 架空——这个决定要有明确授权，不能顺手做。

### 3.4 数据线（数据源 → 信号 → 机会）

**有什么：**

| 组件 | 文件 | 作用 |
|---|---|---|
| 数据源注册表 | `heron_data_source`（119 条）+ `HeronDataSourceMapper` | 源、实体、指标、形状、节奏 |
| 取数 | `DatasetFetchService` + `data/` 下 100+ 适配器 | 产出 `dataset/1` 契约快照 |
| 信号 | `service/impl/DatasetSignal.java` | z-score、短窗、新高新低、按类别的「大变化」尺度 |
| 合并与打分 | `service/impl/DataOpportunityService.java` | 同事实合并、阈值 50、去重键 |
| 机会表 | `heron_data_opportunity` | `score`、`signals`(json)、`status`、`used_version_id` |
| 用法账本 | `heron_dataset_usage` | 源被用了几次 |

**判定：✅ 可复用，且是 Agent「有事可查」的数据基础。**

**但要说清一个已实测的事实：** 真实数据跑下来，机会产量很低（某日 107 源 → 1 条机会）。这不是 bug，是信号校准后的诚实结果——按每天 1~3 条算，Agent 每天能研究的题材也就这个量级。

**这对成本模型影响很大：** 每天 1~3 条 × 每条 Agent 研究（假设 8 轮 × 若干工具调用），日成本是可算的、可控的。但也意味着**样本极少**，见 §8。

### 3.5 新闻线

**有什么：** `heron_news_source` / `heron_news_candidate` / `heron_news_work` / `heron_news_metric`；`NewsCandidateScreener`（打分排序）、`NewsModelScriptAuthor`（模型写稿）、`NewsSourceRunner`（采集）、`NewsAutoRunner` / `NewsAutoService`（自动槽位）。

**判定：⚠️ 部分可复用。**

新闻线已有「筛选 → 打分 → 写稿」，与 Agent 的能力**重叠**。Agent 介入新闻线的价值不大，除非要做「多源事件归并 + 事实核验」——而这恰恰是 plan.md §1.1 说的「新闻是辅助线，只筛高传播潜力的」。

**建议：第一期不让 Agent 碰新闻线。** 数据线有 119 个源、有结构化数据、有可验证的事实，比新闻更适合验证「Agent 能不能自主发现值得做的东西」。

### 3.6 模型调用封装

**有什么：** `service/impl/OpenAiModelClient.java`（184 行）

```java
/**
 * OpenAI 兼容模型入口：一次 chat/completions，用量进账本，失败不重试。
 */
private static final Duration TIMEOUT = Duration.ofSeconds(300);
```

配置来自基座参数表 `sys_config`：`heron.model.base-url` / `heron.model.name` / `heron.model.api-key`，未配齐抛 `HeronBusinessException(503, "model_not_configured")`。每次调用 `usage.start(run, stage, callId, host, model)` 记账。

**判定：⚠️ 可复用其「记账 + 配置 + 失败语义」，不能复用它作为 Agent 的执行器。**

它只做一次性补全，没有工具调用、没有循环、没有多轮。Agent 需要的是另一层。**但它解决的三个问题必须继承：**
1. 模型配置读 `sys_config`，不散落在 yml 里；
2. 每次调用有 `callId`，进 `heron_usage_record`；
3. 「失败不重试，由人点重跑」——这条语义对 Agent 更重要（自动重试 Agent 会烧钱）。

### 3.7 Prompt 管理

**有什么：** `src/main/resources/prompt/` 下 16 个 `.md`：

```
data-board/{routing, script-contract, title-contract}.md
data-board/columns/{atlas,business,market,other,tech,trend}.md
editorial/daily-editor.md
news-explainer/{script-contract, selection, writing-style}.md
prototype/rp1-authoring-contract.md
review/{editorial-review, evidence-review}.md
```

**判定：⚠️ 缺版本管理。**

plan.md §15 第 16 条要求「模型和 Prompt 支持版本管理」。现状是：prompt 随 jar 打包，改了没有版本号，产物里也没有记录「这条视频是用哪版 prompt 生成的」。

**这是现有系统的真实缺口，而且和 Agent 直接相关**——Agent 的 prompt（尤其子 Agent 的 system prompt）会更多，没有版本号就更难回溯「为什么这次判断变了」。

**建议：** 不新建系统，只在冻结上下文里加一个 `promptVersion`（可以是文件内容的 hash），成本极低。

### 3.8 发布与效果回流

**有什么：**

| 组件 | 作用 |
|---|---|
| `IPublishAdapter` | 发布出口抽象 |
| `SandboxPublishAdapter` | 沙箱，不发真平台 |
| `DouyinCliPublishAdapter` | 调 `sau` CLI（浏览器自动化，不用开放平台凭据） |
| `heron_video_version.review_status / publish_source / publish_url / publish_at` | 复核与发布痕迹 |
| `heron_news_metric` | `plays`、`completion_rate`、`likes`、`comments`、`shares`，多次采样取最新 |
| `NewsMetricServiceImpl` → `NewsCandidateScreener` | 表现数据反哺选题打分 |

**判定：✅ 可复用，是最有价值但最不成熟的一块。**

「效果回流反哺选题」这条链**代码在，数据没有**——没有跑量的发布数据，「哪种内容更好」就无法用数据回答。这直接限制了第三个验收问题（多 Agent 是否更好）能不能被证明。

### 3.9 权限、审计、调度

| 能力 | 现状 | 判定 |
|---|---|---|
| 权限 | 17 种权限串、共 27 处 `@SaCheckPermission`，命名 `heron:{业务}:{动作}` | ✅ 复用，Agent 相关接口沿用同一套 |
| 审计 | 7 个文件用 `@Log`，落 `sys_oper_log` | ✅ 复用，但 Agent 内部工具调用**不该**每条都进 `sys_oper_log`（会淹没人工操作记录），应进独立表 |
| 调度 | `@Scheduled` runner × 4（NewsAutoRunner / NewsSourceRunner / DataOpportunityRunner / ProductionRunner）+ `heron_auto_slot` 幂等 + Redis 暂停标记 | ✅ 复用，不引第二套 |
| 分布式任务 | 基座带 SnailJob（`snailjob.version 2.0.2`） | ⚠️ 未使用；Phase 2 若要分布式再评估 |

### 3.10 基座已有的 AI 资产（重要发现）

| 资产 | 实际内容 | 判断 |
|---|---|---|
| `ruoyi-common-ai` | 只有 1 个类 `SnailAiConfig`，条件是 `snail-ai.enabled=true` | 壳 |
| `ruoyi-modules/ruoyi-ai` | **1 个 controller**：`SnailAiController` | 壳 |
| `ruoyi-common-mcp` | `McpAutoConfiguration` + `McpClientTemplate` + 2 个结果类 | 薄 MCP 客户端，可作为「Agent 调外部工具」的现成通道 |
| `spring-ai-bom 2.0.2` | **只 import BOM，无模块依赖** | 未使用 |
| `snail-ai 1.1.1` | dev `enabled: true`（连 `127.0.0.1:18888` gRPC），prod `enabled: false` | 外部 Agent Server 的客户端壳 |
| `ruoyi-heron` 依赖 `ruoyi-common-ai` | 已在 classpath 上 | 意味着引入 Agent 运行时时要注意共存 |

**判定：❌ 没有可复用的 Agent 运行时。**

基座里「看起来有 AI」的部分，实质都是**连一个外部 Snail AI Server 的客户端**，dev 开着、prod 关着，且没有业务逻辑。**不要误以为可以直接用**——它给的是「聊天窗口 + 外部 agent server 连接」，不是可编程的研究循环。

**但它给了两条可用的东西：**
1. `ruoyi-common-mcp` 的 `McpClientTemplate` —— 如果将来要接外部 MCP 工具，通道已有；
2. SnailJob —— 分布式调度已有，将来 Agent 任务要水平扩展时有现成的，不必引 XXL-Job 之类。

---

## 四、四张判定表

### 4.1 可以直接复用（零改造）

| 能力 | 复用方式 |
|---|---|
| 任务底座（租约/心跳/重试/取消） | Agent 任务挂在同一套 Pattern 上（是否同一张表见 §6） |
| `heron_task_step.checkpoint` 跨 attempt 传递 | 存 Agent 会话/中间结论 |
| `heron_usage_record` + `ProductionUsageService` | Agent 每次模型调用记账 |
| `FrozenContext` 规范化 + sha256 | 研究依据的冻结与校验 |
| `sys_config` 模型配置 | 不新增一套配置 |
| `@Scheduled` + `heron_auto_slot` + `AutoPauseStore` | 定时触发与紧急暂停 |
| `@SaCheckPermission` + `sys_oper_log` | 人工入口的权限与审计 |
| `IPublishAdapter` / `heron_news_metric` | 发布与效果回流 |
| `heron_data_opportunity` | Agent 的研究输入 |
| `ruoyi-common-mcp` | 将来接外部工具 |

### 4.2 需要扩展（改现有代码）

| 能力 | 扩展内容 | 风险 |
|---|---|---|
| `FrozenContext` 的 content 结构 | 加 `research` 节点：研究目标、证据 ID、工具调用摘要、prompt 版本 | 低：`SCHEMA` 仍是 `1.0` 时，旧内容不含该键，hash 逻辑不变；若要改 `SCHEMA` 则要处理旧任务 |
| `OpenAiModelClient` | 抽出「一次调用 + 记账」为一个可复用方法，给 Agent 的每次模型调用使用 | 低：现成 184 行，抽取不改行为 |
| `ProductionStatus` 语义 | Agent 研究任务若复用同一枚举，需要确认 `PARTIAL`（部分成功）怎么表达 | 中：`ProductionStatus` 只有 6 值，没有 partial；plan.md §10 要求 `PARTIAL` |
| prompt 目录 | 加版本标识 | 低 |
| `heron_auto_slot` | Agent 研究槽位 | 低 |

### 4.3 需要新增

| 能力 | 为什么现有不足以覆盖 |
|---|---|
| Agent 研究任务表 | 一次研究 ≠ 一个视频版本；形状是「1 研究 → N 候选」，而 `heron_production_task` 是 1:1 版本 |
| 工具调用记录表 | `sys_oper_log` 记人，`heron_task_step` 记阶段，都不记「第 3 轮调了哪个工具、耗时多少、返回多大」 |
| 内容决策记录表 | `heron_data_opportunity` 存的是「发现了什么」，不存「为什么决定做/不做」 |
| 预算执行器 | `maxIters` 只能限轮数；工具次数、token、金额、超时需要一个统一闸 |
| Java Tool 门面层 | 现有 Service 都是给 Controller 用的，入参是 BO，返回 VO；Agent 需要的是「有界、只读、参数校验过」的查询 |
| Agent 配置与启停 | 与研究槽位分开的开关 |

### 4.4 不能迁移（明确不做）

| 不能做的事 | 依据 |
|---|---|
| 用 AgentScope 的 Session/Memory 代替业务状态 | plan.md §3.2 原文 |
| 用 AgentScope checkpoint 代替 `heron_task_step` | 同上 |
| 让 Agent 直接写业务表 | plan.md §2.2「不允许 Agent 直接修改业务数据」 |
| 让 Agent 执行任意 SQL | plan.md §6.3 第 2 条 |
| 让 Agent 调抖音发布 | plan.md §6.3 第 7 条 |
| 引入第二套任务状态机 | plan.md §10「实际状态应与现有任务体系统一」 |
| 用官方 Spring Boot starter | `heron-agentscope-compatibility.md` §3.3 |
| 替换 HyperFrames / GSAP / FFmpeg / TTS | plan.md §16 Phase 6 重点 |
| 把数据看板线改成 Agent 驱动 | 现有链路刚跑通并有测试覆盖，Phase 5 之前不动 |

---

## 五、数据库现状与改造范围

**现状：17 张表，30 个 SQL 文件（001~030），全部为增量迁移。**

| 域 | 表 |
|---|---|
| 生产执行 | `heron_production_lane`、`heron_production_task`、`heron_task_step`、`heron_usage_record`、`heron_production_artifact`、`heron_production_context` |
| 新闻 | `heron_news_source`、`heron_news_candidate`、`heron_news_work`、`heron_video_version`、`heron_news_metric` |
| 数据线 | `heron_data_source`、`heron_dataset_usage`、`heron_data_opportunity` |
| 调度 | `heron_auto_slot` |
| 原型工坊 | `heron_proto_project`、`heron_proto_version` |

**Phase 0 不建任何表。** 后续改造范围（Phase 2 起）：

| 表 | 动作 | Phase |
|---|---|---|
| `heron_research_task` | 新增 | 2 |
| `heron_agent_tool_call` | 新增 | 2 |
| `heron_content_decision` | 新增 | 5 |
| `heron_research_task` 加 `prompt_version` 等 | 扩展 | 5 |
| 现有 17 张表 | **不动** | —— |

**为什么研究任务要独立成表、不复用 `heron_production_task`：**

| 维度 | 生产任务 | 研究任务 |
|---|---|---|
| 与版本的关系 | 1:1，`uk_task_version` 保证 | 1:N，一次研究可能产出 0 或多个选题 |
| 耗时 | 分钟级（渲染 2~3 分钟） | 可能五分钟以上，且会调外部模型十几轮 |
| 幂等键 | 「同一版本不能做两次」 | 「同一机会同一天不要重复研究」，语义不同 |
| 失败重试 | 重跑整条流水线 | 可能只想重跑「事实核验」这一步 |
| 预算 | 无（固定路径） | 必须有（轮数/调用数/token/金额/时间） |

**但状态词表与租约模式必须一致**，否则就是第二套状态机。

---

## 六、任务体系与 Agent 会话的衔接（回答第二个问题）

这是 Phase 1 要验证的核心问题。现状盘点能给出**设计判断**，但**不能替代实测**。

### 6.1 现有的衔接点

| 现有机制 | 能否承接 Agent 会话 |
|---|---|
| `heron_task_step.checkpoint`（json，已跨 attempt 传递） | ✅ 可存「会话 ID + 已完成轮次 + 中间结论」 |
| `attempt_no` | ✅ 重试计数，Agent 重试可沿用 |
| `run_token` + `lease_until` + `heartbeat` | ✅ Agent 跑得久，比生产更需要心跳 |
| `uk_task_request(actor_id,biz_type,biz_id,request_key)` | ⚠️ 幂等键是「谁 + 什么 + 哪次请求」，Agent 需要「哪个机会 + 哪个研究目标」 |
| `heron_usage_record.uk_usage_call(task_id,attempt_no,call_id)` | ✅ Agent 的每次模型调用 `callId` 天然可落 |
| `ProductionStatus` 六值 | ⚠️ 缺 `partial`（plan.md §10 要求 `PARTIAL`） |

### 6.2 三个必须验证的衔接假设

| # | 假设 | 若不成立会怎样 |
|---|---|---|
| H1 | 一个研究任务能稳定映射一个 Agent `sessionId`，且重试时**复用**同一会话 | 每次重试都从零开始，成本翻倍，且结论前后不一致 |
| H2 | Agent 的中间状态能序列化进 `checkpoint` 列，且 attempt 递增时能读回 | 会话断在第一次失败，长研究永远跑不完 |
| H3 | `ExceedMaxItersEvent` / 工具异常能落成可读的 `error_code` | 失败原因变成「模型没输出」，排查无门 |

**H1 的不确定性最大。** 从 `heron-agentscope-compatibility.md` §6.2 看：`sessionId` 参数存在，但**持久化存储未核实**，且默认 `Memory` 叫 `InMemoryMemory`。如果 AgentScope 的会话不能跨进程持久化，H1 就不能靠框架，只能靠我们自己把「已完成的事实与结论」写进 `checkpoint`，重建会话时喂回去。

**这是 Phase 1 必须第一天就动手验的一条**——它决定了整个衔接方案是「薄封装」还是「自己管会话」。

### 6.3 初步方案（待 Phase 1 验证后定稿）

```
heron_research_task (1 行)
   task_id ─────┬─→ heron_task_step.checkpoint  {'session_id':'...', 'round':4, 'findings':[...]}
                ├─→ heron_usage_record          每次模型调用
                └─→ heron_agent_tool_call       每次工具调用

重试时：读 checkpoint → 有 session_id 且可恢复 → 续跑
                    → 无 / 不可恢复   → 用 findings 重建上下文，**不重跑已完成的事实查询**
```

---

## 七、生产环境风险

| 风险 | 具体表现 | 缓解 |
|---|---|---|
| 🔴 自动发布 | dev 配的是 `publish-adapter: douyin-cli`（真浏览器上传），且 `auto-confirm-enabled` 默认 `true` | 验证期必须显式 `auto-confirm-enabled: false`；prod 保持 `snail-ai.enabled: false` |
| 🔴 上下文丢失 | Reactor 线程上 Sa-Token/租户/审计全丢 | 见兼容性文档 §4，权限在任务创建时校验，工具层显式传参 |
| 🔴 文件系统越界 | HarnessAgent 的 workspace/skills 默认读目录 | 不用 workspace，走 inline prompt；Phase 2 前验证读不到 `application-prod.yml` |
| 🟠 费用失控 | Agent 循环 + 子 Agent，一天可能几十次模型调用 | 预算闸硬约束（轮数/调用数/token/金额/时间），到点停并落 `PARTIAL` |
| 🟠 与现有 cron 抢资源 | 数据机会分析 + Agent 研究 + 渲染 三者同机 | 研究槽位与生产槽位错开；渲染期间不跑研究 |
| 🟠 双 Agent 运行时 | 基座已带 snail-ai（dev 开） | 明确二选一或明确分工，不能两个都跑完整链路 |
| 🟡 Jackson 双栈 | Jackson 2 与 3 并存 | 工具出入参统一走一层转换，不在业务代码里混用 |

**`application-prod.yml` 有一处本地未提交修改，任何提交都不得带上它。**（既有约定）

---

## 八、一个必须先承认的事实约束

你在任务书里写的最重要的验收标准是：

> **系统能否自主发现值得做的视频**，而不只是能否自动把视频做出来

Phase 0 盘点后要给一个诚实的进展判断：

| 环节 | 状态 |
|---|---|
| 能采集 | ✅ 119 个源注册，107 个实测跑过（跨次探测中 22~40 个不稳定，源健康回写已在步骤 2 修过） |
| 能算信号 | ✅ 已实测（z-score、短窗、新高新低、按类别尺度） |
| 能合并去重 | ✅ 已实测（同事实合并，Frankfurter ×7 → 1） |
| **能发现值得做的东西** | ⚠️ **只能发现「数据上有异常」的东西，不能发现「观众想看」的东西** |
| 能做成视频 | ✅ 两条线真渲染通过 |
| 能证明做得对 | ❌ 没有播放量数据 |

**这句话是这份审查里最该被记住的一句：**

现在的链路能回答「哪个数字动了」，不能回答「哪个数字动了值得做一条视频」。中间缺的正是 Agent 要做的事——**但它能不能做好，取决于它有没有「什么算好」的判断依据，而这个依据只能来自发布数据回流。**

所以在发布数据起来之前，Agent 的价值只能用定性方式观察，不能用数据证明。这一点我在 `heron-agent-implementation-plan.md` 里落成了 Phase 1 的准入口径（`compatibility` §7 问题三）。

---

## 九、不应该改造的部分（明确划线）

| 部分 | 为什么不动 |
|---|---|
| 渲染栈（HyperFrames / GSAP / FFmpeg / Edge TTS） | Phase 6 明确要求复用，不重写 |
| `dataset/1` 契约与 100+ 适配器 | 刚跑通，有测试；改它等于重做数据线 |
| `heron_production_task` 的租约与幂等实现 | 现有实现正确且被测试覆盖；研究任务另建表，不改它 |
| `EditorialDecisionService` 的选线逻辑 | 改它等于改现有产出行为，风险高；Agent 只在上游加候选、在下游加理由 |
| 新闻线的筛选与写稿 | 与 Agent 能力重叠，第一期不碰 |
| 两条视频线的模板与场景契约 | 有真渲染测试兜底，不动 |
| `@Scheduled` 那 4 个 runner | 复用，不重写 |

---

## 十、本阶段未检查的范围

诚实标注，避免让人误以为已经看全：

| 未检查 | 影响 |
|---|---|
| `heron-ui` 只看了目录结构（`views/heron/*` 8 个模块 + `views/ai/chat` + `views/monitor/snailai`），**没读具体页面代码** | 前端改造量估计只能给到「页面级」，给不到「组件级」 |
| `ruoyi-common-*` 20 多个公共模块只确认了 `ai` / `mcp` 的内容 | 若将来要用 `liteflow`（已引入）做 Agent 编排，未评估 |
| `data/` 下 100+ 适配器的具体实现 | 只知道契约与产出形状，不知道单个适配器的健壮性 |
| `heron_proto_*`（原型工坊） | 与 Agent 线无关，未深入 |
| SnailJob 的实际可用性 | 只确认依赖存在，未验证能接到现有任务 |
| AgentScope 的实际运行（无 PoC） | 这是 Phase 1 的事，Phase 0 只做代码与文档层面的核实 |
| 生产环境实际配置 | 只读了 `application-dev.yml` 与 `application-prod.yml` 的片段；`application-prod.yml` 有本地未提交修改，未据其下结论 |

---

## 十一、结论

1. **不需要重建执行体系。** 生产底座、冻结上下文、任务恢复、用量账本、发布与回流都已就位，且质量不低（`FrozenContext` 的规范化 hash、任务租约与幂等这两处的设计考虑比多数项目细）。
2. **缺的只有「研究层」。** 现有系统的上限是「按规则挑一条做出来」，Agent 要补的是「判断哪个值得做、为什么」。
3. **最大的技术风险不在 AgentScope，在上下文与安全边界。** Reactor 线程上的 ThreadLocal 与 workspace 文件访问，这两件事如果处理不干净，做出来的东西能跑但不可信。
4. **最大的不确定性是「值不值得」。** 机会产量每天 1~3 条，发布数据尚未跑量 —— 在样本和效果数据起来之前，多 Agent 的价值无法用数据证明，只能定性观察。
