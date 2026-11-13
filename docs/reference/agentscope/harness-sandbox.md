# 沙箱（Sandbox）

> 上游：<https://java.agentscope.io/v2/zh/docs/harness/sandbox> · 快照：`_upstream/harness-sandbox.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：隔离执行 + 跨调用恢复 + 多副本部署

三种文件系统模式的对比见 [harness-filesystem.md](harness-filesystem.md)，本页专讲沙箱模式。

## 解决什么

把 agent 的**文件操作和命令执行**收进隔离环境，宿主不参与。三个额外好处：

1. **执行边界** —— 不可信输入、奇怪脚本、可能 `rm -rf` 的命令都关进沙箱。
2. **跨调用恢复** —— 不只恢复对话状态，连 `pip install` / `npm install` / 临时文件这些**可执行环境**也进快照，下次 `call()` 在同一沙箱继续，不用重装。
3. **多副本可用** —— 跨副本/跨进程服务同一逻辑用户时，沙箱状态共享同一 slot，任意节点都能 resume 出同一份工作区。

## 最小例子

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("code-agent").model(model).workspace(workspace)
    .filesystem(new DockerFilesystemSpec().image("ubuntu:24.04"))
    .build();

agent.call(msg, RuntimeContext.builder().userId("alice").sessionId("conv-1").build()).block();
```

同一 `userId` 的多次 `call()` 自动复用同一沙箱（或从快照恢复）；不同 `userId` 各自独立；`userId` 缺失时降级为按 `sessionId` 隔离。

## IsolationScope

配置都在 `SandboxFilesystemSpec`（如 `DockerFilesystemSpec`）上，核心参数 `isolationScope`：

| Scope | 谁共享 | 典型场景 |
| - | - | - |
| `USER`（默认） | 同 `userId` 的多个 session；userId 缺失自动降级 `SESSION` | 多用户 SaaS，同一用户跨会话保持工作区 |
| `SESSION` | 每个 sessionId 独立 | 严格按对话隔离 |
| `AGENT` | 该 agent 的所有用户/会话共享 | 公共工具型 agent、共享知识库 |
| `GLOBAL` | 一个 store 内全局共享 | 谨慎 |

`SESSION` 天然并发安全；`USER` / `AGENT` / `GLOBAL` 多副本部署建议配并发互斥。

## 跨调用恢复 = 快照

每次 `call()` 结束把工作区状态打包成快照，下次开始时按情况恢复：

- 容器还在 + 工作区还在 → 直接接着用（最快）
- 容器没了 → 拿快照重新起一个并恢复工作区
- 没快照 → 按 `WorkspaceSpec` 全量初始化（冷启动）
- 快照读取/恢复失败 → 本次 `call()` 报错可重试，**不会静默丢弃快照状态**

已打开的快照归档流即使初始化失败也会关闭。

| `snapshotSpec` | 适合 |
| - | - |
| `NoopSnapshotSpec`（默认） | 不持久化，容器没了就冷启动 |
| `LocalSnapshotSpec` | 宿主本地文件（单机长期运行） |
| `OssSnapshotSpec` | OSS / S3 兼容（多副本） |
| `RedisSnapshotSpec` | Redis（低延迟、小工作区） |
| `JdbcSnapshotSpec` | MySQL / JDBC BLOB（已有关系库） |

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/")))
```

`AGENTS.md` / `skills/` / `subagents/` / `knowledge/` 等宿主侧文件每次沙箱启动时按内容哈希增量同步进去；改了 `skills/` 里的脚本，下次 `call()` 就是新版。

## 分布式部署

同一 agent 多副本，要让任意副本接住同一用户的对话，需要三件事：

1. 分布式 `AgentStateStore`（如 Redis 实现）——`.stateStore(...)`
2. 非 `NoopSnapshotSpec` 的远端快照（OSS / Redis）——filesystem spec 上的 `.snapshotSpec(...)`
3. 合适的 `IsolationScope`（默认 `USER` 通常够）

```java
HarnessAgent.builder()
    .name("assistant").model(model).workspace(workspace)
    .stateStore(redisStateStore)                    // 分布式状态
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .snapshotSpec(ossSnapshotSpec)              // 跨副本快照
        .isolationScope(IsolationScope.USER))
    .build();
```

框架把沙箱元数据（容器 ID、快照指针、workspace-ready 标记）和 agent 运行时状态存在**同一个** `AgentStateStore` 里；配了分布式 store，跨副本 resume 自动可用。

用本地 `AgentStateStore`（默认 `JsonFileAgentStateStore`）却在构建期开沙箱时，框架会 warn：沙箱状态不能跨 JVM 恢复、也不能跨实例共享。

## 并发控制

`USER` / `AGENT` / `GLOBAL` 在多副本下，两个副本同时处理同一用户会都写同一 slot，最后写入者胜。要避免就用分布式锁。

**推荐**：`distributedStore(...)` 自动注入 stateStore + snapshotSpec + executionGuard：

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04").isolationScope(IsolationScope.USER))
    .build();
```

要自定义锁参数，在 spec 上显式覆盖：

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.USER)
    .executionGuard(RedisSandboxExecutionGuard.builder(jedis)
        .leaseTtl(Duration.ofMinutes(30)).build()))
```

内置实现：`RedisSandboxExecutionGuard`（Redis `SET NX PX`）、`JdbcSandboxExecutionGuard`（MySQL `GET_LOCK()`）。也可实现 `SandboxExecutionGuard` 接 Zookeeper / etcd。

## 自管沙箱实例（高级）

**1. 已有容器，让 agent 用它**

```java
Sandbox mySandbox = dockerClient.create(workspaceSpec, snapshotSpec, options);
mySandbox.start();

SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)
    .externalSandbox(mySandbox)       // 框架 call 结束只 stop()，不 shutdown()
    .build();

agent.call(msgs, RuntimeContext.builder()
    .sessionId("my-session")
    .put(SandboxContext.class, callCtx)
    .build()).block();

mySandbox.shutdown();
```

**2. 有具体快照串，恢复到那个时刻**：`SandboxContext.builder().client(...).externalSandboxState(savedState)`——框架按该 state 恢复，但生命周期仍由框架管。

**3. 多 agent 共享同一沙箱**：同一个 `externalSandbox` 透传给多个 agent 的 `call()`，最后由你 `shutdown()`。

## 后端怎么选

| 后端 | 适合 |
| - | - |
| **Docker** | 本地开发 / 单机 / 信任 shell |
| **Kubernetes** | 自建 K8s；基于 [agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox)（SandboxClaim / WarmPool），工作区持久化靠 PVC |
| **Daytona** | 通用托管沙箱 HTTP API |
| **E2B** | 通用托管沙箱 + 平台原生快照 |
| **AgentRun** | 阿里云托管（函数计算 FC 3.0），实例级 NAS / OSS 动态挂载，中国大陆低延迟；与其他后端等价对待，接入细节归 integration 文档 |

所有后端实现同一组接口，agent 代码、工具集、`AGENTS.md` 都不用变。

### 工作区根目录

系统提示词里的沙箱目录说明和文件工具的路径归一化都用**当前调用绑定的**工作区根；恢复旧会话时保留已保存的目录，不改用 Agent 的新配置。根目录内的绝对路径会转成工作区相对路径，其他路径继续受文件系统策略约束。

- E2B 默认 `/home/user`，可用 `new E2bFilesystemSpec().workspaceRoot("/home/user/project")` 或带 root 的 `WorkspaceSpec` 改；两者都显式给出时根目录必须一致（忽略末尾斜杠），否则构建时直接报错。恢复根目录字段不一致的旧 E2B 状态时以已保存的实际执行目录为准，只对齐 manifest、不移动文件。
- 只提供 `WorkspaceSpec` 配 entries/environment 而没调 `setRoot(...)` 时，其默认 `/workspace` 不算显式配置，会沿用 E2B 执行根；但主动 `setRoot("/workspace")` 属于显式选择，会和另一个不同的 `workspaceRoot(...)` 冲突。
- Docker 默认仍是 `/workspace`。

## 运行时镜像约束

镜像不是随便选的。Harness 的文件工具（`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`）和快照机制**全靠在沙箱内执行 POSIX shell 命令**实现，镜像不满足契约时工具会以难排查的方式失败。

### 基线约束（所有后端通用）

| 类别 | 要求 | 谁在用 |
| - | - | - |
| Shell | POSIX 兼容 `sh`（支持 `[ ]`、`&&`、管道、重定向、heredoc） | 所有文件工具、`execute` |
| 核心工具 | `mkdir` `dirname` `rm` `mv` `test` `printf` `sort` | 各文件操作 |
| 文本/查找 | `sed` `grep`（支持 `-rHnF` `--include`）`find` | `read_file` 分页、`grep_files`、`glob_files` |
| 元数据 | GNU 风格 `stat -c`（非 BSD `stat -f`） | `list_files`、`glob_files` |
| 归档/编码 | `tar`、`base64`（编码 + `-d`） | 快照持久化/恢复、文件上传下载 |
| 解释器 | 可选 `python3` | `edit_file` 原生快速路径；缺失时回退下载/替换/上传 |
| 文件系统 | 工作区根（默认 `/workspace`）可写 | 全部 |

`ubuntu:24.04`、`debian` 系天然满足；`alpine`（BusyBox 的 `stat` / `grep` 行为不同）和 distroless **不满足**。没有可执行 `python3` 时编辑仍可通过文件传输回退路径工作，但会慢。

镜像内自检：

```bash
sh -c 'echo ok' && { python3 --version 2>/dev/null || echo 'python3 缺失；edit_file 将使用传输回退路径'; } && tar --version \
  && printf x | base64 | base64 -d && stat -c %Y /tmp && grep -rHnF --include='*.txt' x /tmp; true
```

`edit_file` 会拒绝完整命令（含编码后的参数与脚本）超过 **112 KiB** 的请求，超大编辑要拆多次——限制针对整条命令，不是分别限 `oldString` / `newString`。

### Kubernetes（agent-sandbox）附加约束

agent-sandbox 后端不进容器 `kubectl exec`，而是访问运行时容器暴露的 HTTP API（默认 8888）：

| 端点 | 语义 |
| - | - |
| `GET /capabilities` | 返回 `{"execute":{"timeout_seconds":true,"max_timeout_seconds":900,"cleanup_timeout_seconds":6}}`。SDK 提交命令前检查支持情况与请求时长；缺失或无效时拒绝执行，不静默忽略 timeout |
| `POST /execute`（`{"command": "...", "timeout_seconds": 30}`） | **必须以 POSIX shell 语义解释 command**（等价 `sh -c`），返回 `{"stdout","stderr","exit_code"}`。Harness 发的命令含 `cd ... && (...)`、管道等结构 |
| `POST /upload`（multipart，`filename` 为相对路径） | 写到文件 API 根目录下 |
| `GET /download/{path}` | 按相对路径下载字节 |
| `GET /list/{path}`、`GET /exists/{path}` | 目录列表 / 存在性检查 |

`timeout_seconds` 是正整数秒，覆盖排队、进程创建与执行、stdout/stderr 排空；到期终止进程组，在 `cleanup_timeout_seconds` 内清理，以退出码 `124` 返回已收集输出。父 shell 退出后被子进程继承的输出管道同受此期限；自行创建新 session 的脱离进程不在保证内。

- `execute` 工具默认 30 秒，直接调 SDK `commands().run(command)` 默认 60 秒，都会显式发时长。每次命令 POST 的 HTTP 等待预算 = 执行时长 + 运行时清理预算 + 连接器 `perAttemptTimeout`；命令 POST 之前的能力预检另用 `perAttemptTimeout`，不算进上述公式。各层 gateway/router 的请求与空闲时限须覆盖最大预算，或明确关掉限制（路由仍固定 180 秒时，300 秒命令过不去）。
- 先发布满足契约的运行时与路由设置再接入。缺 capabilities 的旧镜像会在提交命令前被拒（含工作区初始化与文件工具）。**开始提交命令后**的 HTTP 错误、等待超时或无效响应表示**执行结果未知**（退出码 `null` / outcome `UNKNOWN`），不等于进程已被终止。能力校验失败抛 `SandboxRequestException.RuntimeCapabilityException`，保留原始 HTTP 状态，应用应按运行时故障处理（即使状态是 400/404）。已确认支持协议的运行时中，请求时长超上限按普通 422 参数错误处理。命令 POST 不自动重试、不跟随重定向。
- **文件 API 根目录必须与工作区根一致**（推荐都用 `/workspace`）。`/upload` / `/download` 传两类内容：工作区快照 tar（临时文件放根目录下 `.agentscope-tmp/`）与单文件字节（Linux 单命令行参数约 128 KiB 上限，走文件 API 的原生传输不受此限）。根目录用 `KubernetesSandboxClientOptions.fileApiBaseDir` 配（默认 `/workspace`），置空退回 base64-over-exec。

> agent-sandbox 上游示例运行时（`examples/python-runtime-sandbox`）用 `shlex.split` 直接 `subprocess.run`、不经 shell，文件 API 根是 `/app`——**不满足本契约**，只能当端点形状参考。上游 KEP-539.2 在推进运行时接口标准化。

### 为什么这样设计

`Sandbox` 抽象的主数据面入口是 `exec(command)`——刻意的：`edit_file` / `grep_files` 这类语义（正则、字符串替换、glob）不可能靠有限文件 API 表达，靠镜像内标准工具链执行 shell 脚本是唯一通用解。文件 API 只承担纯字节搬运。**镜像契约本身就是沙箱接口的一部分**，换镜像前先跑自检。

**从模型视角跨越边界**：upload/download 是内部机制，对 LLM 不可见，`FilesystemTool` 不暴露传输工具。沙箱内 agent 把产物交给沙箱外的受支持方式是通用 `deliver_artifact` 工具——**只有当**你通过 `HarnessAgent.builder().artifactDeliveryTarget(...)` 配了 `ArtifactDeliveryTarget` 才注册。SPI 保持业务无关：`deliver(RuntimeContext, ArtifactDeliveryRequest) -> ArtifactDeliveryResult`，目标逻辑（如 WebDAV 上传）由应用实现。未配 target 时，沙箱工作区提示语会明确说明文件无法离开容器。

## Kubernetes HTTP 交换超时

K8s 沙箱请求同时限制响应头与完整响应体的等待时间（服务端发完头就不发体的也能超时返回）。命令请求用「执行时长 + 清理预算 + `perAttemptTimeout`」之和。命令交换超时表示执行结果未知；只有有效运行时响应才能确认退出码。

- 运行时返回 `timed_out: true` 表示进程已被执行时限终止并保留部分输出；HTTP 交换超时表示结果未知——**仅凭退出码 124 不能确认**运行时超时。
- 文件上传/下载默认 10 分钟交换预算；目录列表与存在性检查默认 60 秒。接受 `Duration` 的客户端方法用传入值作交换预算，重试与退避共享这份预算。
- 这些期限只限 HTTP 等待；连接发现与 pod IP 查询用各自机制。取消客户端交换不保证远端命令停止——要终止远端进程应在运行时侧设执行限制。

## Kubernetes 后端的状态保存：PVC 是第一层

K8s 后端完全基于 agent-sandbox：镜像、资源、存储声明在集群侧 `SandboxTemplate` / `SandboxWarmPool`，Java 侧只负责 `SandboxClaim` 领取与连接。由此带来一个与其他后端不同的点——**工作区持久化主要靠 PVC，而不是 Harness 快照**：

| 层 | 保存什么 | 恢复场景 |
| - | - | - |
| **PVC**（`SandboxTemplate.volumeClaimTemplates`） | 工作区文件本身 | pod 重启 / 驱逐 / 休眠唤醒——claim 还活着，文件随 PVC 原样回来，零传输 |
| **SandboxState + snapshotSpec**（Harness 层） | 身份指针（claimName / namespace）+ 工作区 tar 快照 | resume 时定位沙箱（始终需要）；claim 删除 / PVC 丢失 / 跨集群的冷恢复 |

框架不需要为 PVC 做特殊适配：resume 后启动时探测 `test -d /workspace`，PVC 在就命中「工作区仍存活」分支，快照恢复整个跳过。

**必须配对的三件事**：

1. **PVC 必须挂载在 `workspaceRoot` 上**（默认 `/workspace`）。挂错或用 `emptyDir`，pod 一重启工作区就丢，每次 call 退化成快照恢复甚至冷启动。

```yaml
apiVersion: extensions.agents.x-k8s.io/v1beta1
kind: SandboxTemplate
spec:
  podTemplate:
    spec:
      containers:
      - name: runtime
        image: your-conformant-runtime:latest   # 须满足运行时镜像约束
        ports:
        - containerPort: 8888
        volumeMounts:
        - name: workspace
          mountPath: /workspace                 # = workspaceRoot = fileApiBaseDir
  volumeClaimTemplates:
  - metadata:
      name: workspace
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 1Gi
```

2. **注意 claim 生命周期**：`shutdownTime` / `shutdownPolicy: Delete` 到期删掉 Sandbox 后，PVC 模式的热恢复就失效——下次 resume 找不到 claim，框架新建沙箱（新 PVC 是空的），能否找回工作区看第 3 条。
3. **按需选 snapshotSpec**：PVC + `NoopSnapshotSpec`（默认）省掉每次 call 结束的 tar 打包，代价是 claim 没了就冷启动；PVC + OSS / Redis 快照是双保险，代价是每次 call 结束多一次全量打包。

> 另提醒：`SandboxState`（身份指针）这层永远绕不开——多副本要配分布式 `AgentStateStore`，否则别的副本拿不到 claimName，PVC 里数据再完整也 resume 不回来。

## 工作区怎么映射进沙箱

K8s 共享 PVC 时，创建 Claim 时传由认证身份派生的固定目录：

```java
.claimEnv(List.of(new SandboxClaimSpec.EnvVar(
    "WORKSPACE_SUBPATH", "users/1001/2001", "python-runtime")))
```

`claimEnv` 把条目透传到 `SandboxClaim.spec.env`；集群模板须允许 env 注入，并通过 `subPathExpr: $(WORKSPACE_SUBPATH)` 把外部 PVC 子目录挂到 `/workspace`，存储权限在集群侧配。agent-sandbox v0.5.4 对带 env 的 Claim 跳过预热实例；新沙箱可在保留的 PVC 上续用同一目录。单次调用 env 为 `null` 时沿用客户端默认值，空列表清除默认值。

宿主 `workspace/` 下的关键文件每次沙箱启动时同步进去（按内容哈希增量，不变则跳过）。要把宿主目录 bind 进沙箱（如代码仓库）用 `BindMountEntry`——**仅 Docker 支持**；K8s 的挂载在集群侧 `SandboxTemplate` 的 podTemplate 里声明，Daytona / E2B 在云上跑自然挂不了宿主目录。

> 沙箱内对文件的修改**不会**反向同步回宿主——要取沙箱产物，让 agent 自己 `read_file`（或配 `deliver_artifact`）。

## 自己实现沙箱后端

接 Docker 以外的隔离环境（自建远端执行器、商用沙箱 API、本地 mock）不用改 Harness 源码——实现几个契约接口传给 `filesystem(...)`。参考 `agentscope-harness` 测试里的 `InMemorySandbox` 系列，是最小可改造骨架。

## Heron 现状

- Heron **没有**用沙箱：`AgentRuntimeConfig` 关了 shell 与文件系统工具，也没有 `.filesystem(...)`。研究层 agent 只调本地 Java 工具，执行面本来就在进程内，没有不可信代码要隔离。
- 数据看板线的渲染走的是 `data/tools` 下的 Node + HyperFrames（`HERON_NODE` / `HERON_HYPERFRAMES` 环境变量），**不是** AgentScope 沙箱。两者别混：沙箱是给 agent 执行任意命令用的，渲染是固定流水线。
- 如果将来要让 agent 跑模型生成的脚本（例如自己写数据抓取代码），沙箱是唯一的正确做法——尤其注意本页的「运行时镜像约束」，别用 alpine 打底。
- Heron 是单租户单实例自部署，`IsolationScope` 与分布式 `executionGuard` 都用不上；一旦上多副本，`.stateStore` 与 `snapshotSpec` 必须一起换成远端实现，否则沙箱跨副本 resume 是空话。
