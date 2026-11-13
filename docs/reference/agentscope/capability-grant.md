# Capability Grant（能力裁剪）

> 上游：<https://java.agentscope.io/v2/zh/docs/building-blocks/capability-grant> · 快照：`_upstream/building-blocks-capability-grant.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：按用户/请求的实际权限，把 agent 无权的能力彻底切除

## 定位

**SkillFilter 决定这个 agent 设计上有什么；grant 决定这个用户被授权用什么。有效能力 = 两者之交。**

企业部署里同一个 agent 面向大量权限不同的用户。grant 层把无权的能力**彻底切除**——被切除的能力对模型**不存在**：不进 tool schema、不可枚举、不可被 `reset_equipped_tools` 激活、不可被 `load_skill_through_path` 加载、不可执行。Harness 的技能链路（`HarnessSkillMiddleware` 的提示词与技能目录、harness 版查看器）同样受授权面约束。

## 与其它层的关系

| 层 | 关系 |
| - | - |
| **grant（本页）** | 设置了白名单则只放行白名单内能力；未配置 resolver 或 resolver 返回 `null` 时为 `ALLOW_ALL`（不裁剪） |
| SkillFilter / 动态可见性（`skillFilter(...)`、RuntimeContext overlay、`filterVisible`） | 配置意图层：决定 agent 设计上展示哪些技能；运行时 overlay 可宽可紧。**grant 排在其后执行**，最终可见技能 = SkillFilter ∩ grant——overlay 抬不高授权面 |
| activation（`activatedGroups`） | 会话级软开关，决定已有能力「当前是否激活」；与 grant 独立叠加 |
| Permission system | 执行期审批（ALLOW / DENY / ASK）；grant 是**事前切除**，两者互补 |
| 外部工具（`ToolRequestConfig`） | 调用方经请求传入、在客户端执行，不在裁剪范围 |

组合链：一个能力要真正执行，须依次通过 **配置意图（SkillFilter）→ 用户授权（grant）→ 当前激活（activation）→ 本次 Permission 审批**。交集生效，同一个问题不放在两处配置。

> 与 [permission-system.md](permission-system.md) 的区别：权限系统管「这次调用批不批」，grant 管「这个用户根本看不到」。

## 接入

只需两件事：实现 `CapabilityResolver`，注册到 agent。

```java
CapabilityResolver resolver = (ctx, agent) -> {
    String userId = ctx.getUserId();
    // 粒度自选：按 (userId, agentId) 做 per-agent 差异，
    // 或忽略 agentId 按用户出一份数据（框架一视同仁）
    return new CapabilitySurface(
            authzService.allowedSkills(userId),      // 允许的 skill 名；null = 未设置
            authzService.allowedToolGroups(userId),  // 允许的 ToolGroup 名；null = 未设置
            authzService.allowedTools(userId));      // 允许的工具名；null = 未设置
};

ReActAgent agent = ReActAgent.builder()
        .name("enterprise-agent").model(model)
        .toolkit(toolkit)
        .skillRepositories(repos)       // 多租户用动态路径；勿用静态 skillBox()
        .capabilityResolver(resolver)   // 框架在每次 call 入口调用
        .build();
```

运行规则：

- **何时解析**：每次 `call()` 入口、服务端、恰好一次；返回 `null` 等同不裁剪。
- **生命周期**：结果随 `RuntimeContext` 走完整条调用链，**请求内冻结、不持久化**。
- **子代理**：未自配 resolver 的子 agent 直接复用父授权面，零重算。
- **Harness**：`HarnessAgent.builder().capabilityResolver(...)` 同样支持；解析发生在沙箱 acquire 与技能暂存之前，暂存清单、提示词目录、各道门看到同一份授权面。
- **缓存**：resolver 每次调用入口都执行；后端是 DB / RPC 时自行加缓存（按 `userId` + TTL），并接受权限变更生效延迟，框架不代管。
- **超集安全**：surface 可超出 agent 实际能力范围——本 agent 没有的名字不会被任何读点命中。

## 三维语义（必读）

`CapabilitySurface` 只有三种形态：

| 形态 | 语义 |
| - | - |
| 三个维度**全为 `null`** | **不裁剪**，全部放行（`ALLOW_ALL`） |
| 三个维度**全为 `Set.of()`** | **全部禁止**（system 能力除外，`DENY_ALL`） |
| 任意 1~3 个维度已设置 | surface 即**完整授权清单**：工具命中**任一已设置维度**（逐名 / 所属组 / 技能派生组）即通过；未设置的维度不产生任何授权 |

```java
// 仅授权 reporting 组内的工具
new CapabilitySurface(null, Set.of("reporting"), null);

// 完全不裁剪（唯一无条件放行的形态）
new CapabilitySurface(null, null, null);

// 全部禁止（除 system 能力）
new CapabilitySurface(Set.of(), Set.of(), Set.of());
```

`null`（未设置）与 `Set.of()`（明确为空）是两回事：前者「未参与」，后者「显式声明该维度零授权」。surface 一旦不是三维全 `null`，两者判定效果一致（都不放行），访问器（`allowedSkills()` 等）仍保留区分。

## 授权判定规则

只看白名单，命中**任一已设置维度**即放行：

- 授权一个 **skill** 即隐含授权其手册、脚本与关联工具（技能组约定组名 `skillId + "_skill_tools"`），**并自动放行经 `activateOnSkill` 绑定到该 skill 的自定义工具组**（call 入口把绑定组并入授权面）。
- 授权一个 **ToolGroup** 即隐含授权组内全部工具。
- 逐名授权一个**工具**即授权该工具本身（不问分组，也不放大到组内其他工具）。

**技能用名字授权，不用 skillId。** 名字是业务身份：一份名单条目覆盖该名字在目录中的全部副本（目录按标准语义合并，同名只保留高优先仓库那份）。skillId（`name + "_" + source`）仍是框架内部注册与寻址键（加载入参用 skillId）；标准命名禁止名字含下划线，故 skillId 第一个下划线必是分隔符、拆分无歧义。

只提供白名单，**不提供 deny**；默认拒绝——不在清单内的能力即「不存在」。

## 系统能力标记（`system`）

保证 agent 正常运行的核心能力不参与裁剪（框架元工具即属此类）。该标记不是业务形态的裁剪手段，**不要当业务开关用**。

框架元工具（`reset_equipped_tools`、`load_skill_through_path`）标记为 system，在任何授权面下（含 `DENY_ALL`）都可用。装配方也可把自有工具/组/skill 标记 `system = true`（`@Tool(system = true)` 或对应 Builder），注册时输出 INFO 留痕。

标记只作用于被标记对象，**不传染**：

- 工具 `system`：豁免该工具自身的可见与执行；
- 组 `system`：仅豁免该组自身的枚举与激活，组内成员工具仍受控（成员需豁免要逐一标记）；
- 技能 `system`：豁免其提示词可见与加载。

> **信任边界**：`system` 对装配方（agent 开发者）开放且可信。本层威胁模型是「终端用户 vs agent 能力面」，不设防装配方自身。

## fail-closed 行为

原则：**授权体系出问题时宁可中止，不可放开。**

- resolver 抛异常 → **中止本次调用**：异常传播给调用方并记 ERROR（授权体系故障 ≠「无授权」）。需要降级继续服务，就在 resolver 内部捕获并返回 `DENY_ALL`。
- **直连执行** → `Toolkit.callTool` / `callTools` 等不经 agent `call()` 入口的路径**不经过授权解析**，无 surface 即视为不裁剪；直连调用 `DynamicSkillMiddleware` 同理（rc 无 surface = 可信装配代码）。需要按权限裁剪时，在 `RuntimeContext` 里显式放入：`rc.put(CapabilitySurface.class, surface)`。

## 静态 skillBox 路径限制

`skillBox(...)` 已随 hook 系统在 2.0.0 标记过时，**新代码不要用**——技能接入一律走 `skillRepositories(...)` 动态路径。

- `skillBox(...)` 与 resolver 可以组合：静态提示词注入前按当次授权面裁剪；未启用授权（或 `ALLOW_ALL`）时提示词与历史版本逐字节一致；零技能获授时整块不注入；子代理继承父授权面时同样生效。
- **唯一被拒绝的组合**：`skillRepositories(...)` + `dynamicSkillsEnabled(false)` + resolver——没有 per-call 技能提示词可裁，等于无效授权，`build()` 直接抛 `IllegalStateException`（不允许静默退化成不裁剪）。

**持久化与降级**：授权状态（surface、托管标记、撤销范围）从不写入持久化状态；撤销清单（`projectedWorkspacePaths`）仅在启用授权后写入——未启用的部署持久化 JSON 与旧版逐字段一致，可安全回滚。已启用授权的部署回滚到旧版时，旧版无法识别该字段，会放弃恢复的沙箱状态并重建工作区（WARN，不影响调用）。多实例部署请配 `DistributedStore` 做真实执行互斥——默认 Noop 互斥下，同作用域并发调用的投影状态是最后写入者胜出。

## Heron 现状

- `AgentRuntimeConfig` 未配置 `capabilityResolver`，即 `ALLOW_ALL`：当前所有工具/技能对任何调用方都可见。工具集只有 `HeronResearchTools` 一个本地工具，风险面小。
- Heron 的授权边界现在由基座 `@SaCheckPermission` 在 **controller 层**把关，与 agent 内部能力面是两套。若将来 agent 工具集扩大（尤其引入技能仓库或用户可配置工具），应在 `HarnessAgent.builder().capabilityResolver(...)` 上按当前登录用户出 `CapabilitySurface`，让「这个用户根本看不到这个工具」而不是「看到了再被拒」。
- 注意 `disableDynamicSkills()` 已在调用列表中；若同时启用 resolver，需先恢复动态技能路径，否则会命中上文那个 `IllegalStateException`。
