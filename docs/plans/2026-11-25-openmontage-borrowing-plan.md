# OpenMontage 借鉴优先级计划

**日期**：2026-11-25
**范围**：`ruoyi-modules/ruoyi-heron`（后端）、`data/templates/**`（只标注，不动）
**外部参照**：<https://github.com/OpenMontageInc/OpenMontage>（AGPLv3，只借方法，不搬代码）
**排序轴**：控风险 / 控成本优先（2026-11-25 拍板）

---

## 0. 一句话

**OpenMontage 值得借的不是它的工具链，是它给无人值守生产加的三道约束：钱有上限、选择有理由、节奏有阈值。**

heron 的结构比它规整（分层、契约、闸门都在），但自动模式在跑的同时缺三样东西：
花费算不出来、单次运行没有天花板、几个已经写好的阈值常量没有读者。
这三样都不需要新架构，只需要把已有的东西接上或删掉。

---

## 1. OpenMontage 是什么（只取可借的部分）

它把一次视频生产拆成 8 个阶段，每个阶段在 `pipeline_defs/*.yaml` 里声明：

| 字段 | 含义 |
| --- | --- |
| `produces` / `required_artifacts_in` | 阶段产物与依赖，构成 DAG |
| `tools_available` | 本阶段允许调用的工具白名单 |
| `review_focus` | 人 / 模型评审时看什么（定性） |
| `success_criteria` | 机器可判的通过条件（定量） |
| `checkpoint_required` / `human_approval_default` | 是否必须落检查点、是否必须过人闸 |

外层还有 `orchestration.budget_default_usd` / `max_revisions_per_stage` / `max_wall_time_minutes`，
以及 `styles/*.yaml` 把视觉规则从内容里分离（`motion.pacing_rules`、`audio.ducking_threshold_db`、
`color_rules.colorblind_safe`、`quality_rules`）。

**它对 agent 的定位**：没有代码层的编排器，AI 助手自己读 manifest、调工具、自评、落检查点、请求人批。
这一点 heron 不适用——heron 是服务端定编排、Java 定状态机，产品形态不同，不讨论。

---

## 2. 现状取证（2026-11-25 在 `dev` 上核实）

### 2.1 已经比它强、不要动的

| 能力 | heron 现状 | 位置 |
| --- | --- | --- |
| 模板静默替换防护 | 结构上不可能：冻结输入带 `templateKey`，渲染只认这个值，`assetsGate` 再核对目录名与 `LOCK.json` 一致 | `NewsWorkServiceImpl.java:296`、`NewsProductionPipeline.java:262`、`:1693` |
| 决策留痕 | `selected_line` / `decision_reason` / `candidate_id` / `pool_json` 全落库；NO_PUBLISH 也留痕；被否候选的理由挂在槽位上 | `NewsAutoService.markDecision`、`decisionReason` |
| 机器判 vs 模型判的分工 | `MACHINE_DECIDED` 8 条由代码给结论，`MODEL_DECIDED` 2 条只把证据交给模型 | `QualityCriteria.java:33-39` |
| 幻灯片风险 | 黑场拦（3% 像素阈，按暗底实测调过）+ 冻结帧只记 + `picture_motion` 判据 | `VideoSignalCheck`、`QualityCriteria.FREEZE_MAX_SECONDS` |
| 重试上限 | `MAX_ATTEMPTS=2`、`MAX_REPAIRS=2`、`MAX_REVIEW_RETRIES=2`、数据源连续失败判 blocked | 各 service 常量 |
| 阶段轨迹 | `heron_task_step` 全字段（stage/status/起止/error_reason/evidence/runtime_identity），前端已有表格 | `HeronTaskStep`、`production/index.vue:162` |

### 2.2 真缺口

| # | 缺口 | 证据 |
| --- | --- | --- |
| 1 | **没有任何金额概念**：`heron_usage_record` 只有 token 与耗时，无价格列；`ResearchBudget` 超限只让单次工具调用失败，不终止 run | `script/sql/heron/001-production.sql:54`、`ResearchBudget.java:16` |
| 2 | **自动模式无花费/墙钟天花板**：槽位跑完没有任何上限；`AutoPauseStore.pause()` 只有人工调用方；无「连续失败自动暂停」 | `HeronAutoController.java:61-70` 是唯一 `pause(true)` 调用点；`NewsAutoService` 无止损逻辑 |
| 3 | **单屏时长无上界**：两个契约只校验 `duration_s > 0` 与合计 30–75；`QualityCriteria` 的 `LONG_SCENE_MS` / `SHORT_SCENE_MS` / `PACING_RATIO` **零读者** | `NewsScriptContract.java:109`、`DatasetScriptContract.java:200`、`QualityCriteria.java:42-46` |
| 4 | **模板声明的时长没人消费**：`minDuration`/`maxDuration` 解析后只给目录页展示 | `NewsProductionDomain.java:750`，唯一读者 `TemplateCatalogService.java:32` |
| 5 | **选源理由被丢弃**：`DatasetRouter.plan()` 只留 `source_key`，模型其余输出直接丢 | `DatasetRouter.java:78` |
| 6 | **分阶段 checkpoint 是死代码**：`IProductionExecutionService.checkpoint(...)` 零生产调用方，`heron_task_step.checkpoint` 恒为 NULL，`resumeCheckpointJson` 只有测试在用 | `ProductionExecutionServiceImpl.java:186`；全仓无调用方 |
| 7 | **图表色板对色盲不友好**：明度按 `[46,58,66][index % 3]` 循环，第 0/3/6/9 条系列明度相同只差色相；支持下限到 20 条系列 | `data/templates/_gen/behaviors/chart.js:98-104`、`:301` |

---

## 3. P0 · 成本可见与自动刹车

**目标**：自动模式在无人看管时，花出去的钱有数、有上限、有刹车。

### P0-1 · 单价进配置，金额在读侧折算

不新增列、不新增迁移。单价走 `sys_config`：

```
heron.model.price-in     # 元 / 千输入 token
heron.model.price-out    # 元 / 千输出 token
```

`ResearchCostService.overview()` 已经在按 grade / decision 聚合 token，加一个乘数出金额即可。

**取舍**：历史记录按当前单价重算，不冻结当时单价。理由——单人项目没有跨期对账需求，
加列要多一次迁移加回填，收益不抵。若以后要在界面上看「这条视频当时花了多少钱」并冻结单价，
再改成写入时记账。

### P0-2 · 单次 run 的花费上限

在 `ProductionExecutionServiceImpl.beginStage` 里加一处检查：该 `(taskId, attemptNo)` 已累计
token 折算金额超上限则抛 `ServiceException`，run 终止。

- 不动 `NewsProductionPipeline.java`（当前由并行任务单 A 独占）。
- 上限走配置项，默认给一个宽松值，不写死。
- **必须是终止而不是提醒**。`ResearchBudget` 的教训就在这里：超限只让这一次工具调用失败，
  run 照常往下走，模型换个工具继续烧。

### P0-3 · 连续失败自动暂停

`AutoPauseStore.pause(true)` 目前只有人工接口在调。补一条自动触发：连续 N 个槽位失败即置暂停，
并在健康条留痕。

实现照 `DatasetFetchService` 的成熟模式抄——「连续失败够多次才判 blocked，单次抖动只记计数」，
不发明新的计数机制。

### P0 的边界

三条都不碰 `data/templates/**`、不碰 `NewsProductionPipeline.java`、不碰 `publish_*` 适配器，
与在跑的三份并行任务书无冲突。

---

## 4. P1 · 成片质量与可诊断性

### P1-1 · 单屏时长上界前移到契约

现状：3 段 75 秒里可以有一段 60 秒，契约放行、渲染、然后**没有任何地方报出来**
（`LONG_SCENE_MS` 是死常量）。渲染钱已经花完。

改法：`NewsScriptContract` 与 `DatasetScriptContract` 各加一条判断——单段超过 12 秒退回写稿。
12 秒这个数与 `QualityCriteria.LONG_SCENE_MS` 同源，不新造数字。

同时处理死常量：`LONG_SCENE_MS` / `SHORT_SCENE_MS` / `PACING_RATIO` 要么在契约里真正用起来，
要么删掉。留着一排没人读的阈值常量，比没有更误导。

### P1-2 · checkpoint 死代码：标记或删除，不接线

`checkpoint()` 零调用方意味着**当前不存在阶段级续跑**，渲染跑到第 4 段挂了前 3 段白做。

本计划**只清理，不接线**。理由：接线要动 `NewsProductionPipeline`（A 单独占），
而真实收益取决于失败分布——需要先知道是「渲染偶尔挂」还是「渲染经常挂在后段」。

处理方式二选一：删掉 `checkpoint()` 与 `resumeCheckpointJson`，或保留但加注释写明未接线。
**推荐删除**：一个看起来像已实现、实际没人调用的续跑能力，比明说没有更危险。

若后续确认渲染失败集中在中后段，单独立项接线。

### P1-3 · 图表色板色盲安全（归并行任务单 B）

`seriesColor` 的色相摆动只有 ±38°，明度三档循环，20 条系列时第 0/3/6/9 条对红绿色盲几乎不可分。

修法：拉长明度阶梯、缩小色相摆动，并加 `colorblind_safe` 校验。

**`data/templates/**` 归并行任务单 B**，本计划只登记，不实施。
需在 `data/templates/_gen/behaviors/chart.js` 改后跑 `build-templates.mjs` 重建。

---

## 5. P2 · 补齐已有声明的消费方

### P2-1 · 模板声明的时长要真的参与判定（归并行任务单 B）

`template.json` 的 `recommendedDuration`（如 `news-data-9x16` 是 30–60）只用于目录展示，
两个契约硬编码 30–75。一套按 45 秒设计的版式可以收到 75 秒的稿子——`timeComposition` 会按实测音频
重铺时间轴所以不会崩，但节奏被挤。

改法：契约不再用全局常量，按冻结输入里的 `templateKey` 读该模板声明的区间。
读模板目录属 B 单范围。

### P2-2 · 选源理由留痕

`DatasetRouter.plan()` 丢掉模型输出的其余字段。对比总编辑：`Decision` 有 `reason` 与
`editorialIntent` 并落库，**上一层留了理由、下一层没留**。

改法：`Route` 加 `reason`，落进现有的决策留痕（`heron_auto_slot.pool_json` 那套），不动表结构。

---

## 6. 明确不借鉴

| OpenMontage 能力 | 不做的理由 |
| --- | --- |
| 参考视频 → 多套提案 → 选题材 | 这是**新产品能力**（贴链接出方案），不是修补现有链路。要做是独立立项 |
| Backlot（文件驱动可回放看板） | heron 已有 `heron_task_step` + 前端阶段表格，是同一件事的数据库版；换文件驱动是纯退化 |
| provider 七维打分选型 | 单租户单模型配置（`sys_config` 三项），为单一供应商建打分表是给不存在的需求写代码 |
| `pipeline_defs/*.yaml` 多流水线 | 4 条固定线、8 个阶段在 Java 里由编译期检查兜底；YAML 化换来的是更少的类型安全 |
| JSON Schema 校验契约 | 9 个 `*Contract.java` 有测试在跑；换 Schema 只是把 Java 挪进 JSON |
| SSE 推进度 | 单用户后台，轮询够用 |
| `required_skills` 技能包体系 | heron 的编排在服务端，不依赖 AI 助手读技能文件 |

---

## 7. 执行顺序与冲突区

| 优先级 | 条目 | 触碰文件区 | 冲突判定 |
| --- | --- | --- | --- |
| P0-1 | 单价配置 + 读侧折算 | `ResearchCostService`、`sys_config` | 无冲突 |
| P0-2 | run 花费上限 | `ProductionExecutionServiceImpl` | 无冲突（不碰 pipeline） |
| P0-3 | 连续失败自动暂停 | `NewsAutoService`、`AutoPauseStore` | 无冲突 |
| P1-1 | 单屏时长入契约 | `NewsScriptContract`、`DatasetScriptContract` | 无冲突 |
| P1-2 | checkpoint 清理 | `IProductionExecutionService`、`ProductionLeaseTest` | 无冲突 |
| P1-3 | 色板色盲 | `data/templates/**` | **与任务单 B 冲突，须提给 B** |
| P2-1 | 模板时长消费 | 契约 + 读模板目录 | **与任务单 B 冲突，须提给 B** |
| P2-2 | 选源理由留痕 | `DatasetRouter` | 无冲突 |

**建议开局**：P0 三条 + P1-1 + P1-2 可并行，均不触碰 `NewsProductionPipeline.java` 与
`data/templates/**`。P1-3 与 P2-1 交 B 单，或在 B 单收尾后单独排。

---

## 8. 验收口径

每条改动必须能回答「哪条闸 + 哪段输入 + 为什么拒」，与业务规格 §3.7 一致。

- P0-1：给一组已知 token 的 run，折算金额与手算一致
- P0-2：故意配一个极小上限，run 在第二阶段终止并留下上限原因，不是静默跳过
- P0-3：连续 N 次失败后槽位不再被 cron 领取，健康条能给出触发原因
- P1-1：给一份单段 20 秒的稿，契约退回写稿并指出第几段、多少秒、上限多少
- P1-2：删掉后编译通过，`heron_task_step.checkpoint` 列保留（不删列，历史数据仍在）
