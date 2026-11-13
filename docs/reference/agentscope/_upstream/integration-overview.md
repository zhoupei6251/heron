> ## Documentation Index
> Fetch the complete documentation index at: https://java.agentscope.io/llms.txt
> Use this file to discover all available pages before exploring further.

# 概览

本节汇总 AgentScope Java 与第三方系统、生态服务的集成扩展。每个扩展都是 `agentscope-extensions/` 下的独立 Maven 模块，按需引入即可。

按主题分为以下几组：

## 模型提供商

所有模型提供商已经迁移到独立模型扩展模块中，`agentscope-core` 只保留共享模型契约。完整模型创建方式、Spring Boot 配置、formatter、credential 和高级 registry 行为见 [模型文档](https://java.agentscope.io/v2/zh/docs/building-blocks/model.md)。

| 提供商 | Maven artifact | `ModelRegistry` id | 标准环境变量 | 文档 |
| - | - | - | - | - |
| OpenAI | `agentscope-extensions-model-openai` | `openai:<model>` | `OPENAI_API_KEY` | [OpenAI](https://java.agentscope.io/v2/zh/integration/model/openai.md) |
| OpenAI Official | `agentscope-extensions-model-openai-official` | `openai-official:<model>` | `OPENAI_API_KEY` | [OpenAI Official](https://java.agentscope.io/v2/zh/integration/model/openai-official.md) |
| DeepSeek | `agentscope-extensions-model-openai` | `deepseek:<model>` | `DEEPSEEK_API_KEY` | [DeepSeek](https://java.agentscope.io/v2/zh/integration/model/deepseek.md) |
| GLM | `agentscope-extensions-model-openai` | `glm:<model>` | `ZAI_API_KEY` / `GLM_API_KEY` / `ZHIPUAI_API_KEY` | [GLM](https://java.agentscope.io/v2/zh/integration/model/glm.md) |
| Kimi | `agentscope-extensions-model-openai` | `kimi:<model>` | `MOONSHOT_API_KEY` / `KIMI_API_KEY` | [Kimi](https://java.agentscope.io/v2/zh/integration/model/kimi.md) |
| MiniMax | `agentscope-extensions-model-openai` | `minimax:<model>` | `MINIMAX_API_KEY` | [MiniMax](https://java.agentscope.io/v2/zh/integration/model/minimax.md) |
| DashScope | `agentscope-extensions-model-dashscope` | `dashscope:<model>` / `qwen*` | `DASHSCOPE_API_KEY` | [DashScope](https://java.agentscope.io/v2/zh/integration/model/dashscope.md) |
| Gemini | `agentscope-extensions-model-gemini` | `gemini:<model>` | `GEMINI_API_KEY` | [Gemini](https://java.agentscope.io/v2/zh/integration/model/gemini.md) |
| Anthropic | `agentscope-extensions-model-anthropic` | `anthropic:<model>` | `ANTHROPIC_API_KEY` | [Anthropic](https://java.agentscope.io/v2/zh/integration/model/anthropic.md) |
| Ollama | `agentscope-extensions-model-ollama` | `ollama:<model>` | `OLLAMA_BASE_URL`（可选） | [Ollama](https://java.agentscope.io/v2/zh/integration/model/ollama.md) |

> [!NOTE]
>
> `agentscope-extensions-model-e2e-tests` 是仓库测试模块，不是面向用户的模型集成依赖。

## 结构化决策

[OpenAI Decisions](https://java.agentscope.io/v2/zh/integration/judge/openai-decisions.md) 通过独立 judge 客户端判断文本和图片，返回类型化的 predicate、choice 和 score 结果。

## 分布式存储（Distributed Store）

生产多副本部署所需的全链路分布式存储组件。通过 `DistributedStore` 一键配置 Agent 状态、工作区文件系统、沙箱快照与并发锁。

* [分布式存储总览](https://java.agentscope.io/v2/zh/integration/distributed/index.md) — `DistributedStore` API、能力矩阵、混合后端
* [Redis](https://java.agentscope.io/v2/zh/integration/distributed/redis.md) — `AgentStateStore` + `BaseStore` + `SandboxSnapshotSpec` + `SandboxExecutionGuard`
* [MySQL / JDBC](https://java.agentscope.io/v2/zh/integration/distributed/mysql.md) — `AgentStateStore` + `JdbcStore` + `JdbcSnapshotSpec` + `JdbcSandboxExecutionGuard`
* [阿里云 OSS](https://java.agentscope.io/v2/zh/integration/distributed/oss.md) — `AgentStateStore` + `OssBaseStore` + `OssSnapshotSpec`

## 沙箱执行环境（Sandbox）

隔离的代码执行环境，所有 `SandboxFilesystemSpec` 实现。Docker 内置在 harness 中，其余为独立扩展模块。

* Docker — 内置默认，无需额外依赖
* [Kubernetes](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md) — `agentscope-extensions-sandbox-kubernetes`
* [AgentRun（阿里云）](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md) — `agentscope-extensions-sandbox-agentrun`
* [Daytona](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md) — `agentscope-extensions-sandbox-daytona`
* [E2B](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md) — `agentscope-extensions-sandbox-e2b`

## 记忆（Memory）

跨会话持久化用户偏好与事实，所有实现都符合 `LongTermMemory` 接口。

* [Mem0](https://java.agentscope.io/v2/zh/integration/memory/mem0.md)
* [百炼记忆](https://java.agentscope.io/v2/zh/integration/memory/bailian.md)
* [ReMe](https://java.agentscope.io/v2/zh/integration/memory/reme.md)

## RAG 知识库

通过 `Knowledge` 接口接入不同的检索后端。

* [Simple（自建 embedding + 向量库）](https://java.agentscope.io/v2/zh/integration/rag/simple.md)
* [百炼知识库](https://java.agentscope.io/v2/zh/integration/rag/bailian.md)
* [Dify](https://java.agentscope.io/v2/zh/integration/rag/dify.md)
* [HayStack](https://java.agentscope.io/v2/zh/integration/rag/haystack.md)
* [RAGFlow](https://java.agentscope.io/v2/zh/integration/rag/ragflow.md)

## 技能仓库（Skill）

`AgentSkillRepository` 的多种存储实现。

* [Git 技能仓库](https://java.agentscope.io/v2/zh/integration/skill/git-repository.md)
* [MySQL 技能仓库](https://java.agentscope.io/v2/zh/integration/skill/mysql-repository.md)
* [PostgreSQL 技能仓库](https://java.agentscope.io/v2/zh/integration/skill/postgresql-repository.md)
* 也可以使用 [Nacos 技能仓库](https://java.agentscope.io/v2/zh/integration/infrastructure/nacos.md#skill-仓库)

## Channel 适配器

通过 Harness Channel 接口将 Agent 接入消息平台。

* [钉钉](https://java.agentscope.io/v2/zh/integration/channel/dingtalk.md)
* [飞书 / Lark](https://java.agentscope.io/v2/zh/integration/channel/feishu.md)
* [GitHub](https://java.agentscope.io/v2/zh/integration/channel/github.md)
* [GitLab](https://java.agentscope.io/v2/zh/integration/channel/gitlab.md)
* [企业微信](https://java.agentscope.io/v2/zh/integration/channel/wecom.md)

## 智能体协议

让 Agent 与外部世界以标准方式交互。

* [A2A（Agent-to-Agent）](https://java.agentscope.io/v2/zh/integration/protocol/a2a.md)
* [AG-UI](https://java.agentscope.io/v2/zh/integration/protocol/agui.md)
* [Agent Protocol](https://java.agentscope.io/v2/zh/integration/protocol/agent-protocol.md)

## 基础设施 / 中间件

把 Agent 接到企业基础设施。

* [Higress AI 网关](https://java.agentscope.io/v2/zh/integration/infrastructure/higress.md)
* [Nacos](https://java.agentscope.io/v2/zh/integration/infrastructure/nacos.md)
* [Scheduler（Quartz / XXL-Job）](https://java.agentscope.io/v2/zh/integration/infrastructure/scheduler.md)

## 生态扩展

运行环境、语言生态、调试与训练流水线。

* [Chat Completions Web](https://java.agentscope.io/v2/zh/integration/ecosystem/chat-completions-web.md)
* [AgentScope Studio](https://java.agentscope.io/v2/zh/integration/ecosystem/studio.md)
* [在线训练（Training）](https://java.agentscope.io/v2/zh/integration/ecosystem/training.md)

> [!NOTE]
>
> 若你正在使用 Spring Boot，绝大多数扩展都有对应的 `agentscope-spring-boot-starter-*` 一键接入版本，可减少手动装配代码。


This documentation is built and hosted on [Mintlify](https://mintlify.com), a developer documentation platform.