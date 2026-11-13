# 权限系统（Permission System）

> 上游：<https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system> · 快照：`_upstream/building-blocks-permission-system.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：精细控制 agent 可以执行哪些 tool、何时执行

`io.agentscope.core.permission` 拦截 agent 的每一次工具调用，给出三种决策之一：**ALLOW** 执行、**DENY** 执行、**ASK** 询问用户确认。

三个组件共同决定结果：

| 组件 | 作用 | 可否被覆盖 |
| - | - | - |
| **Rules** | 针对每个 tool 与命令的显式 allow / deny / ask 模式，最高优先级。来源两种：`PermissionContextState` 静态预配置，或 ASK 提示中用户接受**建议规则**后动态加入 | — |
| **Mode** | 全局静态策略，决定不命中任何规则的调用的默认行为 | — |
| **Built-in Checks** | tool 自身在运行时基于真实入参做的动态分析（`ToolBase#checkPermissions`），**不可绕过** | 不受 mode / rules 覆盖 |

建议规则由本次工具调用自动生成，一旦接受，将来相同的调用被自动处理，不再询问。

### 决策流程

```
Tool Call
 ├─ 命中 Deny Rule?            → DENY
 ├─ 命中 Ask Rule?             → ASK
 ├─ Tool 自带检查
 │    ├─ EXPLORE + 写操作      → DENY
 │    ├─ 危险路径              → ASK
 │    └─ 通过
 ├─ 命中 Allow Rule?           → ALLOW
 ├─ ACCEPT_EDITS + 安全文件操作 → ALLOW
 ├─ 只读 Bash 命令             → ALLOW
 ├─ BYPASS 模式                → ALLOW
 ├─ DONT_ASK 模式              → DENY
 └─ 其余                       → ASK（并生成建议规则）
```

> **Deny 规则与危险路径检查不可绕过**——即使在 `BYPASS` 模式下也照常生效。

### 拦截器与权限引擎的分工

- 对**单次调用的具体参数**做业务规则（沙箱、租户隔离、审计）→ [工具调用拦截器](tool.md#工具调用拦截器)（`ToolkitConfig.interceptor(...)`）。
- **人审策略**（ALLOW / ASK / DENY）→ 权限引擎。

两层协同：权限引擎判定**模型原始输入**（ASK 确认框展示的也是原始参数）；拦截器随后作用于**最终参数**，改写发生在用户批准*之后*。若安全敏感参数（路径、SQL、URL）改写后的最终值需要用户确认，应在拦截器里挂起人审而非静默改写。拦截器拒绝一个已批准的调用属预期行为：拒绝结果直接回馈模型，不触发二次人审。

## Permission Mode

`PermissionMode`（`io.agentscope.core.permission.PermissionMode`）：

| Mode | 行为 | 适用场景 |
| - | - | - |
| `DEFAULT` | 所有操作都要显式规则或用户确认 | 最安全，推荐默认值 |
| `ACCEPT_EDITS` | 自动放行工作目录内的文件操作 | 用户在场的活跃开发 |
| `EXPLORE` | 只读：放行读，拒绝所有写与命令 | 代码探索、规划 |
| `BYPASS` | 放行一切（deny / ask 规则仍生效） | 完全可信的沙箱 |
| `DONT_ASK` | 把所有 ASK 转成 DENY | 无人值守 / 计划任务 |

```java
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;

PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent").sysPrompt("...").model(model)
                .permissionContext(permCtx)
                .build();
```

`ACCEPT_EDITS` 还需要登记工作目录：

```java
import io.agentscope.core.permission.AdditionalWorkingDirectory;

PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.ACCEPT_EDITS)
                .addWorkingDirectory(
                        "/my/project",
                        new AdditionalWorkingDirectory("/my/project", "userSettings"))
                .build();
```

## Permission Rule

`PermissionRule`（record）把某个 tool 与具体调用模式映射到 `ALLOW` / `DENY` / `ASK` / `PASSTHROUGH`。

| 字段 | 必填 | 说明 |
| - | - | - |
| `toolName` | 是 | 规则适用的 tool 名（内置 `todo_write` 或任意自定义 tool） |
| `ruleContent` | 否 | 匹配模式，语义随 `toolName` 变化，由该 tool 的 `matchRule()` 解释；`null` 表示匹配该 tool 的所有调用 |
| `behavior` | 是 | `ALLOW` / `DENY` / `ASK` / `PASSTHROUGH` |
| `source` | 是 | 规则来源：`"userSettings"`、`"projectSettings"`、`"session"`、`"suggested"` 等 |

```java
PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("safe_read",
                        new PermissionRule("safe_read", null, PermissionBehavior.ALLOW, "userSettings"))
                .addAskRule("dangerous_delete",
                        new PermissionRule("dangerous_delete", null, PermissionBehavior.ASK, "userSettings"))
                .addDenyRule("drop_table",
                        new PermissionRule("drop_table", null, PermissionBehavior.DENY, "userSettings"))
                .build();
```

**运行时追加建议规则**：ASK 决策里带 `suggestedRules`（位于 `ToolUseBlock` 上），接受时放进结果即可：

```java
ConfirmResult result =
        new ConfirmResult(
                /* confirmed = */ true,
                /* toolCall  = */ toolCall,
                /* rules     = */ toolCall.getSuggestedRules());
```

### 权限上下文与已存在的会话

非空 `permissionContext` 会在实例第一次加载某个会话槽位时，**替换**该槽位已持久化的权限上下文；传 `null` 时保持不变。

> 注意 `PermissionContextState.builder().build()` 是合法的 trivial context，**不等于「不改动」**——它会用默认 mode + 空规则替换旧上下文。要保留旧状态就传 `null`，或先读出来重建：

```java
PermissionContextState old = agent.getAgentState(userId, sessionId).getPermissionContext();
PermissionContextState next =
        PermissionContextState.builder()
                .mode(old.getMode())
                // 按需保留工作目录和规则
                .build();
```

## Built-in Checks

每个 tool 都实现 `checkPermissions(toolInput, context)`（位于 `ToolBase`），运行时基于真实入参检查，返回 `Mono<PermissionDecision>`。不可绕过。

`PermissionDecision` 四个静态构造：`allow(message)` / `deny(message)` / `ask(message)` / `passthrough(message)`。返回 `PASSTHROUGH` 表示「不强加判断，交给引擎按 rules / mode 评估」。

```java
public class MyTool extends ToolBase {

    public MyTool() {
        super(ToolBase.builder().name("MyTool").description("...").readOnly(false));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, ToolExecutionContext context) {
        Object target = toolInput.get("target");
        if (target instanceof String s && s.startsWith("prod-")) {
            return Mono.just(
                    PermissionDecision.ask("Operation targets production resource: " + s));
        }
        return Mono.just(PermissionDecision.passthrough("default"));
    }
}
```

### 危险路径保护

`ToolDangerousPathConstants` 维护默认列表；自定义 tool 可在 `@Tool` 上用 `dangerousFiles` / `dangerousDirectories` 追加。命中后即使在 `BYPASS` 下也强制 ASK。

| 类别 | 默认受保护示例 |
| - | - |
| Shell 配置 | `.bashrc`、`.zshrc`、`.bash_profile`、`.profile` |
| Git 配置 | `.gitconfig`、`.gitmodules` |
| SSH | `.ssh/config`、`.ssh/authorized_keys`、`id_rsa`、`id_ed25519` |
| 凭证 | `.env`、`.env.local`、`.npmrc`、`.pypirc`、`.aws/credentials` |
| 目录 | `.git/`、`.ssh/`、`.aws/`、`.kube/` |

## 结合 HITL

权限引擎返回 ASK 时 agent 不执行，而是暂停并返回 `GenerateReason.PERMISSION_ASKING` 的响应，`Msg` 里带 `ASKING` 状态的 `ToolUseBlock`。调用方展示给用户，收集决策后用 `ConfirmResult` 恢复。

```java
Msg result = agent.call(new UserMessage("Delete /tmp/important.txt")).block();

if (result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
    List<ToolUseBlock> askingTools =
            result.getContent().stream()
                    .filter(b -> b instanceof ToolUseBlock)
                    .map(ToolUseBlock.class::cast)
                    .filter(t -> t.getState() == ToolCallState.ASKING)
                    .toList();

    boolean approved = askUser();
    List<ConfirmResult> confirmResults =
            askingTools.stream().map(t -> new ConfirmResult(approved, t)).toList();

    Map<String, Object> meta = new HashMap<>();
    meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
    Msg resumeMsg =
            Msg.builder()
                    .name("user").role(MsgRole.USER)
                    .textContent(approved ? "approved" : "denied")
                    .metadata(meta)
                    .build();

    Msg finalResult = agent.call(List.of(resumeMsg)).block();
}
```

### 全部工具被拒绝

用户拒绝本轮推理产出的**全部**工具调用时，agent 默认继续下一轮——模型只看到 "Permission denied by user"，容易产生无效推理。要停止，挂一个 `onActing` middleware 观察 `AllToolsDeniedEvent` 并发 `RequestStopEvent`，之后 `Msg.getGenerateReason()` 返回 `ALL_TOOLS_DENIED`。见 [middleware](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.md#全部工具被拒绝时停止-agent)。

### Streaming 模式

用 `streamEvents()` 时不必从 `Msg` 提取，直接拿 `RequireUserConfirmEvent.getToolCalls()`；恢复方式相同。配置了状态存储时，agent 会先保存待确认状态和 replyId 再发布事件，保存失败则本次调用报错、不发布提示。

| | Blocking `call()` | Streaming `streamEvents()` |
| - | - | - |
| 待确认工具 | `Msg.getContent()` 里筛 `ToolCallState.ASKING` | `RequireUserConfirmEvent.getToolCalls()` |
| 恢复方式 | 构建 `ConfirmResult` 附 metadata，发起新 `call()` | 相同 |
| 适用 | REST API、同步服务 | WebSocket、SSE、实时 UI |

### 无人值守

`DONT_ASK` 下所有 ASK 自动降级为 DENY：

```java
PermissionContextState headless =
        PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addAllowRule("safe_read",
                        new PermissionRule("safe_read", null, PermissionBehavior.ALLOW, "policy"))
                .build();
```

## 常见配方

| 场景 | 配置 |
| - | - |
| 只读探索 | `mode(EXPLORE)`，写工具自动拒绝 |
| 无人值守自动化 | `mode(DONT_ASK)` + 显式 `addAllowRule`（如 `deploy` 限定 `"staging"`、`git_commit`）；其余静默拒绝 |
| BYPASS 下仍挡危险操作 | `mode(BYPASS)` + `addDenyRule("drop_table", ...)`、`addDenyRule("force_push", ...)` |

完整可运行示例：`agentscope-examples/documentation/.../tool/PermissionContextExample.java`、`hitl/PermissionHITLExample.java`。

> Heron 现状：`AgentRuntimeConfig` 未配置 `permissionContext`，工具集也只有 `HeronResearchTools` 一个本地工具，目前等价于「不设闸」。要接权限闸，入口就是 `ReActAgent`/`HarnessAgent` builder 上的 `.permissionContext(...)`。
