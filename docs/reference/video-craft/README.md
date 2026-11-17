# 视频表现力参考（本地蒸馏版）

本目录蒸馏自本机 HyperFrames 技能包（`~/.claude/skills/hyperframes-*` 与 `media-use`），
只收「怎么让成片更好看、更耐看」的部分，供 Heron 新闻视频线（以数据看板分支为主）离线查阅，**不作为项目规范**。

## 与 ../hyperframes/ 的分工

| 目录 | 管什么 | 回答的问题 |
| - | - | - |
| [../hyperframes/](../hyperframes/README.md) | 技术契约 | composition 怎么写、定时属性、CLI、渲染、lint |
| `video-craft/`（本目录） | 创作判断 | 画面怎么构图、数据怎么动、节奏怎么排、字幕怎么写 |

一句话：那份决定「渲染得对」，这份决定「看起来像做的，不像生成的」。

## 文件清单

| 文件 | 内容 |
| - | - |
| [frame-design.md](frame-design.md) | 视频不是网页：尺寸、密度、色彩、构图、字体、AI 套路清单、配色与视觉预设 |
| [data-motion.md](data-motion.md) | 数据呈现铁律、数字滚动、条形/环形/星级、图表刮擦读数 |
| [motion-and-transition.md](motion-and-transition.md) | 动效动词、48 条原子规则分组、22 个场景蓝图、CSS/Shader 转场 |
| [story-and-rhythm.md](story-and-rhythm.md) | 钩子、反向冰山、分镜提案、节拍设计与节奏命名 |
| [caption-and-audio.md](caption-and-audio.md) | 字幕能量分档、卡 Karaoke、音频反应、调色与 LUT |
| [quality-gate.md](quality-gate.md) | 出片前检查清单：能自动查的与只能人眼看 |

## 来源

| 项 | 值 |
| - | - |
| 来源 | `C:\Users\zhoupei\.claude\skills\` 下的 `hyperframes`、`hyperframes-creative`、`hyperframes-animation`、`hyperframes-registry`、`media-use` |
| 抓取日期 | 2026-11-17 |
| 抓取方式 | 通读 SKILL.md 与 `references/`，按 Heron 数据看板线的需要筛选后蒸馏 |
| 上游 | HyperFrames 官方技能包（随插件升级变动） |

原包里未被蒸馏的部分（按需回查原包）：

| 原包位置 | 内容 | 为什么没进来 |
| - | - | - |
| `hyperframes-core/references/` | 定时属性、子 composition、确定性规则、变量与媒体 | 已在 [../hyperframes/](../hyperframes/README.md) 覆盖 |
| `hyperframes/cli`、`hyperframes-cli` | CLI 全量命令 | 同上，见 [../hyperframes/cli.md](../hyperframes/cli.md) |
| `hyperframes-registry/references/discovery.md` | ~400 个可安装组件的搜索与装配 | 操作手册，用时现查：`npx hyperframes add <name>` |
| `hyperframes-creative/references/storyboard-recipe.md` | 分镜 HTML 页面做法 | 与 Heron 自有分镜产物不同源 |
| `hyperframes/references/intent-interview.md`、`pitch-round.md` | 需求访谈与提案流程 | 属于人机交互流程，非创作知识 |
| `media-use/audio/references/tts.md`、`transcribe.md` | 语音合成与转录接入 | Heron 已用自有 TTS 链路 |
| `hyperframes-animation/adapters/three.md`、`lottie.md`、`typegpu.md` 等 | 非 GSAP 运行时适配 | Heron 固定 GSAP，其余不引入 |

## 13 套现成视觉预设

`hyperframes-creative/frame-presets/<name>/FRAME.md`，每套 16–20KB，含配色、字体、构图、动效与 Do/Don't，可直接当 `frame.md` 用（`frame.md` 的优先级高于 `design.md`、`DESIGN.md`）。

`biennale-yellow` · `blockframe` · `blue-professional` · `bold-poster` · `broadside` · `capsule` · `cartesian` · `cobalt-grid` · `code-editorial` · `coral` · `creative-mode` · `daisy-days` · `editorial-forest`

对 Heron 三条目标风格最接近的起手式：

| Heron 风格 | 可参考预设 |
| - | - |
| 专业新闻包装 | `blue-professional`、`cobalt-grid`、`cartesian` |
| 数据调查纪录片 | `broadside`、`blockframe`、`editorial-forest` |
| 动态数据解释 | `code-editorial`、`capsule`、`bold-poster` |

> 预设是**起手式不是成品**。直接套会得到「别人的片子」；Heron 要的是在这三套骨架上长出自己的一套。

## 升级流程

技能包随 HyperFrames 插件更新，内容会变。重抓时：

1. 重读上表原包路径，与本文档对照，标出新增/改名的规则与蓝图。
2. `motion-and-transition.md` 里的规则名与蓝图名必须逐条核对——名字改了，按图索骥会落空。
3. 更新本表的抓取日期。
