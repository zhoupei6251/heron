# Heron Agent 实施计划

**日期：** 2026-11-13
**阶段：** Phase 0 产出（**本文件只是计划，未实施任何代码**）
**上游：** `docs/plans/plan.md` §16、`heron-agent-architecture-review.md`、`heron-agentscope-compatibility.md`

> **授权状态：** 本文档描述 Phase 1~8。**当前只有 Phase 0 被授权**，Phase 1 需要你单独批准后才能开始。Phase 2 及之后每阶段同样需要逐段批准。

---

## 一、最终推荐架构

```
┌───────────────────────────── 人（监督 / 审核 / 调策略）──────────────────────┐
│  决策中心：看机会、看研究、看证据、否决、改策略                                │
└───────────────────────────────────┬───────────────────────────────────────┘
                                    │ REST
┌───────────────────────────────────▼───────────────────────────────────────┐
│ Heron 确定性业务层（现有）                                                  │
│                                                                            │
│  【新增】研究闭环                     【现有，不动】                         │
│  ┌──────────────────┐               ┌──────────────────────────────────┐   │
│  │ ResearchTask      │              │ 数据线：源→信号→机会              │   │
│  │  ├ 预算闸          │──只读工具──▶│ FrozenContext / FrozenArticle     │   │
│  │  ├ 会话+checkpoint │              │ 决策链：候选池→总编辑→线内编辑     │   │
│  │  └ 工具调用账本    │              │ 生产：任务/租约/阶段/产物          │   │
│  └────────┬─────────┘               │ 发布 + heron_news_metric 回流      │   │
│           │ 结构化决策               └──────────────────────────────────┘   │
│           ▼                                                                │
│  【新增】ContentDecision → 写回 heron_content_decision                      │
│           │ 人工/规则放行                                                  │
│           ▼                                                                │
│      现有 EditorialCandidatePool（作为「候选」之一加入，不取代它）            │
└───────────────────────────────────┬───────────────────────────────────────┘
                                    │ 进程内调用（不经网络、不开端口）
┌───────────────────────────────────▼───────────────────────────────────────┐
│ Agent 智能层（AgentScope Java 2.0，进程内）                                 │
│  HarnessAgent（Supervisor）                                                │
│    ├ 研究与数据 Agent      → 只读工具：快照/序列/对比/机会                 │
│    ├ 内容策划 Agent        → 输出角度/结构/钩子                            │
│    ├ 事实核验 Agent        → 独立回原始快照核对，不看别的 Agent 的结论      │
│    └ 增长分析 Agent（后置） → 读 heron_news_metric                        │
│  maxIters / 工具次数 / token / 金额 / 超时 —— 五道闸，硬停                 │
└───────────────────────────────────────────────────────────────────────────┘
```

**三条边界（比架构图更重要）：**

1. **Agent 只读。** 所有工具都是查询；没有任何工具能写库、改状态、发内容。
2. **决策权在 Heron。** Agent 输出 `PRODUCE/OBSERVE/REJECT/NEED_REVIEW`，是否生产仍由现有业务规则校验。
3. **执行权在现有流水线。** Agent 不碰 HyperFrames、不碰发布、不碰任务状态机。

---

## 二、模块划分

### 2.1 建议：不新建 Maven 模块，在 `ruoyi-heron` 内加子包

```
ruoyi-modules/ruoyi-heron/src/main/java/org/dromara/heron/
├── domain/
│   ├── HeronResearchTask.java          【新增】研究任务实体
│   ├── HeronAgentToolCall.java         【新增】工具调用记录
│   └── HeronContentDecision.java       【新增】决策记录
├── domain/bo/vo/                        【新增】对应 BO/VO
├── mapper/                              【新增】3 个 Mapper
├── service/
│   ├── IAgentResearchService.java      【新增】研究任务编排
│   └── IContentDecisionService.java    【新增】决策结果入库与放行
├── service/impl/
│   ├── AgentResearchServiceImpl.java   【新增】
│   ├── ContentDecisionServiceImpl.java 【新增】
│   ├── AgentBudget.java                【新增】五道闸
│   ├── AgentRuntimeConfig.java         【新增】HarnessAgent 装配（@ConditionalOnProperty）
│   └── agent/                          【新增】只读工具门面（子包，仍在既有分层内）
│       ├── DatasetQueryTools.java
│       ├── OpportunityQueryTools.java
│       ├── NewsQueryTools.java
│       └── MetricQueryTools.java
└── controller/
    ├── ResearchTaskController.java     【新增】
    └── ContentDecisionController.java  【新增】
```

**为什么不建独立模块：**

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| `ruoyi-heron` 内子包 | 直接复用 `BaseMapperPlus`、事务、权限、`JsonUtils`；无需跨模块 API | 模块依赖增加 | ✅ 推荐 |
| 新建 `ruoyi-heron-agent` | 依赖隔离干净 | 要么造跨模块 API 层，要么破坏分层；单租户自部署没有独立部署需求 | ❌ |

**分层遵守项目约定**：不发明新分层。`agent/` 只是 `service.impl` 下的子包，装工具门面。

**依赖开关**：`AgentRuntimeConfig` 用 `@ConditionalOnProperty(prefix = "heron.agent", name = "enabled", havingValue = "true")`，默认关闭。**不加这个依赖，应用照常启动、照常跑现有业务。**

### 2.2 依赖变更（已完成，2026-11-13 实测）

```xml
<!-- ruoyi-modules/ruoyi-heron/pom.xml -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>2.0.4</version>
</dependency>
```

**不引** `agentscope-spring-boot-starters`（版本钉 4.0.x）。reactor-core 由 core 传递引入（依赖树已确认）。

**同一批必须做的减法：** 基座的 spring-ai 2.0.2 与本框架不可共存（victools 5.0 返回 Jackson 3 节点，agentscope 需要 Jackson 2，
实测 `NoSuchMethodError`，详见 `docs/reports/heron-phase1-agentscope-probe.md`）。因此 `ruoyi-admin` 停止装配
`ruoyi-common-mcp` / `ruoyi-ai` / `ruoyi-demo`，`ruoyi-heron` 停止装配 `ruoyi-common-ai`；**模块源码全部保留**，
恢复只需放开注释（但同时必须去掉 agentscope）。

副作用：`/mcp` 端点下线，前端 `views/ai/chat`、`views/monitor/snailai`、`views/demo/*` 暂无后端。


Phase 1 第一件事：`mvn -pl ruoyi-modules/ruoyi-heron dependency:tree` 查清 harness 的完整传递依赖，确认没有第二套 Web 栈（Netty / Vert.x）。

---

## 三、类与接口设计建议

### 3.1 研究任务

```java
// domain/HeronResearchTask.java  extends BaseEntity
Long   researchId;        // 主键
String researchNo;        // 对外的稳定编号
Long   opportunityId;     // 来源机会
String goal;              // 研究目标（自然语言，人读得懂）
String snapshotRef;       // 冻结依据：FrozenContext id 或 dataset 快照 key
String status;            // 与 ProductionStatus 同一套词表 + partial
String grade;             // L1 / L2 / L3（三级智能分析）
String decisionType;      // PRODUCE / OBSERVE / REJECT / NEED_REVIEW
BigDecimal score;
BigDecimal confidence;
Integer rounds;           // 实际推理轮数
Integer toolCalls;        // 实际工具调用次数
Long   inputTokens;
Long   outputTokens;
String sessionId;         // Agent 会话标识
String promptVersion;     // prompt 内容 hash
String errorCode;
String errorReason;
```

### 3.2 预算闸（最重要的一段代码）

```java
// service/impl/AgentBudget.java
/**
 * 五道闸。任何一道到点都必须真的停下并把已获得的部分结果落成 partial，
 * 不允许继续推理，也不允许把部分结果伪装成完整结论。
 */
public final class AgentBudget {
    private final int maxIters;          // 推理轮数
    private final int maxToolCalls;      // 工具调用次数
    private final long maxInputTokens;
    private final long maxOutputTokens;
    private final Duration maxDuration;

    /** 每次工具调用、每轮推理、每次模型返回后都要调；返回 false 表示必须停。 */
    public boolean allowNext();
    public String stopReason();          // 到点原因，落 error_code
}
```

**设计要点：**

- 闸在 **Java 层**，不在 prompt 里。plan.md §12 原文：「成本控制不能只靠 Prompt 提醒，必须在 Java 调度和执行层强制实施」。
- `maxIters` 可以同时交给 AgentScope 的 `ReActAgent.Builder.maxIters()` 双保险，但**不能只靠它**——它只管轮数，不管 token 和金额。
- 到点后的产物必须是 `partial`，不是 `succeeded`。

### 3.3 研究编排

```java
public interface IAgentResearchService {
    /** 为一条机会创建研究任务；已存在同机会同日的未完成任务则返回它（幂等）。 */
    Long create(long opportunityId, String goal, String grade);

    /** 执行一轮：领取 → 装配 Agent → 跑 → 落 checkpoint → 判定。 */
    void run(long researchId);

    /** 人工重跑：复用 sessionId 与已有 findings，不重跑已完成的事实查询。 */
    void rerun(long researchId);
}
```

### 3.4 会话与断点

```java
// 落在既有 heron_task_step.checkpoint 的 JSON 形状（草案）
{
  "sessionId": "…",
  "round": 4,
  "findings": [ { "fact": "…", "evidenceId": "…", "toolCallId": "…" } ],
  "toolsUsed": [ "query_trend_series", "compare_video_performance" ],
  "budget": { "iters": 4, "toolCalls": 7, "inputTokens": 18234, "outputTokens": 2011 }
}
```

**重试语义：**

| checkpoint 状态 | 重跑做法 |
|---|---|
| 有 `sessionId` 且框架可恢复 | 续跑，预算沿用 |
| 有 `sessionId` 但框架不可恢复 | 用 `findings` 重建上下文，**不重跑已完成的事实查询** |
| 无 checkpoint | 从头跑 |

**这一条是 Phase 1 必须实测的（见 §7 问题二 H1）。**

---

## 四、Tool 设计

### 4.1 原则

| 原则 | 落实 |
|---|---|
| 只读 | `@Tool(readOnly = true)`；实现里只调 mapper 的 select |
| 有界 | 每个工具都有 `limit` 参数，且有硬上限（默认 50，最大 200） |
| 显式上下文 | 不依赖 ThreadLocal；`ownerId` 由研究任务传入 |
| 有超时 | 单工具 10 秒（plan.md §5.2） |
| 有日志 | 每次调用落 `heron_agent_tool_call` |
| 结构化 | 返回对象，不返回大段原始 JSON |
| 不越权 | 工具里**不做** `@SaCheckPermission`，权限在创建研究任务时校验一次 |

### 4.2 工具清单（12 个，第一期只做前 8 个）

| # | 工具 | 对应现有实现 | 一期 |
|---|---|---|---|
| 1 | `query_data_sources` | `HeronDataSourceMapper` | ✅ |
| 2 | `query_dataset_snapshot` | `DatasetFetchService` / `dataset/1` 快照 | ✅ |
| 3 | `query_trend_series` | 同上，按实体+指标取序列 | ✅ |
| 4 | `compare_series` | `DatasetSignal` 的 z-score / 变化率 | ✅ |
| 5 | `query_opportunities` | `HeronDataOpportunityMapper` | ✅ |
| 6 | `query_opportunity_detail` | 同上，含 `signals` json | ✅ |
| 7 | `query_source_health` | `HeronDataSourceMapper` 的 `probe_status` | ✅ |
| 8 | `query_recent_productions` | `heron_video_version`（做了什么、做没做成） | ✅ |
| 9 | `query_news_events` | `heron_news_candidate` | 二期 |
| 10 | `query_published_performance` | `heron_news_metric` | 二期（数据起来后才有意义） |
| 11 | `query_content_history` | 历史选题与表现 | 二期 |
| 12 | `query_columns` | `prompt/data-board/columns/*` | 二期 |

### 4.3 不提供的工具（明确不做）

| 不给 | 原因 |
|---|---|
| 任何 `insert/update/delete` | Agent 不改业务数据 |
| 任意 SQL / 任意表查询 | plan.md §6.3 第 2 条 |
| 文件读取 | §6.3 第 3 条 |
| Shell 执行 | §6.3 第 4 条 |
| 发布接口 | §6.3 第 7 条 |
| `fetch` 任意 URL | 外部内容一律经现有受控 HTTP 客户端 |

---

## 五、Agent 角色

| 角色 | 职责 | 输入 | 输出 | 一期 |
|---|---|---|---|---|
| Supervisor（Supervisor=HarnessAgent） | 定计划、派活、汇总、判充分性 | 研究目标 + 快照引用 | `ContentDecisionResult` | ✅ |
| 研究与数据 Agent | 查序列、找异常、给证据 | 工具集 1~8 | 事实列表 + 证据 ID | ✅ |
| 内容策划 Agent | 定角度、结构、钩子、受众 | 事实列表 | 策划方案 | ✅ |
| 事实核验 Agent | **独立**回原始快照核对关键结论 | 证据 ID | 通过/驳回 + 理由 | ✅ |
| 增长分析 Agent | 读历史表现给建议 | `heron_news_metric` | 表现洞察 | 二期 |

**关键设计：事实核验 Agent 不看别的 Agent 的总结，只看证据 ID 回原始数据。**（plan.md §4.4 原文）这是整个多 Agent 设计里唯一不可妥协的一条——没有它，多 Agent 只是「多次 LLM 调用」，不产生可信度。

---

## 六、数据库变更建议

**Phase 0 不建表。** Phase 2 起建 3 张：

```sql
-- 031-research.sql（Phase 2）
CREATE TABLE heron_research_task (
  research_id bigint NOT NULL,
  research_no varchar(32) NOT NULL,
  opportunity_id bigint NULL,
  owner_id bigint NOT NULL, actor_id bigint NOT NULL,
  goal varchar(1000) NOT NULL,
  snapshot_ref varchar(256) NULL,
  grade varchar(4) NOT NULL,              -- L1/L2/L3
  status varchar(16) NOT NULL,            -- queued/running/succeeded/partial/failed/cancelled
  decision_type varchar(16) NULL,
  score decimal(6,2) NULL, confidence decimal(5,4) NULL,
  rounds int NOT NULL DEFAULT 0,
  tool_calls int NOT NULL DEFAULT 0,
  input_tokens bigint NULL, output_tokens bigint NULL,
  cost_micros bigint NULL,
  session_id varchar(128) NULL,
  prompt_version char(64) NULL,
  attempt_no int NOT NULL DEFAULT 0,
  lease_until datetime(3) NULL, heartbeat_at datetime(3) NULL,
  error_code varchar(64) NULL, error_reason varchar(2000) NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (research_id),
  UNIQUE KEY uk_research_no (research_no),
  UNIQUE KEY uk_research_opportunity_day (opportunity_id, create_time),
  KEY ix_research_claim (status, create_time, research_id),
  KEY ix_research_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE heron_agent_tool_call (
  call_id bigint NOT NULL, research_id bigint NOT NULL, attempt_no int NOT NULL,
  round_no int NOT NULL, tool_name varchar(64) NOT NULL,
  request_summary varchar(2000) NULL, response_summary varchar(2000) NULL,
  status varchar(16) NOT NULL, duration_ms bigint NULL,
  error_code varchar(64) NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (call_id),
  KEY ix_tool_call_research (research_id, attempt_no, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 032-content-decision.sql（Phase 5）
CREATE TABLE heron_content_decision (
  decision_id bigint NOT NULL,
  research_id bigint NOT NULL, opportunity_id bigint NULL,
  decision_type varchar(16) NOT NULL,
  score decimal(6,2) NULL, confidence decimal(5,4) NULL,
  decision_reason varchar(2000) NOT NULL,
  key_insights json NULL, evidence_ids json NULL, risk_warnings json NULL,
  suggested_angles json NULL, content_plan json NULL,
  model_id varchar(160) NULL, prompt_version char(64) NULL,
  human_override varchar(16) NULL, human_reason varchar(1000) NULL,
  decided_by bigint NULL, decided_at datetime(3) NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (decision_id),
  UNIQUE KEY uk_decision_research (research_id),
  KEY ix_decision_type (decision_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**设计约束：**

| 约束 | 理由 |
|---|---|
| `status` 词表与 `ProductionStatus` 对齐，**只多一个 `partial`** | 不造第二套状态机（plan.md §10）。`partial` 是 plan.md §10 明确要求的 |
| 研究任务**另建表**，但沿用租约/心跳/attempt 的**模式** | 形状不同（1 研究 : N 候选），模式相同 |
| `uk_research_opportunity_day` | 同一机会同一天不重复研究 —— 这条是省钱的关键 |
| 不加 `@TableLogic` | 研究记录要留痕，不做逻辑删除 |

**回滚：** 三份 SQL 各自 `DROP TABLE`，不引用既有表，删了不影响生产链路。

---

## 七、Phase 1 准入门槛（你提的三个问题，落成可验收项）

> **这是本文档最重要的一节。** Phase 1 不是「把 Agent 接进业务」，而是**回答三个问题**。三个问题任何一个答不上来，都不进入 Phase 2。

### 准入口径

| 问题 | 否决线 |
|---|---|
| **Q1 能否稳定嵌入现有 Spring Boot** | 引入后现有 290 项默认组测试不再全绿，或应用起不来、或停机留僵尸线程 → **否决** |
| **Q2 现有任务体系能否与 Agent 会话正确衔接** | 会话无法复用到重试，或 checkpoint 读不回 → **否决**（意味着只能做「一次性研究」，那就不值当） |
| **Q3 多 Agent 是否有足够的质量提升** | 见下方「关于 Q3 的口径修正」 |

### Q1 验收项

| # | 验 | 通过标准 |
|---|---|---|
| 1.1 | 依赖树干净 | `dependency:tree` 无第二套 Web 栈；无 Jackson 版本冲突 |
| 1.2 | 应用能起 | `@SpringBootTest` 通过，且 `heron.agent.enabled=false` 时零副作用 |
| 1.3 | 现有测试不回归 | 默认组 **290 项**全绿 |
| 1.4 | 不占请求线程 | 一次 5 分钟研究期间 Web 请求 p99 不受影响 |
| 1.5 | 优雅停机 | 停应用时在跑的 Agent 收敛，不留线程 |

### Q2 验收项

| # | 验 | 通过标准 |
|---|---|---|
| 2.1 | 权限不靠 ThreadLocal | 在 Reactor 线程调一个带 `@SaCheckPermission` 的 Service，结果可预期 |
| 2.2 | 会话复用 | 同任务重试复用同一 `sessionId`，或能由 `findings` 重建且不重跑事实查询 |
| 2.3 | checkpoint 往返 | 写进 `heron_task_step.checkpoint` 后 attempt 递增能读回并续跑 |
| 2.4 | 幂等 | 同 `request_key` 重复触发不产生第二次模型费用 |
| 2.5 | 失败可读 | `ExceedMaxItersEvent` 与工具异常落成可读 `error_code` |
| 2.6 | 五道闸真停 | 轮数/工具次数/token/金额/时间 任一超限都真的停，且结果标 `partial` |

### 关于 Q3 的口径修正（重要）

plan.md §14 要求「每组至少 30 个样本」。但 Phase 0 盘点发现：**数据机会每天只产出 1~3 条**，攒 30 条要 10~30 天，而发布数据还没跑量。

所以 Q3 在 Phase 1 **不设「30 样本」门槛**，改为：

| 阶段 | Q3 口径 |
|---|---|
| Phase 1 | 只做**定性**：3~5 条真实机会，A/B/C 三组各跑一遍，人看能不能说出「C 比 A 好在哪」。**不追求统计显著** |
| Phase 7 之后（有播放量后） | 才做定量对照：样本 ≥ 30，用真实完播率/播放量裁决 |

**理由：** 没有播放量数据时比较 A/B/C，比的是「文案看着像不像样」，这个结论不可靠。与其在 Phase 1 假装证明，不如明确推迟。

**Phase 1 若发现 C 组明显不如 B 组，也允许结论是「不做多 Agent」**——这同样是有效产出。

---

## 八、Phase 1~8 开发任务

### Phase 1：HarnessAgent 技术 PoC（装配已完成，真实调用待做）

> **进度（2026-11-13）：** 依赖接入、装配、两个只读工具完成，299 项默认组测试全绿。
> **未做：** 真实模型跑一次研究。详见 `docs/reports/heron-phase1-agent-runtime.md`。


**做：**
1. 加依赖 `io.agentscope:agentscope-harness:2.0.4`，查依赖树。
2. 写 `AgentRuntimeConfig`（`@ConditionalOnProperty`，默认关）+ 手工装配 HarnessAgent（不用 workspace）。
3. 写 2 个只读工具（`query_trend_series`、`query_opportunity_detail`）做冒烟。
4. 用真实机会数据跑一次研究，产出结构化结论。
5. 验 §7 的 Q1（5 项）、Q2（6 项）。
6. 写 PoC 报告 `docs/reports/heron-phase1-agentscope-poc.md`。

**不做：** 不建表、不接业务、不改任何现有 Service、不开自动、不发布。

**测试要求：** 一个 `@SpringBootTest` 装配测试 + 一个工具 Schema 测试 + 一个预算闸单测。真实模型调用标记为手动/环境变量触发，不进默认组。

**验收：** Q1 五项全过 + Q2 六项全过 → 进 Phase 2；任一项不过 → 出报告说明，回到方案讨论。

**工作量估计：** 2~4 天（含踩 Reactor / Jackson / 上下文三个坑）。

### Phase 2：Agent 基础设施

**做：** 建 `heron_research_task` / `heron_agent_tool_call` 两表；`AgentBudget` 五道闸；工具调用账本；研究任务领取（租约/心跳）；配置项（`heron.agent.*`）；工具注册与参数校验。

**测试：** mysql 组的租约/幂等/预算到点停止；工具 Schema 与越界参数拒绝。

**验收：** 能创建研究任务、能查完整执行记录、预算到点真停、Agent 异常不影响现有生产任务。

### Phase 3：自主调查能力

> **进度（2026-11-16）：Phase 3 已全部完成。** 8 个只读工具齐备；三条预算闸收口；
> 调查结束判断（`submit_findings` 用 `returnDirect` 短路 ReAct 循环）；证据链（引用对质 + 未核实标记）；
> 结构化研究报告（`report_json`：decision / keyInsights / riskWarnings / 快照绑定 data_sha256）。
> 详见 `docs/reports/heron-research-tools-phase3.md`、`heron-research-stop-condition.md`、
> `heron-research-evidence.md`、`heron-research-report.md`。
> **下一步：** Phase 4 多 Agent 协作；或先做 Phase 5 内容决策中心，把报告接进业务。

> **进度（2026-11-16）：Phase 4 与 Phase 5 已落地。**
> Phase 5：`heron_content_decision` 存决策快照（不引用报告，因为报告会被重跑覆盖）、
> 人工采纳/否决（否决必填理由）、决策中心页面。
> Phase 4：独立核验员——另起会话与提示词、同一批只读工具，只对 `PRODUCE` 跑，
> 判定与研究员结论分开存以便识别冲突。未打开 `disableSubagents`，用业务层编排实现。
> 详见 `docs/reports/heron-content-decision.md`。
> **未做：** L1/L2/L3 三级策略、重复选题检测接入触发链路、核验链路的真跑验证。

**做：** 补齐 8 个只读工具；实现「调查结束判断」；证据收集；结构化研究报告；结果绑定 `FrozenContext`。

**验收：** 能从研究目标自主发起调查、能按中间结果改查询方向、能发现异常、能形成证据链、能在证据不足时停下并说明原因。

### Phase 4：多 Agent 协作

**做：** 按 §5 配四个角色；实现子 Agent 委派；实现独立事实核验（核验 Agent 只看证据 ID）；冲突识别。

**验收：** 核验 Agent 能独立访问原始证据；能识别不同 Agent 结论冲突；多 Agent 不强制全上。

### Phase 5：内容决策中心

**做：** 建 `heron_content_decision`；三级智能分析策略（L1/L2/L3）；决策结果入库；人工否决入口；决策历史；重复选题检测。

**验收：** 每个决策都有评分依据；关键结论能追溯证据；人工可修改/否决；不破坏现有候选池。

### Phase 6：自动生产接入

**做：** `PRODUCE` → `EditorialCandidatePool`（作为候选之一，**不取代**总编辑）；复用现有脚本生成、渲染、门禁；生产幂等。

**验收：** Agent 输出可生产；Frozen Context 完整；生产异常不影响研究任务状态。

### Phase 7：自动发布与效果反馈

**做：** 接入现有 `IPublishAdapter`；发布风险控制；效果回流到 `heron_news_metric`；历史表现进 Agent 依据。

**验收：** 只有经业务校验的任务能自动发布；**不因恢复任务重复发布**；发布数据回流可用。

### Phase 8：人工监督工作台

**做：** 前端决策中心、Agent 执行详情、异常处理、成本监控、A/B 实验。

**验收：** 能看全链路、能定位异常、能手动介入、能控费用。

---

## 九、API 设计建议

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/heron/researchTask/list` | `heron:research:list` | 研究任务列表 |
| GET | `/heron/researchTask/{id}` | `heron:research:query` | 详情（含工具调用、证据） |
| POST | `/heron/researchTask` | `heron:research:add` | 为机会创建研究（人工） |
| POST | `/heron/researchTask/{id}/rerun` | `heron:research:rerun` | 重跑（复用会话） |
| POST | `/heron/researchTask/{id}/cancel` | `heron:research:cancel` | 取消 |
| GET | `/heron/contentDecision/list` | `heron:decision:list` | 决策列表 |
| POST | `/heron/contentDecision/{id}/override` | `heron:decision:review` | 人工否决/改判 |
| GET | `/heron/agentUsage/summary` | `heron:research:query` | 成本汇总 |

**需要新增菜单与权限种子**（`004-menu.sql` 之后新增一个 `031-menu-research.sql`）。这是 Phase 2 的一部分，Phase 1 不做。

---

## 十、前端页面规划

现有 `views/heron/` 已有 8 个模块，**优先扩展，不重做**。

| 页面 | 动作 | Phase |
|---|---|---|
| `views/heron/editorial/index.vue` | 加一列「来源」：人工挑选 / 规则 / Agent 建议 | 5 |
| `views/heron/analytics/index.vue` | 加「机会 → 研究」入口 | 5 |
| `views/heron/research/index.vue` | **新增**：研究任务列表 + 详情（计划/步骤/工具调用/证据/结论/成本） | 8 |
| `views/heron/research/detail.vue` | **新增**：证据链、Agent 执行摘要、风险提示、人工否决 | 8 |
| `views/heron/monitor/agent.vue` | **新增**：今日费用、调用量、失败率、单条有效选题成本 | 8 |

**不展示完整思考链**（plan.md §13.2 原文），只展示可审计的执行摘要、调用记录、关键证据和决策依据。

---

## 十一、工作量与风险

### 工作量（粗估，单人）

| Phase | 天数 | 主要不确定性 |
|---|---|---|
| 1 PoC | 2~4 | Reactor 上下文、Jackson 双栈、harness 传递依赖 |
| 2 基础设施 | 4~6 | 租约/预算与现有模式的贴合度 |
| 3 自主调查 | 6~8 | 工具质量决定 Agent 质量，试错多 |
| 4 多 Agent | 6~10 | 子 Agent 稳定性、核验 Agent 的独立性 |
| 5 决策中心 | 5~7 | 与现有候选池的整合方式 |
| 6 生产接入 | 5~7 | 幂等与状态不串 |
| 7 发布回流 | 4~6 | 依赖真实发布数据 |
| 8 前端 | 8~12 | 页面多 |
| **合计** | **40~60 天** | —— |

**最可能出现「远超预估」的地方是 Phase 3**：工具写得好不好，直接决定 Agent 能不能自主调查。工具写得糙，Agent 会反复调同一个工具、拿不到有用数据，然后得出「没有发现」的假结论——而这个失败**看起来像模型不行**，实际是工具不行。

### 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 🔴 Reactor 上下文丢失 | 权限/租户/审计失效 | 权限前置校验 + 工具显式传参（见架构审查 §6） |
| 🔴 文件系统越界 | 安全事故 | 不用 workspace；Phase 2 前验证读不到 `application-prod.yml` |
| 🔴 费用失控 | 真金白银 | 五道闸 + 每日上限 + 同机会同日不重复研究 |
| 🟠 Agent 结论看起来合理但错 | 做出来的片子不可信 | 事实核验 Agent 独立回原始证据；证据 ID 进冻结上下文 |
| 🟠 与现有流水线抢资源 | 渲染变慢、任务堆积 | 研究槽位错开生产槽位 |
| 🟠 双 Agent 运行时 | 行为不可解释 | 明确 snail-ai 与本方案二选一或分工 |
| 🟡 机会产量低 | 每天只能研究 1~3 条，投资回报不明 | 先小规模验证，不铺开 |
| 🟡 发布数据未跑量 | 「值不值」无法证明 | 明确推迟定量评估到 Phase 7 之后 |
| 🟡 40~60 天工期 | 中途方向可能变 | 每 Phase 独立验收，可随时停 |

---

## 十二、一句话总结

**这不是「把 Agent 塞进 Heron」，而是「给已有的执行系统补一个会判断的前端」。**

现有系统的瓶颈从来不是「做不出来」，是「不知道该做什么」。Agent 要补的就是这一层，所以它必须只读、必须有证据、必须能被人工否决——一旦它开始能写库、能发布，「这套系统为什么这么做」就再也说不清了。

---

## 十三、本阶段声明

- 本文档**只是计划**。Phase 0 未修改任何业务代码、未改库结构、未动配置、未重启服务、未引入任何新依赖。
- **Phase 1 需要你单独批准。** 未获批准前不开始写 PoC 代码。
- 本文档中的版本号（`2.0.4`）、依赖坐标、API 签名来自对 `agentscope-ai/agentscope-java` 仓库与文档的实际读取；**未做过运行验证**，详见 `heron-agentscope-compatibility.md` §10。
- 工作量估计为粗估，误差可能很大（尤其 Phase 3）。
