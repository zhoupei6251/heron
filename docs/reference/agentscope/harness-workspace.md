# 工作区（Workspace）

> 上游：<https://java.agentscope.io/v2/zh/docs/harness/workspace> · 快照：`_upstream/harness-workspace.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：智能体定义与进化的 source of truth：目录布局、工作区与 API 的等价关系、原生多租户隔离、filesystem 模式

## 设计理念

工作区是 `HarnessAgent` **智能体定义与进化的 source of truth**：agent「是什么」和「学到了什么」都以一个目录 + 普通 Markdown / JSON 表达，不散落在代码里、不绑死在数据库表上。四条线索：

**1. 定义可以完全用文件声明**

| 要定义的内容 | 文件 |
| - | - |
| 人格、行为约定、系统指令 | `AGENTS.md` |
| 领域知识 | `knowledge/KNOWLEDGE.md` + 参考文件 |
| 技能 | `skills/<skill-name>/SKILL.md` |
| 子 agent 声明 | `subagents/<agent-id>.md` |
| 工具白名单 + MCP server | `tools.json` |

以上全部可选，每项都有**完全对等的 API 配置**（`.systemPrompt(...)`、`.skill(...)`、`.subagent(...)`、`.toolsConfig(...)`）。工作区与 API 等价。

用工作区的理由是：把「定义」变成文件，是让一个 agent 天然多租户的关键——同一套逻辑为不同用户携带不同人格、知识、技能，只需一个用户级覆盖目录，不用代码分支、不用多套部署。

**2. 内容按生命周期分三类**

| 类型 | 谁写 | 谁读 | 例子 |
| - | - | - | - |
| 静态资产（工程师编辑） | 你 / 团队 | 每轮注入 system prompt 或按需读 | `AGENTS.md`、`knowledge/`、`skills/`、`subagents/`、`tools.json` |
| 运行时文件（每次 call 写回） | 框架 / agent | 下次 call 时还原 | `agents/<agentId>/sessions/`、`tasks/`、`plans/` |
| 长期记忆（跨会话累积） | agent + 后台任务 | 每轮注入 + agent 用工具查询 | `MEMORY.md`、`memory/YYYY-MM-DD.md` |

混在一棵树里只为部署方便（拷一个目录就是完整 agent），框架内部走不同读写路径。

**3. 原生多租户隔离** —— 由单一 `IsolationScope` 分桶，应用层不写分区逻辑：

| `IsolationScope` | 谁共享一个桶 | 典型场景 |
| - | - | - |
| `SESSION` | 每个 `sessionId` 完全隔离 | 按会话隔离；一次性沙箱 |
| `USER`（默认） | 同一 `userId` 的所有会话 | 用户多个会话共享长期记忆 / 技能（`userId` 缺失降级为 `SESSION`） |
| `AGENT` | 该 agent 的所有用户与会话 | 共享知识库型 agent |
| `GLOBAL` | 整个 store 实例一个桶 | 慎用——所有 agent/用户争抢同一槽位 |

**4. 工作区与 filesystem 解耦** —— 同一份目录布局可落在本机磁盘、共享 KV（Redis / JDBC）、沙箱容器。这是「代码不动、部署形态切换」的根因。

> `AgentState` **不在工作区**。在途上下文（对话缓冲、滚动摘要、权限/工具/任务/Plan 子上下文）序列化后进独立的 `AgentStateStore`（默认 `~/.agentscope/state/<agentId>/`）。工作区存持久**文件产物**，`AgentState` 存易失**运行期上下文**——两套存储、两套生命周期。

## 目录布局

```
.agentscope/workspace/
├── AGENTS.md                    ← 静态：人格 + 行为约定
├── MEMORY.md                    ← 长期记忆：策划后的长期事实
├── tools.json                   ← 静态：MCP server + 工具白名单（可选）
├── memory/                      ← 长期记忆：每天追加的事实流水账
│   └── YYYY-MM-DD.md
├── knowledge/                   ← 静态：领域知识入口 + 任意参考文件
│   ├── KNOWLEDGE.md
│   └── ...
├── skills/                      ← 静态：每个子目录一份 SKILL.md
│   └── <skill-name>/SKILL.md
├── subagents/                   ← 静态：子 agent 声明（文件名即 agent_id）
│   └── <agent-id>.md
├── plans/                       ← 运行时：Plan Mode 写下的计划
│   └── PLAN.md
└── agents/<agentId>/            ← 运行时：每个 agent 自己的运行时根
    ├── sessions/
    │   ├── sessions.json
    │   └── <sessionId>.log.jsonl
    └── tasks/
        └── <sessionId>.json
```

> 这棵树是**逻辑布局**，不是固定磁盘路径。同一份相对路径在本机 / 远端分布式存储（`RemoteFilesystemSpec`）/ 沙箱（`SandboxFilesystemSpec`）下完全一致，变的只是后端。

**唯一真正需要你写的是 `AGENTS.md`**（不写也能跑，只少一段人格注入）。其他目录在启用对应能力时自动出现：`.compaction(...)` → `memory/` + `MEMORY.md`；放 subagent spec → `subagents/`；`.enablePlanMode()` → `plans/`；跑过任意一次 `call()` → `agents/<agentId>/`。

## Builder 配置

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))   // 不传则走下面的默认解析顺序
    .additionalContextFile("SOUL.md")                // 任意 workspace 相对路径，全文注入
    .additionalContextFile("PREFERENCES.md")
    .maxContextTokens(8000)                          // MEMORY 注入预算
    .build();
```

### workspace 解析顺序（高 → 低）

| 优先级 | 来源 |
| - | - |
| 1 | `workspace(Path)` / `workspace(String)` |
| 2 | `agentscope.workspace` 系统属性（`-Dagentscope.workspace=/data/workspace`） |
| 3 | `AGENTSCOPE_WORKSPACE` 环境变量 |
| 4 | 默认 `${user.dir}/.agentscope/workspace` |

系统属性和环境变量服务于镜像打包 / 容器部署：代码里不写死路径，构建时注入。空白值视为未设置，继续往下回退。

`AGENTS.md` 至少写个骨架：

```markdown
# MyAgent

你是 XX 助手，遵循以下行为约定。

## 行为
- ...
```

### 可关掉的子系统

| 方法 | 关掉的是 |
| - | - |
| `disableWorkspaceContext()` | system prompt 注入（`AGENTS.md` / `MEMORY.md` / `knowledge/`） |
| `disableMemoryHooks()` | 记忆 flush + 后台维护；同时去掉 Persistence 段里「对话结束自动抽取」的文案 |
| `disableMemoryTools()` | `memory_search` / `memory_get` / `memory_save` / `session_search` 工具 |
| `disableSubagents()` | 整个子 agent 子系统 |
| `disableDynamicSkills()` | 每轮重新合并技能（改成 build 时一次） |
| `disableToolsConfig()` | 不读 `tools.json` |
| `disableSessionPersistence()` | AgentState 自动持久化 |

## 工作区内容如何被加载

读取从不假设它是普通本机目录——每次都经过配置的 `AbstractFilesystem`，所以本机 / 远端 / 沙箱同一套逻辑成立。

### 一次推理的 system prompt 拼装

每次 `call()` 进入 reasoning 阶段，`WorkspaceContextMiddleware`（`io.agentscope.harness.agent.middleware`）按下表把文件拼成文本，**追加在** builder 的 `sysPrompt` 之后：

| 段落 | 来源 | 受预算约束 |
| - | - | - |
| `## Session Context` | 模板生成（日期、OS、workspace 绝对路径、临时目录、`sessionId`） | 否 |
| `## Domain Knowledge` / `## Memory Recall` / `## Memory Persistence` 引导段 | 内置模板；会随 `disableMemoryTools()` / `disableMemoryHooks()` 裁剪或整段省略 | 否 |
| `## Workspace` 段 | 模板生成，**按 filesystem 模式分支**，告诉模型自己跑在本机 / 沙箱 / 远端 | 否 |
| `## Workspace Files (Injected)` | 框架把下列文件拉成 `<loaded_context>` XML 块 | 见下 |
| `<agents_context>` | `AGENTS.md` 全文 | 无限 |
| `<memory_context>` | `MEMORY.md`（超出按字符截断 + 提示用 `memory_search` 查更早） | `maxContextTokens`，默认 8000 |
| `<domain_knowledge_context>` | `KNOWLEDGE.md` 全文 + `knowledge/` 下所有文件**路径列表** | 无限（仅文件名索引） |
| `<x_md>` / `<y_md>` | `additionalContextFile("X.md")` 添加的任意文件 | 无限 |

要点：

- **每轮重新拼**：改 `AGENTS.md` 或 `MEMORY.md` 下一次 `call()` 立刻生效，不用重启。
- `MEMORY.md` **估算 token 后才注入**，超预算截断并引导模型用 `memory_search` 查老内容。
- `knowledge/` 是**目录索引 + 入口文件**：只把 `KNOWLEDGE.md` 全文 + 其它文件路径注入，模型用 `read_file` 自取。

### 两层读（filesystem-first + 本地兜底）

对**被注入 prompt 的关键文件**（`AGENTS.md` / `MEMORY.md` / `knowledge/KNOWLEDGE.md` / `additionalContextFile`），`WorkspaceManager.readWithOverride()` 都走两层读：

```
1. 问当前 AbstractFilesystem：有这个 relative path 吗？
   ├─ 有 → 返回该内容（"覆盖"层）
   └─ 没有 → 走第 2 步
2. 读本地磁盘 workspace.resolve(relativePath)
```

**写入永远走第 1 层**，从不直接落本地磁盘。

共享存储模式下价值最明显：首个副本启动时本地有团队 git 同步来的 `AGENTS.md` 模板立即可用；之后任何节点对它的覆盖写到共享 KV，所有副本下次 `call()` 读到最新版本。模板是 fallback，远端覆盖是事实。

### 多用户同一工作区：覆盖优先级

`RuntimeContext.userId` 是切多用户的钥匙。

- **运行时数据**（sessions / tasks / memory）按 `NamespaceFactory` 加前缀（本机 → 路径前缀，远端 → KV 命名空间，沙箱 → 状态 slot）。
- **静态资产**（尤其 `skills/`、`subagents/`）用户级目录**覆盖**共用版：

```
workspace/
├── skills/code-reviewer/SKILL.md     ← 共用版
├── subagents/researcher.md           ← 共用版
└── alice/
    ├── skills/code-reviewer/SKILL.md ← 只对 alice 生效，覆盖共用版
    └── subagents/researcher.md
```

`userId="alice"` 时先看 `alice/skills/code-reviewer/`，没有再退到共用版；下层独有的 skill 仍可见，只在重名时被挡。

**同一套 agent 逻辑按用户定制**：

| 用户级层 | 定制什么 | 解析方式 |
| - | - | - |
| `<userId>/AGENTS.md` | 该用户的人格 / 行为 | 两层读上层（共享版兜底） |
| `<userId>/knowledge/` | 该用户可见的领域知识 | 用户级目录，共享版作基线 |
| `<userId>/skills/` | 只对该用户解锁的能力 | 覆盖同名，独有叠加可见 |
| `<userId>/subagents/` | 只有该用户能 spawn 的子 agent | 覆盖同名声明 |
| 运行时数据 | 该用户累积的进化数据 | 按 `userId` 进命名空间 |

效果是**两层多租户同时生效**：定义按用户不同（覆盖目录），进化按用户隔离（命名空间）。一份二进制、一份 agent 定义。

### 三种 filesystem 模式下的加载行为

**模式 1 · 共享存储（`RemoteFilesystemSpec`）—— 模板 + 远端覆盖**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("store").model(model).workspace(workspace)
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
        .isolationScope(IsolationScope.USER))
    .build();
```

- `AGENTS.md` / `MEMORY.md` / `tools.json` 按「远端为上层、本机模板为下层」overlay 读；本地是只读模板/种子。
- `memory/`、`skills/`、`subagents/`、`knowledge/`、`agents/<id>/sessions|tasks` 自动按 `IsolationScope` 进命名空间。
- 最佳实践：团队约定的 `AGENTS.md` / `knowledge/` / 共享 `skills/` 用 git 同步到各副本作模板；运行时产物让 KV 自己长。

**模式 2 · 沙箱（Docker / K8s / E2B / AgentRun）—— Projection + Hydrate**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox").model(model).workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION))
    .build();
```

- 启动时把静态资产（projection roots）打成 tar，hydrate 进沙箱 `/workspace`；`AGENTS.md` 等仍走两层读（先沙箱内、后宿主模板）。
- projection 按内容 hash 比对，没变跳过 hydrate；变了按 SHA-256 增量重写。
- `MEMORY.md`、`memory/`、`agents/<id>/...` 落在沙箱内，随沙箱快照保留——同 `sessionId` 下次 `call()` 连 `node_modules`、`pip install` 一起恢复。
- 最佳实践：代码执行 / shell 全隔离在沙箱，宿主只留「种子」。生产跑不可信代码的首选。

**模式 3 · 本机 + shell（默认 `LocalFilesystemSpec` 或不配）—— 直读直写**

- 所有文件直接从 `<workspace>/` 读，没有 overlay；`<userId>/skills/` 按目录前缀切。
- 路径安全默认 `ROOTED`：绝对路径只允许 `workspace` 与 `project`（shell cwd）两个根之下，`..` 被拦。
- 适合单进程 / 本机开发 / 单测 / 信任环境。**生产别用它跑不可信代码**——`execute` 就是宿主 `sh -c`。

## 运行时数据与 Memory 怎么存

两个数据面，**落在不同地方**：

| 数据面 | 是什么 | 落在哪 |
| - | - | - |
| `AgentState` | 易失运行期上下文：对话缓冲、压缩摘要、权限/工具/任务/Plan 上下文，以及指向工作区产物的元数据 | `AgentStateStore`，**不在工作区**（默认 `~/.agentscope/state/<agentId>/`） |
| 工作区运行时 / 长期文件 | 持久产物：会话日志、任务记录、`MEMORY.md` + `memory/` | 工作区树内，物理位置随 filesystem 模式 |

### 会话日志（这些才是工作区文件）

- `sessions.json` —— 会话索引（key = sessionId，value = summary + updatedAt）。
- `<sessionId>.log.jsonl` —— **永不压缩**的原始对话日志，append-only。`session_search` / `session_history` 查的就是它。

> 默认 `JsonFileAgentStateStore` 仅适合单机。生产多副本必须换分布式后端；用了沙箱或远端 filesystem 却没换，`build()` 直接抛 `IllegalStateException`。

### Memory 两层

```
workspace/
├── MEMORY.md                  ← 策划后的长期记忆，每轮注入 system prompt
└── memory/
    └── YYYY-MM-DD.md          ← 每天追加的事实流水账（未去重）
```

- 写：对话压缩前 `MemoryFlushMiddleware` 把新事实追加到 `memory/YYYY-MM-DD.md`；后台节流任务定期合并去重、重写 `MEMORY.md`。
- 读：框架自动读 `MEMORY.md`（两层读，filesystem 优先）；agent 可调 `memory_search` / `memory_get` 查老内容。

### 命名空间怎么落到路径

| 模式 | 运行时数据的物理位置 | 多用户隔离机制 |
| - | - | - |
| 本机 + shell | `<workspace>/<userId>/agents/<agentId>/...` | 路径前缀 |
| 共享存储（KV） | KV key 前缀，如 `namespace=alice/memory/...` | KV 命名空间 |
| 沙箱 | 沙箱状态 slot key（配合 `IsolationScope.USER`） | 沙箱实例隔离 |

不传 `userId` 走单租户默认，所有人共享一个根。

> **静态资产不自动按 userId 切**（`AGENTS.md`、`tools.json`、`knowledge/` 对所有用户共享），只能靠用户覆盖目录差异化；跟着 `userId` 走的是运行时数据。

## 智能体如何进化

五条自动累积通道：

| 通道 | 落在哪 | 怎么开 | 怎么累积 |
| - | - | - | - |
| 长期记忆 | `MEMORY.md` + `memory/YYYY-MM-DD.md` | `.compaction(...)` | 压缩前抽取事实；后台节流合并去重；每轮重新注入 |
| 自学习技能 | `skills/`、`skills/_drafts/`、`skills/.archive/` | `.enableSkillManageTool(...)` | `propose_skill` 起草 → 可选审批闸 → curator 把 30 天未用标 stale、90 天归档 |
| 计划文件 | `plans/PLAN.md` | `.enablePlanMode()` | 只读阶段用 `plan_write` 写计划，跨调用保留 |
| 工具结果落盘 | 工作区下的 eviction 目录 | `.toolResultEviction(...)` | 单结果超阈值（默认 80K 字符）时完整写盘，上下文只留 head/tail 预览 + `read_file` 指针 |
| 会话日志 | `agents/<agentId>/sessions/` | 默认开启 | 每次 `call()` 追加到永不压缩的 JSONL |

## 重点目录

### `skills/`

```
skills/code-reviewer/
├── SKILL.md                    ← YAML frontmatter (name + description) + 指令
├── references/style-guide.md   ← 可选，agent 按需 read_file
└── scripts/run-checks.sh       ← 可选，agent 通过 shell 工具调
```

注册路径四层（低 → 高优先级）：

1. `projectGlobalSkillsDir(Path)` —— 项目全局，如 `~/.agentscope/skills/`
2. `skillRepository(...)` —— 市场后端（Git / Nacos / MySQL / classpath）
3. `workspace/skills/` —— 工作区共用
4. `<userId>/skills/` —— 用户隔离（覆盖以上所有）

下层独有都保留，重名上层覆盖。每轮推理前 `DynamicSkillMiddleware` 重新合成，在 system prompt 里渲染成 `<available_skills>` 块（只有 name + description），agent 觉得相关才 `load_skill_through_path` 拉详情。

### `subagents/`

每个 `<agent-id>.md` 是一份子 agent 声明（文件名即 `agent_id`），frontmatter 描述身份、模型、工具白名单、工作区策略，正文是系统提示。

```markdown
---
description: 代码评审专家。当用户需要 review PR、找代码风格问题时使用。
workspace:
  mode: isolated         # isolated（默认） | shared
model: qwen3-max         # 可选；默认继承父
tools: [read_file, grep_files]   # 可选；继承工具的白名单
---

你是一个专注代码评审的子 agent…
```

`AgentSpecLoader` 在构建期**非递归**扫 `workspace/subagents/*.md`，再合并 `.subagent(SubagentDeclaration...)` 编程注册的声明。主 agent 通过 `agent_spawn agent_id="reviewer" task="..."` 调用。

### `tools.json`

```jsonc
{
  // 白名单：非空时只允许列出的工具
  "allow": ["read_file", "grep_files", "execute"],
  // 黑名单：列出的一律不暴露（优先级高于 allow）
  "deny":  ["write_file"],
  "mcpServers": {
    "amap": {
      "transport": "streamableHttp",
      "url": "https://mcp.amap.com/mcp?key=${AMAP_API_KEY}"
    },
    "local-py": {
      "transport": "stdio",
      "command": "python",
      "args": ["mcp_servers/my_server.py"],
      "env": {"PYTHONUNBUFFERED": "1"}
    }
  }
}
```

- MCP server 在构建期一次性注册到 toolkit。
- **`allow` / `deny` 在所有工具注册完之后才应用**，所以也会过滤 Harness 内置工具（`read_file`、`memory_search`、`agent_spawn` 等）。**用 `allow` 时务必把要保留的内置工具一并列出**，否则一起被砍。
- `${ENV_VAR}` 做环境变量替换；未设置时 warning 并替换成空串。
- 不想用文件就 `builder.toolsConfig(ToolsConfig.builder()...)`；完全不读用 `disableToolsConfig()`。

### `plans/`

Plan Mode 的计划文件，默认 `plans/PLAN.md`，可用 `.planFileDirectory("design-docs")` 改。

> `PlanModeContext`（是否处于 plan 阶段、当前计划文件路径）跟 `AgentState` 走，是运行时状态；`plans/` 下只是 markdown 内容本身。

### `agents/<agentId>/`

运行时根，框架自己写，一般不动：`sessions/`（索引 + append-only 日志）、`tasks/`（子 agent 后台任务记录）。

> 序列化的 `AgentState`（`agent_state`）**不**在工作区，在 `AgentStateStore` 里。

### `knowledge/`

`KNOWLEDGE.md` 全文进 `<domain_knowledge_context>`；同目录其它文件（任意深度）只把**路径清单**进 prompt，agent 用 `read_file` / `grep_files` / `glob_files` 按需读。这种「目录里全是细节、prompt 里只放索引」的模式避免撑爆 token 预算。

## 写入工作区的安全规则

`additionalContextFile`、`writeUtf8WorkspaceRelative`、`memory_get` 接受**工作区相对路径**，框架做基本 path-traversal 校验（拒绝 `../../etc/passwd`）。

> 写文件**走 `HarnessAgent#getWorkspaceManager()`，不要用 `java.nio.Files`**——后者在沙箱或共享存储模式下会写到错的地方。例外：builder 装配期的初始化脚本（如生成 `AGENTS.md` 种子），那时还没有运行时上下文，用 `java.nio.Files` 是对的。

## Heron 现状

`AgentRuntimeConfig` 当前调用了 `disableWorkspaceContext()`、`disableDynamicSkills()`、`disableDefaultWorkspaceSkills()`、`disableSubagents()`、`disableToolsConfig()`、`disableMemoryTools()` / `disableMemoryHooks()`、`disableSessionPersistence()` / `disableTranscript()`，也没有 `.workspace(...)`。

也就是说：本页描述的工作区机制目前在 Heron 里**全部关闭**，agent 只有 `sysPrompt` 里那一段静态提示词。要重新接上，最小的两步是 `.workspace(path)` + 去掉对应的 `disable*`；但注意 2 条硬约束：

- 一旦用了沙箱 / 远端 filesystem，就必须配分布式 `stateStore`，否则 `build()` 抛 `IllegalStateException`。
- 用 `tools.json` 的 `allow` 白名单会把 Harness 内置工具一并砍掉，要保留的必须显式列出。
