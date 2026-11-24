# 人工编辑策划结论，按编辑后的结论重出片

日期：2026-11-23
对应欠账：`docs/plans/2026-11-23-remaining-work.md` 第 3 条「人工编辑故事 + 故事历史表现回填」的**前半段**（后半段的表现回填见文末）。

## 为什么要做

story/1 是数据看板线的策划结论：主角、张力、主张、落点、谁受影响。它现在是模型一次写定的东西：

- 策划期由 `DatasetStoryPlanner` 生成，`DatasetStoryContract.violations` 当场判成立不成立；
- 生产期随冻结快照落进 `selection.story`，`NewsProductionPipeline.storyGate` 拿生产快照复核一遍；
- 全仓没有 story 的写接口（`controller/` 下只有只读的 `EditorialController`）。

结果是：总编辑判错一个角度、选错一个主张时，人只能看着。改不了就只能换选题重来（`NewsAutoService` 的重试路径最多换 3 次），或者重新触发一次自动槽位——但那条路仍是模型写定。

「人工编辑故事」要补的就是这个缺口：**人能把策划结论改掉，并让改完的结论真的决定这条片子讲什么。**

## 口径（先说清楚不做的是什么）

- **只有数据看板线能编辑**。解说线没有 story，知识解释线的素材是固定知识包，改策划结论没有意义。
- **只改故事，不改选材**。源、取数口径、模板、候选都沿用上一版冻结的选择；人改的是「这条片子讲什么事」，不是「拿哪份数据」。改选材是另一件事（换选题），不在这一轮。
- **`basis` 不给改**。它是机器算出来的依据（信号类型、方向、z-score、覆盖、点数、区间、规划哈希），策划依据不接受模型自述，也不接受人自述。编辑时原样沿用上一版的 basis。
- **改完必须重出片**。故事不是独立文档，它只通过写稿与审稿两条通道影响输出。编辑后不产出一条新版本，这次编辑就没有意义——所以这个接口直接产片，不做「先存后出」两步。

## 做法

### 一句话流程

```
人看策划结论（GET /heron/newsWork/{id}/story）
  → 改字段（narrative / oneLine / protagonist / tension / claims / payoff / stakes / assumptions）
  → POST /heron/newsWork/{id}/story/reproduce
      → 校验（对着同一份数据快照跑契约闸）
      → 冻结新版本：selection.story 换成编辑后的，其余原样
      → 照常跑写稿、审稿、渲染
```

### 后端

| 位置 | 改动 | 为什么 |
| --- | --- | --- |
| `domain/EditorialOverride`（新） | `record EditorialOverride(Map<String,Object> selectionNode, Map<String,Object> editorialNode)` | 沿用上一版的 selection / editorial 节点，逐字冻结进新快照 |
| `domain/bo/ProductionSubmitBo` | 加第 7 个字段 `override`（可空），保留原 4/6 参构造器 | 现有调用点一行不用改 |
| `service/impl/NewsProductionDomain.reserve` | `override` 非空时：kind 取节点里的 `line`，`editorial`/`selection` 逐字写入；否则走原逻辑 | 预留版本是事务核心，只加分支不改原路径 |
| `service/impl/NewsWorkServiceImpl` | 新增 `story(workId)` 与 `reproduceWithStory(workId, bo)` | 作品级操作，跟草稿/生成放一起 |
| `domain/bo/NewsStoryBo`（新） | `record NewsStoryBo(String requestKey, Long expectedRevision, Map<String,Object> story)` | 故事按 story/1 的 JSON 形状收，字段多且嵌套 |
| `domain/vo/NewsStoryVo`（新） | 策划结论 + 可选枚举 + 当前版本的取数口径 | 页面要按枚举渲染下拉框，枚举在 Java 侧（`DatasetStoryContract`） |
| `controller/NewsWorkController` | `GET /{id}/story`、`POST /{id}/story/reproduce` | 权限沿用 `heron:newsWork:list`（读）与 `heron:newsWork:publish`（出片要过发布闸，权限按最严的给） |

### 校验（编辑当场判，不让问题留到渲染后）

1. 形状：`schemaVersion` 必须是 `story/1`；`narrative` / `tension.kind` / `claim.kind` 必须在契约枚举内。
2. 成立性：`DatasetStoryContract.violations(edited, dataset)` —— 与策划期同一套硬校验（主张 3–6 条、事实主张 ≥2、假设与 assumptions 一一对应、证据信封 ≤8、方向/时段/绝对词等）。
3. 生产期复核：`DatasetStoryContract.evidenceViolations(edited, dataset)` —— 引用能不能解析、方向有没有翻转、覆盖有没有塌陷。
4. 数据同源：取数用 `DatasetFetchService.snapshot(sourceKey, points)`，`points` 取冻结口径 `DatasetWindowContract.pointsOf(snapshot)`——**和生产取的是同一份口径**，否则校验通过也可能生产失败。

任一条不过就抛 409 `news_story_invalid`，把问题逐条回给页面（「引用解析不到 facts.xxx」），不静默改写人的结论。

### 为什么沿用上一版的 selection 而不是重建

生产链路只认冻结快照里的 `selection` 节点（源、取数口径、模板、story 全在里面）。重建 `EditorialSelection` 要把 `datasetWindow`（点数、节奏、指标、规划哈希）和 `opportunity`（头线、信号）都还原回去，还原错一个字段就是「按另一条口径取数」，故事里的引用会解析不到。逐字沿用上一版节点、只换 `story` 这一个键，是唯一不会走偏的做法。

### 落痕

新版本快照里：

- `selection.story` 是编辑后的结论（与上一版逐字段可比）；
- `editorial.storyEditedBy` / `editorial.storyEditedAt` 记下谁在什么时候改的——改过的策划结论必须能查出来是谁改的。

## 边界

- 不做「保存策划结论但不重出片」：故事不改稿就没有作用。
- 不做跨作品复用策划结论：一条片子的故事跟它的数据口径绑定，搬走就失真。
- 不做批量编辑：一次改一条。
- 不给解说线、知识解释线开这个口：它们没有 story。
- 改完仍然要过原有的全部闸（story 闸、脚本对位、审稿、渲染）：人工编辑不是绕过闸的口子，只是把结论换成人写的。

## 定位修正（2026-11-24）：不是人工页面，是 Agent 纠错通道

平台的目标是零人工，人工编辑只是兜底。这个接口真正的用途是给「审稿没过」的自动回路一个
比换选题更准的纠正手段——接口本身不区分调用者。

### 自动线怎么用它

`NewsAutoService.tick` 的重试循环：

1. `generateForAuto` 抛出 `editorial_review_unsupported` / `editorial_review_unverifiable`（证据问题）；
2. 当前线是数据看板或趋势洞察、且本槽位还没改写过 → `StoryRewriter` 按审稿意见改写 story/1；
3. 改写结果走 `NewsWorkServiceImpl.reproduceWithStoryForAuto`（与人工路径同一套校验，只跳过对象级权限）；
4. 成功即产出，槽位 detail 记 `story_rewritten`；失败则落回原路径：`abandonAuto` + 换选题重试（最多 3 个选题）。

一个槽位最多改写一次（`MAX_STORY_REWRITES = 1`）：改写是定点修，一次改不动说明问题不在故事上，
继续改只是多烧模型调用。

### 为什么改写器只喂 facts

`StoryRewriter` 的输入是「当前故事 + 这份数据的 `facts` + 审稿意见」，不给整份行数据：
能引用的键就是 facts 里那些键，模型编不出解析不到的引用。校验不在改写器里做，
落到生产侧同一处闸——改写器只管产出候选，过不了闸就退回换选题。

### 本轮新增

- `StoryRewriter` + `prompt/editorial/story-rewrite.md`
- `NewsWorkServiceImpl#reproduceWithStoryForAuto`（与人工路径共用 `reproduce`，只换权限口径）
- 测试：`NewsStoryEditTest`（6 例）、`StoryRewriterTest`（3 例）

前端页面按零人工定位**不做**：接口留着，将来由 Agent 或兜底操作直接调用。

## 验证

- 单测（`NewsStoryEditTest`）：编辑后 basis 沿用、契约不过就 409、上一版没有 selection 时 400、override 冻结出的 selection 只换 story 一个键、kind 按 line 取对。
- 接口自测：对着真片工作（geo 验收片的作品）改一句话 → 出新版本 → 抽帧确认画面变了。
