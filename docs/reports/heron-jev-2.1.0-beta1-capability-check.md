# AgentScope 2.1.0-BETA1 构件能力面核实（JEV）

日期：2026-11-23
对应结论：`docs/reports/heron-jev-architecture-review.md`（第 24–26 行、第 251–252 行要求「接线前对构件核实 JEV 能力面，不按文档清单照单全收」）

这份文档只回答一个问题：**已发布的构件到底提供了什么**，以及它对报告里的每个阶段决定意味着什么。不含新的架构决定。

## 核实方法

| 动作 | 结果 |
| --- | --- |
| `https://repo1.maven.org/maven2/io/agentscope/agentscope-extensions-jev/2.1.0-BETA1/agentscope-extensions-jev-2.1.0-BETA1.pom` | HTTP 200，构件已发布 |
| 下载 `agentscope-extensions-jev-2.1.0-BETA1.jar`（504 KB） | `D:/tmp/jev/`（本机临时目录，不入库） |
| 解包列顶层类 | 68 个 |
| 扫描类常量池里的 `SHADOW` / `shadow` | 命中 8 个类 |
| `javap` 看 `agentscope-core` 的 hook 事件 API | 新抽象底座 + 旧事件类并存 |

## 构件里有什么

按报告关心的话题归类（括号内是类名）：

- **模式与旁路**：`JevExecution$Mode`（常量池含 `SHADOW`）、`JevAutoModeMiddleware`、`JevTraceEvaluationMiddleware`（含 `SHADOW`）、`JevResponseMiddleware` + `$Capture`（含 `shadow`）
- **模型路由**：`JevModelRouterMiddleware`、`JevRouteCatalog`、`JevCandidateSelector`、`JevSelectionSupport`
- **工具面**：`JevToolSelectionMiddleware`、`JevGuardrail`、`JevEvidenceProcessor`、`JevEvidenceTool`、`JevConfirmedCalls`
- **调度与督导**：`JevSupervisor`、`JevSupervisionMiddleware`、`JevTaskSupervisor`、`JevPhaseRouting`、`JevStageRouter`
- **评估与追踪**：`JevEvaluationRunner`、`JevEvaluator`、`JevTraceEvaluator`、`JevTrace`、`JevTraceMetric`、`JevTraceMetrics`、`JevTraceRunner`、`JevJudge`、`Answer`/`ChoiceAnswer`/`Evaluator`/`Question`/`ScoreAnswer`/`NoulAnswer`
- **上下文**：`JevContextCompactor`、`JevContextPlanner`、`JevMemoryGate`、`JevDraftPipeline`、`JevRetryPolicy`
- **其它**：`JevClient`、`JevKnowledge`/`JevKnowledgeTool`/`JevRag`、`JevBrowser*`、`JevPlaywrightSession`、`JevCodeReview*`、`JevTeamPlanner`、`JevCustomerSupport`

## 对报告决定的影响

| 报告决定 | 构件是否支持 | 结论 |
| --- | --- | --- |
| 阶段 0：先用现有账本做离线评估、建金标集 | 有 `JevEvaluationRunner`/`JevEvaluator`/`JevTraceEvaluator` 可用 | 不变。阶段 0 的口径是「不引入 JEV 运行时」，金标集与离线评估仍用现有报告产出；构件侧的评估器是阶段 1 之后的选项，不是阶段 0 的前提 |
| 阶段 1：SHADOW 旁路（需先过数据出境口径） | 有构件基础：`JevExecution$Mode` 带 `SHADOW`、`JevTraceEvaluationMiddleware` | 不变，且可行性从「文档说有」升级为「构件里确有」。仍卡在数据出境口径与开放平台凭据，不动代码 |
| 阶段 2：校准阈值 | 依赖阶段 1 的旁路数据 | 不变 |
| 阶段 3：有限约束（只许转人工，永不自动放行） | 有 `JevGuardrail`/`JevToolSelectionMiddleware` | 不变 |
| 永远不做：Supervisor/Phase 调度业务、Tool Guard 当授权闸、Answer Refinement | **构件里都有**（`JevSupervisor`/`JevPhaseRouting`/`JevResponseMiddleware`/`JevToolSelectionMiddleware`） | 报告判断成立：这是「重复建设与投入产出」的选择，不是版本能力缺失。以后有人提「JEV 有现成的」，答案是「有，但口径上不用」 |

## 2.1.0-BETA1 对本仓库代码的实际影响

- 升级范围：`ruoyi-modules/ruoyi-heron/pom.xml` 里三个 AgentScope 构件 `2.0.4` → `2.1.0-BETA1`；`AgentscopeResearchAgent.AGENT_VERSION` 同步。基座 Boot 4.1.2 未动。
- 编译与测试：`mvn -pl ruoyi-modules/ruoyi-heron -am -DskipTests compile` BUILD SUCCESS；`mvn -o -pl ruoyi-modules/ruoyi-heron test` 707 例全过。构件已进本地 m2，后续可离线构建。
- **一处需要盯的弃用**：`ToolCallLedger` 用了 `io.agentscope.core.hook.PostReasoningEvent` / `PostActingEvent`，2.1.0-BETA1 把它们标为「已过时且待删除」。核实结果：这两个类仍在（final 类，构造器与 `getReasoningMessage()` / `getToolResult()` 载荷都在），新抽象底座 `ReasoningEvent`/`ActingEvent`/`HookEventType`（含 PRE_CALL…ERROR 全套枚举）与 `LegacyHookDispatcher`、`JsonlTraceExporter` 是并行的新模型。**本轮不迁移**：迁移不是 drop-in（结果载荷只在旧 Post* 子类上），且弃用不等于移除。等上游真正移除前再动，届时把 ledger 迁到新底座并补一条回归。
- 行为面未变：旧事件类仍由同一套 dispatcher 分发，工具账本的记录语义不变。
