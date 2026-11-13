# 步骤 3：机会进入生产并收尾

**日期：** 2026-11-13
**前置：** `docs/plans/2026-11-13-data-opportunity-discovery-plan.md` 第七章「步骤 3」
**上游：** `heron-step2-editorial-opportunities.md`

---

## 一、先说范围

计划里步骤 3 的三件事——线内编辑锁源、拆掉新闻短路、槽位原因细分——前两件在步骤 2 已经做了（不做的话步骤 2 的决策链是空转的）。

所以这一步只剩收尾的三件事：

1. **机会做成片子之后置 `used`**（原计划漏了这个，是个真缺陷）
2. **槽位空原因细分**
3. **机会 ID 进入冻结上下文**，让「这条片子为什么做」可反查

---

## 二、改动

### 2.1 机会消费后置 used

`heron_data_opportunity` 从步骤 1 起就有 `status` 和 `used_version_id` 两列，但**没有任何代码写过它们**——机会只会因为人工淘汰变成 `rejected`。

后果：同一天第二个槽位建池时，第一条机会还是 `new`，会被再挑一次，产出同一条内容。

现在生产成功后调用 `HeronDataOpportunityMapper.markUsed(opportunityId, versionId, now)`，把状态置 `used` 并记下版本号。

记账失败只记日志，不回滚已经产出的片子——片子是对的，账错了不能倒过来。

### 2.2 槽位空原因细分

原来空池一律记 `no_candidate`。看着像「没有素材」，实际可能是三种完全不同的情况，排查时会往错方向找。

| 情况 | 原因码 |
|---|---|
| 没配任何自动新闻源，也没有机会 | `no_news_source_and_no_opportunity` |
| 有源，但 24 小时内没有新鲜候选，也没有机会 | `no_fresh_candidate_and_no_opportunity` |
| 有机会 | 不空，照常往下走 |

`buildPool()` 现在返回 `BuiltPool(pool, emptyReason)`，槽位 detail 直接用 `emptyReason`。

### 2.3 机会 ID 进冻结上下文

`EditorialSelection` 增加 `opportunityId`（可空，只有从机会来的数据看板选择才有）。它随 `selectionNode()` 写进冻结输入，于是：

- 「这条视频来自哪条机会」可以从任务输入直接读出来；
- 与 `heron_data_opportunity.used_version_id` 双向对得上。

**只有非空时才写这个键**，解说线的冻结快照因此与改动前逐字节一致，`FrozenContext.hash` 不受影响。

---

## 三、影响面

| 面 | 影响 |
|---|---|
| 数据库 | 无迁移。用的都是已有列 |
| API | 无变更 |
| 冻结上下文 | 数据看板（机会来源）的选择多一个 `opportunityId` 键；解说线不变 |
| 任务重试 | hash 按内容重算，旧任务存的是旧 JSON + 旧 hash，校验照旧 |
| 槽位 detail | 三个新原因码替代 `no_candidate`。前端没有解析这个字段，不需要改 |
| 同日重复生产 | 修掉了：机会用过之后当天不再进池 |

---

## 四、测试

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
```

| 项 | 值 |
|---|---|
| Tests run | **290** |
| Failures / Errors | 0 / 0 |
| Skipped | 5 |
| BUILD | SUCCESS |

断言层新增：`focusedOpportunityLocksItsSource` 现在同时断言 `selection.opportunityId() == 900L`——确认机会 ID 真的传到了最终选择上，否则「置 used」无从下手。

| 项 | 值 |
|---|---|
| Tests run | 294 |
| Failures | 0 |
| Errors | 1（`DataBoardRealChainTest` 缺 `HERON_REAL_MODEL_KEY`，环境问题） |
| Skipped | 5 |

两条线真渲染都通过（数据看板 119.4 s、解说线 173.8 s）。

---

## 五、未验证 / 风险

| 项 | 说明 |
|---|---|
| `markUsed` 没在库里跑过 | 真实 UPDATE，只有 `heron_it_*` 隔离库能验；mysql 组仍跑不了 |
| 槽位原因码只在 mysql 测试里断言 | `NewsAutoTickTest` 属于 mysql 组，本地没运行 |
| 机会用掉后不再进池 | 同一天后续槽位只能等新机会；如果当天只算出 1 条，第二个槽位会空着 |
| `NoSuchElement` 风险 | `markUsed` 按 id 更新，id 不存在时静默 0 行；不会报错也不会误标 |
| 产出失败时机会不回收 | 认领失败会 `abandonAuto`，但机会仍是 `new`，下一槽位会重试——这是想要的 |

---

## 六、这条线到此的状态

```
数据采集(119 源) → 信号与合并 → 机会表 → 候选池 → 总编辑 → 线内编辑锁源 → 生产 → 置 used
       ↑                                                                          ↓
   源健康探测 ←──────────────── 取数结果写回 ────────────────────────────────────┘
```

每一段都有代码和单测，**但没有一段在真实环境里跑通过**：
`auto-enabled` 与 `data-analysis-enabled` 都还是关的。

下一步要做的不是继续加功能，是把它打开跑一天。
