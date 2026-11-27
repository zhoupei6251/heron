# 视频线参考仓库

> 2026-11-25 整理。star 为当日快照，只作热度参考，不代表质量。
> 「可借鉴」一列是我按 heron 新闻线（文稿解说线 / 数据看板线）场景做的初判，不是结论——真要动手前先读源码。

## 一、视频生成全链路（同类产品，看整体架构）

| 仓库                                                                              | 语言 | star | 是什么 | 可借鉴 |
|-----------------------------------------------------------------------------------| --- | --- | --- | --- |
| [MoneyPrinterTurbo](https://github.com/harry0703/MoneyPrinterTurbo)               | Python | 145k | 主题/关键词一键生成高清短视频，AI 工作流 | 最完整的"选题→文案→配音→素材→合成"流水线拆解，闸门与降级怎么摆 |
| [hyperframes](https://github.com/heygen-com/hyperframes)                          | TypeScript | 90k | HeyGen 出品，HTML 写、渲染 MP4，开源 AI 视频编辑器 | **本仓库已在用**（`HERON_HYPERFRAMES` + hyperframes skill），重点读渲染管线与 composition 组织 |
| [OpenMontage](https://github.com/OpenMontageInc/OpenMontage)                      | Python | 83k | 12 条生产管线、100+ 工具、700+ agent skill 的 agentic 视频制作系统 | 多管线如何共用一套工具层；skill/知识文件怎么切分 |
| [Toonflow-app](https://github.com/HBAI-Ltd/Toonflow-app)                          | TypeScript | 23k | 短剧/漫剧制作：剧本、分镜、角色场景资产、图生视频，无限画布 | 剧本→分镜→素材的资产模型；画布式编排 |
| [claude-real-video](https://github.com/HUANGCHIHHUNGLeo/claude-real-video)        | Python | 2.3k | 把视频变成"LLM 能看的东西"：场景感知去重抽帧 + 字幕转写 | 抽帧与去重策略，量大时怎么控 token |
| https://github.com/hypit-ai/hypit                                                 |
| https://opentrend.org/zh/project/autoclip                                         |
| https://github.com/XiaoYouChR/Ghost-Downloader-3                                  |
| https://github.com/Azure12355/weilanx-claude-videos?utm_source=chatgpt.com        |
| https://github.com/sirioberati/YouTube-AI-Motion-Graphics?utm_source=chatgpt.com  |
| https://github.com/Anil-matcha/vox-ai-motion-graphics-generator?utm_source=chatgpt.com  |
| https://github.com/andyhuo520/vibe-knowledge  |
| https://github.com/xiazhuo123456789/vibe-knowledge-video?utm_source=chatgpt.com  |

## 二、分镜、镜头与逆向拆解

| 仓库 | 语言 | star | 是什么 | 可借鉴 |
| --- | --- | --- | --- | --- |
| [video-shotcraft](https://github.com/Vincentwei1021/video-shotcraft) | TypeScript | 22k | Remotion 产品宣传片 skill：124 张镜头配方卡、150 个动效预览、卡点同步、剪映导出 | 镜头配方卡的组织形式，正是分镜模板要的"卡片库" |
| [rea-video](https://github.com/JuneYaooo/rea-video) | Python | 13k | 逐镜头逆向任意视频（实拍/AI/代码生成），输出 `VIDEO.md` | 用开放文本格式描述分镜，比 JSON schema 更利于人机共读 |
| [PySceneDetect](https://github.com/Breakthrough/PySceneDetect) | Python | 5.6k | 基于 OpenCV 的场景切换/转场检测 | 切分点检测，做参考片拆解或成片校验时用 |

## 三、代码驱动的动效与动画

| 仓库 | 语言 | star | 是什么 | 可借鉴 |
| --- | --- | --- | --- | --- |
| [motion](https://github.com/motiondivision/motion) | TypeScript | 36k | Framer Motion，React/JS 主流动画库 | 网页类 template 的过渡曲线与编排 |
| [motion-canvas](https://github.com/motion-canvas/motion-canvas) | TypeScript | 20k | 用代码做可视化动画，本身就是编辑器 | 时间轴抽象；代码→画面→预览的闭环 |
| [lemo-opuscar](https://github.com/lemomo-ai/lemo-opuscar) | JavaScript | 21k | 99 种"用代码做片"的风格箱，每种是风格提示词 + 一段纯代码 demo | **模板族的直接参照**：一种风格 = 提示词 + 可运行样例，和 `news-*` 模板的组织方式同构 |
| [oil-motion](https://github.com/oil-oil/oil-motion) | Python | 3k | Claude Code / Codex 的网页动画 skill：跟随滚动与鼠标的动效 | skill 形态的动画能力封装，看怎么给 agent 描述动效 |

## 四、素材、数据与热点

| 仓库 | 语言 | star | 是什么 | 可借鉴 |
| --- | --- | --- | --- | --- |
| [awesome-public-datasets](https://github.com/awesomedata/awesome-public-datasets) | — | 80k | 按主题分类的高质量公开数据集清单 | 数据看板线选源的种子池，可作 `dataset` 注册表的人工初筛 |
| [TrendRadar](https://github.com/sansan0/TrendRadar) | Python | 64k | 多平台热点聚合 + RSS + AI 筛选 + 多通道推送，支持 MCP | 热点采集与打分链路；和内容机会评分/选题门禁对照读 |
| [douyin-downloader](https://github.com/jiji262/douyin-downloader) | Python | 13k | 抖音单条/主页批量下载，SQLite 去重、浏览器兜底 | 去重与失败重试的工程做法；发布侧回执补链接可参考 |
| [FFmpeg](https://github.com/FFmpeg/FFmpeg) | C | 68k | 官方镜像 | 合成、转码、封面抽帧的底座，查参数直接看文档 |

## 五、视觉设计与排版

| 仓库 | 语言 | star | 是什么 | 可借鉴 |
| --- | --- | --- | --- | --- |
| [ppt-master](https://github.com/hugohe3/ppt-master) | Python | 80k | 文档/主题转原生 PPT：真形状、转场动画、数据图表、讲稿配音 | 长文 → 版面拆解的思路；数据图表与文字的配比规则 |
| [diagram-design](https://github.com/cathrynlavery/diagram-design) | HTML | 72k | 编辑级图表设计，52 种图型，自包含 HTML + SVG，明确反对 Mermaid 味 | 数据看板线的图表审美基准；"不要什么"写得比"要什么"更具体 |

## 六、配方卡与 skill 型工具库（2026-11-26 侦察补充）

> 背景：ADR（`docs/architecture/heron-visual-agent-adr.md`）定案后，VisualRecipe 配方库需要
> 直接可抄的资源。本节按配方卡形态组织，star 为当日快照。

| 仓库 | 语言 | star | 是什么 | 可借鉴 |
| --- | --- | --- | --- | --- |
| [video-talkcraft](https://github.com/Vincentwei1021/video-talkcraft) | TypeScript | 2.5k | video-shotcraft 同作者的解说视频 skill：**109 张动效配方卡**、词级配音同步、防幻灯片镜头系统 | **配方卡格式直接参照**；词级同步正是 heron `subs.vtt` 对齐链路要的；「防幻灯片」规则可进 Playbook |
| [anything2explainer](https://github.com/Vincentwei1021/anything2explainer) | TypeScript | 3.6k | 话题进、解说视频出：黑底 motion-graphics + TTS + 字幕 + 章节进度条，逐帧代码绘制 | **黑底统一视觉基调 = 片级锚定的现成范例**；「话题→脚本→逐帧」链路与 heron 新闻线同构 |
| [huashu-art-motion](https://github.com/alchaincyf/huashu-art-motion) | — | 7.7k | 艺术动画 skill：35 种艺术风格、9 种解说语法，用代码让画动起来 | 「风格 = 提示词 + 代码」的第三家范本；解说语法 9 种可对照 Playbook 粒度 |
| [openmotion](https://github.com/nexu-io/openmotion) | — | 16k | 参考视频 → 品牌套件化 motion graphics，300+ Motion.md 指南，22 种 agent 支持 | **Motion.md 指南组织方式**；「参考视频反推配方」的抽卡思路可配 rea-video 用 |
| [awesome-opus5-5-videos](https://github.com/yihui-dev/awesome-opus5-5-videos) | — | 17k | 病毒式传播视频 + 背后 prompt 的合集，原片与复刻对照 | 配方卡的「片例库」素材来源：爆款 → 拆解 → 提炼配方 |
| [awesome-claude-video-skills](https://github.com/zhuyansen/awesome-claude-video-skills) | — | 2.7k | 180 个视频类 agent skill 合集，按类型分类、逐个安全评级 | **建工时的 skill 检索索引**，按需从里面挑，不整库吸收 |
| [claude-code-video-toolkit](https://github.com/digitalsamba/claude-code-video-toolkit) | — | 2.8k | Claude Code 全链路做视频：脚本、AI 配音、音乐、渲染，开源模型低成本 | 成本控制段可对照；配音/音乐选型参考 |
| [openfilm](https://github.com/openfilm/openfilm) | — | 9.4k | 把任意 coding agent 变成视频 agent，配可改产物编辑器 | 编排层对照读：它全自由、heron 全锚定，看差距在哪 |
| [vizzu-lib](https://github.com/vizzuhq/vizzu-lib) | C++/JS | 2.1k | 动画数据可视化与数据故事库，图表间平滑过渡 | 数据看板线图表动效的直接弹药：DATA_CHART 类配方的过渡可抄它的动画模型 |

> 观察：video-shotcraft（124 卡）→ video-talkcraft（109 卡）→ anything2explainer 同一位作者，
> 配方卡格式已在三个仓库里迭代过三轮，**抄格式认准这一族**，别自己发明。
>
> **许可警示（2026-11-26 审计）**：video-talkcraft 与 anything2explainer 是 **PolyForm Noncommercial**——
> 商业使用需作者授权，heron **不收录其任何文件**（卡格式知识已通过设计文档合法消化）。
> 合法可收的是同作者的 video-shotcraft（Apache-2.0）、huashu-art-motion（MIT）、openmotion（Apache-2.0，
> 与 openmotion 同源的 **hyperframes 官方 registry** 448 组件也已直接 vendor）。

### 已收录（2026-11-26，进 `data/reference-cards/`，见该目录 ATTRIBUTION.md）

| 收录物 | 许可 | 对 heron 的用途 |
| --- | --- | --- |
| hyperframes 官方 registry 31 件（16 block + 15 component） | Apache-2.0 | **人物互动 6 张的等价物全齐**：lt-* 信息条 8 变体、news-ticker、social-proof-card、cta-lockup（talkcraft 的 subscribe-cta / lower-third / 关注卡在此有 Apache 版）；数据卡 bar-chart-race / data-chart / chart-story / count-up / number-wheel / 三张地图直接是 heron 缺的场景实现候选 |
| video-shotcraft 125 张镜头卡 + taxonomy + aesthetic-rules | Apache-2.0 | 产品片配方母本池（data/typography/ui-entrance/transition 各类），卡格式源头 |
| huashu-art-motion 57 张风格配方 + 9 份解说语法 | MIT | 风格卡格式第三家范本；解说语法（3b1b/Vox/Kurzgesagt）是 Playbook 的直接素材 |
| openmotion registry（catalog.json 已留在原克隆，未全量收录） | Apache-2.0 | 448 组件含 catalog 索引，与 hyperframes registry 同源；按需再从 `hyperframes catalog` 拉 |

### 能抄 / 不能抄（2026-11-26 读源码结论）

**video-talkcraft 能抄**：卡字段集（输入/语义/位置/props）；「已知坑」写法（具体翻车形态，不写抽象警告）；「动效范围」（属于/不属于本卡）；输入类型索引作选卡第一道过滤；两条通用规律——入场→hold→出场三段式、同屏重音同一时刻只能一个；转场口诀（最大反转 black-slam 全片限一次）。**108 卡的知识层能合法抄（方法范畴，已进设计文档）；实现层文件因 PolyForm NC 不可收录**——实现等价物改从 hyperframes 官方 registry（Apache-2.0）取，lt-* 信息条/news-ticker/social-proof-card/cta-lockup 覆盖人物互动 6 张中不需要真人的全部场景；真正不可替代的只有 4 张需真人出镜的卡（behind-text-title、host-shrink-to-chip、parallel-items-with-host、host-card-glass-board），heron 现在也产不了人物画面。另一条路：取得作者授权后按 talkcraft 设计语言整体引入新 composition——产品决策非架构决策。

**anything2explainer 能抄**：视觉纪律进 Playbook——闪烁只给重点（每镜头 ≤1 处）；持续动作+落位停留，入场即停/落位即切都是缺陷；最长静止 ≤3s、末拍稳定期 ≥30 帧（有量化脚本）；镜头按画面单元分不按句分；字幕带禁放内容。**不能抄**：黑底视觉体系本体；四个人工确认点（heron 全自动）；Remotion 工程结构（heron 走 hyperframes HTML）。

## 待办

- [ ] 按上表优先级逐个读源码，每读一个补一段"能抄什么/不能抄什么"，别只留链接
- [x] hyperframes 与 lemo-opuscar 最先读：前者已在用，后者和模板族同构（hyperframes 已在用；lemo-opuscar 结论：风格=提示词+代码的组织方式已被 ADR 吸收为配方卡思路，风格箱本体不搬——heron 场景长在模板里）
- [x] 读 video-talkcraft 的配方卡 schema 与 anything2explainer 的链路，产出 heron `data/recipes/*.json` 的字段设计初稿 → `docs/plans/2026-11-26-visual-recipe-field-design.md`（含词汇真源盘点、字段 schema、首批约 30 张卡的建工路径）
