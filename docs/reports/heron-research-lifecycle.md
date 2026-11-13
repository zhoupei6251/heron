# 研究任务的租约、取消与接口层

**日期：** 2026-11-13
**Phase：** 2（第四步）

---

## 一、租约续命：不是优化，是前提

原来的租约是 5 分钟，而一次研究最长跑 10 分钟（`TIMEOUT`）。也就是说**任何超过 5 分钟的研究
都会在跑完之前就被自己的租约判定为死任务**——回收逻辑一旦上线，就会误杀正在干活的执行方。

所以顺序必须是先续命、再回收：

- `ResearchLeaseKeeper` 每 15 秒一拍，把 `heartbeat_at` 和 `lease_until` 往前推；
- 领取条件从「只认 queued」放宽到「queued **或** running 且 `lease_until < now`」，
  执行方真的挂掉后，下一次触发能把这一行领回来，而不是永远卡在 running。

15 秒这个间隔同时是取消的最长生效延迟。

每次研究一个 daemon 线程、跑完即关。研究量级是「一天几条」，不值得为它建调度中心——
SnailJob 那套留给真正的定时任务。

## 二、取消：三级生效

| 阶段 | 行为 |
|---|---|
| 排队中 | `cancel()` 直接把状态写成 `cancelled`，`run()` 的领取条件不再包含它 |
| 执行中 | 续命线程读到 `cancelled` → 置位取消信号 |
| 下一次工具调用 | `ResearchBudget.tryAcquireTool()` 最先判取消 → 抛 `research_cancelled` |

为什么取消复用了预算的闸：**已经发出去的模型调用拦不住**（没有便宜的办法打断 `block()`），
但能立刻停止后续的工具调用与取数。模型看到「这次研究已被取消，请立即基于已获得的信息给出结论」
就会收尾，把已经查到的东西留下来——这和预算到点的处理是同一套语义。

执行中取消的收尾状态是 `cancelled`，不是 `partial`：用户喊停和预算耗尽是两件事，
看板上要能分开。

## 三、`partial` 的口径补齐

框架在 `iter >= maxIters` 时不再推理、直接转去总结（`ReActAgent` 的 `summarizing()`）。
所以**轮数跑满就等于被上限截断**，`rounds >= maxIters` 即标 `partial`。

现在 `partial` 有两个来源：预算到点（工具次数 / token）、轮数跑满。两者都是「不完整但可用」，
和 `failed` 的区别不变。

## 四、接口层

`POST /heron/research` 是**异步**的：一轮研究最长 10 分钟，同步等必然被网关掐断。
接口立刻返回当前状态，结果靠列表/详情回看。后台用 2 线程的守护池，`@PreDestroy` 时关闭。

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/heron/research/list` | `heron:research:list` |
| GET | `/heron/research/{id}` | `heron:research:query` |
| POST | `/heron/research` | `heron:research:run` |
| POST | `/heron/research/{id}/run` | `heron:research:run` |
| POST | `/heron/research/{id}/cancel` | `heron:research:cancel` |

两个容易漏的点：

1. **控制器也要条件装配**。服务是 `@ConditionalOnProperty(heron.agent.enabled)`，控制器无条件
   装配时，关掉开关会因缺 Bean 把整个应用带崩。已补同样条件：开关关掉，这几个路由不存在。
2. **`owner_id` / `actor_id` 不再是 0**。人工触发时取登录用户；定时/脚本入口没有登录上下文才记 0。

菜单迁移 `035-research-menu.sql` 用的是 `1761400000000003000` 段：`004-menu.sql` 会整段清理它自己的
2000–2999，新菜单放进那段会被下一次重跑顺手删掉。**页面还没建，所以父菜单 `visible='1'` 隐藏**——
菜单行在、权限能授予角色，但不给用户一个点进去 404 的入口。

## 五、账本脱敏

工具入出参是模型可以自由构造的，落库前把键名像凭据的值打掉
（`api_key` / `access_key` / `token` / `secret` / `password` / `authorization` / `cookie`）。
只有长度截断挡不住这类内容，而这张表会被长期回看。

## 六、验证

322 项测试全绿（新增 10 项）。涉及 wrapper 的用例都用真实 `LambdaCrudChainWrapper`，
断言的是**实际生成的条件与参数**，不是 mock 出来的假通过：

| 用例 | 断言 |
|---|---|
| 领取条件 | WHERE 里同时有 `status` 与 `lease_until`（没有后者，挂掉的任务永远卡 running） |
| 续命一拍 | 租约推到「现在 + 5 分钟」、心跳推到「现在」 |
| 取消探测 | 库里 `cancelled` → 信号置位 |
| 终止态 | 已结束不续命、行没了不抛 |
| 终态映射 | 轮数跑满 / 预算到点 → `partial`，正常 → `succeeded` |
| 取消信号 | 下一次工具被拒，且不多算一个工具名额 |
| 取消落库 | 只写状态，不碰结论 |

### 真跑：跑到一半发取消

用临时探针（验完已删）在真模型上跑了一次：第 8 秒发取消信号。

```
rounds=4  toolUsed=2  textLen=1506   ← 结论仍有内容

账本： round 1  query_opportunity_detail   ok
      round 2  query_trend_series         ok
      round 3  query_trend_series         failed  research_cancelled
```

前两次工具正常执行，第三次被取消拒掉，模型据此收尾并给出 1506 字结论。
「取消 → 工具被拒 → 收尾」这条链路是通的，不是只靠单测推出来的。

### 接口与页面验收（重启后端 + 菜单迁移后）

菜单迁移 `035` 执行到 dev 库，后端按 `--heron.agent.enabled=true --captcha.enable=false` 重启，
用 admin 在浏览器里跑了一遍：

| 动作 | 结果 |
|---|---|
| 侧边栏出现「研究任务」并进入页面 | 正常 |
| 列表加载 | 正常，3 条真实任务分页展示 |
| 详情弹窗 | 结论文本、轮数、工具、token、会话、目标都在 |
| 发起研究 | 立即返回「已发起」，行出现且状态为「执行中」，操作列自动变「取消」 |
| 跑完回填 | `succeeded`，rounds=3，tool_calls=2，token **8226 / 3846**，结论 2864 字 |
| `owner_id` / `actor_id` | `1761100000000000001`（真实登录用户），不再是 0 |
| 重跑 | 先把状态回退排队再领走，复用同段会话再跑一轮 |
| 列表自动刷新 | 25 秒不碰页面，行自己从「执行中」变成「已完成」（3 轮 / 2 次工具 / 7833–3655 token） |

**取消的真跑**：新机会（冷会话）创建后第 4 秒发取消，最终状态 `cancelled`，
但模型已经跑了 3 轮 3 次工具，留下 2670 字结论与 8593 / 4555 token ——
取消停下的是后续动作，不是已经花掉的推理。

### 验收中修掉的两个缺陷

**1. 「重跑」是死按钮。** 领取条件只认 `queued` 或租约过期的 `running`，已结束的行永远领不走，
按钮点下去什么都没发生。补了 `requeue`：显式把终态回退到排队再触发，执行中/排队中的不受影响。

**2. 取消会输给完成。** 续命线程 15 秒一拍，短任务（复用会话时可能只要几秒）会在下一拍之前跑完，
于是用户点了取消却看到 `succeeded`。现在收尾前回读一次状态：执行期间被取消过就以 `cancelled` 收尾。

### 还没验证

- **`maxIters` 到顶的 `partial`** 只验了状态映射，没有真的让模型跑到 8 轮上限。
- 表格在窄窗口下会横向滚动；当前页宽下不溢出，但没有专门测过移动端。
