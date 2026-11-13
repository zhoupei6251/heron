# 源健康：让注册表的探测状态由真实取数写回

**日期：** 2026-11-13
**前置：** `docs/reports/heron-step1-data-opportunity.md`（步骤 1 冒烟发现 20%–37% 的源失败）

---

## 一、问题

`heron_data_source` 有两列运行期状态，但生产代码从来没人写过它们：

| 列 | 谁写 | 实际情况 |
|---|---|---|
| `probe_status` | 只有迁移 SQL | 登记时人工探一次写 `ok`，之后永不更新 |
| `failure_count` | 无人写 | 列存在，代码里零引用 |
| `probe_note` | 无人写 | 同上 |

后果在步骤 1 的冒烟里直接暴露：同一批 `probe_status = ok` 的源，三轮全量取数分别失败 **22 / 37 / 40** 个。注册表说能用，实际连不上一小半。

更麻烦的是 `DatasetRouter.eligible()` 用 `probe_status = 'ok'` 过滤候选源——它筛的是「很久以前某次人工探测过」的结论，而不是「现在能不能取到数」。

---

## 二、方案：取数即探测

不新增探测任务，而是**把每次真实取数的结果写回注册表**。

理由：分析和生产每天已经会把每个源取一遍，再单独跑一个探测任务等于同一件事做两次。ponytail 的做法是让现有流量承担探测，而不是加一条新链路。

### 规则

| 时机 | 动作 |
|---|---|
| 取数成功（适配器产出 + 形状校验 + `dataset/1` 契约全过） | `probe_status = ok`、`failure_count = 0`、`probe_note = null` |
| 取数失败 | `failure_count + 1`；< 3 次仍记 `ok`；≥ 3 次记 `blocked`，`probe_note` 存错误信息（截断 500 字） |

阈值 3 的理由：单次网络抖动不该把源停掉，但连续三次失败基本可以判定不可用。

### 为什么分析可以绕过 blocked

如果分析也遵守 `probe_status = ok`，被 blocked 的源就永远不会再被试——一次判定成了永久毒丸。

所以 `requireReady()` 加了 `ignoreProbe` 重载：

| 调用方 | 是否要求 ok | 理由 |
|---|---|---|
| 生产 `fetch()` | 要求 | 不该把产出押在已知不可用的源上 |
| 分析 `snapshot()` | **不要求** | 分析是唯一的恢复入口：取到数就自动恢复 ok |

未启用（`enabled = '1'`）的源仍然两边都拒绝——绕过只针对探测状态，不针对人工关闭。

---

## 三、改动

| 文件 | 改动 |
|---|---|
| `mapper/HeronDataSourceMapper` | 新增 `markHealthy()` / `markFailed()` 两个默认方法；类注释从「不提供写入」改为「登记走迁移，这里只维护运行期健康状态」 |
| `service/impl/DatasetFetchService` | `fetched()` 加 `requireHealthy` 参数；成功调 `markHealthy`，失败调 `markFailed`；新增 `statusAfterFailure()`；`requireReady()` 加 `ignoreProbe` 重载（原 2 参方法保留） |

健康记账统一包在 `health(Runnable)` 里：写库失败只记日志，**不覆盖真正的取数错误**——记账是附带的，不能反过来决定取数结果。
| `service/impl/DataOpportunityService` | 分析不再按 `probe_status` 过滤；`Report` 新增 `unhealthy`（跑完后非 ok 的启用源数） |
| `service/impl/DataOpportunityRunner` | 日志加「不可用源」一列 |

**没有新增表、没有迁移**——三列本来就存在，只是没人写。

---

## 四、影响面

| 面 | 影响 |
|---|---|
| 数据库 | 无结构变更。`heron_data_source` 三列开始被写入 |
| 生产取数 | 成功时多两次 UPDATE（health + 新鲜度）；失败时多一次 UPDATE 并记 `probe_note` |
| 生产选源 | **行为变化**：连续失败 3 次的源会真的被排除；此前它一直以 `ok` 身份参与轮换，直到取数时才报错 |
| 分析 | 会尝试全部启用源（含 blocked），用于恢复 |
| API | 无变更；`/heron/dataSource/list` 现在能看到真实的 `probeStatus` / `probeNote` / `failureCount` |
| 前端 | **无需改动**：`asset/dataset/index.vue` 早就在展示探测状态、探测备注，并把 `failureCount > 0` 标红。健康数据一写回，页面直接可见 |
| 历史数据 | 已 blocked 的源需要一次分析成功才能恢复，这是设计行为 |

---

## 五、测试

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
```

| 项 | 值 |
|---|---|
| Tests run | **279**（本次改动前 277） |
| Failures / Errors | 0 / 0 |
| Skipped | 5 |
| BUILD | SUCCESS |

新增 2 项：

| 用例 | 断言 |
|---|---|
| 分析绕过 probe | blocked 源在 `ignoreProbe=true` 时放行；未启用仍然拒绝 |
| 失败升级 | 1、2 次仍 `ok`；3 次及以后 `blocked` |

| 项 | 值 |
|---|---|
| Tests run | 283 |
| Failures | 0 |
| Errors | 1（`DataBoardRealChainTest` 缺 `HERON_REAL_MODEL_KEY`，环境问题） |
| Skipped | 5 |

两条线真渲染都通过（数据看板 134.3 s、解说线 161.6 s），说明取数路径加健康记账没有影响生产。

---

## 六、未验证 / 风险

| 项 | 说明 |
|---|---|
| 没在真实库上跑过 | `markHealthy` / `markFailed` 是真实的 UPDATE，只有 `heron_it_*` 隔离库能验证。当前 mysql 组跑不了 |
| blocked 数量未知 | 需要真实跑一轮分析才能看到「今天到底有多少源可用」 |
| `markHealthy` 用 `.set(probeNote, null)` | 项目里已有同类先例（`NewsProductionDomain` / `NewsWorkServiceImpl` 清 `activeTaskId`），写法可用；但本表未实跑验证 |
| 没有主动探测 | 若分析长期关闭、且生产从不选中某个源，那个源的健康状态就停在旧值。这是被动探测的固有边界 |
| 恢复依赖分析 | 分析关闭时，blocked 是一次性判定，不会自愈 |

---

## 七、下一步

源健康修好后，「今天有多少源可用」变成可观测的数字。接下来是步骤 1 报告里列的第二项——**给每个源标「什么算大变化」**，否则 CPI、气温、地震计数仍然共用同一套阈值。
