# heron `agentscope` 目录结构设计

**日期**：2026-11-27
**状态**：设计已确认，等待执行指令
**范围**：`ruoyi-modules/ruoyi-heron/src/main/java/org/dromara/heron/agentscope`、相关测试、`config/` 两个配置类

---

## 0. 一句话

`agentscope` 下 18 个类平铺，Agent 执行者、工具面、研究记录、装配配置混在根目录。
本设计按**角色**切成 `agent` / `toolkit` / `research` 三个子包，两个 `@Configuration` 归入现有 `heron.config`；
**不改类名、不改方法签名、不改行为**。

与 `2026-11-27-heron-module-structure-design.md` 的关系：该文档声明本次不动 `agentscope`，并把
`TrendLearningAgent` 归入 `shared/research`；本设计覆盖这两点，其余不变。

---

## 1. 已核实的事实

| 事实 | 依据 |
| --- | --- |
| `agentscope/` 平铺 18 个类 | `ls agentscope/*.java \| wc -l` |
| `agentscope/agent/` 是空目录，git 未跟踪 | `git ls-files` 无该目录内容 |
| 另一位 Agent 执行者 `TrendLearningAgent` 住在 `service/impl/shared/research` | 包声明 |
| `ResearchGrade` 被 `service/IResearchAgent` 和 3 个 service 类引用 | import 检索；使用枚举常量与 public `of`/`parse`，不依赖包私有构造器 |
| 18 个测试文件引用 agentscope 类 | 15 个在 `test/agentscope` 同包，另 3 个在 `service/impl` |
| 包私有成员 7 个，只有 `AgentscopeResearchAgent.readSummary` 被同包测试直接调用 | 源码扫描；测试随类迁入同一子包即可，不必提 public |
| 所有顶层类型已是 public；`ResearchFindings` / `ResearchVerification` 为隐式 public 无参构造 | 源码 |

---

## 2. 目标结构

```
org.dromara.heron.agentscope/
├── agent/       真正跑模型会话的执行者与工厂
├── toolkit/     Agent 可调用的受控工具面
└── research/    研究记录、账本、预算、核验等框架无关载体

org.dromara.heron.config/
└── (现有 4 个配置类) + AgentRuntimeConfig、JevConfig
```

三个子包各自回答一个角色问题：**谁在跑模型**、**模型能调用什么**、**跑完留下什么记录**。
根目录零残留；`@Configuration` 回到项目既有的 `config` 包，不再破例。

---

## 3. 类归属映射（19 个）

### agent/（5）

`AgentscopeResearchAgent`、`DirectorRepairAgent`、`ShotDirectorAgent`、
`TrendLearningAgent`（从 `service/impl/shared/research` 迁回）、`ResearchAgentFactory`

### toolkit/（4）

`HeronResearchTools`、`ResearchVerificationTool`、`ShotRenderTools`、`TrendLearnTools`

### research/（8）

`ResearchBudget`、`ResearchFindings`、`ResearchGrade`、`ResearchReport`、
`ResearchSnapshots`、`ResearchVerification`、`ToolCallLedger`、`EvidenceCollector`

### config/（2）

`AgentRuntimeConfig`、`JevConfig`

---

## 4. 测试归属映射

| 目标位置 | 文件 |
| --- | --- |
| `test/agentscope/agent/` | `AgentscopeResearchAgentTest`、`AgentResearchRealRunTest`（从 `service/impl/agent` 迁入）、`VerifierDisagreeRealTest`、`VerifierProbeSupport` |
| `test/agentscope/toolkit/` | `HeronResearchToolsTest`、`ResearchVerificationToolTest`、`ShotRenderToolsReferenceCardTest`、`TrendLearnToolsTest` |
| `test/agentscope/research/` | `EvidenceCollectorTest`、`ResearchBudgetTest`、`ResearchGradeTest`、`ResearchReportTest`、`ResearchSnapshotsTest`、`ToolCallLedgerTest` |
| `test/config/` | `AgentRuntimeConfigTest`、`JevConfigTest` |
| 原地只改 import | `OpportunityFactsTest`、`TrendLearnWiringTest` |

测试包与其被测类所在包保持一致，包私有成员无需扩大可见性。

---

## 5. 迁移规则（必须逐条遵守）

1. **一次只迁一个包**，一包一笔提交；只改 `package` 与 `import`，不改类名、方法签名、字段与行为。
2. 顺序按依赖从少到多：`config` → `research` → `toolkit` → `agent`。
3. 测试文件随被测类进入同名子包；包私有成员不扩大可见性，编译暴露的跨包访问按最小原则处理。
4. 每迁完一包跑 `mvn -pl ruoyi-modules/ruoyi-heron -am -q test-compile`，失败立即回退该包。
5. `service/impl/agent` 测试目录迁空后删除。
6. 不自动提交、不自动推送；每笔提交前由用户确认。
7. 每步完成后同步更新本文档「执行记录」一节。

---

## 6. 明确不做

- 不拆类、不合并类、不抽接口——那是后续独立任务。
- 不动 `domain` / `controller` / `mapper` / `service` 接口。
- 不把 `ResearchGrade` 迁去 `domain`（当前使用面可接受；后续 service 使用再增长时另议）。
- 不改提示词、工具协议、并发与预算语义。

---

## 7. 执行记录

| 日期 | 动作 | 提交 |
| --- | --- | --- |
| 2026-11-27 | 设计确认，等待执行指令 | — |
| 2026-11-27 | config → research → toolkit → agent 四包迁移完成；`mvn -pl ruoyi-modules/ruoyi-heron -q test-compile` 通过 | 待确认提交 |
