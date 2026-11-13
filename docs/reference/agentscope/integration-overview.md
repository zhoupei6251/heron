# AgentScope Java 集成扩展总览

> 上游：<https://java.agentscope.io/v2/zh/integration/overview> · 快照：`_upstream/integration-overview.md` · 基线 `v2.0.4` · 抓取 2026-11-13

每个扩展都是 `agentscope-extensions/` 下的独立 Maven 模块，按需引入。使用 Spring Boot 时，多数扩展另有 `agentscope-spring-boot-starter-*` 版本，可减少手工装配。

> 上游备注：`agentscope-extensions-model-e2e-tests` 是仓库测试模块，不是给用户用的模型集成依赖。

## 模型提供商

`agentscope-core` 只保留共享模型契约，提供商全部拆成独立模块。创建方式、Spring Boot 配置、formatter、credential、registry 详见[模型文档](https://java.agentscope.io/v2/zh/docs/building-blocks/model.md)。

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

注：DeepSeek / GLM / Kimi / MiniMax 都复用 `agentscope-extensions-model-openai` 这一个 artifact，只是 registry id 和环境变量不同——OpenAI 兼容协议，不额外引包。

## 结构化决策

[OpenAI Decisions](https://java.agentscope.io/v2/zh/integration/judge/openai-decisions.md)：独立 judge 客户端，处理文本与图片，返回带类型的 predicate、choice、score。

## 分布式存储（Distributed Store）

生产多副本部署用。`DistributedStore` 统一配置 Agent 状态、工作区文件系统、沙箱快照与并发锁。

| 后端 | 提供的组件 | 文档 |
| - | - | - |
| 总览 | `DistributedStore` API、能力矩阵、混合后端 | [分布式存储总览](https://java.agentscope.io/v2/zh/integration/distributed/index.md) |
| Redis | `AgentStateStore` + `BaseStore` + `SandboxSnapshotSpec` + `SandboxExecutionGuard` | [Redis](https://java.agentscope.io/v2/zh/integration/distributed/redis.md) |
| MySQL / JDBC | `AgentStateStore` + `JdbcStore` + `JdbcSnapshotSpec` + `JdbcSandboxExecutionGuard` | [MySQL](https://java.agentscope.io/v2/zh/integration/distributed/mysql.md) |
| 阿里云 OSS | `AgentStateStore` + `OssBaseStore` + `OssSnapshotSpec` | [OSS](https://java.agentscope.io/v2/zh/integration/distributed/oss.md) |

## 沙箱执行环境（Sandbox）

均为 `SandboxFilesystemSpec` 实现。Docker 由 harness 内置，其余在独立扩展模块。

| 实现 | artifact / 说明 |
| - | - |
| Docker | harness 内置默认，无需额外依赖 |
| Kubernetes | `agentscope-extensions-sandbox-kubernetes` |
| AgentRun（阿里云） | `agentscope-extensions-sandbox-agentrun` |
| Daytona | `agentscope-extensions-sandbox-daytona` |
| E2B | `agentscope-extensions-sandbox-e2b` |

细节统一见 [sandbox.md](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md)。

## 记忆（Memory）

跨会话保存用户偏好与事实，均实现 `LongTermMemory`：[Mem0](https://java.agentscope.io/v2/zh/integration/memory/mem0.md)、[百炼记忆](https://java.agentscope.io/v2/zh/integration/memory/bailian.md)、[ReMe](https://java.agentscope.io/v2/zh/integration/memory/reme.md)。

## RAG 知识库

通过 `Knowledge` 接口接入检索后端：[Simple（自建 embedding + 向量库）](https://java.agentscope.io/v2/zh/integration/rag/simple.md)、[百炼知识库](https://java.agentscope.io/v2/zh/integration/rag/bailian.md)、[Dify](https://java.agentscope.io/v2/zh/integration/rag/dify.md)、[HayStack](https://java.agentscope.io/v2/zh/integration/rag/haystack.md)、[RAGFlow](https://java.agentscope.io/v2/zh/integration/rag/ragflow.md)。

## 技能仓库（Skill）

`AgentSkillRepository` 的存储实现：[Git](https://java.agentscope.io/v2/zh/integration/skill/git-repository.md)、[MySQL](https://java.agentscope.io/v2/zh/integration/skill/mysql-repository.md)、[PostgreSQL](https://java.agentscope.io/v2/zh/integration/skill/postgresql-repository.md)、[Nacos](https://java.agentscope.io/v2/zh/integration/infrastructure/nacos.md#skill-仓库)。

## Channel 适配器

通过 Harness Channel 接口把 Agent 接入消息平台：[钉钉](https://java.agentscope.io/v2/zh/integration/channel/dingtalk.md)、[飞书 / Lark](https://java.agentscope.io/v2/zh/integration/channel/feishu.md)、[GitHub](https://java.agentscope.io/v2/zh/integration/channel/github.md)、[GitLab](https://java.agentscope.io/v2/zh/integration/channel/gitlab.md)、[企业微信](https://java.agentscope.io/v2/zh/integration/channel/wecom.md)。

## 智能体协议

让 Agent 以标准方式与外部交互：[A2A](https://java.agentscope.io/v2/zh/integration/protocol/a2a.md)、[AG-UI](https://java.agentscope.io/v2/zh/integration/protocol/agui.md)、[Agent Protocol](https://java.agentscope.io/v2/zh/integration/protocol/agent-protocol.md)。

## 基础设施 / 中间件

把 Agent 接入企业基础设施：[Higress AI 网关](https://java.agentscope.io/v2/zh/integration/infrastructure/higress.md)、[Nacos](https://java.agentscope.io/v2/zh/integration/infrastructure/nacos.md)、[Scheduler（Quartz / XXL-Job）](https://java.agentscope.io/v2/zh/integration/infrastructure/scheduler.md)。

## 生态扩展

运行环境、语言生态、调试与训练流水线：[Chat Completions Web](https://java.agentscope.io/v2/zh/integration/ecosystem/chat-completions-web.md)、[AgentScope Studio](https://java.agentscope.io/v2/zh/integration/ecosystem/studio.md)、[在线训练](https://java.agentscope.io/v2/zh/integration/ecosystem/training.md)。
