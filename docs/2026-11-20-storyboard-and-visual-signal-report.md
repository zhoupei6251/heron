# Heron 分镜系统与成片画面信号：实施报告

**日期：** 2026-11-20
**范围：** `plans/reference_github_4.md` §7.2/§8（分镜与脚本契约 v2）、`docs/plans/plan_impl.md` 七（质量检查闭环）、`plans/reference_github_2.md` 三（FFmpeg 画面质量）
**不在本轮范围：** JEV 治理层、Supervisor 多 Agent、知识解释线、yt-dlp 参考素材

---

## 一、动手前查到的现状

六份规划里的大头已经落地：AgentScope 装配、研究任务、内容决策中心、生产接入、发布与效果回流、三套视觉风格、趋势洞察线都在代码里。本轮要补的是三个仍然空着的地方：

| 缺口 | 证据 |
|---|---|
| 全库 0 处 storyboard | `scenes` 只是「段落数组」，没有「这一屏要表达什么」的表达位 |
| 脚本契约不支持分镜字段 | `DatasetScriptContract.SCENE_FIELDS = {lead, body, duration_s}`，`scene_type` 根本不被允许 |
| 成片没有黑屏/冻结帧检测 | 全库无 `blackdetect` / `freezedetect`；技术闸只证明「mp4 能播」 |

### 关键发现：分镜管线早就铺了一半，死在一个字段名上

`fill.js` 早就能把场景类型落到 DOM 属性上：

```js
const type = declared["scene" + index + "_type"];
if (type) slot.dataset.sceneType = type;
```

`news-quote` / `news-rank` 两套文章模板也确实靠 `[data-scene-type]` 换排版。但：

```
文章线 12 套：sceneTypes = supportedSceneTypes（非空）→ _type 变量已声明 ✅
数据线  4 套：sceneTypes = [] ，supportedSceneTypes 非空 → _type 变量被过滤掉 ❌
```

模板生成器按 `cfg.sceneTypes` 决定要不要声明 `sceneN_type` 变量，而能力声明写在 `supportedSceneTypes`。数据线四套模板（含当前主力的数据看板与趋势洞察）**从来没声明过这个变量**，`scene_type` 写进稿子也不会到达模板。

也就是说：`headline` / `chart` 这两个类型，数据线从第一天起就是**死代码**。

---

## 二、本轮做了什么

### 2.1 契约：分镜三件套

在段落上补三个字段（复用已有的 `scene_type`，只新增两个）：

```json
{
  "scenes": [{
    "beat": "HOOK",            // 新增：叙事节点
    "on_screen_text": "…",     // 新增：上屏短句，与旁白解耦
    "scene_type": "headline",  // 已有：这一屏的画面组件
    "lead": "…", "body": "…"   // 已有：旁白
  }]
}
```

新增 `StoryboardContract`（`service/impl/`），五条判据：

| 判据 | 退回条件 |
|---|---|
| beat 合法 | 不在 9 个叙事节点内 |
| scene_type 合法 | 不在 11 个组件内 |
| 上屏文字放得下 | 解析后超过 18 字 |
| 分镜完整 | 只有几段写了 beat（半拉子分镜比没有更糟） |
| 序列成立 | 首屏不是 `HOOK`，或末屏不是 `CONCLUSION`/`INSIGHT`/`REVEAL` |
| 组件有层次 | 整篇只有一种 scene_type（那还是在照稿子念） |

**旧稿一律放行**：整篇没有 beat 的稿子不进分镜模式，渲染结果与改动前逐帧一致。判据是分镜模式才生效，文章线（`article-enabled: false`）与历史作品不受影响。

### 2.2 闸：分镜闸与修复循环

新增第 11 道闸 `storyboard`（`checks.json` 里可查）。关键在于它进的是**写稿修复循环**而不是渲染闸：

```java
// DatasetScriptAuthor.violations()
violations.addAll(StoryboardContract.violations(script, resolved));
```

分镜不合规时在写稿阶段就定向修一次，而不是渲完 600 秒才发现整条片子作废。

上屏文字的长度按**占位符解析后的字数**算：`{{facts.last.value}}` 原文 20 字，解析后「2310.25 站上关键位」12 字。趋势稿的 `{{trend.*}}` 也算在内（`resolve(script, dataset, trend)`）。

### 2.3 模板：把断点接上

- 生成器改用 `supportedSceneTypes` 决定分镜变量进出；`sceneTypes` 这个重复字段从 16 套配置里删掉（它就是这次断点的成因）。
- 4 套数据模板的 `supportedSceneTypes` 从 `["headline","chart"]` 扩到 `["headline","text","chart","number","compare","timeline"]`。
- 新增 `data/templates/_shared/storyboard.css`，三套数据风格共用一份分镜版式。
- `fill.js` 把 `beat` 落成 `data-beat`，把上屏文字落成一个新的 `.lead screen-text` 节点。

### 2.4 画面信号：黑屏与冻结帧

新增 `VideoSignalCheck`，用 ffmpeg 的 `blackdetect` + `freezedetect` 一次扫成片。

**阈值是量出来的，不是拍的**：三套数据风格都是暗底（亮度 13～20/255），像素黑阈取 3%（约 7.7/255）而不是默认的 10%（25.5/255）——默认值会把深色底的正片整段判成黑屏。

实测：
- 合成片（暗底 4s + 纯黑 3s + 暗底 5s）→ 准确报出 `black_start:4 black_end:7`
- 仓库里 4 支真实样片（film-3~6）→ **零误报**
- 本轮新渲染的片子 → 零黑场、零冻结

结果进 `checks.json` 的 `video_signal` 闸（只记不拦：黑场与冻结是症状，拦下来没有对应的自动修复动作）。

### 2.5 质量判据：画面在不在动

`QualityCriteria` 从 9 条判据增到 10 条，新增 `picture_motion`（机器定论）：

> 最长黑场 > 1 秒（画面没出来）或最长静止段 > 3 秒（画面不动）→ fail

这条直接对应「像 PPT」的观感：版式再花，画面一停就是一张静止的图配旁白。判据进入 L3 审稿报告，`picture_motion` 的证据定位符是 `checks[video_signal].measured.*`，复核器解析得到。

### 2.6 写稿契约

`prompt/data-board/script-contract.md` 增加「分镜：每段三件套」一节；`trend-script.md` 把 `plan.beats`（机器已经算好的六段叙事）映射到 beat 枚举：

> 问题→`HOOK`，现象→`CONTEXT`，证据→`EVIDENCE`，解释→`EXPLANATION`，洞察→`INSIGHT`，待观察→`CONCLUSION`

### 2.7 前端

审片页新增「分镜」区块（`src/views/heron/review/index.vue`）：每屏展示屏次、叙事节点、画面组件、上屏短句与旁白领句。数据来自 `script.json` 产物，走与趋势判定同一个登录态受保护的读取通道。旧稿显示「这一版没有写分镜」。

新增 `src/views/heron/news/work/storyboard.ts`（解析层）与同名测试。

---

## 三、实测结果

### 3.1 渲染验证（news-trend-9x16）

用 `build-templates.mjs --stage` 暂存模板 + 手写 `vars.json`（带完整分镜），跑真实渲染：

```
node .../hyperframes.mjs render . --variables-file vars.json --strict-variables \
  --quality looks --workers 1 --no-best-effort --output raw.mp4
```

`--strict-variables` 通过 → 新增的 `sceneN_beat` / `sceneN_text` 声明与取值一致。

**新稿 vs 老稿同一屏对照**（`docs/2026-11-20-storyboard-scene1.png`）：

| | 左：有分镜 | 右：老稿 |
|---|---|---|
| 上屏 | 「这轮抬升是趋势吗」（屏幕文字） | 「先问：这轮抬升是趋势还是反弹？」（领句） |
| 正文 | 无（headline 屏让位） | 一整段正文 |

老稿渲染结果与改动前逐帧一致——向后兼容成立。

### 3.2 测试

| 范围 | 结果 |
|---|---|
| 后端 `ruoyi-heron` 单测 | **632 项全绿**（含新增 `StoryboardContractTest` 7 项、`VideoSignalCheckTest` 3 项、判据增量 2 项） |
| 后端生产构建 | `mvn -pl ruoyi-admin -am package` 通过，`ruoyi-admin/target/heron.jar` 187.8 MB |
| 模板生成 | 16 套模板重新生成，全部通过 |
| 前端 vitest | 新增 `storyboard.test.ts` 4 项通过；heron-ui 既有测试未受影响 |
| 前端 lint + build | `oxlint` 无告警；`vite build` 通过 |

---

## 四、改动清单

### 后端（`ruoyi-modules/ruoyi-heron`）

| 文件 | 改动 |
|---|---|
| `service/impl/StoryboardContract.java` | **新增** 分镜契约（beat / 组件 / 上屏文字 / 序列 / 多样性） |
| `service/impl/VideoSignalCheck.java` | **新增** 黑屏与冻结帧检测 |
| `service/impl/DatasetScriptContract.java` | 段落允许 `scene_type` / `beat` / `on_screen_text` |
| `service/impl/DatasetPlaceholders.java` | 解析上屏文字里的占位符 |
| `service/impl/DatasetScriptAuthor.java` | 分镜违例进写稿修复循环；趋势稿带上 `{{trend.*}}` 解析 |
| `service/impl/NewsProductionPipeline.java` | `storyboard` 闸；`sceneN_beat` / `sceneN_text` 变量；`video_signal` 闸 |
| `service/impl/QualityCriteria.java` | 新增 `picture_motion` 机器判据 |
| `resources/prompt/data-board/script-contract.md` | 分镜三件套写稿口径 |
| `resources/prompt/data-board/trend-script.md` | `plan.beats` → beat 映射 |
| 4 个测试文件 | 判据 9→10 的计数与新判据用例 |

### 模板（`data/`）

| 文件 | 改动 |
|---|---|
| `tools/build-templates.mjs` | 分镜变量按 `supportedSceneTypes` 进出；引入 `storyboard.css` |
| `_shared/storyboard.css` | **新增** 三套数据风格共用的分镜版式 |
| `_gen/behaviors/fill.js` | 落 `data-beat` 与上屏文字节点 |
| 16 套 `template.config.json` | 删掉重复字段 `sceneTypes`；4 套数据模板扩 `supportedSceneTypes` |
| 16 套生成产物 | `index.html` / `template.json` / `LOCK.json` / `assets-manifest.json` 重新生成 |

### 前端（`../heron-ui`）

| 文件 | 改动 |
|---|---|
| `src/views/heron/news/work/storyboard.ts` | **新增** 分镜解析层 |
| `src/views/heron/news/work/storyboard.test.ts` | **新增** 4 项用例 |
| `src/views/heron/review/index.vue` | 审片页「分镜」区块 |

### 数据库

**无变更。** 分镜字段落在已有的 `script.json` 产物里，不新增表、不改列。

---

## 五、尚未完成的问题

1. **分镜只到「这一屏用什么画面」这一层，没到「这一屏接管整个画面」。** 数据线的标题、KPI 大数字、图表三块是常驻的，分镜只能改下方文字块。想让某一屏把图表让位（真正的冲击屏），需要按当前屏次动态切 `#chart-slot` 的显隐——那是 JS 侧的事，本轮没做。
2. **`compare` / `timeline` 两个组件只有排版差异，没有专属图形。** 对比屏现在只是给正文垫了面板，没有真正的左右两栏；时间轴屏只是把屏次编号的字距放开。趋势线模板本身有一条静态时间轴刻度（`#scene-N::after`），但那是写死的五屏刻度，不是按分镜画的。
3. **黑场与冻结帧只记不拦。** 阈值是量出来的，但还没有真实线上样本分布，暂不升级成拦截闸。攒够 `checks.json` 里的 `video_signal` 数据再定。
4. **知识解释线与 yt-dlp 参考素材采集本轮未做。**
5. **JEV 治理层未接入。** `docs/reference/agentscope/jev-integration.md` 的研究结论已在仓库里，依赖也明确了（`agentscope-extensions-jev`），但没写代码。

---

## 六、后续建议（按投入产出排序）

1. **让模型自己写出分镜，先看真实产出。** 现在 prompt 已经要求模型填三件套，但还没有一条真片是带分镜跑出来的。下一步应该跑一条真实的数据看板片，看模型写的 beat 与 scene_type 是否站得住，再决定要不要收紧判据。
2. **冲击屏。** 某一屏是 `headline` 时，让图表与常驻标题让位，这一屏独占画面。这是分镜最大的未兑现价值。
3. **`compare` 组件做成真正的两栏对照**，配合 `DatasetFacts` 已有的首末/领先者数据，不需要新数据源。
4. **冻结帧数据先收一个月的量**，看清真实片子的静止段分布，再决定 `picture_motion` 的阈值是否要调，以及要不要升级成拦截闸。
5. **JEV 治理层**按 `jev-integration.md` 的结论接 Tool Guard 与 Model Router，先 SHADOW 模式跑数据，用 Heron 自有数据校准概率阈值。

---

## 七、复现方式

```bash
# 模板重新生成（Node ≥ 22）
node data/tools/build-templates.mjs

# 暂存一份模板并渲染带分镜的样片
node data/tools/build-templates.mjs --stage news-trend-9x16 --out /tmp/heron-sb/composition
# 往 /tmp/heron-sb/composition/vars.json 写入带 sceneN_type / sceneN_beat / sceneN_text 的变量
cd /tmp/heron-sb/composition
node <repo>/data/tools/node_modules/hyperframes/bin/hyperframes.mjs render . \
  --variables-file vars.json --strict-variables --quality looks --workers 1 \
  --no-best-effort --output raw.mp4

# 成片画面信号自查
ffmpeg -hide_banner -nostats -i raw.mp4 \
  -vf "blackdetect=d=1:pic_th=0.98:pix_th=0.03,freezedetect=n=-60dB:d=2" \
  -an -f null -
```

样片与对照帧见 `docs/2026-11-20-storyboard-scene1.png`。
