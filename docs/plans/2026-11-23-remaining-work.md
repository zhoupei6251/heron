# 还没做的事（2026-11-23 按代码核实）

> 这份清单是逐条对着当前代码核出来的，不是照抄文档里的「未做」。
> 文档里的大多数「未做」其实已经做了（真片、3D 链路、可变字重、成片清理都已落地）。
> 每条都标了证据位置，方便直接开做。

## 真的还没做

| # | 欠账 | 现状（代码证据） | 下一步的卡点 |
| --- | --- | --- | --- |
| 1 | **compare 做成真正两栏对照** | `data/templates/_shared/storyboard.css:22-26` 只有面板底色，没有两栏结构；`data/templates/_gen/behaviors/chart.js:3-5` 没有 compare 分支 | 数据其实有：`DatasetFacts.java:77-83`（首末/变化）、`:116-132`（每序列首末、领先者）、`:144-180`（rows/geo 的领先者与差值）。缺的是契约：`DatasetScriptContract` 里 compare 零命中，得先定「比哪两个」 |
| 2 | **黑场/冻结帧从「只记」变成「拦」** | `VideoSignalCheck.java:29-45` 用 ffmpeg 算得出黑场与冻结，`:106` 解析；`NewsProductionPipeline.java:405-420` 注释明写「只记不拦」，只写 measured | 阈值没定。报告 `docs/2026-11-20-storyboard-and-visual-signal-report.md:216` 建议先收一个月量再定 |
| 3 | **人工编辑故事 + 故事历史表现回填** | story 由 `DatasetStoryPlanner` 生成；`controller/` 下没有任何 story 写接口，前端 `api/heron/editorial/index.ts` 只有 2 个 GET | 表现数据挂在版本上不挂故事：`NewsMetricBo.java:15-22` 按 versionId/platform/plays/completionRate；消费方 `NewsCandidateScreener.java:61,93-97` |
| 4 | **3D 资产库只有 1 个模板** | `data/templates` 下只有 `news-data-3d-9x16` 声明 globe；`data/templates/_gen/behaviors/globe.js:13` 只认 `dataset.shape === "geo"` | 要第二个空间模板（terrain / 剖面 / 柱阵），先定做哪种 |
| 5 | **渲染成本与预算观测** | `NewsProductionPipeline.java:875-884` 的 renderGate 只记 bytes + exitCode；`:1038-1048` 只校验退出码与文件大小 | 没有耗时/显存指标，也就没有预算线。`docs/plans/2026-11-23-data-visual-mode-3d-design.md` §6 #3 |
| 6 | **2D vs 3D 对照实验** | 没有实验脚本与对照报告 | 3D 线已有真片（`docs/acceptance-2026-11-23/geo-acceptance-summary.md`），但留存/完播/成本没记录，跑不出结论 |
| 7 | **agentscope 升级到 2.1.0-BETA1 + JEV 阶段 0** | `ruoyi-modules/ruoyi-heron/pom.xml:53/59/65` 三个依赖写死 `2.0.4`；JEV 全仓零代码（无依赖、无客户端、无金标集与离线评估） | 版本已拍板（`docs/reports/heron-jev-architecture-review.md`）；阶段 0 按该报告是「不引入 JEV，用现有账本 + 报告做离线评估并建金标集」 |
| 8 | **模板生成器里 `cfg.storyboard` 的死代码** | `data/tools/build-templates.mjs:97` 算了局部 `storyboard` 却没赋给 `cfg.storyboard`，真正生效的是配置里的显式 `"storyboard": true` | 改它等于给 14 个文稿/知识模板打开分镜样式（`sceneN_beat`/`sceneN_text`/`storyboard.css` 全进），视觉大改，要单独一轮验证 |

## 不做（设计上的决定，不是欠账）

- **案例拆解栏目**：`docs/plans/reference_github_4.md:399` 明确不单独建第五条生产线。
- **多租户、套餐、配额、计费、自助注册、第二套 RBAC**：`CLAUDE.md` 边界。

## 已经做了（别重复做）

- **分镜契约（报告 §六.1）**：模型已经写出真实分镜——geo 验收片 6 屏是
  `headline / number / compare / chart / text / compare`，`scene_count=6`。
- **3D 链路端到端**：2026-11-23 已跑通（16 道门禁全过，见 `docs/acceptance-2026-11-23/geo-acceptance-summary.md`）。
- **冲击屏**（本轮做的）：`docs/plans/2026-11-23-storyboard-takeover-design.md`。
- **字体可变字重**：`data/fonts/NotoSansSC-Regular.otf` 带 fvar/gvar 真可变表，文档里「单字重」的说法与代码不符。
- **成片保留 30 天**：`NewsCleanupRunner` 已在跑。
- **geo 数据源接入**：`UsgsQuakeGeoAdapter` + 迁移 `V52__data_source_geo.sql`。
