# 数据看板线：视觉模式层（2D / 3D）与首个三维地球模板

**日期**：2026-11-23
**范围**：`heron` 后端 `ruoyi-modules/ruoyi-heron` + 模板生成器 `data/tools` + `data/templates`。
**上游**：`docs/reference/root/reference_3d-visual-storytelling.md`（落地建议）、`docs/plans/2026-11-19-data-board-story-plan.md`（story/1）、`docs/plans/2026-11-17-video-visual-upgrade-design.md`（模板生成器）。

---

## 0. 一句话

**3D 不是新生产线，是数据看板线内部的一种视觉表达模式。** 先按故事定风格，再按数据形态定模式；只有带经纬度的 `geo` 数据进三维地球，其余保持 2D。模型不写渲染代码，渲染由确定性模板执行。

三条不可让的边界：

1. **2D 默认，3D 例外**：只有 `shape=geo` 才转 3D，其它形态一律 2D，不让「好看」决定模式。
2. **数字只有一个来源**：地球上的高度柱、图例与 KPI 全部来自 `dataset_json` 的真实行与 `facts`；三维只负责空间关系，不生成数字。
3. **降级不空屏**：WebGL 不可用时退回同一组数据的 2D 正交投影，画面仍可审片；渲染器 WebGL 卡死重试关闭 WebGL 时也一样。

---

## 1. 架构与数据流

```
DatasetStoryPlanner（geo 快照）
   └─ story.basis.signalType = "geo"
        ↓
VisualMode.of(story) = THREE_D          ← 机器判定，模型不参与
        ↓
NewsTemplatePicker.pick(line, narrative, mode)
   ├─ 按叙事收窄到风格（StoryStyleMap，仍是 1:1）
   ├─ 按 visualMode 收窄到实现（2d / 3d 模板）
   └─ 3D 缺失 → 返回 null，候选被拒；不悄悄退回 2D（2D 模板画不出经纬度）
        ↓
EditorialSelection.templateId（随冻结快照一起锁住，不新增快照字段）
        ↓
DatasetScriptContract.chartFor("geo") = "globe"
        ↓
NewsProductionPipeline.dataTemplateVariables
   ├─ chart = globe
   ├─ dataset_json = 生产快照
   └─ sceneN_focus = 按真实稿子重算
        ↓
news-data-3d-9x16（globe 能力，Three.js 地球 + 2D 兜底）
```

**为什么不加表 / 不加快照字段**：模式由 `templateId` 唯一决定，模板自己的 `template.json` 登记 `visualMode`；归因读模板即可，不需要第二份真相。冻结快照的 `schemaVersion` 仍是 `1.0`。

---

## 2. 关键改动

| 位置 | 改动 |
|---|---|
| `service/impl/VisualMode.java` | 新增 `TWO_D` / `THREE_D`；`of(story)` 只认 `basis.signalType == "geo"`；`ofKey` 认不出来一律 2D |
| `NewsProductionDomain.TemplateInfo` | 增加 `visualMode` 组件（缺省 2D），从 `template.json` 读取；保留 9 参构造，旧调用不改 |
| `NewsTemplatePicker` | 新增五参 `pick(..., narrative, mode)`；叙事收窄后按模式收窄；3D 缺失即拒绝，不退回 2D |
| `EditorialLineEditor` | 看板线两条路径（机会 / 速览）都按 `VisualMode.of(story)` 选模板 |
| `DatasetScriptContract` | `chartFor("geo") = "globe"`，`globe` 进入可渲染集合；契约测试同步改为「geo 走 3D」 |
| `DatasetFetchService` | 注释与语义对齐：geo 由 globe 承接后不再在取数闸被拒 |
| `prompt/data-board/script-contract.md` | 形态→章表补 `geo → globe`，渲染端白名单加 `globe` |
| `build-templates.mjs` | 新能力 `globe`；`visualMode` 只给 3D 写（2D 是缺省，避免全仓无意义 diff）；globe 模板复制并校验 `three.min.js` |
| `_gen/behaviors/globe.{js,css}` | 三维地球渲染、图例、KPI 与 2D 兜底 |
| `templates/news-data-3d-9x16` | 首个 3D 模板（data-board 风格、DATA_BOARD 线、示例 geo 数据集） |

---

## 3. 三维模板怎么落地

- **画面**：球面用 Fibonacci 点阵 + 极淡线框给结构；每个城市一条从球面伸出的高度柱，柱长与点大小按数值归一；最高值带一枚环。
- **文字**：地名与数值不进 WebGL，放在 DOM 图例里（前 3 名），中文字体走现有共用字体，不经过 canvas 合成。
- **确定性**：几何与初始朝向都是纯函数；旋转由 GSAP 时间线的 `onUpdate` 驱动。**不用 `requestAnimationFrame`**——抓帧只认时间线，墙钟时间会闪、会漏（首版已被 `hyperframes check` 拦下）。
- **兜底**：另一张 canvas 先用 2D 正交投影画出经纬网与高度柱；WebGL 就绪后切到上层画布。两张画布分开，避免「先取 2D 上下文后拿不到 WebGL」。
- **依赖**：`three@0.149.0` 的 UMD `three.min.js` 随模板提交（与 `gsap.min.js` 同一种做法），渲染不联网；构建期从 `data/tools/node_modules` 复制并校验哈希。

---

## 4. 安全与诚实性

- 地球位置来自 `rows` 的 `latitude/longitude`，是真实坐标；数值来自同一行 `value`，示例数据明确标注「示例数据集」，不冒充真实新闻。
- 非 geo 数据若被人工误选这套版式，画布给「此版式用于空间数据」的说明，不拿别的数据硬凑三维。
- 3D 缺失时不降级到 2D：`chart=globe` 进 2D 模板只会在渲染期炸，拒绝发生在选模板层。
- 模板能力声明（`globe` / `visualMode`）由构建期校验：3D 模板必须带 globe，未知模式直接失败。

---

## 5. 验证（2026-11-23）

| 项 | 结果 |
|---|---|
| 后端主源码 / 测试源码编译 | 304 / 142 文件通过 |
| 模板生成一致性 | `build-templates --check` 19/19 通过 |
| 版式检查 | 新模板 `hyperframes check` 通过（lint / runtime / layout 0 错误） |
| 关键帧 | 8s / 20s / 34s 三帧目检：球体、高度柱、图例、KPI、场景均正常 |
| WebGL 兜底 | 强制关闭 WebGL 后同一时间点画面正常（2D 经纬网 + 高度柱） |
| 真实渲染 | 45s / 1080×1920 / 30fps / H.264，1 分 10 秒渲完（hardware GPU），抽帧确认球体、图例、字幕、KPI 入片 |
| 定向单测 | `VisualModeTest`、`NewsTemplatePickerTest`、`DatasetScriptContractTest`、`StoryStyleMapTest`、`StoryDecisionTest`、`EditorialLineEditorFrozenPoolTest`、`EditorialLineEditorTrendTest`、`DatasetFetchServiceTest`、`DatasetStoryContractTest`、`DatasetStoryPlannerTest`、`DatasetVisualFocusTest`、`SceneTypeGateTest` 共 87 条全通过 |

---

## 6. 未做 / 下一步

1. ~~**还没有 geo 数据源**~~ 已接入：`UsgsQuakeGeoAdapter`（USGS 近 30 天 4.5 级以上地震，地点/经纬度/震级），注册表种子 `script/sql/heron/046-data-source-geo.sql` + Flyway `V52__data_source_geo.sql`（幂等）。真实出片仍需部署执行迁移并跑一轮生产验证。
2. **3D 资产库**：第二个/第三个空间模板（地形、剖面）复用同一 `globe` 能力的扩展位，尚未建。
3. **成本与预算**：L-1 抽帧、WebGL 渲染耗时与显存占用未做长期观测；批量上线前需要一条渲染预算。
4. **对照实验**：按参考文档要求，先用 6~10 条视频对比 2D / 3D 的 3 秒留存、完播与单条成本，没有明显收益不扩大。
5. ~~**前端提示**~~ 已补：模板列表 API 透出 `visualMode`，模板目录卡片显示 3D 标签，工作室模板下拉与能力块标注 3D（空间数据）。
6. **字体**：仍是单字重 otf 声明可变字重，粗体靠浏览器合成（与既有视觉报告同一遗留项）。
7. **成片保留**（2026-11-23 追加需求）：本地成片副本保留 30 天——OSS 模式定稿上传后不再即时删本地；`NewsCleanupRunner` 每小时驱动既有 `cleanupExpired`（30 天阈值、发布保护、活动任务与 `.keep` 判定均沿用），到期同时删桶对象与本地副本。

## 7. 明确不做

- 不新增 `ProductionBizType`、不新建 3D 生产线、不加数据库表。
- 不让模型输出 Three.js / Blender 代码；渲染永远是模板的事。
- 不在无空间关系的选题上默认使用 3D。
- 不把 Blender / Manim 引入生产运行时。
