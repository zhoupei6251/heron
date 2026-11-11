# 编辑引擎实现计划

**依据**：[数据新闻编辑引擎设计 v2](2026-11-11-data-editorial-engine-design.md)
**目标**：把「总编辑 → 线内编辑 → 标题 → 正文 → 审稿」五段决策接进现有流水线
**原则**：每批可独立验证、可独立回退；不重写已有能力，只补缺失的两层和边界

---

## 批次 1：恢复解说线并验证

解说线被 `article-enabled: false` 关了一段时间，回归状态未知。**先证明它能跑，再往上面加决策层**——否则总编辑选了新闻线才发现跑不通。

**改动**

1. `heron-production.yml`：`article-enabled: true`
2. **字体缺口（本批真正的工作量）**：12 套 `news-*` 模板的 CSS 引用 `fonts/NotoSansSC-Regular.otf`，但目录里**没有 `fonts/`**，渲染时靠 headless Chrome 回退系统字体。Windows 上可能碰巧有中文字体，Linux 上会渲染成方块。
   - 每套各拷一份 17 MB 字体不可接受（12 套 = 204 MB）
   - 方案：在 `data/templates/_shared/fonts/` 放一份，管线准备 composition 时统一复制进工作目录
3. 修 `NewsProductionPipelineRenderTest`（已存在，渲染 `news-9x16`）

**验证**

```
跑 NewsProductionPipelineRenderTest，抽帧确认：
  - 中文不是方块
  - 字幕、标题、来源行都在框内
hyperframes check 对 news-9x16 报 0 error
```

**完成判据**：一条解说视频真渲染通过，肉眼确认中文字体正常。

---

## 批次 2：候选池 + 总编辑

**改动**

1. 新服务 `EditorialDecisionService`：
   - 输入：两条候选池的**摘要**（不是全量）、最近 7–30 天发布记录、编辑策略提示词
   - 输出严格 JSON：`{decision, selectedLine, reason, editorialIntent}`
   - `NO_PUBLISH` 是合法结果，run 记 `skipped`，不报错
2. 新提示词 `heron/editorial-daily.md`：写清两线权重（数据看板优先，解说线要真有爆款相）、`NO_PUBLISH` 的条件
3. 候选池摘要构造（Java）：
   - 看板池：热榜前 N 条 + 检索到的前 15 个数据集（名称 + 一句能讲什么 + 时效）
   - 解说池：只含**有正文**的 RSS 候选（热榜候选 `summary` 是来源名，不是摘要）
4. 决策结果落库，供后续追溯「今天为什么走这条线」

**验证**

- 单测：三条路径（两条线各一条、NO_PUBLISH）各一个固定输入 → 断言解析正确
- 单测：看板池为空时，总编辑不能选 `DATA_BOARD`
- 真跑一次，看日志里两池摘要的字符数（超过 8K 就要收紧）

---

## 批次 3：线内编辑（含模板选择与跨线回退）

现在是两处割裂：`DatasetRouter` 只选源，`NewsWorkServiceImpl:190` 的模板来自 `autoTemplate` 配置而不是模型。本批把「选素材 + 选形态」合并成一次模型调用。

**改动**

1. `DatasetRouter` 返回 `(sourceKey, templateId)`；模板候选由代码给出已验证兼容的组合（形态声明 → 模板），模型只在合法集合里选
2. `NewsCandidateScreener` 输出补 `storyAngle` 与 `keyPoints`
3. **跨线回退**：数据看板线内编辑返回「无合适数据集」时，再调一次新闻线编辑；两条线都无可选才 `NO_PUBLISH`
4. `NewsWorkServiceImpl` 的模板选择改为接受模型结果，配置值降级为兜底

**验证**

- 单测：模型返回非法模板 ID 时被拒，不进入渲染
- 单测：回退路径（看板无数据 → 走新闻线）有固定输入
- 真跑一条数据看板 + 一条解说，确认两条线都产出成片

---

## 批次 4：独立审稿 + 修复

**改动**

1. 新服务 `EditorialReviewer` + 提示词 `heron/editorial-review.md`
2. 两线的事实清单分开构造：
   - 看板：`validatedFacts`（快照 `facts` 节点 + 占位符解析出的 `factRefs`）
   - 解说：`quotes`（原文片段 + 来源 URL + 抓取时间）
3. 输出分级：`BLOCKER` / `MAJOR` 必修，`MINOR` 只记录；一次定向修复后必经 Java 硬闸再复审，不过则放弃
4. 审稿提示词明确禁止重新计算数据

**验证**

- 单测：构造「涨跌说反」的稿子 → 必须返回 `BLOCKER`
- 单测：`MINOR` 不触发修复调用
- 单测：修复后仍不合格 → 放弃发布，不循环

---

## 批次 5：第二批形态

赛跑已打通。本批加共用 `long` 的三个模块：堆叠面积、多线叠加、雷达。

**验证**：每个模块用真数据渲一段，抽帧确认；`hyperframes check` 0 error。

---

## 不在本批范围

- 地图（`geo`）：缺数据源、缺 geojson、竖屏排版未设计
- 运营表现数据采集：`heron_news_metric` 有表无数据，「最近视频表现」暂时喂空
- `keepsMoving` 断言修复：要先把折线模块改成持续有动作，否则改断言会让现有模板判失败
- 气泡图 / 热力图 / 时间轴：需要新 `shape`，动契约、闸、适配器、模板四处

---

## 每批的退出条件

| 批 | 退出条件 |
|---|---|
| 1 | 一条解说视频真渲染通过，中文正常 |
| 2 | 三条决策路径单测绿，真跑一次日志可读 |
| 3 | 两条线各出一条成片，回退路径有单测 |
| 4 | 说反的稿子被拦，MINOR 不触发修复 |
| 5 | 三个新形态各有一段真数据渲染 |
