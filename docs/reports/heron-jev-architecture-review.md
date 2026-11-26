# Heron AgentScope + JEV 架构评审

日期：2026-11-23。评审任务书：`docs/reference/root/jev.md`。
评审对象：当前 dev 分支实际代码（AgentScope Java 2.0.4 手工装配）+ JEV 引入提案。
证据口径：全部结论以本仓库源码与官方文档为准；官方 dev 文档与 2.0.4 发布件的能力差异单独列出。

## 0. 结论摘要

1. **现有架构保留。** L1/L2/L3 分级执行器 + 独立核验 + 确定性业务层的三层结构是合理的，
   jev.md 要求的大部分治理目标（越权防护、预算、证据链、幂等、状态分离）已经由 Heron 自有代码实现。
2. **引入 JevClient 本身没有问题，不应整体引入的是 jev.md 的全量套件。** JevClient + 3 个示例
   middleware 是 2.0.4 的稳定发布能力（v2 文档页明确标注 based on v2.0.4 release tag），
   加一个 `agentscope-extensions-jev` 依赖即可用，官方还提供 `agentscope-jev-spring-boot-starter`
   （Heron 可仿照现有手工装配直接用 JevClient.builder，绕开 starter 的基座版本问题）。
   但 jev.md 列举的完整治理套件（Trace Evaluation、Supervision、Context Compaction、
   Content Guardrail、Answer Refinement、Phase/Team Routing）与 OFF/SHADOW 运行模式是
   **dev 版文档能力**，2.0.4 稳定版里不存在（对应的正式构件是已发布的 2.1.0-SNAPSHOT，用户已授权采用，见结论 5）。
   按 jev.md 全量设计前必须先对 2.1.0-SNAPSHOT 构件核实能力面，不能对着文档清单接线。
3. **JEV 是外部付费判断 API（TypeSafe System One）**，引入即意味着：数据出境（提示词与业务数据
   发往 api.typesafe.ai）、新增故障域、每个判断点一次额外付费调用。这三项成本 jev.md 未计价。
4. 唯一值得观察的候选是 **Judge 辅助选题/核验（P1）**：先用现有账本做离线评估，发现系统性误判后
   再以自建 SHADOW 旁路收集对照数据，用自有数据校准阈值，且永远只允许「转人工」不允许「自动放行」。
5. **版本决策（用户已拍板）：JEV 用最新版，dev 能力可接受。** Maven Central 已发布
   `2.1.0-SNAPSHOT`（2026-11-14，harness 与 extensions-jev 同版），dev 文档对应的能力有正式构件可用，
   不必等稳定版。升级范围限 AgentScope 族内；基座 Boot 4.1.2 不动（官方 starter 钉 Boot 4.0.x，
   继续手工装配，见模块 pom 注释）。接入前必须对 2.1.0-SNAPSHOT 的实际构件核对 JEV 能力面——
   dev 文档与构件仍可能有出入，按构件接线，不按文档清单照单全收。

## 1. 事实基线

### 1.1 当前依赖与 JEV 实际能力面

`ruoyi-modules/ruoyi-heron/pom.xml`：`agentscope-harness`、`agentscope-extensions-model-openai`、
`agentscope-extensions-mysql` 均 2.0.4；**无任何 JEV 依赖**，本地仓库也无 JEV 构件。
`docs/plans/plan.md` 无 JEV 章节——JEV 是新提案，不是已批准设计的一部分。

| 能力 | 2.0.4 发布件 | dev 文档 |
|---|---|---|
| JevClient（Noul/Choice/Score 三类问题） | 有 | 有 |
| agentscope-jev-spring-boot-starter（配置式装配） | 有 | 有 |
| JevToolSelectionMiddleware（onReasoning 筛工具） | 示例代码 | 有 |
| JevModelRouterMiddleware（onAgent/onModelCall 路由） | 示例代码 | 有 |
| JevAutoModeMiddleware（onActing 守护工具，guardedTools/safetyThreshold/failOpen） | 示例代码 | 有 |
| Trace Evaluation / Supervision / Context Compaction / Content Guardrail / Answer Refinement / Phase·Team Routing | **无** | 有 |
| OFF / SHADOW 框架级运行模式 | **无** | 有 |

JevClient 传输面：HTTP POST `api.typesafe.ai/v1/systemone`，`TYPESAFE_API_KEY`/`JEV_API_KEY`，
模型 `jev-latest`，5 秒超时，429/529/5xx 重试。HITL 已 ALLOWED 的调用跳过 Jev。
官方边界原文：**「JEV 判断不授予工具权限，也不证明业务操作已经成功。」**
（https://java.agentscope.io/dev/zh/docs/harness/jev ；v2 文档同口径。）

### 1.2 Heron 已实现的治理面（源码证据）

- `ResearchAgentFactory`：逐项 disable 13 类危险能力（shell/文件系统/联网/记忆/记忆钩子/会话落盘/
  transcript/工作区上下文/@路径展开/动态技能/默认工作区技能/子 Agent/外部工具配置）；
  按会话建 Agent（单例共用上下文事故已发生过一次）；提示词版本 = 内容 sha256 前 12 位。
- `AgentscopeResearchAgent`：L1 单次调用不查工具、结论只落 OBSERVE/REJECT 从不上生产；
  L2=ReActAgent、L3=HarnessAgent；预算闸（工具 12 次 / 30 万 token / 300 秒 / 8 轮 / 单工具 10 秒）；
  轮数跑满标 partial；没按契约回复就不记结论，绝不替模型编造。
- 独立核验员：独立会话（`verify-<researchId>-<attempt>`）、独立提示词、账本 attempt 偏移 +1000，
  看不到研究员的推理过程，只看数据本身。
- 证据链：工具账本落 MySQL（`heron_agent_tool_call`）、沿途取数冻结快照（`data_sha256`）、
  `ResearchReport` 绑定落库快照、`EvidenceCollector` 汇总引用。
- 状态分离：AgentState 落 MySQL（`heron_agent_state`，迁移 032），业务状态在
  `heron_auto_slot`/生产表；transcript 与本地工作区全部关闭，多副本无本地目录依赖。
- 工具面：9 个只读查询 + `submitFindings`，全部走 mapper 固定查询，无自由 SQL。
- 业务闸：槽位幂等（日期+时刻）、lane 锁、候选锁、focus ID 必须在池内、7 天源去重、
  探测 ok 准入、模板 LOCK.json 哈希、`auto_enabled` 默认不允许自动发布、
  审稿重试上限 2、连续核不了上限 2。

## 2. 评审一：整体架构（任务书十问）

1. **HarnessAgent 适合做核心执行框架吗？** 适合做 L3 复杂调查的执行器，不适合做业务主链路。
   Heron 的生产主链（总编辑→策划→脚本→渲染→门禁）是确定性流水线 + 定点单次模型调用，
   换成 HarnessAgent 只会把幂等、恢复、审计交给模型。**保留现有分工。**
2. **ReAct/Harness 职责重叠？** 不重叠。等级只改执行器与预算，不改工具面；L2 不带计划模式/
   任务清单/子 Agent 那套开销，L3 才用。这正是 plan §12 的口径。**保留。**
3. **Subagents 有必要吗？** 现在没有。`disableSubagents` 是显式安全边界；研究+核验已经是
   「多 Agent」，用独立会话实现而非父子调度，账本与审计更清晰。**延后**，直到出现单会话
   装不下的真实调查任务。
4. **Supervisor 动态调度多 Agent？** **删除该提案。** 业务流程由 `NewsAutoService`/
   `ProductionRunner` 确定性编排，Agent 只在判断点被调用。让 Supervisor 调度业务步骤等于
   把业务授权和操作执行交给模型，违反任务书第六节的五分离。
5. **JEV 独立治理层还是分散嵌入？** 若引入，以 middleware/evaluator 形式嵌在具体判断点，
   不建「独立治理层」抽象——2.0.4 根本没有框架级治理层，自建一层就是任务书反对的
   「为统一接口造大量抽象」。
6. **JEV 与 HarnessAgent 边界清晰吗？** 官方边界清晰（判断≠授权≠成功证明）。落地要求：
   JEV 输出只进决策记录的参考字段（如 `decision_reason` 旁的对照记录），永不进授权路径。
7. **重复评估/重复消耗？** 已有三处语义评估：独立核验员、EditorialReviewer、QualityReviewer。
   再加 Judge 是第四处。引入任何新评估必须说明替代或削减哪一处现有调用，否则是纯增本。
8. **互评/无限循环？** 当前不存在：核验单轮、重试与核不了都有硬上限。JEV 的 Answer Refinement
   是最大的循环风险源，且与「结论不替模型改写」的现有契约冲突。**不引入。**
9. **Session 与业务状态重复建设？** 没有。AgentState 只存会话上下文，业务状态在业务表；
   会话落盘与 transcript 已关。**保留现状。**
10. **会过度复杂吗？** 全量引入 JEV 会：当前装配约 500 行配置+工厂，边界逐项可指认；
    叠加外部判断层后每个判断点都有「本地结论 + 外部结论 + 冲突处理」三态，复杂度翻倍而
    收益未证。**按第 3 节逐项裁剪。**

### 保留 / 合并 / 延后 / 删除

| 设计 | 处置 | 理由 |
|---|---|---|
| L1/L2/L3 分级 + 预算闸 + 账本 + 快照证据链 | 保留 | 已实现且经过真实运行验证 |
| 独立核验员（独立会话/提示词/账本偏移） | 保留 | 即任务书要的「独立事实核验」，无需 JEV 重做 |
| 确定性业务编排 + 幂等 + 人工锁 | 保留 | 业务事实与执行的唯一承载 |
| Supervisor 动态调度、Phase/Team Routing | 删除 | 与确定性编排重复且越界 |
| Subagents、Context Compaction、Supervision | 延后 | 无真实需求；dev 能力不可依赖 |
| Judge 辅助选题/核验 | 延后到 P1 | 先离线评估证明增量价值（见第 7 节） |
| Tool Selection / Model Routing / Content Guardrail / Answer Refinement / Tool Guard（作为闸） | 不建议 | 见第 3 节 |

## 3. 评审二：逐项能力评估

| JEV 能力 | 2.0.4 可用 | 评级 | 理由 |
|---|---|---|---|
| Judge | 有（JevClient Choice/Score） | **P1** | 选题价值与核验辅助有真实需求，但现有总编辑+核验员已覆盖。先 SHADOW 对照收集差异、用自有标注校准阈值，证明增量后再谈有限约束。外部付费+数据出境，必须计价。 |
| Trace Evaluation | 无（dev） | **P2** | Heron 已有完整轨迹（账本+快照+报告），离线批评估可用现有模型做，不必为每条轨迹付外部 API。且轨迹评估不能撤销已发生的工具调用，主链价值有限。 |
| Tool Guard | 示例 middleware | **不建议** | 工具面全只读、危险能力逐项关闭，不存在「需要外部判断保护的危险工具」。示例默认 `failOpen` 与 Heron fail-closed 相反；若做 SHADOW 观察必须显式 failOpen=false。JEV 判断不授予权限，业务授权永远在 sa-token+标志位。 |
| Supervision | 无（dev） | **P2** | 长任务监督现由预算+超时+partial 标记承担；「建议」不能证明任务完成，持续监督没有对应的业务动作。 |
| Tool Selection | 示例 middleware | **不建议** | 工具面只有 10 个，选择难度低；onReasoning 每轮一次外部调用，token 没省、成本翻倍、延迟加 5s+。 |
| Content Guardrail | 无（dev） | **不建议** | 与 EditorialReviewer/QualityReviewer/事实核验重复；外部护栏不懂 Heron 口径（示例数据标注、因果边界、快照绑定），通用护栏只会产生误杀与漏放。 |
| Context Compaction | 无（dev） | **P2** | 单次调查 ≤8 轮 ≤12 次工具调用，上下文有限；压缩有丢证据风险，而证据链靠的是快照冻结，不是上下文。 |
| Answer Refinement | 无（dev） | **不建议** | 无限修订风险；与「没按契约就不记结论、绝不替模型编一条」的现有契约直接冲突。 |
| Model Routing | 示例 middleware | **P2** | 当前单模型配置（heron.model.*）；L1/L2/L3 分级已经是零成本确定性「路由」。每次调用前先问一次外部 API 该用哪个模型，路由成本高于收益。 |
| Phase / Team Routing | 无（dev） | **不建议** | 业务阶段编排属确定性代码（ProductionRunner/槽位状态机），交给外部判断 API 违反五分离。 |

**P0 = 0 项。** 首期没有任何「必须具备」的 JEV 能力：现有系统的安全与证据边界已闭环，
JEV 能补的只有「判断质量」，而判断质量的增量尚未被任何数据证明。

注：表中「无（dev）」指 2.0.4 没有；这些能力预期在已发布的 2.1.0-SNAPSHOT 中提供（用户已授权采用）。
可用性不再是障碍后，上表评级依据的是**重复建设与投入产出**，不是版本——升级后这些判断依然成立，
接入前按第 7 节先 SHADOW 证明增量。

## 4. 评审三：完整业务链路推演

### 4.1 当前真实链路（源码核对）

```
采集/探测(确定性) → 快照+机会评分(确定性) → [研究线: L1/L2/L3 调查 → 独立核验 → 证据报告]
→ 建池(确定性: 准入/去重/冻结 poolHash) → 总编辑(LLM, 人工锁作提示) → 线内编辑(LLM 策划)
→ claim(确定性: lane锁+候选锁+占位 source_kind) → 脚本(单次模型调用) → 事实审稿(模型)
→ 质量审稿(模型) → 渲染(确定性, 模板 LOCK 哈希) → 质量门禁(确定性) → 发布确认(人工/标志位)
→ 发布(幂等) → 效果回流(确定性) → 参考卡+样本门槛(确定性) → 人工策略调整
```

### 4.2 对任务书链路的修正

- **Trace Evaluation 与 Judge 不进主链。** 放在离线/旁路：主链每个同步判断点加 5s+ 外部调用，
  300 秒调查窗口会被显著侵蚀；且两者都不能撤销已发生的操作，主链拦截价值低。
- **Content Guardrail 与「Heron 业务规则验证」合并**为现有审稿双闸+确定性门禁，不加第四道。
- **可并行**：不同候选的研究调查（预算已 per-attempt 隔离）；渲染与下一槽位决策。
- **必须串行**：claim → 脚本 → 审稿 → 渲染 → 门禁 → 发布（幂等链，乱序即重复生产风险）；
  核验必须在研究结论之后（依赖结论）。
- **额外模型调用点**（现状已存在，须计价）：总编辑、线内策划、选源、脚本、事实审稿、质量审稿、
  研究循环、核验循环。JEV 每加一个判断点就是第九、第十处。
- **人工介入点**：发布确认（auto_enabled 默认关）、策略锁/解锁、异常槽位、核验冲突。
- **证据保存点**（已具备）：pool_json/poolHash、快照 data_sha256、工具账本、研究报告、
  模板 LOCK 哈希、成片 ffprobe 与抽帧。
- **必须确定性代码而非 LLM 的环节**：focus ID 校验、源准入与 7 天去重、槽位幂等、模板收窄
  （visualMode）、发布授权、效果聚合的样本门槛。这些现在全部是代码，**不要让 JEV 碰**。

### 4.3 推荐执行流程

保持 4.1 现状不变；仅在研究/选题判断点旁挂**异步 SHADOW 记录**（若第 7 节阶段 1 启动），
主链零阻塞、零授权变化。

## 5. 评审四：安全性与可靠性

| 风险 | 现状 | 结论 |
|---|---|---|
| Prompt Injection / 间接指令注入 | 工具结果来自外部源（USGS/DEV/东财），会进上下文；缓解=只读工具面+无执行能力+结论契约+独立核验 | 残余风险是污染判断文本，靠核验与人工审兜底。**JEV 不解决注入**，护栏本身也可被注入 |
| Agent 越权 / 自由 SQL / Shell / 文件系统 | 13 类能力逐项 disable；工具全走 mapper 固定查询 | 已达成，无需 Tool Guard |
| 跨租户数据泄露 | 当前单租户运营数据；工具查询不带租户上下文 | 若未来多租户，必须在工具层加租户过滤——这是 Heron 代码的责任，不是 JEV 的 |
| 重复创建生产任务 / 重复发布 | 槽位幂等+lane 锁+候选锁+发布幂等键+auto_enabled 默认关 | 已达成 |
| Agent 误判任务已完成 | submitFindings 只记结论；生产成功由 receipt/versionId/ffprobe 证明 | 已达成，五分离落地 |
| JEV 评估失败被当 PASS | 未引入 | 若引入：ERROR/INCONCLUSIVE/超时/预算耗尽一律记「未定」转人工，代码里显式分支，禁止默认放行 |
| JEV 超时阻塞主任务 | 未引入 | JevClient 5s 超时+重试最坏 15s+/点；SHADOW 必须异步旁路，不进 block 路径 |
| 工具成功但结果未持久化 | 账本 Hook 落库+快照冻结 | 已达成 |
| 上下文压缩丢证据 | 未启用压缩；证据在快照不在上下文 | 已达成，保持不启用 |
| 多副本 Session/Workspace 不一致 | 状态落 MySQL，transcript/工作区关闭，无本地目录 | 已达成 |
| 长任务中服务重启 | ProductionRecoveryService+槽位状态机；研究不可续跑标 partial | 可接受；恢复后重复外部操作由发布幂等键挡住 |

**五分离对照**：语义判断=LLM/（未来 SHADOW 的 JEV）；业务授权=sa-token+源级/发布标志位；
业务验证=确定性门禁；操作执行=流水线代码；操作成功证明=receipt+哈希+ffprobe。
JEV 只允许出现在第一格。

**新增风险（引入 JEV 才有）**：业务数据出境到 TypeSafe（机会快照、候选标题、研究结论都会进
判断提示词）；外部 API 密钥管理；外部服务不可用时的降级口径。这些在 P1 评审时必须先过
数据合规口径，再谈技术接入。

## 6. 评审五：评估体系与成本控制

### 6.1 指标与数据来源（大部分已有落点）

| 指标 | 数据来源 | 现状 |
|---|---|---|
| 单任务 token / 工具次数 / 延迟 | heron_agent_tool_call 账本 + ResearchReport | 已有 |
| 事实核验通过率 / 冲突数 | ResearchVerification + 审稿记录 | 已有 |
| 选题判断准确率 / 新颖性 | 需人工标注金标集（抽样槽位决策） | 缺，需建 |
| 工具选择/结果使用正确率 | 账本+快照离线评（现有模型批评） | 可做 |
| 内容审核通过率 / 生产成功率 / 发布成功率 | 槽位状态机 + works + receipts | 已有 |
| 最终视频表现 | heron_news_metric + MetricInsightAnalyzer（样本门槛） | 已有 |
| 单个有效选题/有效发布成本 | 账本 token × 单价 ÷ 有效产出 | 可算 |
| 人工介入比例 | 策略覆盖记录 + 发布确认记录 | 已有 |

### 6.2 四臂对照实验

- **A 臂**：单次 LLM 调用（现新闻线口径）；**B 臂**：ReActAgent（L2）；**C 臂**：HarnessAgent（L3）；
  **D 臂**：C + JEV SHADOW→有限约束。
- 同一批冻结机会快照回放（data_sha256 保证可比），每臂 ≥30 个机会；金标 = 人工标注的
  「值得做/不值得做 + 事实要点」。
- 领先指标：核验通过率、选题准确率（对金标）、单任务成本、延迟、人工介入率。
  滞后指标：发布后表现（需数周样本，遵守参考卡样本门槛，不足不下结论）。
- **D 臂只有 SHADOW 数据显示增量价值后才开**；否则不为「评估环节更多」付钱。
- 北极星：**每 1 元模型费用产出多少个经过验证、值得发布且实际表现良好的视频选题。**
  调用次数、Agent 数量、评估环节数都不是质量指标。

## 7. 评审六：上线方式

前提修正：**2.0.4 没有 OFF/SHADOW 框架开关**；dev 文档描述的运行模式预期在 2.1.0-SNAPSHOT 提供
（用户已授权采用）。接入时先对构件核实：官方自带 OFF/SHADOW 就用官方模式；构件里没有的，
SHADOW 用 Heron 自建小旁路（异步调 JevClient、只记录差异、不影响主链）。
无论哪种，都不要按文档假设组件自带运行模式——以构件为准。

| 阶段 | 动作 | 进入条件 | 退出/回滚 |
|---|---|---|---|
| 0 观察（现在） | 不引入 JEV。用现有账本+报告做离线评估，建金标集 | — | — |
| 1 SHADOW | JevClient 旁路记录选题/核验对照，独立日预算上限，异步不阻塞 | 离线评估显示现有判断有系统性误判；数据出境口径已确认 | 配置开关关闭即回阶段 0，无迁移残留 |
| 2 校准 | 用自有标注校准 Choice/Score 阈值；ERROR/超时≠PASS | SHADOW 样本 ≥ 数百条且分歧可归因 | 阈值不可校准则停 |
| 3 有限约束 | JEV 否决只触发「转人工」，永不自动放行；发布授权永在 Heron | 校准后误杀/漏放率可接受 | 随时降级回 SHADOW |

组件限制（任务书第八节逐条确认）：Trace Evaluation 不能撤销已发生的工具调用；
Supervision 建议不能证明任务完成；Tool Guard 不能替代业务授权；Judge 阈值必须自有数据校准；
ERROR/INCONCLUSIVE/超时/预算耗尽不得默认 PASS——以上全部成立，且前三条在 Heron 语境下
意味着这些组件即使可用也不进主链。

## 8. 优化要求对照（任务书第九节）

1. 复用官方能力：✓ 执行器/状态存储全用官方；JEV 若引入只用 JevClient，不自造 Judge。
2. 不重造 Agent 循环/Evaluator：✓ 现有循环全官方；离线评估用现有模型客户端。
3. 不为统一接口造抽象层：✓ 不建「JEV 治理层」；SHADOW 旁路是一个类级别的封装。
4. 不重复实现已有业务能力：✓ 护栏/路由/监督均不引入（第 3 节）。
5. 短期上下文不当业务存储：✓ 状态早已分离。
6. JEV 不成强制额外成本：✓ P0=0，主链零 JEV 调用。
7. 多 Agent 不成固定流水线：✓ 研究+核验按等级动态起，业务编排确定性。
8. Agent 无生产库/发布权限：✓ 只读工具面+submitFindings。
9. 不为 JEV 升级无关技术栈：✓ 升级限 AgentScope 族（2.0.4 → 2.1.0-SNAPSHOT，用户已授权）；基座 Boot 4.1.2 不动，继续手工装配。
10. 保持现有流程稳定：✓ 主链零改动；本次评审唯一的代码变更是验收暴露的数据集取窗饿死修复，与 JEV 无关。

## 9. 最终建议

- **版本（用户已拍板）**：升级到已发布的 2.1.0-SNAPSHOT（Maven Central，2026-11-14），dev 能力可接受。
  升级限 AgentScope 族，基座不动；接线前对构件核实 JEV 能力面与 OFF/SHADOW 模式，不按文档清单照单全收。
- **接入顺序**：先建评估金标集（阶段 0，零风险）→ Judge SHADOW 对照（P1，先过数据出境口径）→
  校准后才谈有限约束。其余能力按第 3 节评级，不因「可用了」就全接。
- **永远不做**：Supervisor/Phase 调度业务、Tool Guard 当授权闸、Answer Refinement、
  让任何 JEV 输出自动放行发布。

### 引用

- 官方稳定版 JEV 文档（标注 based on v2.0.4 release tag）：https://java.agentscope.io/v2/en/jev
- 官方 dev JEV 文档：https://java.agentscope.io/dev/zh/docs/harness/jev
- 源码：`ruoyi-modules/ruoyi-heron/src/main/java/org/dromara/heron/agentscope/`
  （AgentRuntimeConfig / ResearchAgentFactory / AgentscopeResearchAgent / HeronResearchTools）
- 业务闸：`NewsAutoService` / `EditorialDecisionService` / `StrategyReferenceService` /
  `NewsProductionPipeline`
- 设计口径：`docs/plans/plan.md` §6.3（安全边界）、§12（L2/L3）