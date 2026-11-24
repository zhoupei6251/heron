# 还没做的事（2026-11-23 按代码核实）

> 这份清单是逐条对着当前代码核出来的，不是照抄文档里的「未做」。
> 文档里的大多数「未做」其实已经做了（真片、3D 链路、可变字重、成片清理都已落地）。
> 每条都标了证据位置，方便直接开做。

## 真的还没做

| # | 欠账 | 现状（代码证据） | 下一步的卡点 |
| --- | --- | --- | --- |
| 1 | **compare 做成真正两栏对照** | 已做（2026-11-23）：`docs/plans/2026-11-23-compare-two-column-design.md` | 原来只有「正文垫一层面板」，对照关系只在句子里 |
| 2 | **黑场/冻结帧从「只记」变成「拦」** | 黑场已拦（2026-11-23）：`VideoSignalCheck.blockingBlack()` 给出致命黑场，`NewsProductionPipeline.videoSignalGate` 抛 `news_video_black_frame`（409）；冻结仍只记 | 冻结阈值还没定。报告 `docs/2026-11-20-storyboard-and-visual-signal-report.md:216` 建议先收一个月量再定 |
| 3 | **人工编辑故事 + 故事历史表现回填** | 后端已做（2026-11-24）：`GET /{id}/story` 与 `POST /{id}/story/reproduce`，只换 story 一个键、basis 沿用、契约不过当场 409。按零人工定位**不再做人工页面**，改成 Agent 纠错通道：审稿证据失败先让 `StoryRewriter` 改写再重出片，改不动才换选题（`NewsAutoService` 重试循环）。见 `docs/plans/2026-11-23-manual-story-edit-design.md` | 表现数据挂在版本上不挂故事：`NewsMetricBo.java:15-22` 按 versionId/platform/plays/completionRate；消费方 `NewsCandidateScreener.java:61,93-97` |
| 4 | **3D 资产库只有 1 个模板** | `data/templates` 下只有 `news-data-3d-9x16` 声明 globe；`data/templates/_gen/behaviors/globe.js:13` 只认 `dataset.shape === "geo"` | 要第二个空间模板（terrain / 剖面 / 柱阵），先定做哪种 |
| 5 | **渲染成本与预算观测** | 耗时已记（2026-11-23）：`renderGate` 增加 `renderMs` / `videoSeconds` / `msPerVideoSecond`；本地渲一条 57.8s 成片 hyperframes 自报 3m8s（≈3.3× 实时） | 还没有显存/WebGL 指标，也没有预算线。`docs/plans/2026-11-23-data-visual-mode-3d-design.md` §6 #3 |
| 6 | **2D vs 3D 对照实验** | 没有实验脚本与对照报告 | 3D 线已有真片（`docs/acceptance-2026-11-23/geo-acceptance-summary.md`），但留存/完播/成本没记录，跑不出结论 |
| 7 | **agentscope 升级到 2.1.0-BETA1 + JEV 阶段 0** | 版本已升（2026-11-23）：`ruoyi-modules/ruoyi-heron/pom.xml` 三个构件 `2.0.4` → `2.1.0-BETA1`，编译通过、模块 707 例全过；构件能力面已核实（`docs/reports/heron-jev-2.1.0-beta1-capability-check.md`：JEV 扩展 68 类、`JevExecution$Mode` 带 `SHADOW`）。JEV 装配层已接（2026-11-23）：`agentscope-extensions-jev` 依赖 + `JevConfig`，默认关闭、缺 key 启动即拒，见 `docs/plans/2026-11-23-jev-assembly-design.md`。SHADOW 已接（2026-11-23）：挂在 `EditorialDecisionService.decide`，异步对照写 `heron_jev_shadow`（迁移 V54），不进任何分支 | 阶段 0 仍欠：用现有账本 + 报告做离线评估并建金标集。真实外呼已冒烟（2026-11-24：Command Code `typesafe/jev` 返回 ChoiceAnswer）；生产库配置与开关未动 |
| 8 | **模板生成器里 `cfg.storyboard` 的死代码** | 死代码已删（2026-11-23）：`build-templates.mjs` 里算了却没用的局部 `storyboard` 已去掉，规则写进注释——分镜由模板配置显式开启 | 给 14 个文稿/知识模板开分镜仍是另一件事：它们的稿子不产 beat 与上屏文字，开了 headline 屏会藏正文、藏标题卡，要等文稿线启用（`article-enabled: false`）后单独一轮渲染验证 |

| 9 | **自动线开关仍全关，人在当主力** | 2026-11-24 已补「失败槽位自动补跑」（迁移 V55 + `NewsAutoService.retryFailedSlots`，只补 failed 一次、间隔 30 分钟）。但 `heron-production.yml` 的 `pipeline-enabled` / `auto-enabled` / `auto-confirm-enabled` 与 `application-dev.yml` 的 `auto-enabled` 都还是 `false`——能力建好了没开 | 需要灰度决策：先开 `auto-enabled` 一条/天（sandbox 出口），`auto-confirm-enabled` 等开放平台凭据齐备再开 |

## 不做（设计上的决定，不是欠账）

- **案例拆解栏目**：`docs/plans/reference_github_4.md:399` 明确不单独建第五条生产线。
- **多租户、套餐、配额、计费、自助注册、第二套 RBAC**：`CLAUDE.md` 边界。

## 已经做了（别重复做）

- **分镜契约（报告 §六.1）**：模型已经写出真实分镜——geo 验收片 6 屏是
  `headline / number / compare / chart / text / compare`，`scene_count=6`。
- **3D 链路端到端**：2026-11-23 已跑通（16 道门禁全过，见 `docs/acceptance-2026-11-23/geo-acceptance-summary.md`）。
- **冲击屏**（本轮做的）：`docs/plans/2026-11-23-storyboard-takeover-design.md`。
- **compare 真两栏**（本轮做的）：`docs/plans/2026-11-23-compare-two-column-design.md`。
- **黑场闸与渲染耗时**（本轮做的）：`docs/plans/2026-11-23-video-signal-gate-design.md`。
- **字体可变字重**：`data/fonts/NotoSansSC-Regular.otf` 带 fvar/gvar 真可变表，文档里「单字重」的说法与代码不符。
- **成片保留 90 天**：`NewsCleanupRunner` 与 `ProductionArtifactCleanupRunner` 已在跑，分别覆盖业务版本和 `sys_oss` 登记的云端对象。
- **geo 数据源接入**：`UsgsQuakeGeoAdapter` + 迁移 `V52__data_source_geo.sql`。
