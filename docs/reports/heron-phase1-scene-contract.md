# Heron Phase 1 实施报告：统一视频场景契约

**执行日期：** 2026-11-13
**对应任务书：** `docs/plans/plan.md` 第五节
**前置报告：** `docs/reports/heron-phase0-baseline.md`
**基线：** 后端 `dev` `dd3a06a4b3b8…`，前端 `dev` `1deb386b0fd7…`（本次未改前端）

---

## 一、本阶段做了什么

三件事：

1. **修复 Phase 0 发现的渲染阻塞（4.10）**：后端给模板的变量改为「模板声明什么就给什么」。此前 12/13 模板因为多收 `sceneN_image` 被 `--strict-variables` 直接中止渲染，数据看板线完全出不了片。
2. **让写稿提示词与 Java 契约、模板能力对齐**：消除同一份 prompt 里「只允许 lead/body/duration_s」与「用 scene_type=image 选图」的自相矛盾；把本次模板能渲染的场景类型在写稿前告诉模型。
3. **给场景加稳定标识 `scene_id`**，为 Phase 6 的场景级编辑与版本对比提供锚点。

---

## 二、实际修改文件

| # | 文件 | 修改内容 |
|---|---|---|
| 1 | `service/impl/NewsProductionPipeline.java` | 新增 `declaredVariables()` 读取模板 `index.html` 的 `data-composition-variables`；`templateVariables` / `dataTemplateVariables` 增加 `declared` 参数并按键过滤；`supportedSceneTypes` 由 private 放宽到包内可见 |
| 2 | `service/impl/NewsScriptContract.java` | v2 段落新增 `scene_id` 字段；`scene_id` 形态（`scene_001`）与全篇唯一性校验；纯文字段也允许带 `scene_id` |
| 3 | `service/impl/NewsModelScriptAuthor.java` | 注入 `HeronProductionProperties`；`prompt()` 增加 `sceneCapability` 参数；新增 `sceneCapability()` 把所选模板支持的 `scene_type` 追加到写稿提示词 |
| 4 | `service/impl/NewsTemplatePicker.java` | 候选版式的场景能力写进选版式提示词；系统提示词要求「有配图/引语/排序的题材优先选支持的版式」 |
| 5 | `service/impl/NewsProductionDomain.java` | 删除无调用方的 `TemplateInfo.supportsScenes()` |
| 6 | `resources/prompt/news-explainer/script-contract.md` | 新增「场景字段」一节：`scene_id` / `scene_type` / `visual.assets` 的用法、5 种可用类型及其使用条件、明确禁止未实现的类型 |
| 7 | `test/.../NewsScriptContractV2Test.java` | 新增 4 项：合法 `scene_id`、形态错误、重复、老稿可省略 |
| 8 | `test/.../NewsProductionPipelineTest.java` | 新增 2 项：**13 套模板逐个校验只收到声明过的变量**、模板未声明时全部输出 |
| 9 | `test/.../NewsModelScriptAuthorTest.java` | 新增 1 项：模板场景能力进提示词；3 处 `prompt()` 调用适配新签名 |
| 10 | `test/.../NewsScriptContractTest.java` | 构造函数补 `HeronProductionProperties` |
| 11 | `test/.../DataBoardPipelineRenderTest.java` | 闸数量断言（11）改为**闸名集合断言**（含 `scene_type`，共 12 道） |

**未修改：** 渲染主流程、TTS、ffmpeg、`timeComposition`、artifact/租约/attempt、数据库、API 契约、前端任何文件。

---

## 三、关键设计决策

### 3.1 变量由模板声明驱动，不再由后端硬编码

`declaredVariables()` 读模板 `index.html` 的 `data-composition-variables`，把后端产出的变量与模板声明的变量用同一份来源对齐。模板读不到时返回空集 = 「未声明」，按旧行为全量输出，不因为读不到就拒绝渲染。

这样以后新增模板、或模板删掉某个变量，都不会再出现「后端多给一个」的静默失败。

### 3.2 Java 保留 11 种场景类型，收敛放在提示词与模板闸

Phase 0 报告 6.6 曾建议「契约直接收缩到模板能渲染的 5 种」。实施时改为：**Java 继续认识 11 种类型，提示词只开放 5 种，模板闸按各自声明逐模板强制。**

原因：收缩 `SCENE_TYPES` 会让历史上带 `compare`/`timeline` 的 v2 稿变成非法，破坏本阶段自己要求的「旧版本脚本仍可读取和渲染」。而实际约束效果相同——模型只被允许写 5 种，模板闸再兜一层。

### 3.3 `scene_id` 可选但严格校验，不设为必填

同样出于兼容：早期 v2 稿没有这个字段，设必填等于让历史作品无法重渲。现在的口径是——提示词要求每段都给，一旦出现就必须是 `scene_001` 形态且全篇唯一。Phase 6 做场景编辑时可以再收紧。

### 3.4 选版式不做「按场景类型过滤」

Phase 0 报告曾写「候选按 `supportedSceneTypes` 过滤」。实施时调整：**选版式发生在写稿之前，此时还不知道稿子会出现哪些场景类型**，按未知条件过滤没有意义。

改为把每个候选的 `supportedSceneTypes` 写进选版式提示词，让模型基于题材（有配图 / 有引语 / 有排序）选一个画面支持它的版式。真正的「写稿后硬约束」仍由 `sceneTypeGate` 承担。

### 3.5 数据看板线不引入 `scene_type`

数据看板的图表由全局 `chart` 变量（`line/bars/race/stacked/map`）驱动，与「第几屏是什么类型」无关；`news-data-9x16/index.html` 也不读 `scene{N}_type`。

因此没有给 `DatasetScriptContract` 加场景字段，也没有抽公共 `SceneContract` 类——那会造出一条没有消费者的路径。数据看板线的契约本来就自洽（提示词 v1 → Java v1 → 模板读 lead/body），只是表达力弱，属于 Phase 4 的范围。

### 3.6 跳过的两项

| 跳过 | 原因 |
|---|---|
| 「稿件带场景类型但模板未声明能力时拒绝」 | 13 套模板全部有声明，此路径无实例；且 `supportedSceneTypes()` 读文件失败与「未声明」返回同一个空集，改成拒绝会把一次 IO 抖动变成生产失败。现有 `SceneTypeGateTest` 也把「老模板放行」写成了有意行为 |
| `DatasetScriptContract` 复用 v2 场景校验 | 见 3.5 |

---

## 四、测试

### 4.1 命令与结果

**默认组**（不含 mysql / render）：

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
```

| 项 | Phase 0 | Phase 1 |
|---|---|---|
| Tests run | 251 | **258** |
| Failures | 0 | **0** |
| Errors | 0 | **0** |
| Skipped | 5 | 5 |
| BUILD | SUCCESS | **SUCCESS** |

新增 7 项测试全部计入（契约 4 项 + 变量映射 2 项 + 写稿提示词 1 项）。

**render 组**（含真渲染）：

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql
```

| 测试类 | Phase 0 | Phase 1 |
|---|---|---|
| `NewsProductionPipelineRenderTest`（解说线） | ✅ 153.7 s | ✅ 183.9 s |
| `DataBoardPipelineRenderTest`（数据看板） | ❌ Variable validation failed | ✅ **127.3 s** |
| `DataBoardRealChainTest`（真数据 + 真模型） | ❌ 缺密钥 | ❌ 缺密钥（环境未就绪） |
| 合计 | 255 项 / 2 error | 262 项 / **0 failure / 1 error**（仅环境用例） |

`DataBoardPipelineRenderTest` 的实测产物（来自 checks 闸）：

- `raw.mp4` 3,408,833 字节，30 fps，37.5 秒
- 音频 37,488 ms，字幕 13 条覆盖 37,433 ms
- 12 道闸全部 `passed: true`，联络表 `blankFrames: 0`，`checkErrors: 0`

### 4.2 13 套模板的覆盖情况

- 单元层：`variableKeysStayWithinTemplateDeclaration` 遍历 `data/templates/*`，逐套断言「解说线变量」与「数据看板变量」的键都 ⊆ 模板声明。此前 12 套会违反。
- 结构层：13 套模板的 `root / narration / title-card / closing-slot / scene-1..6` 槽位齐全（脚本核对，0 缺失）。
- 真渲染层：只实测了 `news-9x16`（解说线）与 `news-data-9x16`（数据看板）两套，**其余 11 套未做真渲染**——见第六节。

---

## 五、影响面

| 面 | 影响 |
|---|---|
| API | 无变更 |
| 数据库 | 无迁移 |
| 历史载荷 | v1 稿、无 `scene_id` 的 v2 稿、模板未声明能力的老模板，读取与渲染行为不变 |
| 前端 | 无改动；模板场景能力的展示链路本来就已存在 |
| 提示词 | `news-explainer/script-contract.md` 改版；`news-board` 未动 |
| 生产行为 | 变化点：模型被限制在模板支持的场景类型内；12 套模板从「渲染失败」恢复为「可渲染」 |

---

## 六、未解决 / 未验证

| 项 | 说明 |
|---|---|
| 其余 11 套模板未真渲染 | 只有变量与槽位层面的保证。要逐套真渲染，需要把渲染测试参数化，代价约 11 × 2–3 分钟 |
| `DataBoardRealChainTest` | 缺 `HERON_REAL_MODEL_KEY`，真数据 + 真模型全链路未验证 |
| `mysql` 组 24 个测试类 | 缺 `HERON_TEST_MYSQL_*` 与 `heron_it_*` 隔离库，本阶段未运行 |
| 人工看片 | 未做。Phase 1 验收要求里没有强制看片，Phase 4 才强制 |
| 数据看板 `supportedSceneTypes` 声明 `chart` | 该声明永远不会被脚本满足（看板线不产 scene_type）。不影响功能，等 Phase 4 决定是删声明还是让看板线真的产场景类型 |

---

## 七、风险

| 风险 | 说明 |
|---|---|
| 提示词缩小了模型可用表达 | 从「随便写」变成「模板支持什么写什么」。需要一条真稿观察产出是否变单调 |
| `declaredVariables` 解析 `index.html` | 依赖 `data-composition-variables='…'` 这一书写形式。模板若改用别的注入方式，会退回「全量输出」并再次触发 strict-variables 中止。已有 13 套模板的断言兜底 |
| `scene_id` 非必填 | 模型漏写时不会失败，只是失去稳定锚点。Phase 6 收紧前，人工改稿仍要靠下标定位 |

---

## 八、Git

**Diff 统计（不含 Phase 0 就存在的 `application-prod.yml` 改动）：**

```
11 files changed, 244 insertions(+), 23 deletions(-)
```

- 生产代码 5 个文件，提示词 1 个文件，测试 5 个文件
- 未触碰前端仓库（`heron-ui` 工作区仍为干净）
- 未触碰 `ruoyi-admin/src/main/resources/application-prod.yml`（Phase 0 时即存在的未提交改动，保持原样）

**Commit SHA：** 待授权后提交。
