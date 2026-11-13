# AgentScope Java 2.0 概览

> 上游：<https://java.agentscope.io/v2/zh/docs> · 快照：`_upstream/docs.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游原文标题：What Is AgentScope 2.0?

2.0 有三个主题，各自对应一个具体问题。上游同时声明：2.0 力求与 1.x 保持兼容，但**确实存在 API 级破坏性变更**，迁移细节见 [V1 迁移指南](https://java.agentscope.io/v2/zh/docs/change-log.md)。

## 一、Harness 工程 —— 支撑长周期复杂任务

裸 ReAct 循环只能处理单次推理。真实任务可能跑几小时、积累大量状态、依赖跨轮次持续存在的能力。Harness 提供这部分工程基建：核心推理循环不变，能力按需叠加。

| 能力 | 要点 | 上游 |
| - | - | - |
| 自进化与技能库 | 成功的做法自动固化为 `workspace/skills/` 下的 Markdown 技能，每轮按需加载、跨会话共享 | [skill.md](https://java.agentscope.io/v2/zh/docs/harness/skill.md) |
| 分层记忆 | 三级：对话上下文、Agent 自维护的 `MEMORY.md`、磁盘上的事实事务日志；自动压缩控制 prompt 体积，`memory_*` 工具做显式召回 | [memory.md](https://java.agentscope.io/v2/zh/docs/harness/memory.md) |
| 子 Agent | Markdown 声明 subagent 规格，运行时用 `agent_spawn` / `agent_send` 调用，支持同步与后台委派；后台终态通过 system-reminder 推回，无需轮询 | [subagent.md](https://java.agentscope.io/v2/zh/docs/harness/subagent.md) |
| 自动上下文管理 | 结构化压缩保留目标、状态、关键发现与下一步；超大工具结果落盘只留占位；ContextOverflow 兜底重试 | [compaction.md](https://java.agentscope.io/v2/zh/docs/harness/compaction.md) |
| 复杂任务规划（Plan Mode） | 只读规划态，计划文件持久化到 `workspace/plans/`，把「意图」和「行动」分开 | [plan-mode.md](https://java.agentscope.io/v2/zh/docs/harness/plan-mode.md) |
| 工作区工程基座 | 人设、知识、技能、subagent 规格、会话日志都以 Markdown / JSON 落盘，每轮自动注入系统提示词 | [workspace.md](https://java.agentscope.io/v2/zh/docs/harness/workspace.md) |

## 二、企业级分布式部署

目标是无状态水平扩展：任意副本都能恢复任意用户的完整上下文，沙箱状态可跨进程恢复，权限闸 + 多维隔离保证租户数据严格分开。

| 能力 | 要点 | 上游 |
| - | - | - |
| 多租户隔离 | `session` / `user` / `agent` / `org` 维度状态隔离；`RuntimeContext` 的 key 覆盖工作区路径、KV 命名空间、沙箱状态槽位 | [context.md](https://java.agentscope.io/v2/zh/docs/building-blocks/context.md) |
| 安全沙箱执行 | 工具执行限制在隔离环境：本地子进程、Docker、远程 AgentRun；支持快照与恢复，进程重启后长任务可续跑 | [sandbox.md](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md) |
| 工具权限控制 | 三态决策 allow / 需审批 / deny，结合静态规则、工具类型、入参分析；敏感工具走人工审批，HITL 是框架内置能力 | [permission-system.md](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.md) |
| 平滑发布与会话恢复 | 同一 `(userId, sessionId)` 可在任意进程恢复完整会话；`AgentStateStore`（内存 / JSON 文件 / MySQL / Redis）支撑零停机滚动发布与崩溃恢复 | [context.md](https://java.agentscope.io/v2/zh/docs/building-blocks/context.md) |

## 三、核心框架重构 —— 更轻、更一致的抽象

消息、事件、扩展机制被重构成更小更正交的形态；HITL 与事件流不再是附加层，而是框架运行时的一部分。

| 能力 | 要点 | 上游 |
| - | - | - |
| 原生事件流输出 | 模型调用、文本增量、工具执行、工具结果每一步都发出类型化事件，订阅一次前端即可实时同步 | [message-and-event.md](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.md) |
| 更简单的消息模型 | 文本、文件、图片、音视频、模型推理、工具结果统一为 `ContentBlock`；角色校验严格，非法消息在构造期就被拒 | [message-and-event.md](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.md) |
| 中间件取代松散 hook | `onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt` 五阶段取代 v1 的扁平 hooks | [middleware.md](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.md) |
| HITL 一等公民 | 执行中确认工具参数、审批敏感操作、把执行交给外部系统；Agent 在暂停点等待并精确恢复 | [permission-system.md](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.md) |

## 评估升级时看什么

- 迁移工作量分层：迁移指南把改动分成「必须改」和「建议改」两层，配新特性清单。
- 版本级改动：[Release Notes](https://java.agentscope.io/v2/zh/docs/others/release-notes.md)。
- 未发布 API：<https://java.agentscope.io/dev/zh/intro.md>。
- AgentScope 独立服务形态：[AgentScope Service](https://java.agentscope.io/v2/zh/service/index.md)。
