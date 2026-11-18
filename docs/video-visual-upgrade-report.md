# 视频视觉表现升级 · 实施报告

> 2026-11-17。对应需求 `docs/plans/plan_impl.md`。设计依据 `docs/plans/2026-11-17-video-visual-upgrade-design.md`。
> 本文只写**这一轮实际做了什么、验证到什么程度、哪些没做**。没验证的不会写成已验证。

## 1. 改造前架构分析

### 生产线现状

两条子线共用 `ProductionBizType.news_video`：文稿解说线（`kind=article`，`article-enabled: false`，不参与发版）与数据看板线（`kind=data_board`，当前唯一在跑的产品形态）。

渲染链路是固定的外部效果序列，无一处可跳过：

```
冻结输入 → 取数(dataset/1 快照) → 写稿 → 审稿 → 契约闸 → 变量映射
        → edge-tts 配音 → 时间轴补丁 → hyperframes check → render
        → ffprobe 解码闸 → ffmpeg 混 BGM + AIGC 元数据 → 联络表 → 十道闸
```

### 视觉层现状（改造前）

| 问题 | 证据 |
| - | - |
| 13 个模板是同一骨架的 13 份手抄 | 78 个场景标记完全一致（78 × `<p class="lead">` + 78 × `<p class="body">`，零例外） |
| 没有纵深 | 全仓库 `repeat/yoyo/ambient` 命中 **0 次**：没有任何环境动效，装饰层是死的 |
| 配色散乱 | 20+ 个六位 hex 散落在各模板，一个模板一套 |
| 字体单薄 | 全部模板只有 `Noto Sans SC` 一个静态 otf，却声明 `font-weight: 100 900`，粗体靠浏览器合成 |
| 声明不实 | 11 个模板声明 `supportedSceneTypes: [headline, text]`，但没有任何场景类型实现 |

### 已有能力（不重造）

- `hyperframes check`：lint / runtime / layout / contrast / craft / audio / motion 七段，`layout` 段能查出文字被自身盒子裁掉（clip_text）、元素溢出容器。
- `index.motion.json`：`appearsBy` / `staysInFrame` / `keepsMoving` 断言，随 check 一起跑。
- `contact.png`：3×2 联络表，`blankCells` 逐格判空白帧。
- `hyperframes snapshot`：按时间点/按场景（`--review`）导出关键帧与 review sheet。**这条命令此前没被用起来。**

## 2. 参考项目学习成果

**本轮没有联网重新研读上游仓库。** 依据是仓库内已蒸馏的学习成果 `docs/reference/video-craft/`（frame-design / motion-and-transition / data-motion / story-and-rhythm / caption-and-audio / quality-gate）与 `docs/reference/hyperframes/`（本地按 0.9.6 对齐的 CLI 与渲染文档）。

由此落地的判断：

| 结论 | 来源 | 落地方式 |
| - | - | - |
| 装饰必须会动，静态装饰等于没有 | quality-gate「每个装饰元素都有环境动效」 | 环境层三件（网格 / 光晕 / 颗粒）+ `ambient()`，`keepsMoving ≤ 4s` 断言兜底 |
| 入场要有多种缓动与方向 | quality-gate「3 种以上不同缓动和方向」 | `enter(el,{dir})` 五方向 + 每条线各自 `--ease-out` |
| 字号下限：竖屏 32px 起 | quality-gate | 构建期硬校验，低于 24px 直接 build 失败 |
| 一个强调色打满饱和，不做全屏线性渐变 | frame-design | 每套令牌只准一个 `--accent`，装饰用 rgba 低透明度分层 |
| 数据要「同起点、同缓动、同时落定」 | data-motion | chart.js 未改绘制逻辑，KPI 滚动与折线绘制同一条时间线 |
| 上游 Registry 的 component 可复用、block 不可（画布尺寸写死） | 设计文档 §4.6 | **本轮未安装任何 registry 组件**（见 §10） |

**Motion Canvas 结论：不引入。** 当前缺口（复杂矢量时间轴、旁白逐词同步）在本轮交付范围内没有真实需求；HyperFrames + GSAP 的时间线已能覆盖场景进出与图表推进。引入第二套渲染编排会与 `LOCK.json` 的确定性契约冲突。

## 3. 采用的核心技术

**构建期模板生成器**，不是渲染期生成：

```
data/tools/build-templates.mjs
  1. 读 template.config.json（唯一手写源）
  2. 按 scenes 展开场景槽位，按 capabilities 插 chart / image
  3. 注入 _shared/base.css + tokens/<style>.css + layouts/<style>/<key>.css
  4. 注入 fill.js + [chart.js] + motion.js + 同源变量声明块
  5. 落盘 index.html / template.json / index.motion.json / 重算 manifest + LOCK
```

产物仍是**单个自包含 `index.html`**，渲染链路、哈希闸、`LOCK.json` 全部不动。

三个"同源"是这轮防漂移的关键：

- 变量声明块与 `fill.js` 读取的键，从同一份配置派生；
- `index.motion.json` 的 `duration` 来自同一份配置（此前手写，改时长必漏）；
- `template.json` 的 `style` 色板从令牌 CSS 里现读（前端不另抄色值）。

## 4. 新增的视觉风格

三套原创令牌 + 三套版式，都已经实渲出片。

| | A · Professional News | B · Data Investigation | C · Dynamic Data Explainer |
| - | - | - | - |
| 模板 | `news-data-broadcast-9x16` | `news-data-investigate-9x16` | `news-data-9x16` |
| 底色 / 前景 / 强调 | `#0a1626` / `#ffffff` / `#e23b2e` | `#0e0d0b` / `#f2ece1` / `#d9a05b` | `#0c1417` / `#f3f0e8` / `#18b7c9` |
| 标题字号 / 字重 | 86 / 900 | 100 / 900（字距 −0.03em） | 92 / 800 |
| 场景字号 | lead 50 / body 34 | lead 50 / body 36（300 字重） | lead 54 / body 38 |
| 视觉签名 | 下三分之一信息条（整幅色带 + 红色立标 + 屏次号） | 细引线 + 大留白 + 颗粒 | 网格 + 强调线 + KPI 大数字 |
| 入场节奏 | 0.42s，`power4.out` | 0.90s，`power2.out` | 0.55s，`power3.out` |
| 环境层 | 网格 0.30 / 红晕顶部 | 网格 0.35 / 琥珀晕左下 / 颗粒 0.34 | 网格 0.55 / 青晕右上 / 颗粒 0.16 |

四组令牌（色 / 字 / 尺度 / 动效）全部走 CSS 自定义属性，`motion.js` 用 `getComputedStyle` 读同一份值 —— **改令牌就换风格，不存在第二份动画参数**。

风格选择走模板选择：模板说明里写清适用场景，`NewsTemplatePicker` 的系统提示已改为"排版与视觉风格编辑"，并保留"最近两期不重复"的硬约束。

## 5. 新增和改造的组件

### 生成器部件

| 文件 | 改动 |
| - | - |
| `_gen/skeleton.html` | 新增 `data-style`、环境层三件、栏目标签 `#kicker`、每个场景的 `#scene-N > .scene-no`、可选 `.scene-figure` 图片槽 |
| `_gen/behaviors/fill.js` | 屏次编号 `01…0N`、按稿件展开配图（`img` 由脚本创建，避免重复媒体节点被判风险） |
| `_gen/motion.js` | 收敛成三个动作 `enter / ambient / swap`，参数全部来自令牌；`immediateRender: true` 防止中段动画先裸现 |
| `_gen/behaviors/chart.js` | 涨跌幅取绝对值（箭头已表方向，此前出现「▼ 15.8（-20.20%）」双负号）；跌色改用 `var(--down)` |
| `_gen/behaviors/chart.css` | 三处硬编码 `rgba(24,183,201,*)` 光晕改为 `var(--accent-glow)` |

> 数字滚动没有在 `motion.js` 里重写一遍：KPI 的小数位与单位随数据集变化，`chart.js` 已按 `precision` 驱动，重复实现只会多一份真相。

### 覆盖到的能力清单

- 标题与文字：栏目标签、主标题（按字数三档降字号）、强调线、屏次号、KPI 数字块、结尾条、来源行、AIGC 角标。
- 数据可视化：折线 / 排行 / 赛跑 / 堆叠 / 多线 / 雷达（沿用既有 chart.js），KPI 滚动、区间标注。
- 视频动效：入场（五方向）、场景进出、进度条、环境层呼吸/漂移/闪烁、图表缓慢推近。
- 信息表达：来源、城市/指标单位、区间高低点标注。

**没有做的组件**：遮罩切换、模糊转清晰、图形转场、区域地图高亮、事件时间线、观点对比卡。理由见 §10。

## 6. 前后端文件修改清单

### 后端

| 文件 | 改动 |
| - | - |
| `domain/vo/VideoStyleVo.java` | 新增：风格对外视图（key/name/label/role/bg/fg/accent） |
| `domain/vo/VideoTemplateVo.java` | 追加 `style` 字段（新增字段，不影响既有字段语义） |
| `service/impl/NewsProductionDomain.java` | `TemplateInfo` 追加 `style`；`templateCatalog()` 解析 `template.json` 的 `style` 对象 |
| `service/impl/TemplateCatalogService.java` | 映射到 VO；老模板无风格时返回 `null`，前端画「未声明」 |
| `service/impl/NewsTemplatePicker.java` | 系统提示改为「排版与视觉风格编辑」，把风格选择纳入选模板 |
| `src/test/.../NewsTemplatePickerTest.java` | 构造器参数对齐（8 → 9 参，补 `null`） |

### 前端（heron-ui）

| 文件 | 改动 |
| - | - |
| `src/api/heron/template/types.ts` | 新增 `VideoStyle`，`VideoTemplate.style` |
| `src/views/heron/asset/template/index.vue` | 模板卡新增风格预览：用真实 `bg/fg/accent` 画的色板 + 中英文风格名 + 适用场景 |

### 模板资产

```
data/templates/
  _gen/                      生成器输入（skeleton / motion / behaviors）
  _shared/base.css           画布基础 + 环境层 + 内容层 z-index
  _shared/tokens/*.css       四套令牌（三套数据线风格 + article 共用底层）
  _shared/styles.json        风格登记表（名字/标签/适用场景）
  layouts/<style>/<key>.css  15 套版式（数据线 3 + 文稿线 12）
  news-data-9x16/            迁移到生成器（风格 C）
  news-*/（12 个）            文稿线全部迁移到生成器（style=article）
  news-data-broadcast-9x16/  新增（风格 A）
  news-data-investigate-9x16/新增（风格 B）
data/tools/build-templates.mjs  生成器
```

## 7. 数据库变更及兼容性

**无数据库变更**：没有新表、没有新列、没有新字典。

兼容性：

- `VideoTemplateVo` 只**追加**字段，老调用方不受影响；`style` 可能为 `null`。
- 新增的两个模板目录各自带 `LOCK.json`，`assetsGate` 的哈希校验照常；`NewsTemplatePicker` 按 `supportedLines` 过滤，两条线的候选集不会串。
- 12 个文稿模板已全部迁到生成器：ID 结构与骨架本来就一致（连 `#kicker` 都有），迁移后版式差异只剩 `layouts/article/<key>.css` 与各自的 accent，`supportedSceneTypes` 逐模板保留（quote/rank/image 的声明没有丢）。三套版式另带的装饰节点（`#plate` / `#rail` / `#tick`）改用 `decor` 声明，不再手写在 HTML 里。

## 8. 测试结果

| 项目 | 命令 | 结果 |
| - | - | - |
| 生成器自洽 | `node data/tools/build-templates.mjs --check` | 15/15 一致 |
| 时间窗不变量 | 构建期 `checkWindows`（随生成执行） | 15/15 通过；反向验证：改回旧写法 exit 1 |
| 版式闸 | `build-templates --template <key> --verify` | 15/15 通过（暂存并注入字体后跑） |
| 类型/配图分支 | 注入 `scene_type` + `scene1_image` 后 `snapshot` | quote 引号、rank 圆序号、image 图位均生效 |
| 关键帧目检 | `hyperframes snapshot --at 2,9,20,36` | 三套画面差异显著，中文无缺字、无溢出、无重叠 |
| 后端编译 | `mvn -pl ruoyi-modules/ruoyi-heron -am -DskipTests compile` | 通过 |
| 后端单测（全量） | `mvn -o -pl ruoyi-modules/ruoyi-heron test` | 437 跑，434 通过；3 个失败全在 `AgentRuntimeConfigTest`（`HeronResearchSnapshotMapper` bean 缺失导致上下文起不来），与本次改动无关 |
| 后端单测（本次涉猎） | `-Dtest='NewsProductionPipelineTest,NewsTemplatePickerTest'` | 11/11 通过 |
| 前端类型检查 | `npx vue-tsc --noEmit` | 通过 |
| 前端构建 | `npm run build` | 通过 |
| 样片渲染 | `hyperframes render --quality looks --workers 1` | 3 条 45s / 1080×1920 / 30fps / 含音轨，单条约 3 分钟 |

闸门明细（三套共用）：

```
lint err=0  runtime err=0  layout err=0  contrast err=0
craft err=0  audio err=0   motion err=0（含 appearsBy 装饰层 ≤2s、keepsMoving ≤4s）
```

**渲染耗时说明**：`chart.css` 的 `filter: drop-shadow` 会让 hyperframes 从 fast capture 退到 screenshot capture。这条光晕是改造前就有的写法，属既有基线；本轮把新增的 `mix-blend-mode` 去掉了，没有额外增加慢路径。

### 自查阶段修掉的两处

| 问题 | 影响 | 处理 |
| - | - | - |
| `build-templates --check` 只比对 `index.html` | 手改 `index.motion.json` 能悄悄放宽 `keepsMoving` 断言而检查不出来 | 三个生成物全比对；用一次人为漂移验证过退出码 1，再重新生成复原 |
| 模板卡风格色板的「Aa」用前景色画在前景块上 | 文字与底色同色，预览里看不见 | 改为 `color: var(--bg)` |

### 字体去重（第二批）

`copyTemplate` 本来就会在模板没带字体时注入 `data/fonts/NotoSansSC-Regular.otf`，而 3 个生成出来的模板各存了一份同名同内容（哈希相同）的字体——每个目录 17.7MB。删掉这三份重复后，模板目录从 18MB 降到 452KB。

- 生成器不再从模板目录取字体：`LOCK.fontSha256` 直接算共用那份，缺失即构建失败。
- 模板目录因此**不能单独直接渲染**（CSS 里的 `fonts/...` 会落到系统字体上，且是静默的）。补 `--stage <key>`：复制模板并注入共用字体，产出的目录结构与渲染期工作目录一致；`--verify` 也改为在暂存目录上跑版式闸，避免缺字体导致排版测量失真。
- 实测：暂存目录在 1080×1920 实渲一帧，中文与排版与去重前一致。

### 文稿线 12 个模板迁移（第二批）

12 个模板的 `<style>` 原样抽成 `layouts/article/<key>.css`，只去掉 `@font-face`（`base.css` 已提供）。它们与骨架的 ID 结构本来就一致——连 `#kicker` 都有——所以迁移是「抽出样式 + 补配置」，不是重画。

四处必须单独处理：

| 差异 | 处理 |
| - | - |
| 骨架新增 `.scene-no` 与 `.ambient` | 文稿线的版式补丁里关掉；`ambient` 另外给了配置开关，否则 motion 断言会要求一个不存在的层在 2 秒内出现 |
| 标题字号写死在 `#title` | 改成 `--fs-title / lg / sm` 三档令牌——否则 `fill.js` 的降档会用数据线的 92/76/68，把 poster 的 138px 直接压掉 |
| 来源文案原本写死「来源：」前缀 | 骨架把它交给数据，版式补回 `#source-line::before` |
| `quote` / `rank` 的 `[data-scene-type]` 变体 | 长年没有运行时赋值（旧 HTML 里也搜不到），本轮补上；不补的话 `sceneTypes` 就是一句空声明 |

顺带修掉一个既有对比度错误：`news-contrast` 的 kicker `#6fbccc` 落在 `#f2f5f5` 上只有 1.97:1，改成 `#5996a3`（3.0:1 以上）。这条在旧模板里就存在，只是从没被 `check` 跑过——那 12 个模板此前没有进过任何闸。

**保真核对**：`news-magazine`（亮底、衬线排布）与 `news-rank`（大序号 + 计数器）在迁移前后同一时刻各渲一帧对照，版式、字号、配色、来源前缀一致，差别只在统一后的入场时序（标题淡出由各模板各自的时间改成 3.6 秒）。其余 10 套走的是同一套抽取规则，只验到版式闸与「不溢出不裁字」。

### 文稿线环境层与三处迁移回归（第二批补）

12 个文稿模板此前一律 `ambient: false`，理由是"原本没有这层，硬加等于改画面"。这次逐模板打开，并按各套底色单独配令牌：亮底（contrast / magazine / minimal / qa / rank）用深墨网格，暗底（9x16 / board / compare / mono / poster / quote / rail）用浅墨；网格尺度 72–160px、光晕方位、颗粒强度逐套不同，否则 12 套会长出同一层"通用背景"。

两套例外是有意的：`news-mono` 自带竖线纹理，网格与光晕设 0，只留颗粒；`news-data-*` 本来就有环境层，未动。

同时修掉迁移引入的回归：`#plate`（contrast）、`#rail`（rail）、`#tick`（mono）三个装饰节点在迁移时丢了，但 `layouts/article/*.css` 还在给它们写样式——等于 CSS 变成了死代码，画面也少了东西。改为在 `template.config.json` 里声明 `decor`，由生成器展开；它们的动画（双色板推开、进度引线、屏次读数）回到 `motion.js`，但只对真的存在这些节点的模板生效。

### 结尾口播从未播出过（15 套共有的旧缺陷）

`#closing-slot` 的 `data-start` 一直钉在 `{{DURATION}}` 上、`data-duration="0.01"`，也就是**起点等于总时长**——这段内容永远落在成片之外。它不是迁移引入的：从生成器第一版（`c1849079d`）就这样，三套数据线与 12 套文稿线的结尾口播一次都没出现过。旧的手写模板里它是「最后一屏让出的时间窗」，改成生成器时被压成了 0.01。

修复方式是把尾段真正切开：最后一屏的窗口缩短 `closingTail = clamp(round(slot/2), 3, 6)` 秒，口播独占这段，两段不重叠（重叠会触发 `text_occluded`，闸门实测抓到过）。

这类错误抽样截图很难发现（每个时刻都"有画面"，只是少了一句），所以加了一条构建期不变量 `checkWindows`：任何片段超出成片时长、口播落在成片之外、或口播早于最后一屏结束，直接 build 失败。用旧写法做过反向验证（改回 `{{DURATION}}` / `0.01` → exit 1），再还原。

### 三个此前未验证的路径

| 路径 | 做法 | 结果 |
| - | - | - |
| `quote` 类型变体 | 往变量里注入 `scene_type=quote` | 引号字形 + 加大领句，按设计生效 |
| `rank` 类型变体 | 注入 `scene_type=rank` | 圆形序号随 CSS 计数器递增（2、3…），生效 |
| `news-9x16` 配图分支 | 注入 `scene_type=image` + 一张渐变图 | 图位展开、正文压回 3 行，不溢出 |

这三条都不是真实剧本跑出来的，是直接改 composition 变量的对照实验——能证明版式分支接得上，不代表真实稿件会给出这些类型。

顺带修掉 `--verify` 的误报：`news-data-broadcast-9x16` 要抽 309 帧，超过 `execFileSync` 的 300 秒上限，被当成"check 失败"。上限抬到 900 秒后单独跑是通过的。



| 线 | 实际模板 | 时长 | 闸 | 结果 |
| - | - | - | - | - |
| 文稿解说线 | `news-quote-9x16` | 59.7s | 11/11 | 通过，联络表 6 格全有画面，中文正常 |
| 数据看板线 · 风格 A | `news-data-broadcast-9x16` | 37.5s | 12/12 | 红色立标 + 栏目名 + KPI + 下三分之一信息条 + 屏次号 |
| 数据看板线 · 风格 C | `news-data-9x16` | 32.4s | 12/12 | 深青网格 + KPI 大数字 + 强调线 |
| 数据看板线 · 风格 B | `news-data-investigate-9x16` | 40.8s | 12/12 | 暖黑 + 颗粒 + 琥珀 + 细引线 |
| 文稿线 · 迁移后 | `news-9x16` | 58.4s | 11/11 | 迁移后的生成版式，kicker / 来源前缀 / 配色与迁移前一致 |

三套风格都是真实选题、真实数据源跑出来的，不是摆拍。选哪一套由 `NewsTemplatePicker` 在「最近两期不重复」约束下挑，实测按 A → C → B 轮换：同一期不会出现两套皮，也不会随机换皮。

过程中一次失败值得记：风格 B 首次尝试报 `dataset_invalid: request timed out`，是外部数据源抓取超时，不是模板问题，换一批机会重试即通过。

跑法：本机重启后端（原参数 + `--api-decrypt.enabled=false`，脚本登录需要；跑 B 时再加 `--heron.production.article-enabled=false` 把线锁到数据看板）→ `POST /heron/auto/run` 由总编辑选线 → 轮询任务 → 预览接口取回成片与联络表 → 停止测试实例、按原参数恢复。

**顺带修掉一个阻断性 bug**：数据看板线建占位候选时没写 `dedup_key` 与 `normalized_title`，两列都是 NOT NULL，于是这条线每次都死在认领阶段。本机此前的产出全是文稿线，不是设计如此，是这条线根本没跑通过。修复后数据看板线首次在该环境跑通。

产物落在七牛（`oss-config-key: qiniu`），上传后本地副本会删除，所以 `data/videos/**` 为空是既有设计，不是产物丢失。

## 9. 样片存放位置

| 风格 | 成片 | 分镜（6 格联络表） |
| - | - | - |
| A · Professional News | `docs/samples/style-a-professional-news.mp4` | `docs/samples/storyboard-style-a-professional-news.png` |
| B · Data Investigation | `docs/samples/style-b-data-investigation.mp4` | `docs/samples/storyboard-style-b-data-investigation.png` |
| C · Dynamic Data Explainer | `docs/samples/style-c-data-explainer.mp4` | `docs/samples/storyboard-style-c-data-explainer.png` |

- mp4 已加 `.gitignore`（三条共 60MB+），分镜图进仓库作为质量证据。
- 素材是可复现的样例：A 用合成持仓序列、B 用合成组件价格、C 用模板既有的金价样例，**都标了「示例数据集」，不冒充真实新闻事实**。
- 复现：`node data/tools/build-templates.mjs --stage <key>` 生成暂存目录（模板目录不含字体，暂存时注入共用字体，与渲染期 `copySharedFont` 一致），再对 `data/.stage/<key>` 执行 `hyperframes render`。

## 10. 尚未完成的问题

按影响排序，每条都写清具体阻碍。

1. **文稿线迁移后的收尾。** 12 个版式已全部由配置生成（`--check` / `--verify` 均 15/15），带配图、`quote`、`rank` 三条分支用变量注入做了对照实验（见上文表格），版式与动效都接得上。仍未验证的是：这三条都**不是真实剧本跑出来的**，只证明分支可用，不证明真实稿件会产出这些类型；环境层只在定格帧上目检过，连续播放时的漂移/呼吸节奏没做长时段观察。
2. **可变字体没换。** 设计文档第 2 批的内容。现在仍是单字重 otf 声明 `100 900`，粗体是浏览器合成 —— 三条线的重量差靠字号与字距撑，不靠真实字重。要换需要抬 woff2 进仓库并重测离线渲染。
3. **上游 Registry 组件一个都没装。** 需要 `hyperframes catalog/add` 联网取件，并在 1080×1920 实渲验证不糊不裁。本轮用自建组件替代了 `count-up` / `grain-overlay`，`caption-pill-karaoke`、`animated-bar-chart` 未评估。
4. **Motion Skills 没有安装。** 官方安装方式需要联网拉取；本轮把它要求的工作方法（渲染→截图→检查→修复闭环）手工落在 `snapshot` + `check` 上，没有配成 Agent Skill。
5. **质量检查没有新增闸，只收紧了断言。** `contact.png` 仍是均匀 6 格，没有按场景对齐；`hyperframes snapshot --review` 能出按场景的 review sheet，但**没有接进 `NewsProductionPipeline`** —— 每多跑一次 snapshot 就多一轮浏览器渲染，会显著拉长出片时间，需要先定预算。
6. **端到端只验到四条片子。** 2026-11-17 实测：文稿解说线 1 条、数据看板线 3 条（风格 A/B/C 各一），全部 11–12 道闸通过。**未走到**的环节：自动确认与发布出口、审片页对这几条的展示、连续多期的选题去重策略（本次有一次数据机会重复被总编辑判 `no_publish`，属于预期行为但没做长期观测）。
7. **音画同步未验证。** 样片用的模板自带占位 `narration.mp3`，不是本轮新合成的 TTS，节拍与字幕的对应关系无法据此判断。
8. **组件缺口。** 遮罩切换、模糊转清晰、图形转场、区域地图高亮、事件时间线、观点对比卡都没做。其中地图与时间线依赖 `shape=geo` 的数据源，当前没有启用的地理数据源。
9. **`sceneTypes` 能力仍是空壳。** 三套模板都只声明 `["headline","chart"]` 但没有按类型切换版式的实现，声明与实现仍有差距；本轮没有扩这个口子，避免再写一次「声明不实」。

## 11. 后续优化建议

1. **先解决字体共享**，再迁剩余 12 个模板：把字体放 `_shared/fonts/`，生成器只写相对引用，`copyTemplate` 时按模板声明注入。这一件事同时解决 §10.1 和仓库体积（三份变成一份）。
2. **换可变 woff2**（设计文档第 2 批），验收标准是 300 与 900 在渲染帧上肉眼可分。
3. **把 `snapshot --review` 接成可选的质量闸**：只对草稿态/抽检态跑，出片主线保持现状；按场景的起点+中点取样，比现在的均匀 6 格更能发现「某一屏空着」。
4. **组件按数据形态补**：先补 `bars` 与 `rank` 的动效重排（数据线 6 个模板都用得上），再评估 registry 的 `animated-bar-chart` 是否值得替换自建。
5. **风格 × 栏目对齐**：现在风格随模板由模型选；如果要做成稳定预期，可以在 `Column` 上加一列默认风格，作为选模板的软提示。
6. **样片进发布前的自动比对**：把三条样片当基线，任何令牌/版式改动后重渲同一批时间点，逐帧比对，允许变好看、不允许变坏（设计文档 §9 的迁移基线思路，尚未落地）。

---

## 附：这一轮"没做"的判断依据

| 被砍掉的事 | 理由 |
| - | - |
| 13 模板全铺 | 12 个模板缺字体文件，迁了也渲不出来，属于先修基础设施的事 |
| 引入 Motion Canvas | 现缺口用不上；会与确定性渲染契约打架 |
| 装 registry block | block 画布写死 1920×1080，竖屏装不进 |
| 新增数据库表存风格 | 风格是文件（令牌 CSS + 版式），加表只是给文件系统再抄一份 |
| 在 `motion.js` 再写一遍 countUp | chart.js 已按 precision 驱动，重复实现会漂移 |
| 主观美观的自动评分 | 需求 §7 明确禁止把主观项伪装成客观分；只保留可判定的（空白帧、裁切、溢出、静态时长） |
