# AgentScope Java 参考文档（本地蒸馏版）

本目录是对上游 AgentScope Java 中文文档的本地蒸馏，供 Heron 后端开发时离线查阅，不作为项目规范。
上游地址：<https://java.agentscope.io/v2/zh/docs>

## 版本基线

| 项 | 值 |
| - | - |
| 上游文档标签 | `v2.0.4` |
| 仓库依赖版本 | `2.0.4`（`ruoyi-modules/ruoyi-heron/pom.xml`：`agentscope-harness`、`agentscope-extensions-model-openai`） |
| 抓取日期 | 2026-11-13 |
| 抓取方式 | `curl` 拉取上游 `.md` 原文，逐页蒸馏 |

## 文件清单

### 平台与集成

| 文件 | 内容 | 上游页面 |
| - | - | - |
| [agentscope-2.0-overview.md](agentscope-2.0-overview.md) | AgentScope 2.0 三大主题：Harness 工程、分布式部署、核心 API 重构 | <https://java.agentscope.io/v2/zh/docs> |
| [integration-overview.md](integration-overview.md) | 扩展模块清单：模型、存储、沙箱、记忆、RAG、Skill、Channel、协议、基础设施 | <https://java.agentscope.io/v2/zh/integration/overview> |

### Harness（`docs/harness/*`）

| 文件 | 内容 | 上游页面 |
| - | - | - |
| [harness-architecture.md](harness-architecture.md) | HarnessAgent 是什么、能力清单与 builder 入口、状态三层流转、middleware 顺序 | <https://java.agentscope.io/v2/zh/docs/harness/architecture> |
| [harness-workspace.md](harness-workspace.md) | 工作区目录布局、system prompt 拼装、两层读、三种 filesystem 模式、多租户覆盖 | <https://java.agentscope.io/v2/zh/docs/harness/workspace> |
| [harness-memory.md](harness-memory.md) | 长期记忆：三处 LLM 调用、`MemoryConfig` 全字段、后台维护提交门 | <https://java.agentscope.io/v2/zh/docs/harness/memory> |
| [harness-compaction.md](harness-compaction.md) | 四套压缩策略与默认阈值、大工具结果卸载、溢出兜底、会话查询工具 | <https://java.agentscope.io/v2/zh/docs/harness/compaction> |
| [harness-skill.md](harness-skill.md) | 五种技能市场后端、四层优先级、沙箱投影、自学习闭环三阶段 | <https://java.agentscope.io/v2/zh/docs/harness/skill> |
| [harness-subagent.md](harness-subagent.md) | 子 agent 声明三来源、ISOLATED/SHARED、同步与后台、任务工具、远程子 agent | <https://java.agentscope.io/v2/zh/docs/harness/subagent> |
| [harness-plan-mode.md](harness-plan-mode.md) | 只读规划三工具、四种终态、运行期 `PermissionMode` 切换、与 `todo_write` 协作 | <https://java.agentscope.io/v2/zh/docs/harness/plan-mode> |
| [harness-filesystem.md](harness-filesystem.md) | 本机 / 共享存储 / 沙箱三种模式、`IsolationScope` 分桶、路径安全 | <https://java.agentscope.io/v2/zh/docs/harness/filesystem> |
| [harness-sandbox.md](harness-sandbox.md) | 隔离执行、跨调用恢复、快照、多副本部署 | <https://java.agentscope.io/v2/zh/docs/harness/sandbox> |

### 核心构件（`docs/building-blocks/*`）

| 文件 | 内容 | 上游页面 |
| - | - | - |
| [context-and-agent-state.md](context-and-agent-state.md) | 无状态引擎、`AgentState` 字段与持久化、`AgentStateStore` 选型、`RuntimeContext`、并发与中断 | <https://java.agentscope.io/v2/zh/docs/building-blocks/context> |
| [permission-system.md](permission-system.md) | 三态决策、`PermissionMode` / `PermissionRule`、内置检查与危险路径、HITL 确认流程 | <https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system> |
| [tool.md](tool.md) | 注解式 / `ToolBase` / 外部执行 tool、Context 注入、MCP、Skill、Tool Group、拦截器、请求级工具视图 | <https://java.agentscope.io/v2/zh/docs/building-blocks/tool> |
| [middleware.md](middleware.md) | 五阶段钩子、洋葱模型、内置 middleware、可观测性接入 | <https://java.agentscope.io/v2/zh/docs/building-blocks/middleware> |
| [capability-grant.md](capability-grant.md) | 按调用方授权裁剪能力面：`capabilityResolver`、`ALLOW_ALL` / `DENY_ALL`、技能与工具组授权 | <https://java.agentscope.io/v2/zh/docs/building-blocks/capability-grant> |
| [message-and-event.md](message-and-event.md) | `ContentBlock` 消息模型、事件类型、流式事件订阅 | <https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event> |
| [model.md](model.md) | 模型提供商配置、`ModelRegistry`、formatter、generate options | <https://java.agentscope.io/v2/zh/docs/building-blocks/model> |

### 生产与参考

| 文件 | 内容 | 上游页面 |
| - | - | - |
| [going-to-production.md](going-to-production.md) | 单机 → 多副本的组件选型清单（state store / filesystem / skill / sandbox / 快照 / 观测） | <https://java.agentscope.io/v2/zh/docs/others/going-to-production> |

### Jev

| 文件 | 内容 | 上游页面 |
| - | - | - |
| [jev-integration.md](jev-integration.md) | Jev 结构化决策：依赖、`JevClient`、三种问题类型、三个参考中间件 | <https://java.agentscope.io/v2/zh/jev> |
| [jev-structured-decision.md](jev-structured-decision.md) | 博客：为什么 Agent 需要独立于推理的「判断」能力 | <https://java.agentscope.io/v2/zh/blogs/jev-structured-decision-for-agents> |

`_upstream/` 存放上述 21 页的**上游原文快照**，只读，不要本地编辑。它的存在目的只有一个：升级时能和上游新版本做 `diff`，看清到底改了什么。

## 升级流程

上游发版后按三步更新，不要手工比对网页：

```bash
cd heron/docs/reference/agentscope
for u in docs integration/overview jev blogs/jev-structured-decision-for-agents \
         docs/harness/architecture docs/harness/workspace docs/harness/memory \
         docs/harness/compaction docs/harness/subagent docs/harness/skill \
         docs/harness/plan-mode docs/harness/filesystem docs/harness/sandbox \
         docs/building-blocks/context docs/building-blocks/permission-system docs/building-blocks/tool \
         docs/building-blocks/middleware docs/building-blocks/capability-grant \
         docs/building-blocks/message-and-event docs/building-blocks/model \
         docs/others/going-to-production; do
  n=$(echo "$u" | sed 's|^docs/||; s|/|-|g')
  curl -sSL "https://java.agentscope.io/v2/zh/${u}.md" -o "_upstream/${n}.md"
done
git diff --stat -- _upstream/
```

1. 先看 `git diff _upstream/`，确认上游到底动了哪几张表、哪段代码。
2. 按 diff 改对应的蒸馏文件；改了哪些页，同步改本 README 的「版本基线」表和 `pom.xml` 里的 `agentscope` 版本。
3. 上游未发布内容在 dev 文档：<https://java.agentscope.io/dev/zh/docs>；版本级变更看 [Release Notes](https://java.agentscope.io/v2/zh/docs/others/release-notes.md)，V1→V2 迁移看 [change-log](https://java.agentscope.io/v2/zh/docs/change-log.md)。

全站页面索引：<https://java.agentscope.io/llms.txt>

### 已知上游矛盾

`docs/harness/plan-mode` 说 Plan Mode 期间 spawn 的子 agent **不会**自动继承只读限制（标为「已知缺口」），`docs/harness/subagent` 说**会自动继承**。同一版本内两页冲突，接入前需实测；两份蒸馏文档都已标注。

### 未蒸馏的页面

以下内容判断为与 Heron 单租户自部署形态无关，或会随开发漂移，只保留链接：

- Service（AgentScope 独立部署形态）、Integration 页下的具体实现（Redis / MySQL / OSS 分布式存储、RAG、Memory 集成、Channel 钉钉飞书等、生态 Studio/Training）。
- dev 文档（未发布 API）与 v1 文档。

需要时按 [全站索引](https://java.agentscope.io/llms.txt) 取页，再按上面的循环加一行。

## 相关文档

- [heron-agentscope-compatibility.md](../../architecture/heron-agentscope-compatibility.md)：Heron 与 AgentScope 的适配结论
- [docs/plans/jev.md](../../plans/jev.md)：JEV 架构评审提示词（与本目录的参考文档用途不同）
- [../hyperframes/](../hyperframes/README.md)：视频渲染用的 HyperFrames 参考文档

## 注意

- 本目录是知识点摘录，不是权威来源；与上游冲突时以上游和实际依赖版本为准。
- 蒸馏可能省略细节，涉及接口签名、配置项默认值时必须回 [上游原文](https://java.agentscope.io/v2/zh/docs) 或 `_upstream/` 快照核对。
