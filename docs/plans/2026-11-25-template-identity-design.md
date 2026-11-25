# 模板身份标准与重复模板清理 · 设计

**日期**：2026-11-25
**范围**：`data/templates/**`、`data/tools/build-templates.mjs`、三个脚本契约、三份提示词、前端分镜标签、选模板与资产锁报错、旧生成器
**状态**：已实现，未提交

---

## 0. 一句话

**只换色换数值的不算另一套模板。** 模板的身份是「结构 + 动效」，两套综合相似度 ≥ 90% 就是同一套。
23 套模板按这条标准量下来只有 7 套是真正不同的，其余 16 套删掉，并在构建期加闸防止再长回来。

---

## 1. 为什么要做

改造前的 23 套模板里：

- **16 套文稿模板两两综合相似度 92%–100%**。它们共用同一个 `base.css` 骨架、同一套 `fill+motion` 动效，版式层逐行对比只有颜色和数值不同。
- `knowledge-mono` / `knowledge-poster` 与 `news-mono` / `news-poster` 相似度 **100%**——知识线设计文档自己写的就是「复制改造」。
- 数据线里 `news-data-broadcast` 与 `news-data-investigate` 结构相似度 97%，是同一套骨架换底色。

这些模板在选模板时是独立候选，「最近 2 期用过的排除」会把它们轮着选一遍，观众看到的是同一套画面换了颜色。
成本侧：每套模板都要过 `hyperframes check`、要维护版式、要在提示词里列候选。

---

## 2. 身份标准

### 2.1 定义

```
结构相似度 = (选择器集合 Jaccard + 属性名集合 Jaccard) / 2
动效相似度 = 行为脚本集合 Jaccard
综合相似度 = (结构相似度 + 动效相似度) / 2
```

- **结构**：这份模板拼进去的全部 CSS（`base.css` + 风格令牌 + 版式 + 条件样式）里的选择器与属性名。数值、颜色、注释全部剥掉。
- **动效**：`build-templates.mjs` 按配置挂载的行为脚本集合（`fill` / `headline` / `chart` / `focus` / `globe` / `motion` / `compare` / `skeleton`）。
- **阈值 90%**：按改造前的 23 套实测标定。只换色换数值的版式两两 92%–100%，各有独立构图的 ≤ 88%。阈值落在两群之间的空档里。

### 2.2 不参与比较的

- **横版变体**（`generatedFrom` 非空）。它是竖版的派生物，本来就该像。横版只承接源模板的主线，不继承 `lines` 里的其余线。

### 2.3 这个口径的边界

它量的是「版式与动效的手法」，不是「观众看起来像不像」。两套结构完全不同的模板可以给人类似的观感，反过来一组独立的 CSS 规则也可能只是同一构图的另一种写法。
它挡得住的是这次清理掉的那一类——**同一套骨架换配色**，也就是实际长出来的那一类。

---

## 3. 清理结果

### 3.1 保留的 7 套

| 模板 | 线 | 风格 | 比例 | 场景类型 | 动效脚本 |
|---|---|---|---|---|---|
| `news-9x16` 快报 | 文稿 + 知识 | article | 9:16 | text, image | fill, motion |
| `news-16x9` 快报横版 | 文稿 | article | 16:9 | text, image | fill, motion |
| `news-minimal-9x16` 极简 | 文稿 | article | 9:16 | headline, text | fill, **headline**, motion |
| `news-data-9x16` 数据看板 | 数据 | data-board | 9:16 | headline, text, chart, number, compare | fill, headline, chart, focus, motion, compare, **skeleton** |
| `news-data-16x9` 数据看板横版 | 数据 | data-board | 16:9 | 同上 | 同上 |
| `news-data-3d-9x16` 三维地球 | 数据 | data-board | 9:16 | headline, text, chart, compare | fill, headline, **globe**, motion, compare |
| `news-trend-9x16` 趋势洞察 | 趋势 | data-investigation | 9:16 | headline, text, chart, compare | fill, headline, chart, focus, motion, compare |

两套没有挂 `skeleton.js`，`number` 屏的构图在它们上面没有实现。旧闸扫全量文本时 `_shared/storyboard.css`
里的 `data-scene-type="number"` 让它们蒙混过关；收紧证据后两者的 `number` 声明已删除，`number` 仍由
`news-data-9x16` / `news-data-16x9` 承接，契约全集不变。

5 套竖版主干两两综合相似度，最高一对 85%：

| | 结构 | 动效 | 综合 |
|---|---|---|---|
| `news-data-9x16` ~ `news-trend-9x16` | 0.84 | 0.86 | **0.85** |
| `news-data-3d-9x16` ~ `news-minimal-9x16` | 0.75 | 0.60 | 0.68 |
| `news-9x16` ~ `news-minimal-9x16` | 0.68 | 0.67 | 0.67 |
| `news-data-3d-9x16` ~ `news-trend-9x16` | 0.70 | 0.57 | 0.64 |
| `news-data-3d-9x16` ~ `news-data-9x16` | 0.69 | 0.50 | 0.60 |
| 其余 5 对 | — | — | ≤ 0.57 |

### 3.2 删除的 16 套及理由

| 模板 | 与谁重复 | 相似度 | 理由 |
|---|---|---|---|
| `knowledge-poster-9x16` | `news-poster-9x16` | 100% | 仅 `name` / `description` / `supportedLines` 不同 |
| `knowledge-mono-9x16` | `news-mono-9x16` | 100% | 同上 |
| `news-board` / `news-compare` / `news-contrast` / `news-mono` / `news-poster` / `news-qa` / `news-rail` / `news-magazine-9x16` / `news-magazine-16x9` | 彼此及 `news-9x16` | 92%–100% | `news-poster` 相对 `news-9x16` **零个独有选择器** |
| `news-quote-9x16` | `news-9x16` / `news-rank-9x16` | 92% | 独占部分只有 4 条 CSS 规则（巨型引号），无专属动效 |
| `news-rank-9x16` / `news-rank-16x9` | `news-9x16` / `news-quote-9x16` | 91%–92% | 独占部分只有约 8 条 CSS 规则（名次徽标），无专属动效 |
| `news-data-broadcast-9x16` | `news-data-investigate-9x16` | 99% | 结构 97%、动效 100%，同一套骨架换底色 |
| `news-data-investigate-9x16` | `news-trend-9x16` | 96% | 动效 100%，同一风格的新旧两个版式 |

一并删除的孤儿文件：11 个 `layouts/article/*.css`、`layouts/news-broadcast/`（目录与版式）、`_shared/tokens/news-broadcast.css`、`layouts/data-investigation/news-data-investigate-9x16.css`。
`_shared/tokens/data-investigation.css` 与 `layouts/data-investigation/news-trend-9x16.css` 保留，`news-trend` 仍在用。

### 3.3 连带的两处必然改动

这两处不做的话系统会坏，不是顺手改的：

**风格注册表。** `news-broadcast` 风格随模板消失，它承接的 `rank_competition` / `time_change` 两种叙事并入 `data-board`
（`data-board` 的 `role` 本来就写着「排行与趋势」）。不并的话 `StoryStyleMap.violations` 会报「叙事没有风格承接」，
`NewsTemplatePicker.pick` 直接抛 `IllegalStateException`，数据线整条卡死。现在六种叙事每种仍恰有一个风格承接。

**骨架不变量。** `SKELETON_INVARIANTS['data-investigation']` 原本指向已删的 `investigate` 版式，改指向 `news-trend-9x16.css`。
三条不变量（`.scene` 带 `border-left`、`.body` 有 `font-weight`、`#closing` 用 `border-top`）在 `trend` 上一字不差地成立——
它的文件头注释原本就写着与 `investigate` 共用同一套骨架。

---

## 4. 一个模板声明多条线

`template.config.json` 新增可选字段 `lines`（数组），`template.json` 的 `supportedLines` 取 `cfg.lines ?? [cfg.line]`。

校验：非空、不重复、每项都是已知线（`NEWS_EXPLAINER` / `DATA_BOARD` / `TREND_INSIGHT` / `KNOWLEDGE_EXPLAINER`）、必须包含 `line`。

**横版变体的 `lines` 固定为 `[cfg.line]`。** 否则源模板的多线声明会被 `variantConfig()` 的 `...cfg` 带进横版，
知识线就会多出一个横版候选——而知识线是按 1080×1920 设计的。

### 4.1 知识线挂哪套

删掉 `knowledge-*` 后知识线零模板，`EditorialLineEditor.editKnowledge` 会一直返回 `Optional.empty()`，这条线等于关掉。

**决定：只挂 `news-9x16`。** 理由：

- 知识线的稿子不写 `scene_type`（`KnowledgeScriptAuthor` 明确要求「不要写 scene_type，保持纯文字」）。
- `news-minimal` 开了分镜，没写类型的段落走进分镜规则有出空屏的风险；它也不声明 `image`。
- `news-9x16` 没开分镜、有配图位，是业务规格点名的手维护基线。

知识线现在只有 1 个候选，`NewsTemplatePicker.pickFrom` 在 `pool.size() == 1` 时直接返回，不调模型。

---

## 5. 场景类型收敛

### 5.1 声明了但没有实现的

加闸之后立刻查出来三类，前两类是这次才发现的：

| 类型 | 情况 | 涉及模板 | 处理 |
|---|---|---|---|
| `timeline` | 全仓只有一条 `.scene-no { letter-spacing }`，不是时间轴 | 5 套数据模板 | 从声明、契约、提示词删除；删掉那条假规则 |
| `list` / `qa` | 只存在于契约与前端标签，没有任何 CSS | — | 从契约与前端标签删除 |
| `headline` | 冲击屏逻辑 `headline.js` 只挂在开了 `storyboard` 的模板上，其余模板声明了但渲染出来和文字屏一样 | 3 套保留的文稿模板（改造前 16 套） | 从未开分镜的模板声明里删除 |

`headline` 这一条的含义：模型被告知「可以写 headline 屏」，写出来却没有区别。删掉声明后，
`NewsModelScriptAuthor` 动态读模板的 `supportedSceneTypes` 生成清单，模型只会看到真实可用的类型。

### 5.2 随模板一起消失的

`quote` / `rank` 的唯一实现是 `news-quote` / `news-rank` 两套版式，模板删除后这两种类型在全仓没有任何实现，
一并从契约、提示词、前端标签除名。

### 5.3 收敛后的全集

| 类型 | 实现方式 | 哪些模板有 |
|---|---|---|
| `text` | 缺省形态，无专属规则 | 全部 |
| `image` | `.scene-figure` + `.has-image` | `capabilities: image` |
| `headline` | `headline.js` + `.takeover` | `storyboard: true` |
| `chart` / `number` / `compare` | `skeleton.js` + `skeletons.css`；`compare` 另有 `compare.js` | 数据线模板 |

`NewsScriptContract.SCENE_TYPES`、`StoryboardContract.SCENE_TYPES`：`headline, text, image, chart, compare, number`
`StoryPlanContract.SCENE_TYPES`（数据线子集）：`headline, text, chart, number, compare`

---

## 6. 构建期闸

两道闸都在 `data/tools/build-templates.mjs`，构建不过就不写盘。

### 6.1 模板身份闸

整次构建时（`--template` 单套生成时无从比较，跳过）对每套非变体模板算身份，同比例下两两比较，≥ 90% 即 `fail`：

```
✗ news-9x16 与 news-quote-9x16 综合相似度 92%，超过 90%：
  只换色换数值不算另一套模板——删掉一套，或给它独立的构图与动效
```

放在 `main()` 里逐套构建之前，所以重复模板不会被先生成一半。

### 6.2 场景类型闸

逐套检查 `supportedSceneTypes` 里的每个类型（`text` 除外）在这份模板**独有的装配**里有没有实现。
判据是一张「类型 → 必须装配的文件 + 专属痕迹」的表（`SCENE_TYPE_EVIDENCE`）：

```
✗ news-9x16: 声明了场景类型「headline」，但这份模板没有装配它的实现
✗ news-data-9x16: 未知场景类型「timeline」，先在 build-templates.mjs 的 SCENE_TYPE_EVIDENCE 登记
✗ zz-noimage-9x16: 声明了场景类型「image」，但这份模板没有装配它的实现
```

表里没有的类型直接拒。要新增一种场景类型，先在这里登记它的实现文件与痕迹。

**不能拿「每套都挂」的文件当证据。** 第一版扫的是所有装配文件的合并文本，有两个假阳性：
`fill.js` 每套都挂且会读 `.scene-figure`，于是没有 `capabilities: image` 的模板声明 `image` 也过闸；
`_shared/storyboard.css` 每条分镜模板都挂，里面的 `data-scene-type="number"` 让没装 `skeleton.js` 的模板也能声明 `number`。
现在 `image` 只认版式 CSS 里的图位且要有 `capabilities: image`，`number` 只认 `skeleton.js`。
收紧后 `news-data-3d-9x16` / `news-trend-9x16` 的 `number` 声明被删（见 §3.1）。

### 6.3 `ruleBlock` 的修正

`ruleBlock`（取某个选择器最后一条规则）原先要求选择器前面紧挨着 `}` 或文件开头，规则之间夹着注释就匹配不到。
`investigate` 版式恰好没在相关规则前放注释所以一直没暴露，换到 `trend` 上立刻失败。现在匹配前先剥注释。
`checkLandscapeContract` 也用它，剥注释只会更稳。

---

## 7. 测试

`data/templates/_tests/landscape-templates.test.mjs`（已跟踪，`node` 直接跑）：

- 横版模板的产物、比例、转场位移、底栏安全区、正文 ≥ 32px、资产清单与哈希。
- 竖版主干仍是 9:16（原先钉的是 `index.html` 的产物哈希，模板被有意改动后换成按比例判）。
- **新增四段闸回归**：
  1. 注入一套只换色换数值的 `news-9x16` 副本 → 构建必须失败，报错必须含「相似度」并点名两套模板。
  2. 注入一套声明了 `timeline` 的模板 → 构建必须失败，报错必须点名 `timeline`。
  3. 注入一套没有 `image` 能力却声明 `image` 的模板 → 构建必须失败，报错必须点名 `image`。
  4. 现有模板整次 `--check` 必须通过，且逐套覆盖。

第 1 段是必须有的：这道闸第一版的 `jaccard` 写成了 `[...].filter(...).size`（数组没有 `.size`，结果是 `NaN`），
`NaN >= 0.9` 恒为假，闸对任何一对都不报错。「不报错即通过」的闸，没有「必须报错」的样本就等于没有闸。
第 3 段同理：第一版闸扫全量文本，`fill.js` 让 `image` 空声明过闸，这类假阳性只有「必须报错」的样本才抓得到。

后端：`mvn -o -pl ruoyi-modules/ruoyi-heron test` → 1044 个用例，0 失败，0 错误，5 个跳过（原有）。
其中 4 个是这次新加的：`NewsTemplatePickerTest` 2 个（§8.3）、`NewsTemplateLockMessageTest` 2 个（§8.4）。
前端：`vitest run src/views/heron/news/work/storyboard.test.ts` → 4/4 通过；`vue-tsc --noEmit` 与 `oxlint` 均 0 报错。

整包 `mvn -o -pl ruoyi-admin -am package -DskipTests`：15 个模块（含 `ruoyi-heron`）全部 `SUCCESS`，**本次改动的 Java 代码编译通过**；
但最后一步 `spring-boot:repackage` 失败（`Unable to rename 'heron.jar' to 'heron.jar.original'`）——本机有一个后端实例
（PID 55920，8086 端口）正用 `java -jar ruoyi-admin/target/heron.jar` 运行，占着该文件。**可运行的 fat jar 未生成**：
`target/heron.jar` 现在是 4.2MB 的瘦 jar，不是可运行产物。停掉该实例后重跑上面的命令即可恢复。

新加的三个分支（身份闸、选模板过滤、资产锁报错）都做过故障注入：把判断改坏，对应测试必须变红，确认后才恢复。

> 用例数说明：1044 是在当前工作区上跑出来的，其中包含并行任务单尚未提交的改动（`Opportunity*` 等）。
> 这些改动与本次改动的文件没有交集，本次改动涉及的测试类单独跑也全绿。

---

## 8. 改动清单

### 8.1 模板与生成器

| 区域 | 文件 | 变化 |
|---|---|---|
| 生成器 | `data/tools/build-templates.mjs` | `lines` 校验与输出；`styleFiles` / `behaviorFiles` 抽出共用；场景类型闸；模板身份闸；`ruleBlock` 剥注释；`SKELETON_INVARIANTS` 改指 `trend`、删 `news-broadcast` |
| 模板 | `data/templates/*` | 删 16 套模板目录与 14 个版式/令牌文件；7 套重新生成 |
| 模板配置 | 7 份 `template.config.json` | `news-9x16` 加 `lines`；文稿模板删 `headline`；数据模板删 `timeline` |
| 共享样式 | `_shared/styles.json` | 删 `news-broadcast`，其两种叙事并入 `data-board` |
| 共享样式 | `_shared/storyboard.css` | 删 `timeline` 假规则 |

### 8.2 场景类型收敛

| 区域 | 文件 | 变化 |
|---|---|---|
| 契约 | `NewsScriptContract` / `StoryboardContract` / `StoryPlanContract` | 场景类型全集收敛 |
| 提示词 | `prompt/data-board/script-contract.md`、`story-plan-contract.md`、`prompt/news-explainer/script-contract.md` | 同步类型清单 |
| 前端 | `heron-ui/src/views/heron/news/work/storyboard.ts` | `SCENE_TYPE_LABELS` 删 `timeline` / `list` / `qa` / `quote` / `rank` |
| 测试 | `SceneTypeGateTest`、`landscape-templates.test.mjs` | 示例类型换成仍在全集里的；新增闸回归 |
| 规格 | `docs/plans/2026-10-29-heron-business-spec.md` §3.10 | 写入身份标准与场景类型规则；修正脚本路径 |

### 8.3 删模板的连带改动：选模板只认在册的

`NewsTemplatePicker.recentKeys()` 原先从最近 20 条作品里取前 5 个不同的 `template_key`，不管那套模板还在不在。
删掉 16 套之后，历史作品里会带着这些键：

- 它们会占掉「最近 2 期排除」的名额——真正刚用过的在册模板反而逃过排除；
- 还会被写进给模型的「最近已用过，不要选」清单里，列着几套根本不在候选里的版式。

改成只保留当前目录里还在的键（`domain.availableTemplates()`），顺带跳过还没选版式的作品（`templateKey` 为空）。
判断逻辑抽成包内可见的 `recentInstalled(works, installed)`，测试不用 mock mapper。

### 8.4 删模板的连带改动：资产锁报错说准

`NewsProductionDomain.lockedAssets` 遇到取不到 `LOCK.json` 一律报「缺少 LOCK.json，请运行 template-lock.mjs」。
对已删模板这句话是错的——目录都不在了，跑那个脚本也修不好。

现在区分两种情况（状态码与错误码 `503 news_assets_unconfigured` 不变，只改文案）：

| 情况 | 文案 |
|---|---|
| 目录在，缺 `LOCK.json` | 模板 X 缺少 LOCK.json，请运行 script/tools/template-lock.mjs 生成 |
| 目录不存在（模板已删） | 模板 X 不存在，该作品需要重新选版式 |

### 8.5 删掉被取代的旧生成器

`script/tools/news-templates.mjs` 是 `data/tools/build-templates.mjs` 之前的生成器，11 月 10 日后没再动过，全仓没有任何地方调用它。删它有两个原因：

1. 它内含 12 套模板定义，其中 10 套正是这次删掉的。对它传一个模板根目录跑一次，那 10 套就会被写回来，
   而且**绕过两道构建期闸**（闸只在 `build-templates.mjs` 里）。
2. 它的跳过逻辑是 `existsSync(index.html) && !force && tpl.key !== 'news-9x16'`——`news-9x16` 不在豁免里，
   **不带 `--force` 也会覆盖在册基线模板**的 `template.json` / `index.html` / `index.motion.json` / `assets-manifest.json`。

写回的目录没有 `LOCK.json`，所以后端 `availableTemplates()` 不会立刻把它们当候选，但 `--check` 会红。留着就是一个入口。

`script/tools/template-lock.mjs` 保留：它是现行工具，后端报错文案与测试支撑代码的注释都指向它。

---

## 9. 已知边界

1. **历史数据里的 `template_key` 没有查库确认。** 已经渲染过的作品不受影响（渲染只认冻结输入里的 `templateKey`，产物已落盘）。
   但**尚未生产**的作品若 `heron_news_work.template_key` 指向已删的模板，会在 `reserve()`（`NewsProductionDomain.java:212`）失败，
   报 §8.4 的新文案 `503 news_assets_unconfigured`「模板 X 不存在，该作品需要重新选版式」。
   失败是明示的，不再误导；但**没有做自动重选**——这类作品需要人工重新选版式。
   本机没有 heron 库的客户端（MCP 连的不是 heron 的库），这类行有多少、有没有，未确认。
2. **表现回流数据的键。** `MetricInsightAnalyzer` 按 `template` 维度聚合的历史采样里会有已删模板的键，
   只会落在「未记录」或旧分组里，不影响选模板（候选里已经没有它们）。
3. **趋势学习的模板适配字段。** `TrendLearningAgent.validateTemplateFit` 校验 `heronTemplateFit` 里的模板键是否存在于当前目录；
   已保存的技术卡若引用已删模板，校验会抛 `IllegalArgumentException`——这是期望行为（它本来就该对照当前目录）。
4. **`data/tools/test-visual-focus.mjs` 在本机是挂的**（`cssVar is not defined`）。该文件在 `.gitignore` 里、未入库，
   `_gen/` 下所有文件与 HEAD 一致，与本次改动无关，未处理。
5. **`data/templates/_shared/skeletons.css` 有一处不是本次的未提交改动**（三条骨架规则加了 `#root[data-width="1080"]` 前缀）。
   本次重建把它一并生成进了 `news-data-9x16`——那正是该改动缺的收尾（重建前 `--check` 对它是红的）。
6. **`data/templates/**` 与 `data/tools/**` 归并行任务单 B。** 本次改动全部落在这两个文件区，与 B 单的文件区重叠。
7. **`.impeccable/config.json` 里有一条忽略规则指向已删的** `layouts/news-broadcast/news-data-broadcast-9x16.css`。
   只是设计工具的忽略清单，文件不存在时规则不生效，未清理。
8. **测试里的示例字符串仍出现已删模板名**（`news-rank-9x16`、`news-quote-9x16`、`knowledge-mono-9x16` 等）。
   它们是纯内存测试的夹具值或自建临时目录的名字，不依赖真实模板目录，未改。
9. **`NewsWorkServiceImpl` 的 `auto_template_missing`**（配置了自动模板却不存在）不在本次范围，文案未动。
10. **打包时覆盖了一个正在运行的 jar。** 见 §7 末尾。运行中的实例是从旧 fat jar 启动的，磁盘文件已被换成瘦 jar；
    它是否受影响、是否在跑任务，本次未确认，也未重启。
11. **`NewsWorkServiceImpl.saveDraft` 仍接受任意格式合法的模板键。** 读路径（选模板、资产锁）已过滤已删模板，
    写入口未加同一道校验——那是另一条改动路径，会改草稿保存的报错语义，本次未做。
12. **三处 Java 契约各自持有类型全集**（`NewsScriptContract` / `StoryboardContract` / `StoryPlanContract`）。
    本次逐个收窄，没有让它们互相引用：一个是数据线子集，另外两个分属写稿与分镜，合并会跨契约耦合。
