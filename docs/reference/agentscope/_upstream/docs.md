> ## Documentation Index
> Fetch the complete documentation index at: https://java.agentscope.io/llms.txt
> Use this file to discover all available pages before exploring further.

# AgentScope 2.0 是什么？

> Harness 工程化、企业级分布式部署、底层框架重构。

> [!NOTE]
>
> 本页属于 2.0 文档，基于 `v2.0.4` 发布标签整理。SDK 示例请使用 AgentScope Java `2.0.4`；当前开发版本的 API 请参阅 [dev 文档](https://java.agentscope.io/dev/zh/intro.md)。

AgentScope Java 2.0 从"构建一个智能体"的工具箱，迈向**面向生产环境运行智能体**的完整平台。本次升级围绕三大主题展开，每一部分都对应一个具体要解决的问题。

> [!NOTE]
>
> AgentScope Java 2.0 版本尽量保持了对 1.x 版本的兼容，确保大部分用户的平滑升级；但同时 2.0 也带来了 API 层面的不兼容变更，并在核心抽象、API 和架构上均有大幅改进。完整迁移指南详见 [V1 迁移指南](https://java.agentscope.io/v2/zh/docs/change-log.md)。

## 1 · Harness 工程化 —— 长期运行、复杂任务的工程底座

裸的 ReAct 循环只解决"一次推理"。真实任务往往要跑数小时、积累大量状态、依赖可持续沉淀的能力。**Harness** 把这套工程基础设施一次给齐：核心推理循环原样保留，能力按需叠加，让智能体能稳定长跑、能力越用越强、能从容完成复杂作业。

> [card] [自进化与技能仓库](https://java.agentscope.io/v2/zh/docs/harness/skill.md)
>
> 成功模式以 Markdown 技能自动沉淀到 `workspace/skills/`，每轮按需加载、跨会话共享 —— know-how 在每次运行之间累积。

> [card] [分层记忆管理](https://java.agentscope.io/v2/zh/docs/harness/memory.md)
>
> 三层记忆：上下文对话、agent 自维护的 `MEMORY.md`、磁盘事实流水账。自动压缩控制 prompt 体量，`memory_*` 工具提供显式回忆。

> [card] [子智能体](https://java.agentscope.io/v2/zh/docs/harness/subagent.md)
>
> 在 Markdown 里声明子 agent 规格，运行时按需 `agent_spawn` / `agent_send`，支持同步与后台委派。后台终态经 system-reminder 反向推送，无需轮询。

> [card] [上下文自动管理](https://java.agentscope.io/v2/zh/docs/harness/compaction.md)
>
> 结构化压缩保留目标 / 状态 / 关键发现 / 下一步；超大工具结果落盘、只留占位符；ContextOverflow 兜底重试是最后防线。

> [card] [复杂任务规划（Plan Mode）](https://java.agentscope.io/v2/zh/docs/harness/plan-mode.md)
>
> 只读规划态编排长任务；计划文件持久化到 `workspace/plans/` 并驱动执行，让意图与动作解耦。

> [card] [Workspace 工程底座](https://java.agentscope.io/v2/zh/docs/harness/workspace.md)
>
> 人格、知识、技能、子 agent 规格、会话日志全部以磁盘 Markdown / JSON 表达，每轮自动注入 system prompt。

## 2 · 企业级分布式部署

生产环境的智能体要服务多租户、要安全运行不可信工具代码、要在滚动发布时不丢失在途上下文。AgentScope 2.0 天然面向**无状态水平扩展**：任意副本都能恢复任意用户的完整上下文，沙箱状态可跨进程恢复，权限闸门 + 多维隔离把每一个租户的数据严格分开。

> [card] [多租户隔离](https://java.agentscope.io/v2/zh/docs/building-blocks/context.md)
>
> 支持 `session` / `user` / `agent` / `org` 多维度状态隔离。`RuntimeContext` 的键贯穿工作区路径、KV 命名空间、沙箱状态槽。

> [card] [安全沙箱执行](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md)
>
> 工具执行限定在隔离环境内 —— 本地子进程 / Docker / 远端 AgentRun 任选 —— 支持快照与恢复，长任务能在进程重启后继续。

> [card] [工具权限管控](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.md)
>
> 权限三态决策（允许 / 审批 / 拒绝）综合静态规则、工具类型、输入分析；敏感工具强制人工审批，HITL 是框架内生能力。

> [card] [优雅上下线与会话恢复](https://java.agentscope.io/v2/zh/docs/building-blocks/context.md)
>
> 同一 `(userId, sessionId)` 在任意进程恢复完整对话；`AgentStateStore`（内存 / JSON 文件 / MySQL / Redis）支撑零停机滚动发布与崩溃恢复。

## 3 · 底层框架升级 —— 更轻、更顺手的核心抽象

底层做了一次重构：消息、事件、扩展机制更小、更正交、更顺手；HITL 与事件流式不再是外挂层，而是框架运行的一部分。

> [card] [事件流式输出原生支持](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.md)
>
> 每一步 —— 模型调用、文本增量、工具执行、工具结果 —— 都以类型化事件流出。订阅一次，前端 UI 实时跟上。

> [card] [更简洁的消息模型](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.md)
>
> 文本、文件、图片、音视频、模型思考、工具结果统一收敛到一个 `ContentBlock`；按 role 严格校验，非法消息在构造期就被拦下。

> [card] [Middleware 取代松散 Hook](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.md)
>
> `onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt` 五个阶段取代 v1 的扁平 hook。每个关注点各居其层，组合干净利落。

> [card] [HITL 一等公民](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.md)
>
> 可在执行中确认工具参数、审批敏感操作，或把执行交给外部系统。智能体在暂停点等待并精确恢复，无需自己搭脚手架。

***

正在评估升级时间表的开发者，可以查阅 [V1 迁移指南](https://java.agentscope.io/v2/zh/docs/change-log.md) —— 拆成"必须迁移 / 推荐迁移"两层的迁移指南，加上新功能罗列，足以端到端规划一次升级。各版本具体变更请见 [Release Notes](https://java.agentscope.io/v2/zh/docs/others/release-notes.md)。

## AgentScope Service

[AgentScope Service：部署与使用](https://java.agentscope.io/v2/zh/service/index.md)


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.