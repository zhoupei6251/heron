# Harness 能力清单与中文文档核对

**日期：** 2026-11-13
**版本：** AgentScope Java 2.0.4（我们锁的版本）
**目的：** Phase 2 开工前，确认「我们关掉了哪些 Harness 招牌能力」以及它们在 2.0.4 里到底存不存在。

---

## 一、中文文档 URL 核对

站点 `https://java.agentscope.io`，全部实测 200；对照路径（故意写错）返回 404，说明不是 SPA 兜底。

| 页面 | URL | 状态 |
|---|---|---|
| Harness 架构 | `/v2/zh/docs/harness/architecture` | 200 |
| 上下文压缩 | `/v2/zh/docs/harness/compaction` | 200 |
| 工作区 | `/v2/zh/docs/harness/workspace` | 200 |
| 记忆 | `/v2/zh/docs/harness/memory` | 200 |
| 文件系统 | `/v2/zh/docs/harness/filesystem` | 200 |
| 沙箱 | `/v2/zh/docs/harness/sandbox` | 200 |
| 子 Agent | `/v2/zh/docs/harness/subagent` | 200 |
| 技能 | `/v2/zh/docs/harness/skill` | 200 |
| 计划模式 | `/v2/zh/docs/harness/plan-mode` | 200 |
| Channel | `/v2/zh/docs/harness/channel` | 200 |
| 能力裁剪 Capability Grant | `/v2/zh/docs/building-blocks/capability-grant` | 200 |
| 权限系统 | `/v2/zh/docs/building-blocks/permission-system` | 200 |
| 上下文与 AgentState | `/v2/zh/docs/building-blocks/context` | 200 |
| Middleware | `/v2/zh/docs/building-blocks/middleware` | 200 |
| 上生产 | `/v2/zh/docs/others/going-to-production` | 200 |

---

## 二、纠正一个我此前的判断

我一度认为「`distributedStore` / `skillFilter` 这些东西 2.0.4 里没有，是更新版本才加的」。
**错了**——那次是因为 `javap` 的 classpath 用了 `~` 展开出来的 git-bash 路径，Java 读不到，误判成「方法不存在」。

把 classpath 转成 Windows 路径后，`HarnessAgent$Builder` 有 **184 行方法签名**，其中包含：

```
distributedStore  stateStore  filesystem  memory  subagent  teamsMode
skillsEnabled  skillFilter  skillRepositories  enableSkills
enablePlanMode  enableTaskList  enableMetaTool
compaction  maxContextTokens  toolResultEviction
transcriptStore  permissionContext  stopOnReject
middleware  hook  hooks  maxRetries  fallbackModel  modelResolver
enableAgentTracingLog  asyncToolRegistry  messageBus
```

**结论：招牌能力全都在，是我们主动关掉的，不是版本没有。**

---

## 三、当前实际用到 vs 可用

| 能力 | Builder 入口 | 我们现在的状态 |
|---|---|---|
| 模型 / 工具 / 轮数 | `model` `toolkit` `maxIters` | ✅ 用了 |
| 工作区人格 | `workspace` `additionalContextFile` | ❌ `disableWorkspaceContext` |
| 长期记忆 | `memory` `environmentMemory` | ❌ `disableMemoryTools` `disableMemoryHooks` |
| 子 Agent 编排 | `subagent` `subagents` `teamsMode` | ❌ `disableSubagents` `disableDynamicSubagents` |
| 技能 | `skillsEnabled` `skillFilter` `skillRepositories` | ❌ `disableDynamicSkills` `disableDefaultWorkspaceSkills` |
| 文件系统 / 沙箱 | `filesystem` + sandbox 扩展 | ❌ `disableFilesystemTools` |
| shell | — | ❌ `disableShellTool` |
| 联网 | — | ❌ `disableWebTools` |
| 计划模式 | `enablePlanMode` | ❌ 未开 |
| 任务列表 | `enableTaskList` | ❌ 未开 |
| transcript | `transcriptStore` | ❌ `disableTranscript`（为隔离） |
| 状态存储 | **`stateStore` / `distributedStore`** | ❌ 未配置 → 落到 `~/.agentscope`（隔离问题的根源） |
| 上下文压缩 | `compaction` `maxContextTokens` `disableCompaction` | ⚠️ 默认值，未调 |
| 工具结果驱逐 | `toolResultEviction` | ⚠️ 默认值，未调 |
| 重试 / 降级模型 | `maxRetries` `fallbackModel` `modelResolver` | ❌ 未配 |
| 权限 / 拒绝即停 | `permissionContext` `stopOnReject` | ❌ 未配 |
| 可观测 | `enableAgentTracingLog` `hook` `hooks` `middleware` | ❌ 未用 |

---

## 四、值得在 Phase 2 接的三块

### 4.1 状态存储：把隔离问题从「改名」升级为「存对地方」

文档 `going-to-production` 给了三条路：

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);
// 或 MysqlDistributedStore.create(dataSource);   ← 我们已经有 MySQL
// 或 OssDistributedStore.create(ossClient, bucket, prefix);  ← 我们已经有对象存储
HarnessAgent.builder().distributedStore(store)...
```

对应扩展包 `io.agentscope:agentscope-extensions-mysql`、`-redis`、`-oss`，2.0.4 都发布了。

**这能一次性解掉：**
- 会话按 `researchId` 隔离（不用我现在的「每进程唯一 agent 名」这种绕法）；
- `~/.agentscope/state/` 无限累积；
- pod 重启后能接着上次跑。

### 4.2 预算与上下文：真正需要调的两个参数

- `maxContextTokens`：研究跑多轮时上下文会爆，现在完全没设；
- `compaction` / `toolResultEviction`：长任务下工具返回值会把上下文吃掉，默认值没验证过。

Phase 2 的「预算闸」不该只做一个外部计数器，应该把这两项一起定下来。

### 4.3 可观测：middleware / hook

我们迟早要记「第几轮、调了哪个工具、耗时多少」。`middleware` 和 `hook` 就是官方留的钩子，
比起在工具内部埋点更干净——尤其是 Phase 2 要建 `heron_agent_tool_call` 表。

---

## 五、明确不建议开的

| 能力 | 原因 |
|---|---|
| shell / 文件系统 / 沙箱 | plan.md §6.3 明令禁止；我们也不需要 |
| 工作区人格（`workspace` + `AGENTS.md`） | 要读目录；除非确认与安全边界兼容 |
| 技能仓库 / 技能孵化（`enableSkillCurator`） | 会自己改技能文件，属于文件系统访问 |
| Channel（钉钉/飞书等） | 与内容生产无关 |
| 子 Agent | 不是不做，是**放到 Phase 4**——单 Agent 还没证明不够用 |
