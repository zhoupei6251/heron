我认为你的判断是对的：**Heron 不应该再以「视频模板」作为核心生产架构。**

而且我觉得现在需要纠正一个更深层的设计问题：

你真正需要的不是一个能够自动填充模板的视频生成器，而是一个能够理解内容、设计视觉表达、创作镜头、编排动画，最后自主完成视频制作的 **AI 视频导演系统**。

这里有一个非常重要的区别：

- 模板驱动：先有画面，再想办法把内容塞进去。
- Agent 驱动：先理解内容，再决定应该用什么画面表达。
- 专业制作系统：Agent 决定创意和表达方式，底层引擎负责把创意可靠地变成视频。

我建议 Heron 从第一个方向转向第三个方向。

## **一、GitHub 主流项目实际上在走什么路线？**

先把它们分清楚。并不是所有热门项目都采用同一种方式，也不是所有项目都真正摆脱了模板。

截至 2026 年 11 月，我认为值得重点研究的是下面几条路线。

### **1. 真正值得研究的仓库**

[OpenMontage: What It Is & How to Use It (2026 Guide) | VisionStory](https://images.openai.com/static-rsc-4/OiYvA9fGh0w6NNfi4tWOxhXHfIeSKa9gATYp_r0m1aFWqDIRPpBwR1-3OLVuF1vhIii3neWTt2TBHHFAl9mMsbB9090WQoH1WwiO4JebtGcuipBAd4B9wFRUKyzARPDqdG_wEd09jzWTzy4RD-nlAD_Wmr_ePU9JkEzGd25TsGU?purpose=inline)

**1. [OpenMontage](https://github.com/OpenMontageInc/OpenMontage?utm_source=chatgpt.com)**

最值得借鉴生产架构

它采用 Agent 驱动的分阶段生产模式。Agent 自己决定创作方案、调用工具、生成各阶段产物、检查结果，而不是由 Python 程序把视频的所有画面固定下来。

Research → Proposal → Script → Scene Plan → Assets → Edit → Compose → Publish

参考：官方架构文档和生产规范。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+2



[Behind the Craft: RSPB - Time Flies | Aardman](https://images.openai.com/static-rsc-4/KJCxydRyg3NrCtivMEEdj__7mGQexUtbPFi9WgGRh20VZ9gZIKSwaVNhdfimPsf9oLQYH6D6NfiME1Awko2QMgKA2auZMT343BIySIVrSN1b4URgwfo75lIRu1hPkYCnufEDOL0orPjMg8Qfpes1VfNBU5cbdiY_--QUYTInfiA?purpose=inline)

**2. [Lemo Opuscar](https://github.com/lemomo-ai/lemo-opuscar?utm_source=chatgpt.com)**

最值得借鉴创作方法

99 种视觉风格，每一种都有风格规范和代码制作的 Demo。最关键的是它要求先写自己的导演方案，再研究 Demo 的实现技术，禁止复制 Demo 的故事、镜头、道具和时间线。

DIRECTOR.md + TECHNIQUE.md + STYLE.md + TREATMENT.md

参考：

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



[AIで音楽付きショート動画、本当に作れる？——HeyGen「HyperFrames」で架空の会社と自分のnote記事を動画化してみた｜上野敬人](https://images.openai.com/static-rsc-4/4QGANJpRN9R3Vpxh9OWSrCRVzfZ-X-vkDWknRVx4PE6mFk4q639vnnkEvJqupxXdejJQguQtjWZtqIBlm35rDd12IfFYL2OMgk_pF-hJ3-eOa_5U3xtV92Gts8eYNN8ic9ThaJo0eb88UGoOFCtaflvITfj1ckDmPr4YOMd4vLk?purpose=inline)

**3. [HyperFrames](https://github.com/heygen-com/hyperframes?utm_source=chatgpt.com)**

最推荐的渲染底座

Agent 直接编写 HTML、CSS、JavaScript 和动画，使用 GSAP、Three.js 等技术制作画面，逐帧渲染成 MP4。自带可复用的组件和效果库，但不要求整条视频使用固定模板。

HTML Composition → Seekable Animation → Frame Capture → MP4

参考：

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+2



[Google Flow Review: AI Filmmaking Tool | DevShelfHub](https://images.openai.com/static-rsc-4/DR1nRn-t336ojInQRqsV6FqpBinnRXuIgxNwUv80BFkbVkC1GHDlBcK_K_ixLEwhaMu06wNdAaEcFhXKcwR-EBtr15ozuL2XR01DkykeXY-pExjvu_GwSKXQGOsLRYN1_m0SFwLirvQo2gkdS13ei_lnvPE8EN2bkdhqzrl0hg0?purpose=inline)

**4. [OpenFilm](https://github.com/openfilm/openfilm?utm_source=chatgpt.com)**

值得研究其视频表达模型

Agent 自己编写影片页面，页面里可以组合视频、图片、声音和独立场景。支持 Canvas、SVG、WebGL、Three.js、GSAP 等，并提供实时预览和人工编辑。

Film HTML → Independent Shots → Timeline → Preview / Edit → Render

参考：

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



[How Diffusion Studio is changing video editing with AI-Powered text instructions | by Rysysth Insights | Medium](https://images.openai.com/static-rsc-4/OLu_rjA5t6DvfkYWzFWkSUDtKUneEdb14SuHRkCl5lXAiB0vPLFNSQLOL1BYaVMtwiLha_X6RL74Hbf8aTf80ovLxhDsciLb8s9Opacnr_APzOM8Qly5cLO4RHnKjW-iQrnRaoJ7pJxlzlWdsT3KAUoftndXEvcwGfq97aB2HHo?purpose=inline)

**5. [Hypit](https://github.com/hypit-ai/hypit?utm_source=chatgpt.com)**

值得借鉴时间线设计

它把视频拆成素材、字幕、B-roll 和特效，并且强调以语音中的词语为锚点，而不只是依赖固定秒数。可以根据参考视频复刻工作流，也可以从空白描述开始创作。

Voice / Word Anchors → Visual Workflow → Composition

参考：

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



[视频转提示词生成器 - 从视频提取 AI 提示词 | Video to Prompt](https://images.openai.com/static-rsc-4/h_r_BTgPaMsDqNNKKbu-6TTaYmBpmYA8pjmu08hWLsN3M60SESzU28T0pQRIXjKVz0Q4IuanR-Qi5A_yC4M1SYSnAPepyx9Mz3nQwQ_Aw15XhYbkdS_GM_-1Sy0auIWbLZ9y-N_wiovwKRnNs6e_ZC6v_f3VXCN5JqSVfo4dCjM?purpose=inline)

**6. [Rea Video](https://github.com/JuneYaooo/rea-video?utm_source=chatgpt.com)**

值得借鉴视频研究方法

它不负责生成最终视频，而是反向拆解视频：分析每一个镜头、镜头运动、光影、字幕、声音，以及画面究竟是实拍、AI 生成还是代码制作，最后输出 Agent 可以使用的 VIDEO.md。

Reference Video → Shot Analysis → VIDEO.md → Remake Plan

参考：

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



还有几个辅助项目也值得区分：

| 仓库                                                                                                  | 核心做法                    | 我对它的评价             |
| --------------------------------------------------------------------------------------------------- | ----------------------- | ------------------ |
| [MoneyPrinterTurbo](https://github.com/harry0703/MoneyPrinterTurbo?utm_source=chatgpt.com)          | 文案、素材、TTS、字幕、音乐、剪辑自动化   | 适合批量生产，不是最好的创意导演架构 |
| [Manim](https://github.com/ManimCommunity/manim?utm_source=chatgpt.com)                             | 程序化数学和科学动画              | 专业图形动画子引擎          |
| [Motion Canvas](https://github.com/motion-canvas/motion-canvas?utm_source=chatgpt.com)              | TypeScript 动画、实时预览、旁白同步 | 适合知识图解与矢量动画        |
| [Stickman Video Director](https://github.com/kaomei/stickman-video-director?utm_source=chatgpt.com) | 分镜导演模式、自动插画动画模式         | 适合故事和解说类视频         |

这些项目的实现侧重点不同：MoneyPrinterTurbo 已经有素材来源、AI 视频、TTS、字幕和分场景修订机制；Manim 专门面向数学动画；Motion Canvas 侧重可编程的矢量动画。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+4



**我需要修正前面一条回答中的一个说法：这些项目并非完全没有模板。** Hypit 明确提供模板作为入口，HyperFrames 也有约 450 个可复用的 blocks 和 components。关键是它们把可复用内容放在局部能力层，而不是强制所有视频沿用同一套故事和镜头布局。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+2



这恰恰是 Heron 应该学习的地方。

## **二、我认为最重要的区别：模板、风格、镜头技术不是一回事**

我建议把视觉资产分成四个层次。

| 层次    | 应该固定什么          | 应该动态什么          |
| ----- | --------------- | --------------- |
| 品牌与风格 | 字体、配色、质感、视觉纪律   | 内容如何使用这些规则      |
| 视觉技术  | 图表能力、镜头运动、特效、转场 | 每次使用哪种技术        |
| 视觉组件  | 某个可复用的动画效果或图形能力 | 组件如何组合、如何修改     |
| 整部视频  | 不应该固定完整视频结构     | 故事、镜头、画面、节奏和时间线 |

举个例子。

你有一组真实数据：

2022 年增长 10%，2023 年增长 12%，2024 年增长 45%，2025 年又下降 8%。

这组数据可以有好几种完全不同的表现方式。

[Broker-Margin-Zinsen 2026 im Vergleich: IBKR, CapTrader, Scalable & Co. | MarketBlend Blog | MarketBlend](https://images.openai.com/static-rsc-4/6cGxx9uG26za37rEUK7mR0NMTO5-TiqeHfsWKmTLYl04UY4QHpzNSQDSNiv8yx8eNSUYmHRkOgQhsMZiY9RqT80iezc4_iw3SBC3aLtWV-JX767AijOl_B71mDepFvVCkaBCn28VqQOpvhU2OPfSzLPNTqP-n2G-WirsBN9rZAs?purpose=inline)

**方案 A：数据冲击型**

一开始全部隐藏，只显示缓慢增长，到了 2024 年突然出现巨大增长柱，镜头跟随柱体向上冲，然后突然断崖下跌。

[How will Keir Starmer pay for greater defence spending? | The Week](https://images.openai.com/static-rsc-4/cxChECLTrR3YIYa0FGyg31zkCpKsn6caNuOn6c0fdYlGqeZJEmazpCNbiSr8nL1b60EH40r8NoaHaTq5HMhGCfmxtkMN3-bs5ziJApJe98XqszmkuHFKVs0uzvddZ1EMZMSPU2MkiLbXk8OvC6fp2NWTm2WM-cHVUkPdqmX1_TY?purpose=inline)

**方案 B：调查纪录片型**

从新闻、报告和原始资料切入，先提出一个疑问，再逐步揭示增长背后的原因，最后用数据图表验证。

[Portugal’s Real Estate & Private Equity Funds – The Golden Investment - My Framer Site](https://images.openai.com/static-rsc-4/JwRxGRb3IvyfMCMQktrr4RuxkEdLHbnst3I5hntRODlWJTBA1mWxp2QyDrMBQXOiErJTDeihdcFO1sL06kdtOhRydpeVMDOXkRzCZC36U4XrUFfCKXBXrcllHNfiYVlQ2g9MAuJLycdh6hohBOJ-tr9B4Tey3MR4iFWicKOgU8Y?purpose=inline)

**方案 C：三维视觉故事型**

将增长和下降转化成三维空间里的高度、体积和运动，让观众直观感受到规模变化。

[R15K Kinetic Typography Video | Motion Graphics \[VIDEO\] | FEVR](https://images.openai.com/static-rsc-4/pNjCg06WaH4mF1wtUOPkiXt-X8ijXy4eq4eJZLpaIiCpECtOZ4XR-jJ_WekbNhWyr2agbYKhpEDtXLi8EMEx06ne8wOfequKo_Es8hQl6CcO3ZDlln03DMtbNmxEqXf9izZwiMvxP7lKcVCECXlHz3YKX6Mu9y61B03ArSNWeOE?purpose=inline)

**方案 D：文字观点型**

不从图表开始，而是用一句有冲击力的观点吸引观众，再通过数字、对比、图解逐步证明。

同一组数据，四种不同的创作思路。

这也是我不建议你为 Heron 建立固定的「金融视频模板」「数据视频模板」「知识视频模板」的原因。

这些分类最多可以作为内容类型和制作能力的参考，不应该决定最终的视频画面。

## **三、Heron 应该采用什么架构？**

我最推荐的是：

# **Heron AI Video Director**

以内容和叙事为中心，以 Agent 为创作者，以渲染引擎为执行工具。

**真实数据与素材**

数据、新闻、文章、资料、图片、视频

**Insight Discovery · 发现值得讲的事情**

发现变化、异常、冲突、趋势、反常识

**Editorial Director · 叙事导演**

观点提炼、故事结构、开场钩子、叙事节奏

核心创作环节

**Visual Director · 视觉导演**

决定每一个故事节点应该怎么被视觉化

数据可视化

影视镜头

三维动画

动态文字

插画与视觉素材

信息图解

**Shot Composer · 镜头创作**

Agent 为每个镜头设计画面、编写动画、选择素材

**Timeline & Composition**

声音、旁白、镜头、动画、转场和节奏编排

**HyperFrames + FFmpeg**

确定性渲染、音频混合、视频编码

**Video QA · 视频质量审核**

画面、数据准确性、连贯性、声音、节奏检查和返工

# **Final Video**

这里有一个关键点：

**Visual Director 不应该从一个视频模板里选择画面，而应该根据每一个叙事节点选择最合适的表现手段。**

例如：

你要讲的是「某城市人口连续三年流出」。

Visual Director 可能决定：

- 开头：用城市卫星地图，人口数字逐渐消失。
- 第二个镜头：用动态地图展示人口迁移方向。
- 第三个镜头：用折线图表现三年人口变化。
- 第四个镜头：用年龄结构的可视化展示人口结构变化。
- 第五个镜头：用真实城市街景或合适的视觉素材表达影响。
- 最后：用一句有冲击力的观点收尾。

这些画面不应该提前写死。

### **具体应该产生哪些中间产物？**

我建议 Heron 每个阶段都保存独立的创作产物，而不是只有一个 Template JSON。

| 中间产物                 | 作用                      |
| -------------------- | ----------------------- |
| `ResearchPack`       | 原始数据、事实、来源与证据           |
| `InsightBrief`       | 有价值的变化、核心观点和可信度         |
| `NarrativeTreatment` | 叙事方案、开场、冲突、转折、结论        |
| `VisualTreatment`    | 全片视觉方向、镜头语言和表现策略        |
| `Storyboard`         | 每个镜头的具体设计和叙事目的          |
| `AssetManifest`      | 图片、视频、音频、字体及其来源         |
| `ShotComposition`    | 每个镜头实际生成的 HTML、JS、动画或素材 |
| `EditDecisionList`   | 镜头顺序、旁白锚点、时间、音频及转场      |
| `RenderReport`       | 渲染结果、质量检测和修改记录          |

这些是生产过程中的数据契约，不是画面模板。

**尤其要把 NarrativeTreatment 和 VisualTreatment 分开。**

前者回答「我到底要讲什么」，后者回答「我应该如何让观众看到和理解它」。

## **四、我建议 Heron 采用的具体技术组合**

## **推荐技术栈**

**生产编排**

沿用 Java

Java 负责任务状态、阶段控制、模型调用、工具调用、版本管理、任务队列和产物持久化。

**视觉创作**

Agent + HTML/JS

Agent 根据分镜现场编写视觉代码。HTML、CSS、SVG、Canvas、GSAP 可以组合使用，不将画面限制在固定 Vue 组件或固定视频模板。

**视频渲染**

HyperFrames

将独立镜头编排成可预览、可逐帧渲染的视频。HTML 页面作为画面载体，FFmpeg 负责视频编码及音频处理。

**素材分析**

Rea Video 思路

自动分析参考视频，学习其镜头、构图、转场、声音设计，而不是直接搬运其他人的视频。

**视觉知识库**

参考 Lemo Opuscar

建立风格规范、镜头技术、动效能力和优秀作品案例库。

### **为什么我首推 HyperFrames？**

它解决的是 Heron 最重要的一项工程问题：**如何将 Agent 自由创作的网页画面可靠地变成视频。**

它的渲染引擎使用 Puppeteer、Headless Chrome 和 FFmpeg，能够逐帧定位动画时间，要求相同输入时间产生相同画面。它也不绑定 React，可以使用 GSAP、Lottie、Three.js 或原生网页内容。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



这意味着：

你不用把 Heron 前端从 Vue 改成 React。

可以保持：

- 后端：Java。
- 管理界面：Vue。
- 渲染工作进程：Node.js + HyperFrames。
- 视频处理：FFmpeg。
- 视觉生成：LLM 编写代码，必要时调用图片、视频生成 API。

注意 HyperFrames 当前引擎要求 Node.js 22+，你目前的 Node 20.19.5 不能直接满足这一要求。建议独立部署渲染工作进程，而不是为了视频功能贸然升级现有项目的 Node 运行环境。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



OpenFilm 也很有意思，它的 `film.html` 协议甚至允许不同场景使用完全不同的技术来绘制画面。不过它当前协议标记为 0.1 draft，我更建议现阶段把它作为架构参考，而不是马上把 Heron 的整个视频格式绑定到它。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub



## **五、如何真正产出好看的视频？**

这件事不是换一个渲染引擎就能解决的。

我认为至少要保证以下五个环节。

**1. 先让故事值得看**

不能只是把数据生成图表。

一个好的视频需要让观众产生疑问、期待答案，逐步看到证据，最后获得一个新的认识。

每条视频最好有一个明确的核心观点，而不是塞入十几个互不相关的信息点。

**2. 每一个镜头都必须有视觉目的**

不要为了让画面动起来而添加动画。

例如，增长数据应该有比较、突出变化；人物故事应该有情绪和动作；地理变化应该有地图和空间关系。

动画必须服务于信息。

**3. 建立视觉一致性，但不要牺牲创造力**

应该固定字体体系、视觉层级、颜色纪律、留白、动效原则。

但不同视频可以选择不同的画面风格，甚至同一视频里，不同故事节点也可以采用不同的表现技术。

**4. 重视声音和节奏**

旁白不应该像机器朗读数据。

需要有停顿、强调、情绪变化，背景音乐和音效应当支持故事，而不是从头到尾铺一段音乐就结束。

**5. 必须有可视化审核和返工**

我非常建议学习 OpenMontage 的阶段检查点，以及 HyperFrames 的逐帧验证能力。

视频不能只看最终 MP4 是否生成成功，还要抽取关键帧，检查文字、图表、布局、动画和旁白同步情况。发现某个镜头不好看，只重新创作这个镜头，而不是推翻整条视频。

## **六、关于 Skills：Heron 到底需不需要？**

结合你现在的 Java + LLM 架构，我的意见是：

**不要把 Skills 当成 Heron 运行的必要依赖。**

你完全可以由 Java 控制：

- 研究 Agent 的指令。
- 叙事导演的指令。
- 视觉导演的指令。
- 镜头编排的指令。
- 视频审核的指令。

Lemo Opuscar 和 OpenMontage 的 Skills 值得学习的地方，是它们把专业创作知识组织成了可以被 Agent 使用的规则和技术指南。

你可以把这些内容整理成 Heron 自己的：

`Director Knowledge Library`

比如：

```
creative-knowledge/
├── directing/
│   ├── narrative-direction.md
│   ├── visual-direction.md
│   └── sound-direction.md
│
├── styles/
│   ├── documentary/
│   ├── editorial/
│   ├── cinematic/
│   ├── infographic/
│   └── experimental/
│
├── techniques/
│   ├── data-visualization/
│   ├── kinetic-typography/
│   ├── camera-motion/
│   ├── transitions/
│   └── three-dimensional/
│
└── quality/
    ├── visual-review.md
    ├── audio-review.md
    └── storytelling-review.md
```

这里的 Style 不是整部视频的模板，Technique 也不是固定镜头。它们是 Agent 的专业知识。

## **七、我给 Heron 的最终建议**

# **不要再围绕 VideoTemplate 发展核心架构**

建议定位：AI-native Video Production System

**应该保留和发展**

VisualDesignProfile：只定义风格和视觉纪律

Technique Library：可组合的镜头技术和动画能力

Agent Director：动态决定叙事与视觉表达

Shot Composition：每个镜头独立创作

Deterministic Renderer：可靠地执行与渲染

**不应该继续强化**

固定整片布局和镜头顺序

为每种视频类型开发一套完整模板

把内容、故事、视觉设计与渲染耦合在同一套代码中

我还建议分三步推进：

1. **第一阶段：先改变创作方式。** 去掉固定模板对镜头和整片结构的约束，建立 Research、Narrative、Visual Director、Shot Composer 四个核心阶段。
2. **第二阶段：建立动态视觉能力。** 引入 HyperFrames，让 Agent 能够按镜头创作 HTML/GSAP 动画，完善素材库和镜头技术库。
3. **第三阶段：提高专业制作能力。** 加入 Rea Video 式的参考视频分析、视觉 QA、镜头返工、创作效果评估和历史作品学习。

我尤其不建议你在第一阶段就同时引入 Manim、Blender、Three.js、Remotion 等多个完整引擎。先用 HyperFrames + SVG/Canvas/GSAP 把二维数据可视化和动态图形做好，确有三维叙事需求再扩展 Three.js。

你目前有不错的文本模型、Java 后端和 Vue 管理端，也没有 NVIDIA 独显，因此代码驱动的动态图形、数据视觉化和素材混剪，是比本地 AI 视频模型大规模生成更务实的起点。

**最后我想强调一个判断：**

Heron 最终应该做到：

> 用户提供一个主题，Heron 自己发现值得讲的事情，自己形成观点，自己选择故事表达方式，自己设计每个镜头，自己完成画面创作、声音设计和剪辑，并且能够对不满意的画面自主返工。

这才是我认为值得建设的 Heron，而不只是一个自动套模板的 AI 视频平台。

你之前提出的 `VideoDesignProfile` 可以继续保留，但应当定位为视觉规范，而不是整片视频设计模板。