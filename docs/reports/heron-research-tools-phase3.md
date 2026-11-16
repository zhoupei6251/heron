# 研究工具面补齐与预算闸收口

**日期：** 2026-11-13
**Phase：** 3（第一步）

---

## 一、先补闸，再补工具

顺序是刻意的：工具从 2 个加到 8 个之后，一次失控的调查能跑的表面成大得多。
没有时长和单工具超时，模型可以连着查八次、每次卡在框架默认的 5 分钟上。

### 1. 单次调查 5 分钟

`ResearchBudget` 多一个 `deadline`（构造时按 `Clock` 定死）。工具入口最先判它：

```java
if (!clock.instant().isBefore(deadline)) return TIME_EXCEEDED;
```

和 token 一样只能事后算，所以在**下一次**工具入口生效。`block()` 的超时改成
「预算时长 + 1 分钟」，多出来那一分钟是留给模型把「预算到点」总结成结论的——
预算闸负责停手，`block` 只负责兜底。

配置：`heron.agent.max-duration-seconds`，默认 300。

### 2. 单工具超时 10 秒

框架给工具的超时默认是 **5 分钟**（`ExecutionConfig.TOOL_DEFAULTS`），一个卡住的取数
会吃掉整个调查窗口。改成在 `Toolkit` 上统一配：

```java
new Toolkit(ToolkitConfig.builder()
    .executionConfig(ExecutionConfig.builder().timeout(toolTimeout).maxAttempts(1).build())
    .build());
```

配置：`heron.agent.tool-timeout-seconds`，默认 10。不重试：查不到就是查不到，重试只会把超时翻倍。

### 3. 参数越界改成拒绝，不再夹紧

原来的行为是 `Math.min(points, 60)`。夹紧和拒绝是两种语义：**夹紧会让模型以为自己查到了
想要的范围**，拒绝才会让它换个问法。现在越界直接抛 400，空 `sourceKey`、非正 `opportunityId`
同样拒绝。

`points == 0` 仍走默认值——那是「模型没传」的表示，不是越界。

---

## 二、8 个只读工具

| 工具 | 能看到什么 |
|---|---|
| `query_opportunities` | 候选池里还有什么（按状态过滤） |
| `query_opportunity_detail` | 一条机会的依据、评分、信号与落库快照 |
| `query_trend_series` | 某个源的序列末尾若干点 |
| `query_dataset_snapshot` | 某个源的结构、频率、总点数与首尾各两点样本 |
| `compare_series` | 一到三个源各自「近期窗口 vs 基线」的变化率与 z-score |
| `query_data_sources` | 还能查什么（分类、频率、可商用、是否启用） |
| `query_source_health` | 哪些源现在取不到数，以及累计失败次数 |
| `query_recent_productions` | 最近做过什么、审没审过、发没发出去 |

两个设计取舍：

- **`compare_series` 收逗号分隔的字符串而不是数组**。数组要过 victools 的 schema 派生，
  这里没有验证条件，宁可少一个失败面。语义上没差别。
- **算不出信号的源如实说 `unsupported`**，不返回 0。`DatasetSignal.of` 在点数不足、形态不支持、
  数据过期时返回 null，把它翻译成「这个源说明不了问题」比编一个中性值诚实。
- **`query_recent_productions` 的 id 转成字符串**：`versionId` 是雪花 id，超出 JS 安全整数，
  按数字返回会在前端失真。

工具面仍只有读：没有 insert/update/delete、没有任意 SQL、没有文件、没有 shell、没有发布。

---

## 三、验证

### 真实模型跑一次（8 个工具全部用上）

用 `AgentResearchRealRunTest`（真模型、假数据）跑：

```
Registered tool 'query_recent_productions' ... 'compare_series' ... 'query_source_health' ...
POST_REASONING tool_call: query_opportunity_detail
POST_REASONING tool_call: query_trend_series, query_dataset_snapshot, compare_series
POST_REASONING tool_call: query_source_health, query_data_sources, query_recent_productions
POST_REASONING tool_call: query_opportunities
```

四轮之内把 8 个工具全调了一遍，schema 没有被供应商拒绝。
这是这次改动唯一靠单测查不出来的一点——victools 派生 schema 曾经在这里翻过车。

### 新增测试（8 项，总 332 项全绿）

| 用例 | 断言 |
|---|---|
| 8 个工具 schema | 工具名集合完全匹配，每个都有入参 schema，`compare_series` 有 `sourceKeys`/`points` |
| 装配 | 开关打开后挂的正好是这 8 个只读工具，名字里不含 shell/file/write/edit |
| 越界点数 | 1 和 9999 都抛异常，且**一次都没去查源** |
| 空 sourceKey | 抛异常，不拿空串去查 |
| 时长闸 | 拨快时钟越过 5 分钟，下一次工具被拒且不计入工具名额 |
| 交叉比较上限 | 4 个 key 被拒 |
| 算不出信号 | 返回 `unsupported` 且没有 `zscore` |
| 源列表 | 不回 endpoint / termsUrl；`enabled` 是布尔而不是 `'0'` |
| 最近产出 | id 是字符串 |

### 没验证的

- **时长闸没有真跑到 5 分钟**：只验了逻辑，没让一次真实调查跑满。
- **单工具 10 秒超时没有构造真超时**：配置生效与否靠的是框架的 `ExecutionConfig`，
  没有拿一个会挂住的源去踩。
- 新工具在**真实数据**上的返回质量（尤其 `query_source_health` 依赖 `probe_status` 是否被写过）
  没有逐条核对，只验了形状与边界。

---

## 四、重启验收（dev 环境）

停服前确认生产队列与研究队列都没有在跑的记录，重新打包后以
`--heron.agent.enabled=true` 重启。浏览器里发起一条全新的机会
（`frankfurter-dkkcny`），真模型、真库：

```
succeeded  rounds=6  tool_calls=11  token 43227 / 4051  结论 3020 字

query_opportunity_detail  ok      query_opportunities      ok
compare_series            ok      query_recent_productions ok
query_dataset_snapshot    ok      query_data_sources       ok ×2
query_source_health       ok      query_trend_series       ok ×2
compare_series            ok
```

**8 个工具在真实数据上全部落地调用**，11 次调用没有一次失败。结论也确实用了新工具的信息：
它用 `compare_series` 发现 DKK/CNY 是 EUR/CNY 的机械镜像（30 点比值恒定在 7.474），
判 REJECT 并说明「独立信息量为零」。

### 验收中修掉的第三个缺陷

**对同一天已研究过的机会再点「发起研究」是静默无反应。** `createFor` 返回当天已有的旧任务，
而 `trigger` 只领得到的「排队中 / 租约过期」的行——已结束的行永远领不走，和「重跑」是同一个坑。

改成创建后走 `requeue`（回退到排队再触发）。实测：已 `succeeded` 的任务再点一次，
状态回到 `queued` 并真的重跑了一轮（attempt 2）。前端不需要改，它调的本来就是同一个接口。

> 这个缺陷和「重跑」同源：**入口只调 trigger 就一定会在已结束的行上静默失效**。
> 现在三个入口（发起、重跑、REST 的 /run）里，前两个走 requeue，`/run` 保留 trigger 语义
> （它本来就该只领可领的）。

