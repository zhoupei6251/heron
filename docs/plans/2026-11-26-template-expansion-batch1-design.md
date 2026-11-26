# 模板扩充 · 第一批设计案（数据线 2 套 + 趋势线 1 套）

**日期**：2026-11-26
**状态**：设计案，待确认，未动工
**共识基础**：首批 3–4 套、总盘子 ≤12；借 lemo-opuscar（风格箱组织）与 video-shotcraft（镜头配方卡）；每套新写招牌 behavior；首批纯 9:16；验收 = 真实样片 + 四道硬闸 + 用户过目。

---

## 0. 一句话

按「一种风格 = 独立骨架 + 招牌 behavior + 语义令牌 + 叙事归属」的单位做 3 套新模板：
**排行冲刺**（data-board 第二套实现）、**时间旅行**（data-board 第三套实现）、**档案调查**（趋势线第二套实现）。
每套先算身份闸预估分再写码，出真实样片过四道闸后交用户裁决。

---

## 1. 现状基线（写案前核实过）

| 事实 | 位置 |
|---|---|
| 风格注册表：`data-board` 承接 4 种叙事（trend_reversal / numeric_contrast / rank_competition / time_change），`data-investigation` 承接 2 种（counter_intuitive / problem_evidence_reveal），`article` 空 | `_shared/styles.json` |
| 行为脚本 9 个：fill / headline / chart / focus / compare / skeleton / globe（+ chart.css / globe.css），共约 1572 行 | `_gen/behaviors/` |
| 骨架不变量按风格登记：`data-board` 钉在 news-data-9x16 版式的 `.scene{left,right}` + `#closing{border-left}`；`data-investigation` 钉在 news-trend 的 `.scene{border-left}` + `.body{font-weight}` + `#closing{border-top}`。**新风格必须先在 `SKELETON_INVARIANTS` 登记它凭什么不一样，否则构建直接拒** | `build-templates.mjs:594` |
| 图表语义令牌 8 个（--chart-line-width 等）每风格必须自己定义，缺失则拒——防的是「图表手法退化成同一套」 | `build-templates.mjs:610` |
| 身份闸：非变体模板按比例分组两两比，综合相似度（结构 Jaccard + 动效集合 Jaccard）/2 ≥ 90% 拒 | `build-templates.mjs:707` |
| 场景类型闸：声明的类型必须有本模板独有的装配证据（image 要 capabilities + 版式图位；number 要 skeleton.js） | `build-templates.mjs:674` |
| 现有动效手法：headline 冲击接管、skeleton 按类型换构图、compare 两栏、globe 三维地球、focus 焦点标注、chart 折线 | — |
| 现有 5 套竖版主干最高相似对 85%（news-data-9x16 ~ news-trend-9x16） | 昨日实测 |

---

## 2. 参考仓库借什么（已读，不抄码）

**video-shotcraft 的配方卡格式**（读了 `references/shots/data/timeline-travel.md` 全文）：每张卡 = frontmatter（一句话/适用/时长/能量/标签）+ 意图（这段动效在讲什么故事）+ 动作阶段表（帧段参考 + 内容）+ 参数表（典型值 + 调节手感）+ 已知坑 + 参考实现。**借的是这个组织**：heron 的新 behavior 每个配一张同构的中文配方卡，放在 `_gen/behaviors/<name>.card.md`，意图与坑先写、代码后写。

**lemo-opuscar 的组织**（读了本地 SKILL.md，库本体未 clone——style 本体是「STYLE.md 说明 + 可运行 demo」）：借「每种风格自带说明书与可跑样例」的形态；具体艺术输入有限，风格好坏靠样片与用户裁决。

**明确不借**：Remotion/React 技术栈（heron 是 HTML+CSS+GSAP 交给 HyperFrames 渲染）、逐帧逆向（rea-video）。

---

## 3. 三套模板的设计

### 3.1 news-rank-9x16 冲刺榜（data-board 风格新实现，承接 rank_competition）

- **画面主张**：排行不是一张静态表，是一场**冲刺**——条目从同一起跑线弹射，名次互换时整列位移动画，领先者冲线时封顶定格。现有 data-board 系是「看板」（图表常驻上半屏），这套是「赛况」（榜单即画面）。
- **骨架差异**（登记进 SKELETON_INVARIANTS 的签名）：`.scene` 全宽榜单布局，无左右分栏；`#closing` 用底部横幅（border-bottom）。图表区退化为**迷你 sparkline 列**（每行一个），不再是主 `#chart-slot`。
- **招牌 behavior：`race.js`**（配方卡按 §2 格式写）——借 shotcraft `timeline-travel` 的三段变速思想移植到纵向榜单：排序帧口播到位时整列换位（spring 过冲），首位条目戴冠标；数字滚动用现有 GSAP，不引新依赖。
- **场景类型**：headline, text, number, compare（无 chart 主图，无 image）。
- **叙事**：从 data-board 的 narratives 里**只接走 rank_competition**；styles.json 里 data-board 仍留 3 种，rank 系新风格承接 1 种。
- **闸预估**：动效集合 {fill, headline, motion, race} 对 news-data-9x16 的 {fill, headline, chart, focus, motion, compare, skeleton} Jaccard ≈ 0.2；结构靠全宽榜单布局，预估综合 ≤ 0.6，过闸余量大。

### 3.2 news-chrono-9x16 时间旅行（data-board 第三套实现，承接 time_change）

- **画面主张**：变化不是终点值，是一段**旅程**——横向时间轴掠过各期刻度，每格弹立该期数字卡，急停在最新一期并推近。借 shotcraft timeline-travel 的镜头语义（加速→弹卡→急停推近），heron 版数据源是 dataset 快照的时间序列事实。
- **骨架差异**：`.scene` 为横向轴带（轴在画面下三分之一，卡片在轴上弹立）；`#title-card` 常驻改为首屏淡出；`#closing` 竖排右侧（border-right）。
- **招牌 behavior：`chrono.js`**——时间窗口取真实口播 `data-start/data-duration`（与 headline.js 同口径，不另建时间轴）；每期卡弹出时机由轴位移反查。
- **场景类型**：headline, text, number, compare。
- **叙事**：只接走 time_change。
- **闸预估**：动效集合 {fill, headline, motion, chrono}，与 race 系共享 fill/headline/motion 但招牌不同；结构上轴带布局与榜单、看板都不同，预估综合 ≤ 0.65。

### 3.3 news-dossier-9x16 档案调查（趋势线第二套实现，与 news-trend 分担叙事）

- **画面主张**：调查不是一条折线，是一宗**档案**——证据卡片逐一「入卷宗」：盖章、归档、红笔批注。现有 news-trend 是「沿时间轴推进的图表叙事」，这套是「无图表、纯证据版面」。
- **骨架差异**：无 `#chart-slot`；`.scene` 是证据卡堆叠布局（新卡从右侧滑入压住旧卡一角）；`#closing` 用档案封条样式（双 border）。
- **招牌 behavior：`dossier.js`**——证据卡入场（盖章动作 + 纸张微旋转），批注下划线由 Java 侧事实引用驱动（与 focus.js 的取数口径一致）。
- **场景类型**：headline, text, number（证据卡大数字），compare（两份档案对照）。
- **叙事**：data-investigation 的 2 种叙事（counter_intuitive / problem_evidence_reveal）**两套各承接一种**：counter_intuitive 归 dossier（反常识先抛结论再摊证据），problem_evidence_reveal 归 news-trend。styles.json 相应拆分。
- **闸预估**：与 news-trend 动效集合差一个 chart/focus 换 dossier；无 chart 能力所以 chart.js/chart.css 不装配，结构差异大，预估综合 ≤ 0.7。

### 3.4 收尾说明

第三批数据线名额（总盘子第 4 套）留给首批验收后的判断：若冲刺榜和时间旅行样片风格分化足够，第 4 套补文稿线；若不够，先回炉。

## 3.6 情绪短片：music_video 占位线的第一套实体（样片验证）

用户给了参考片（AI 生成的歌词情绪短片：扁平剪影插画、色彩随情绪流转、歌词上屏；画面歌词对应
朱婧汐《寂寞烟火》，已核实）。定性：代码管线画得出它的静态构图与氛围动效（层叠地形、月亮光晕、
天灯粒子、剪影人物、色彩流转），画不出 AI 逐期生图的「每期画面都不同」——那要靠场景变体库补。

- **定位**：`music_video` 占位线（`ProductionBizType` 里已有枚举与权限键，无 pipeline bean）的第一套实体。
  样片阶段只动模板层；后端管线接入（写稿契约、闸清单、sys_menu）验证成立后再立项。
  用户已定（2026-11-26）：**两态共存**——歌词驱动（真 MV，需解决歌词与音源版权口径）与
  文案驱动（治愈文案 + 曲库配乐，版权全自控）都做；冻结输入 `kind` 用 `music_video`，
  驱动方式在内容里用一个字段区分。两种内容源共用同一套场景模板，模板层不变。
- **首套 `mood-night-9x16`「夜航」**：三幕色彩流转——深夜蓝（海面举灯人）→ 暮紫（离岸）→ 暖金（放天灯）。
  三幕场景由生成器编译期写入（引擎只渲编译期元素，race 已验证），变量是 `line1/line2/line3` 三句文案槽
  （歌词驱动时填歌词，文案驱动时填段落金句）。
- **风格 `mood`**：独立登记 styles.json（narratives 空——它不参加叙事选模板，走自己的线）；
  LINES 白名单加 `MUSIC_VIDEO`。
- **样片 BGM**：先用曲库现有 `we-never`。《寂寞烟火》是 fee=1 版权曲，不抓取；用户后续自备音源或 AI 生成替代。

---

## 3.5 第二批：老模板逐套翻新（首批验收后启动）



「重新设计」= **原键下的版式与动效升级**，不是重做：模板键不能换，历史作品的 `template_key`
与自动槽位默认模板（`news-data-9x16`）都指着现有的键。翻新借首批蹚熟的工艺（配方卡 → behavior → 令牌），
但每套**借不同的配方卡**——7 套若都朝同一批参考审美收敛，会互相撞身份闸。

| 顺序 | 模板 | 借什么 | 动什么 |
|---|---|---|---|
| 1 | `news-data-9x16` | shotcraft `chart-live-moves`（图表活起来：逐段绘制、焦点呼吸） | 图表动效升级 + 令牌微调；自动槽位默认模板，每步出样片 |
| 2 | `news-data-16x9` | 随 1 的横版派生重建 | 生成器重出，横版闸复检 |
| 3 | `news-trend-9x16` | shotcraft `scroll-brake-moves`（纵向急刹的节奏感） | 时间轴推进的变速升级 |
| 4 | `news-minimal-9x16` | shotcraft `paper-title-card` + `marker-underline-title`（纸张质感开场、记号笔下划线） | 开场与标题手法，保留 headline 冲击屏 |
| 5 | `news-9x16` | 最后做：手维护基线、挂知识线、影响面最大 | 单独出样片，改动最小化（令牌与标题层级为主） |
| — | `news-data-3d-9x16` / `news-16x9` | 3D 地球与横版基线暂不动 | 无样片需求来源，翻新价值待观察 |

每套完成后出真实样片过目；翻新不换 `styles.json` 叙事归属（现有映射不动）。

---

## 4. 每套的交付物与验收（同一清单套用）

| 交付物 | 验收 |
|---|---|
| `template.config.json` + `layouts/<style>/<key>.css` 版式 | 身份闸、场景类型闸、hyperframes check、landscape 闸全过（`--check` + `_tests` 全绿） |
| `_gen/behaviors/<race|chrono|dossier>.js` + 同名配方卡 `.card.md` | 配方卡先写（意图/阶段/参数/坑），代码照卡实现 |
| `SKELETON_INVARIANTS` 新风格条目 + `_shared/tokens/<style>.css` 8 令牌 | 骨架检查器不再报「没登记」 |
| `styles.json` 叙事拆分 + `StoryStyleMap` 全绿 | 六种叙事每种仍恰有一个风格承接 |
| 本地真实样片 1 条（真实 dataset/1 快照 + 真实文案，非 fixture） | **用户过目点头，这套才算成立** |

## 5. 顺序与工作量

1. **race**（数据线，最成熟的链路上先蹚工艺）→ 2. **chrono**（复用 race 蹚出的流程）→ 3. **dossier**（趋势线，动 styles.json 叙事拆分）→
4. **第二批逐套翻新**（§3.5，首批样片过目后启动，从 news-data-9x16 开始）。
每套 = 版式 CSS + behavior + 配方卡 + 令牌 + 不变量登记 + 样片，估 1 个会话完成一套；每套样片后你都可以叫停或改向。

## 6. 风险

- 「好看」的裁决在你：三套的艺术输入主要来自 shotcraft 的镜头语义移植，demo 里的参数是调校起点，首次实战须以真实素材回验（配方卡的坑一节原话）。
- dossier 拆叙事会改 styles.json 与 news-trend 的归属，动到 StoryStyleMap 契约——做这套时契约测试全量重跑。
- 三套都不带 chart 主图能力，数据线「图表屏」的承诺仍由现有 data-board 三套承接；若某期选题必须配图表，选模板会自动避开新模板（supportedSceneTypes 里没有 chart）。
