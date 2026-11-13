# 文件系统（Filesystem）

> 上游：<https://java.agentscope.io/v2/zh/docs/harness/filesystem> · 快照：`_upstream/harness-filesystem.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：三种部署模式：本机 + shell / 共享存储 / 沙箱；IsolationScope 隔离维度；多用户隔离；技能与工具在各模式下的行为

## 作用

`HarnessAgent` 把 agent 对**工作区**的访问从「一定是本机磁盘」抽象成统一接口。所有文件工具（`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`）和可选的 `execute`（shell）都走这个抽象，所以能在三种部署模式间切换而**不改 agent 代码**。

## 三种模式（`filesystem(...)` 三选一，不调即模式 3）

| 模式 | 配置 | 提供 shell？ | 适用场景 |
| - | - | - | - |
| **1 · 共享存储** | `filesystem(new RemoteFilesystemSpec(store))` | ❌ | 多副本共享 `MEMORY.md` / 对话日志 / 子任务到 KV；**不在宿主跑 shell** |
| **2 · 沙箱** | `filesystem(new DockerFilesystemSpec()...)` 或 K8s / Daytona / E2B / AgentRun | ✅（沙箱内） | 隔离执行、跨调用恢复工作区、可选快照 + 分布式 |
| **3 · 本机 + shell**（默认） | `filesystem(new LocalFilesystemSpec()...)` 或不写 | ✅（宿主 `sh -c`） | 单进程 / 本机 / 信任环境 / 脚本与测试 |

> `filesystem(...)` 与 `abstractFilesystem(...)` **互斥**；后者是完全自管文件系统的逃生口，正常用法不需要。

### 模式 1：共享存储（`RemoteFilesystemSpec`）

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent agent = HarnessAgent.builder()
    .name("store-agent").model(model).workspace(workspace)
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()   // baseStore 由 store 自动注入
        .isolationScope(IsolationScope.USER))
    .build();
```

| 方法 | 说明 | 默认值 |
| - | - | - |
| `isolationScope(IsolationScope)` | 命名空间隔离维度 | `USER` |
| `anonymousUserId(String)` | `userId` 为空时的兜底标识 | `"_default"` |
| `addSharedPrefix(String)` | 额外路由到 KV 的工作区相对路径前缀（如 `"prompts/"` / `"configs/"`） | 无 |
| `workspaceIndex(WorkspaceIndex)` | 加速远端 ls/glob/grep 的 SQLite 索引 | 不加索引，全量扫描 |

`WorkspaceIndex.open(workspaceRoot)` 初始化失败（如 `.index/workspace.db` 损坏）返回 `null` 并关闭已获取的连接，调用方可回退远端扫描；成功的索引持有连接直到 `close()`。

**内置路由规则**（每段独立命名空间）：

| 路径 | KV 命名空间段 |
| - | - |
| `AGENTS.md`、`MEMORY.md`、`tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |

不在表内的路径落到本地 `LocalFilesystem`（无 shell）。

**多副本客服 agent 场景**：三个 pod 各跑一个 `HarnessAgent`、共用一个 Redis `BaseStore`；本地磁盘上的 `AGENTS.md` / `knowledge/` / `skills/` 作只读模板（git 同步），运行时产物（`MEMORY.md`、`memory/`、对话日志）自动进 Redis，任意 pod 都读得到最新；用户 alice 的记忆在 `agents/customer-service/users/alice/memory/...` 下。

**`BaseStore` 实现**：

| 实现 | 说明 | 所属模块 |
| - | - | - |
| `RedisStore` | 基于 Jedis，低延迟高并发 | `agentscope-extensions-redis` |
| `JdbcStore` | 基于 JDBC，MySQL / PostgreSQL / H2 | `agentscope-extensions-mysql` |
| `InMemoryStore` | 内存实现，测试用 | `agentscope-harness` |

### 模式 2：沙箱

所有文件操作和 shell 命令都发到沙箱执行，宿主不受影响。

**Docker**

```java
new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.SESSION)
    .memorySizeBytes(512 * 1024 * 1024L)
    .cpuCount(2L)
    .network("host")
    .exposedPorts(8080, 3000)
    .environment(Map.of("NODE_ENV", "development"))
    .snapshotSpec(new LocalSnapshotSpec("/data/snapshots"))
```

| 方法 | 说明 | 默认值 |
| - | - | - |
| `image(String)` | Docker 镜像 | 必填 |
| `isolationScope(IsolationScope)` | 隔离维度 | `SESSION` |
| `memorySizeBytes(Long)` | 内存限制 | Docker 默认 |
| `cpuCount(Long)` | CPU 限制 | Docker 默认 |
| `network(String)` | Docker network | Docker 默认 |
| `exposedPorts(int...)` | 暴露端口 | 无 |
| `environment(Map)` | 容器环境变量 | 无 |
| `workspaceRoot(String)` | 容器内工作区挂载点 | `/workspace` |
| `additionalRunArgs(String...)` / `(List)` | 额外 `docker run` 参数，多次调用追加 | 无 |
| `snapshotSpec(SandboxSnapshotSpec)` | 快照策略 | `NoopSnapshotSpec` |
| `workspaceSpec(WorkspaceSpec)` | 工作区挂载规则 | 默认 |
| `executionGuard(SandboxExecutionGuard)` | 并发执行守卫（AGENT / GLOBAL scope 用） | 无 |
| `workspaceProjectionEnabled(boolean)` | 是否启用宿主→沙箱静态资产投影 | `true` |
| `workspaceProjectionRoots(List)` | 投影根路径 | `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache` |

**Kubernetes（agent-sandbox）** —— 沙箱 pod 由集群里的 agent-sandbox 控制器管理，镜像/资源/PVC 声明在集群侧 `SandboxTemplate` / `SandboxWarmPool`（不在 Java 侧），Java 侧通过 `SandboxClaim` 从预热池领取。**用前需先装控制器并建好模板与预热池。**

```java
new KubernetesFilesystemSpec()
    .namespace("agents")
    .warmPoolName("agent-pool")
    .isolationScope(IsolationScope.USER)
```

| 方法 | 说明 | 默认值 |
| - | - | - |
| `namespace(String)` | SandboxClaim 所在 namespace | `default` |
| `warmPoolName(String)` | `SandboxWarmPool` 名称 | 必填 |
| `workspaceRoot(String)` | 沙箱内工作区根，**必须落在模板声明的 PVC 挂载点上** | `/workspace` |
| `fileApiBaseDir(String)` | 运行时文件 API 根，须与 `workspaceRoot` 一致；置空退回 base64-over-exec | `/workspace` |
| `apiUrl(String)` | 直连运行时 API 的 URL（配了优先） | 无 |
| `gatewayName(...)` / `gatewayNamespace(...)` / `gatewayScheme(...)` | 经 Gateway API 访问沙箱 | 无 |
| `serverPort(int)` | 运行时 HTTP API 端口 | `8888` |
| `kubernetesClient(KubernetesClient)` | 自定义 fabric8 客户端 | 自动加载 kubeconfig |
| `snapshotSpec(SandboxSnapshotSpec)` | 快照策略 | `NoopSnapshotSpec` |

`apiUrl` 与 `gateway*` 都不配时默认用 `kubectl port-forward` 建本地隧道（适合开发）。运行时镜像须满足上游的「运行时镜像约束」，**工作区持久化依赖模板里的 PVC**。

**E2B**

```java
new E2bFilesystemSpec()
    .apiKey("${E2B_API_KEY}")
    .templateId("my-template")
    .sandboxTimeoutSeconds(300)
    .isolationScope(IsolationScope.SESSION)
```

对接自建 E2B 兼容后端（如 CubeSandbox）、数据面由单一固定网关承载时，设 `envdSandboxUrl`（等价官方 SDK 的 `E2B_SANDBOX_URL`），所有沙箱 envd 流量打到该地址，按请求头 `E2b-Sandbox-Id` / `E2b-Sandbox-Port` 路由，不需要泛域名 DNS：

```java
.apiBaseUrl("http://10.0.0.1:3000")     // 控制面
.envdSandboxUrl("http://10.0.0.1:8080") // 数据面
```

E2B volumes（private beta）提供独立于沙箱生命周期的持久存储：`volumeMount(name, path)` 在创建时挂载已存在的 volume，随沙箱重建自动重挂；平台 400（无 beta 权限、volume 不存在）会连响应体一起抛出。`workspaceRoot` 落在 volume 挂载点上时，TAR 工作区持久化自动跳过、销毁时也不 `rm -rf`，但原生快照仍执行以保留软件环境。volume 可被多沙箱同时挂载且**无锁保证**——workspace-on-volume 请按单写者使用。

**Daytona**

```java
new DaytonaFilesystemSpec()
    .apiKey("${DAYTONA_API_KEY}")
    .controlPlaneBaseUrl("https://api.daytona.io")
    .image("python:3.12-slim")
    .cpu(2).memory(4).disk(10)     // GiB
    .isolationScope(IsolationScope.USER)
```

**AgentRun（阿里云）**

```java
new AgentRunFilesystemSpec()
    .apiKey("${AGENTRUN_API_KEY}")
    .accountId("your-account-id").region("cn-hangzhou")
    .templateName("python3.12")
    .sandboxIdleTimeoutSeconds(600)
    .isolationScope(IsolationScope.USER)
```

**所有沙箱后端公共配置**（继承自 `SandboxFilesystemSpec`）：`isolationScope`、`snapshotSpec`（默认 `NoopSnapshotSpec`）、`executionGuard`、`workspaceProjectionEnabled`（默认 `true`）、`workspaceProjectionRoots`（默认 `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache`）。

**快照策略**

| 实现 | 说明 |
| - | - |
| `NoopSnapshotSpec` | 不快照（默认） |
| `LocalSnapshotSpec(Path)` | 存宿主本地磁盘 |
| `RedisSnapshotSpec` | 存 Redis |
| `OssSnapshotSpec` | 存阿里云 OSS |
| `RemoteSnapshotSpec` | 存 `BaseStore` |

**工作区投影**：沙箱启动时把宿主工作区的静态资产打成 tar hydrate 到 `/workspace`，按内容 SHA-256 增量比对，没变的跳过。用 `workspaceProjectionRoots(...)` 定制，`workspaceProjectionEnabled(false)` 关闭。

### 模式 3：本机 + shell（默认）

工作区落到 `${cwd}/.agentscope/workspace/`，shell 在宿主跑。

```java
.filesystem(new LocalFilesystemSpec()
    .executeTimeoutSeconds(120)        // shell 超时
    .maxOutputBytes(100_000)           // 单条命令最大输出
    .env("MY_VAR", "value")
    .inheritEnv(true)
    .mode(LocalFsMode.ROOTED)
    .project(Paths.get("/my/project")) // shell 的 cwd + overlay 下层
    .addRoot(Paths.get("/extra/dir")))
```

| 方法 | 说明 | 默认值 |
| - | - | - |
| `executeTimeoutSeconds(int)` | 单条 shell 超时（秒） | 120 |
| `maxOutputBytes(int)` | 单条命令最大捕获输出字节 | 100,000 |
| `env(String, String)` | 添加环境变量 | 无 |
| `inheritEnv(boolean)` | 是否继承父进程环境 | `false` |
| `mode(LocalFsMode)` | 路径解析策略 | `ROOTED` |
| `project(Path)` | 项目根（overlay 下层 + shell cwd） | `user.dir` |
| `addRoot(Path)` / `additionalRoots(Collection)` | 额外可访问宿主目录 | 无 |
| `projectWritable(boolean)` | 写项目文件时直接落项目目录而非 workspace | `false` |
| `namespaceBoundary(Boolean)` | 绝对路径只能落在调用者自己命名空间内；传 `true`/`false` 强制 | `ROOTED` 时开启 |

> **升级提示**：`ROOTED` 下启用命名空间边界后，`<workspace>/skills/tool.md` 这种绝对路径**不能**再触及 workspace 根下的共享内容（以前任一会话都能借绝对路径访问别人的目录）。共享内容仍可通过工作区**相对路径**访问（overlay 解析到只读项目层）；经安全评估的单租户 workspace 要保留旧行为，传 `.namespaceBoundary(false)`。

**路径策略 `LocalFsMode`**

| 模式 | 行为 |
| - | - |
| `ROOTED`（默认） | 绝对路径只允许 `workspace` + `project` + `additionalRoots`；`..` 穿越被拒 |
| `SANDBOXED` | 所有路径锚定 workspace 根，绝对路径与 `..` 全拒 |
| `UNRESTRICTED` | 绝对路径原样透传。仅测试或完全信任环境 |

**Overlay**：上层（读写）是 `LocalFilesystemWithShell`（根在 `workspace`，提供 shell），下层（只读）是 `LocalFilesystem`（根在 `project`）。读先 workspace 后 project（copy-on-write），shell 的 `pwd` 是 project 目录。

**`projectWritable(true)`**：默认所有写都落 workspace，对「生成代码」类任务是错的。开启后按路径自动路由：

| 路径类型 | 写入位置 |
| - | - |
| 工作区元数据（`MEMORY.md`、`memory/`、`agents/`、`skills/`、`knowledge/`、`plans/`、`subagents/`、`rules/`、`tools.json`） | workspace |
| 其他所有文件（`src/...`、`pom.xml`、`README.md` …） | 项目目录 |

读取行为不变（workspace 优先、project 兜底）。

## IsolationScope —— 谁和谁共享同一份状态

模式 1 和模式 2 共用这个概念：

| Scope | 含义 | 命名空间键 | 典型场景 |
| - | - | - | - |
| `SESSION` | 每个 sessionId 独立 | `agents/<agentId>/sessions/<sessionId>/...` | 多用户 SaaS，每段对话完全隔离 |
| `USER`（默认） | 同一 `userId` 跨会话共享 | `agents/<agentId>/users/<userId>/...` | 同用户多会话共享长期记忆 |
| `AGENT` | 该 agent 的所有用户/会话共享 | `agents/<agentId>/shared/...` | 公共知识库型 agent |
| `GLOBAL` | 全局共享一份 | `global/...` | 谨慎 |

**降级规则**：

- `USER` 下 `userId` 为空 → 降级 `SESSION`。
- `SESSION` 下 `sessionId` 为空 → 跳过状态查找，创建全新环境。
- `AGENT` 的命名空间由 agent name（build 时固定）决定，不因缺字段降级。

**沙箱并发**：同一 scope key 的并发调用各自启动独立容器，**顺序复用**而非实时共享；每次调用结束最后写入的快照胜出。`AGENT` / `GLOBAL` 这种多用户共享 scope 要串行化，用 `executionGuard(SandboxExecutionGuard)`。

## 多用户隔离

`RuntimeContext.userId` 是钥匙：

| 模式 | userId 的作用 | 物理表现 |
| - | - | - |
| 本机 | 用户级文件落 `workspace/<userId>/...`（如 `workspace/alice/skills/code-reviewer/SKILL.md` 只对 alice 生效） | 路径前缀 |
| 共享存储 | KV 命名空间前缀 `agents/<agentId>/users/<userId>/...` | KV 键前缀 |
| 沙箱 | 沙箱快照 slot key（配 `IsolationScope.USER`） | 沙箱实例隔离 |

不传 `userId` 走单租户默认，所有人共享一个根。

**运行时数据 vs 静态资产**：运行时数据（对话日志、tasks、memory）跟 `IsolationScope` / `userId` 走、自动隔离；静态资产（`AGENTS.md`、`tools.json`、`knowledge/`）对所有用户共享、**不**按 userId 分区，差异化只能靠用户覆盖目录（`<userId>/skills/...`）。

## 技能与工具在各模式下的行为

**技能**：`HarnessSkillMiddleware` 每次 call 前从技能仓库列表合并技能并渲染进 system prompt；技能文件走 `AbstractFilesystem`，三种模式透明。

| 模式 | 技能加载方式 |
| - | - |
| 本机 | 从 `workspace/skills/` 直读，`<userId>/skills/` 做用户覆盖 |
| 共享存储 | `skills/` 路由到 KV，先查远端再退本地模板；管理台改完所有副本下次 call 生效 |
| 沙箱 | 宿主 `skills/` 启动时经 workspace projection 注入沙箱 `/workspace/skills/` |

四层优先级不变（低 → 高）：`projectGlobalSkillsDir` → `skillRepository` → `workspace/skills/` → `<userId>/skills/`。

**文件工具**：全部经 `AbstractFilesystem` 调用，每次操作传入当前 `RuntimeContext`。

- `read_file` 默认每次最多返回 **16,000** 个 Unicode 码点，即使省略 `limit` 或单行很长也受限；`max_chars` 可调每页大小，但不超过应用配置上限（默认 32,000）。`offset` / `limit` 按行选内容；`char_offset` 从零开始，在**同一行范围返回的文本内**按字符分页。截断时结果给出下一页 `char_offset`，续读保持 path / offset / limit 不变；不会拆开 Unicode 代理对。拼接多页时应保持文件不变（偏移不是不可变快照）。
- 上限用 `HarnessAgent.Builder.readFileMaxChars(int)` 配（如 `.readFileMaxChars(64_000)`），**不得小于 16000**，更小抛 `IllegalArgumentException`；自动创建的子 agent 与声明式子 agent 都继承该配置。提高上限后，省略 `max_chars` 时默认读取量仍是 16,000。
- 转存后的工具结果文件也受限；这是所有后端共用的输出上限，不限制后端读取时的内存与 I/O。二进制文件按编码后文本分页，拼完整再解码。
- `edit_file` 的 `oldString` 不能为空，`newString` 不能为 `null`；删除匹配文本把 `newString` 设为 `""`。替换前把 CRLF 和单 CR 统一为 LF，写回用 LF。

| 模式 | 读写行为 |
| - | - |
| 本机 | `OverlayFilesystem`：写落 workspace，读先 workspace 后 project；`projectWritable(true)` 后非元数据写路由到项目目录 |
| 共享存储 | `CompositeFilesystem`：命中路由的路径走 KV overlay（远端上层 + 本地模板下层），其余走本地 |
| 沙箱 | 所有文件操作转发到容器内 |

**Shell（`execute`）**

| 模式 | 可用？ | 执行位置 |
| - | - | - |
| 本机 | ✅ | 宿主 `sh -c`，cwd 为 `project` |
| 共享存储 | ❌ | 不提供 |
| 沙箱 | ✅ | 沙箱容器内 |

**tools.json / MCP**：`build()` 时一次性从工作区读取（走 `WorkspaceManager`，支持两层读），注册 MCP server 并应用 allow/deny 过滤；**三种模式行为一致**。共享存储下也走「远端上层、本地下层」overlay，通过管理台改完**需重新 build 才生效**（MCP 注册是一次性的）。

## 两层读取与自管

`AGENTS.md`、`KNOWLEDGE.md` 等关键文件先读配置的后端，没有再退本地磁盘——对模式 1 的「模板文件」很有用。按身份的运行时数据（`MEMORY.md`、`memory/*.md`、task records）兜底落在 **caller 命名空间内**，不回退到 workspace 根。**写入永远走配置的后端。**

三种模式都不合适时可传完全自管的 `abstractFilesystem(myCustomFilesystem)`（与 `filesystem(...)` 互斥），通常不需要。

## Heron 现状

- `AgentRuntimeConfig` 显式调了 `.disableFilesystemTools()` 与 `.disableShellTool()`，也没有 `.workspace(...)` 或 `.filesystem(...)`：**文件系统这块整体关闭**，agent 只能调 `HeronResearchTools` 里那几个显式注册的工具——这是刻意的安全边界（类注释写明对应 `docs/plans/plan.md` §6.3）。
- 如果将来要让 agent 读写文件（例如把研究结果落到临时目录再整理），入口组合是：`.workspace(path)` + 去掉 `disableFilesystemTools()`，模式用默认的本机 `ROOTED`（单租户自部署最省事）；**不要**用 `LocalFsMode.UNRESTRICTED`。
- Heron 的单租户自部署意味着 `IsolationScope` 基本无意义（没有多用户共享需求）；但若引入沙箱，注意分布式 `stateStore` 是硬约束（见 [context-and-agent-state.md](context-and-agent-state.md)）。
- `.namespaceBoundary` 那条升级提示与 Heron 无关（Heron 没开文件系统），但如果以后把 `data/` 下的渲染产物暴露给文件工具，要记得 `ROOTED` 下只有 `workspace` + `project` + `additionalRoots` 可达，`data/` 需要 `addRoot`。
