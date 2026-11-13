# 真实机会重跑：Agent 找出三个真缺陷，也编造了一条证据

**日期：** 2026-11-13
**输入：** 真实机会 `2121151796750819328`（frankfurter-audcny，65 点，评分 69.94）
**执行：** 真实 Spring 上下文 + 真实 `DatasetFetchService` + 真实模型，工具调用 18 次，42.9 秒
**结论：** 判 `OBSERVE`（正确），但推理里有一条**伪造的证据**

---

## 一、它做对了什么

它没有直接采信 headline，而是：

1. 调 `query_opportunity_detail` 拿到信号明细，发现 `entity` 为空、`metric=aud_cny`；
2. 调 `query_trend_series` 想核对，**得到 0 个点**；
3. 又换了 60 点、7 点，并查了 `merged_sources` 里点名的 eur/jpy/chf 三个源——**全部 0 点**；
4. 据此判 `OBSERVE`，理由是「证据链无法核验」。

**这个判断是对的**，而且它是在没有任何外部提示的情况下反复换参数去验证的。

---

## 二、它找出的三个真缺陷（已核实）

### 2.1 `query_trend_series` 现在返回 0 点

根因查到：`api.frankfurter.app` 已 **301 重定向**到 `api.frankfurter.dev/v1/...`。

```
$ curl -sL -o /dev/null -w "%{http_code} %{url_effective}" api.frankfurter.app/...
200  https://api.frankfurter.dev/v1/2026-11-01..2026-11-13?from=AUD&to=CNY
```

数据源注册表里存的是旧域名（`008-data-source.sql`），适配器拿不到数据 → 空序列 → 空点。

**注意：这不是一直失败。** 20 分钟前那次分析还拿到了 65 个点。所以是刚发生的变化或间歇性故障。

### 2.2 合并键把不同货币对当成同一件事

机会里的 `merged_sources` 是：

```
frankfurter-jpycny, dkkcny, eurcny, cadcny, sgdcny, chfcny
```

六组不同的货币对，被合并成了「澳元兑人民币」这一条的「同向 7 个源」。

根因在 `DataOpportunityService.merge()` 的键：

```java
String key = candidate.column().key() + '|' + candidate.unit() + '|'
           + direction(...) + '|' + signalType() + '|' + windowPoints();
```

**没有 entity。** 于是所有人民币对只要栏目、单位、方向、类型、窗口一致就被并成一条。
原意的「三个 USGS 震级门槛同源」是同一量的多入口，而这里是不同实体——**合并得太宽了**。

### 2.3 机会没有存快照，事后无法核验

`heron_data_opportunity` 只存了派生出来的 `signals`，没存当时那 65 个点。

后果：源一挂，这条机会就**永久不可复核**——Agent 拿不到原始数据，人也拿不到。
plan.md §8 要求「关键结论必须能追溯到具体证据」，这里只追到「信号」，追不到「数据」。

---

## 三、~~它编造了一条证据~~ → 更正：会话记录跨轮污染

> **本节结论已推翻（同日稍晚核实）。** 初版判它「凭空生成数据」，是**我错了**。
>
> 在仓库根发现了 `heron/.agentscope/workspace/agents/heron-research/sessions/default.jsonl`，
> 其中「0.4712」出现 8 次、「机会 900」也在里面。也就是说：**它读到了自己上一轮的会话记录**，
> 而那一轮用的是测试桩数据（机会 900 是我在 `AgentResearchRealRunTest` 里造的假机会，`0.4712` 是假序列）。
>
> 所以真相不是「模型编造」，而是**两次运行共用同一个 `sessionId=default`，会话被持久化并重新加载**。
> `disableSessionPersistence()` 并没有阻止这件事。

### 这个问题的实际危害

| 危害 | 说明 |
|---|---|
| 测试数据进入真实推理 | 机会 900 是测试夹具，却被当成真实历史引用 |
| 运行之间不隔离 | 同一 `sourceKey` 的旧结论会被带到新一次研究里，无法判断它这次到底看了什么 |
| 复盘困难 | 日志对比只能看出「说了不该存在的数据」，看不出「它是从哪轮会话里读到的」 |

### 顺带澄清两个「看起来矛盾」的点

1. **它说 3.6σ 高估，其实是口径不同。** `DatasetSignal` 的 z 是「末值相对前 30 期**水平**均值」，
   它复算的是「7 期**变动率**序列的 z」，两者不是同一个统计量。它的 -2σ 没错，但不能用来否定 -3.6σ。
   根因是 `signals` 没写清 z 是拿什么算的——**字段缺口径说明，模型只能猜**。
2. **它说「上一轮我按 0.47 量级判 10 倍」，是跨轮记忆，不是本次推理。** 这一点反过来证明会话确实被加载了。

**结论：这次运行不构成「模型会编造证据」的证据。** 真问题在会话隔离与信号口径说明。

---

## 四、这对方案意味着什么

| 发现 | 结论 |
|---|---|
| 工具能自主多轮补查 | ✅ 已验证两次，可以信赖 |
| 数据缺失时它会编造 | 🔴 **必须在 Phase 4 之前解决** |
| 合并口径过宽 | 🔴 需要把 entity 纳入合并键，否则「同向 N 个源」是假的 |
| 机会缺快照 | 🔴 不存快照，任何事后核验都做不了 |

最关键的一条：**事实核验 Agent 的价值不在于「再问一次模型」，而在于它只能拿到证据 ID 并回原始数据核对。**
如果原始数据本身没存，核验 Agent 也只会一起编。

所以优先级要改：

1. **先让机会快照落库**（比接更多 Agent 角色更急）；
2. **修合并键**（不然「同向 N 源」是误导性结论）；
3. **修 frankfurter 域名**（一行注册表更新）；
4. 然后才谈多 Agent。

---

## 五、复跑

一次性 runner（不进仓库）：`D:/zhoupei/tmp/heron-runner/RealResearchRunner.java`，
用 `java -Djarmode=tools -jar heron.jar extract` 解出 classpath 后直接跑，
`runner` 里的 `-Dserver.port=0` 保证不与已启动的服务抢端口。

```bash
cd heron && export HERON_REAL_MODEL_KEY=...
WIN=$(cygpath -w /tmp/heron-extract)
java -cp "D:/zhoupei/tmp/heron-runner/classes;$WIN\\heron.jar;$WIN\\lib\\*" RealResearchRunner
```

---

## 六、修复与验证（同日完成）

修了两处（第三处「机会存快照」经确认暂时不动）：

| 改动 | 文件 |
|---|---|
| 合并键加入 `entity` 与 `metric` | `DataOpportunityService.merge()` |
| 汇率域名换成 `api.frankfurter.dev/v1` | `FrankfurterAdapter`、`EastmoneyMarketCapAdapter`、4 个 SQL 种子、库中 13 行 `endpoint` |

**注意这不是纯修 bug：** 「五种货币同向合并成一条」原本是有测试保证的**有意设计**
（`mergesSameFamilySameDirection`）。改合并键等于反向了这个语义，那条测试已改写为
「同一量的多个入口才合并」，并新增「不同货币对不合并」的用例。测试从 299 项变成 300 项，全绿。

### 验证：重跑分析

| 指标 | 修复前 | 修复后 |
|---|---:|---:|
| 耗时 | 154.4 s | **86.4 s** |
| `mergedAway` | 6 | **0** |
| `created` | 1 | **6**（+1 更新） |
| `failed` | 28 | 28（全是 eastmoney / cheapshark，与本次无关） |
| 机会条数 | 1 | **7** |

库里现在是 7 条独立机会，每条 `merged_count=1`、`merged_sources=[]`：

```
frankfurter-audcny   aud_cny  65 点  69.94  澳元兑人民币 近 7 日 -2.39%，创窗口新低（3.6σ）
frankfurter-jpycny   jpy_cny  65 点  62.17  日元兑人民币 近 7 日 -0.95%，创窗口新低（3.3σ）
frankfurter-dkkcny   dkk_cny  65 点  54.71  ...
（其余 cad / eur / sgd / chf 同形）
```

两点确认：

1. **不再跨实体合并** —— 标题里「（同向 7 个源）」消失，`merged_sources` 为空。
2. ~~域名修好了，七个源都拿到 65 个点，之前是 0~~ —— **这条归因也要更正**：
   「0 个点」是**我的工具读错键**（数据在 `rows` 下，工具读的是 `points`），不是源的问题。
   域名 301 是真的，但只要适配器跟了重定向就不影响取数；改域名仍然正确，只是它**不是**那次 0 点的原因。
   工具已改为读 `rows`，并在返回里给出 `metric` / `unit`。

### 一个副作用要说清

「人民币对七个币种同步走弱」这个**故事**现在不存在了——它被拆成了七条互不相干的机会。

如果这个角度确实想要，它应该是一种**显式的跨实体信号**（例如「同向货币对数 ≥ N」），
由信号层主动识别并如实命名，而不是靠合并把七条并成一条、再在标题里写「同向 7 个源」。
后者看起来像互相印证，实际是不同实体，这正是本次 Agent 挑出来的问题。

