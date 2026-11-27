# **先给结论**

这四个仓库有参考价值，而且其中有一个我认为值得你重点研究：**andyhuo520/vibe-knowledge**。

我对 Heron 的定位有一个判断：你不是要做普通的 AI 知识视频生成器，而是要做一个能够自主策划、制作并持续发布高质量「Vibe 知识大赏」的内容生产系统。

因此，不能简单地把一个现成 Skill 接入 Heron。真正值得吸收的是它们的：

1. **视觉语言**：如何让视频看起来有质感，而不是 PPT 加字幕。
2. **镜头语言**：如何通过镜头变化、构图、转场和节奏讲故事。
3. **组件生产能力**：让 Agent 根据内容组合画面，而不是每次只能选择一个固定模板。
4. **生产质量控制**：配音、字幕、画面、节奏和最终视频如何相互校验。

这也意味着，Heron 应该借鉴它们的制作方法，而不是照搬它们的整体架构。

## **一、你给出的四个仓库，逐个评价**

以下 Stars 是我在 2026 年 11 月 27 日查询 GitHub 时看到的数据，不代表代码质量排名。

| 仓库                                                                                                         | Stars | 我的评价   | 对 Heron 的价值    |
| ---------------------------------------------------------------------------------------------------------- | ----- | ------ | -------------- |
| [vibe-knowledge-film](https://github.com/HeyChengC/vibe-knowledge-film?utm_source=chatgpt.com)             | 1,613 | 非常值得看  | 视觉风格、导演分镜      |
| [vibe-knowledge](https://github.com/andyhuo520/vibe-knowledge?utm_source=chatgpt.com)                      | 842   | 非常值得看  | 完整制作流程、组件库     |
| [whiteboard-teacher-video](https://github.com/liangdabiao/whiteboard-teacher-video?utm_source=chatgpt.com) | 597   | 有选择地参考 | 手绘动画、音画同步      |
| [vibe-knowledge-video](https://github.com/xiazhuo123456789/vibe-knowledge-video?utm_source=chatgpt.com)    | 216   | 非常值得看  | 视觉叙事、镜头语言、质量控制 |

### **1. vibe-knowledge-film：值得借鉴视觉导演能力**

[小五狼的抖音 - 抖音](https://images.openai.com/static-rsc-4/CtM5r7YGJfot25Ffz3OsUrEiAfQ_2MebdXIz9iTH6s0iYVLRqBkkFsFPPzzOnRcOuUheds2YGEld1yXDp3UEJ2Pouu8L1QU9Q5Njfz1f5U_cap8VI7WxgBmqLRV5nBd_V2TpoMKZAWgvs6dNLVPdjG1GmZO3My--4OSXDYIDXAk?purpose=inline)

[**查看 GitHub 仓库**](https://github.com/HeyChengC/vibe-knowledge-film?utm_source=chatgpt.com)

它将知识短片拆成两个视觉世界：

- 夜曲：深色、神秘、抽象、底层原理。
- 寓言：通过人物、物件和具体场景演绎心理与行为机制。

值得借鉴的地方：

- 先分析选题，再写文案和导演分镜表。
- 先制作约 30 秒预览，再制作完整影片。
- 使用 Canvas + JavaScript 逐帧绘制。
- 每帧由时间参数决定，支持任意时间截帧、局部重渲。
- 有独立的字幕、光效、镜头和音效组件。

**我的评价：这是最值得 Heron 研究的视觉导演项目之一。**

但它只有两套主要视觉世界，并不是一个完整的自主内容生产平台。不要将它的两种视觉模式直接变成 Heron 的固定模板。

### **2. vibe-knowledge：最完整的知识短片生产参考**

[VidMakerPro - AI Shorts Generator | Faceless Videos Up to 60s | Scene Coherence | No Editing](https://images.openai.com/static-rsc-4/EobSzogdj7wLy9ztvuXdkvlJbRyWfeB8fxT28fHPNbk-6WdUtG0oD4prIxt87lpPk1mwtgVoG2E2IWu6OktinqIvn7yCXJwlM1Sqh0TsGL3g-qNt21xDoD3ICXB9NlC-9a-TpucuxrK-eulJRhHD_YU1BkcZ5N0_Xdpw8T5C38E?purpose=inline)

[**查看 GitHub 仓库**](https://github.com/andyhuo520/vibe-knowledge?utm_source=chatgpt.com)

它有 44 套视觉风格、8 个风格家族、6 种脚本母版，以及专门的知识视频组件库。

我特别看重它的几个设计：

**第一，内容导演。**

它不是简单地把一篇文章变成视频，而是有六种讲故事的方法：

- 猜谜概念片
- 两难选择片
- 痛点原理片
- 一条主线片
- 闯关片
- 六面轮转片

**第二，组件库 know\.js。**

它已经有不少专门的表达组件，例如：

- `counter`：数字滚动。
- `spread`：地图扩散。
- `trail`：光轨节点。
- `vquote`：古文逐字显现。
- `choice`：两难选择。
- `cutaway`：结构剖面图。
- `layers`：多层景深视差。
- `plate`、`vclip`：图片和动态视频镜头。

**第三，质量控制。**

它有事实来源核验、旁白对抗审查、1080p60 渲染、OCR 回读、响度检查和画面静止检测。

这些能力非常值得吸收。

但它依赖作者自己整合的 cine 引擎、Claude Code 工作流，以及 MiMo、Renoise 等外部服务。它的强制 BGM 等设计也属于作者自己的项目约束，不能直接套用到 Heron。

### **3. whiteboard-teacher-video：不要作为主引擎，但值得吸收一部分**

[Whiteboard Animation Diy at Joshua Allingham blog](https://images.openai.com/static-rsc-4/hNeHp9VpjEERSf1fGQW8rEQF59k_vMtsUlcJK-BC4PZQ3Lq4_kY8cyyP0Z1dGh1EpWgzsYF8zPOfwiL9FA54uR3km1i2DWr8MXl8bGxDHei-HvKgaYo5ZybkmTDOYIwiYhTZpPVsNYuP0hHYIVPomn_LxWSpqSPJSKLGH6ZCJpE?purpose=inline)

[**查看原仓库**](https://github.com/liangdabiao/whiteboard-teacher-video?utm_source=chatgpt.com)

它的强项是手绘过程：文字逐字写出，图形逐笔生长，讲到哪画到哪。

另外，它还有一个升级项目：

[whiteboard-teacher-video-motion](https://github.com/liangdabiao/whiteboard-teacher-video-motion?utm_source=chatgpt.com)。

这个升级版将原生 SVG 矢量图解与 GIF 局部微动效结合，能保留逐帧确定性和音画同步。

我认为可以把这类能力作为 Heron 的一种视觉表现手法：

例如机械结构逐步形成、人物手绘、流程图展开、产品拆解。

但是，白板风格不是你整个账号的视觉定位。

注意：原项目代码是 MIT，但它使用的 Frametell 引擎是 AGPL-3.0-only。不能把两者的许可证混为一谈。

### **4. vibe-knowledge-video：我最喜欢它的视觉叙事方法**

[Gorden Sun (@Gorden_Sun) on X](https://images.openai.com/static-rsc-4/WNQ2kFd6Xb2gGibntuq6pUWt1LxvPHekNNWhG9EUi_TkWdQ-6NcubmXvfjG7z_K11b0C00ekhabh6fQNgqyceJNAZD7WEtA9zU695ei4B7drAcvR_HBRARFqgz6PmoPel-4G8skhkhXdTXH1XGRr9c9eM0HgaAYSym4l3_Qw2zQ?purpose=inline)

[**查看 GitHub 仓库**](https://github.com/xiazhuo123456789/vibe-knowledge-video?utm_source=chatgpt.com)

它逆向分析了 8 条爆款作品，整理了视觉语法、叙事结构、镜头转场和质量门。

我建议你重点阅读它的四份文件：

| 文件                        | 为什么值得看         |
| ------------------------- | -------------- |
| `visualization-design.md` | 如何把抽象知识变成具体画面  |
| `visual-grammar.md`       | 句子应该对应什么画面动作   |
| `quality-gate.md`         | 如何通过截图发现视频质量问题 |
| `beat-scaffold.md`        | 如何把一条视频拆成逐拍叙事  |

尤其是 `visualization-design.md`。

它有一个很重要的观点：

**画面不是给文案配图，而是要把文案真正演出来。**

例如讲复利：

普通做法：显示一个不断上升的折线图。

更好的做法：一堆硬币逐年增长，每年产生的收益重新投入本金，硬币堆越来越高。

这两种视频的观感和理解效果有很大差异。

我认为这份视觉叙事方法论，应该成为 Heron 的重要参考。

## **二、我另外找到三个更适合 Heron 的仓库**

这三个是我认为真正值得你增加到研究清单的项目。

### **第一名：weilanx-claude-videos**



重点推荐

&#x20;286 Stars · MIT



[GitHub - Anil-matcha/vox-ai-motion-graphics-generator: 🎬 Turn any topic into a finished Vox-style paper-collage explainer / motion graphics video — script, collage keyframes, animation, voice-over, music & captions, all automated. An agent skill for Claude Code, Codex & other coding agents. · GitHub](https://images.openai.com/static-rsc-4/VzbCLR_D1-x0-duHUmFHfUrpM5wtdXUAKPVs_2MjMgk1YCUJltVmfvBKVpP1qWzzR2F_cAbtX8_ysioqSRPUqpcWdFoB2q1BgztTtxc3rx0NUcfBC6JZky2mbnbIO1ybKhxYPeLc7jR_SU9XanIw0eYAeRu1u5ZcZJVxQrQAkLs?purpose=inline)

[GitHub：Azure12355/weilanx-claude-videos](https://github.com/Azure12355/weilanx-claude-videos?utm_source=chatgpt.com)

这是我认为你最应该补充研究的项目。

它有：

- 35 套艺术风格。
- 9 种解说视觉语法。
- 50 多期视频的完整源码示例。
- 可复用组件库。
- 参考视频风格提取能力。
- 字幕驱动动画。
- 音乐节拍同步。
- 程序化音效。
- 视频帧级质量检查。

特别值得关注的是它的 `weilanx-creator-distill-video` Skill。

它可以分析参考视频的镜头、关键帧、颜色、运动和节奏，然后将其提炼成一套新的视觉风格组件。

这与你之前担心的固定模板问题非常相关。

它不是简单地选择一个现成模板，而是可以从参考作品中提取视觉语言，形成新的组件与风格。

**建议重点看：**

- `src/library/`：组件库。
- `styles/`：35 套艺术风格和 9 种解说语法。
- `.claude/skills/weilanx-creator-distill-video/`：风格提取流程。
- `src/episodes/`：完整成片代码。

它使用 Remotion + React，但我不建议因此让 Heron 切换 React。你可以只借鉴它的组件组织、风格提取和动画设计方法。

### **第二名：YouTube-AI-Motion-Graphics**



重点推荐

&#x20;261 Stars · MIT



[Think with Google - YouTube lessons - series 1 :: Behance](https://images.openai.com/static-rsc-4/D4398Pcjz4NIjFW2-zoeuIcKhTso1ZU1YztdBkwN_kLgQ4c7GYoYAX95U3wa5NtBvS9B6-qbDnrM-0qRXIN3u2C1EgYMS8r1OZ3QmjVxPhCZpqWp2B5pvVPfg_aJ6ph2N1OpnttNbnzK8Y5FYxEAIdl2cbXaoKUfjtmJ8UKDIro?purpose=inline)

[Next Gen News 2](https://images.openai.com/static-rsc-4/1taGbIAR2pSca3WYiCajhMFnGGZLNBux6H4Ot6SOVDKxdAbXCDFY3B9zBMtHaIa34m6NvBGGA_BI31lGYOx5xCOPGzXh3CEDudB24xN_pa-szEKfplr5Bxno6pnqs8KUU3FSAuztVnY3vYiflqw-c6MO5udzVUO41Gaulmk9K6U?purpose=inline)

[AI Flow | 3D Explainer Video for AI Tool (5) | Images :: Behance](https://images.openai.com/static-rsc-4/ZBoYxIi612BZnJSUdRwLvpxyPzWQsyh0x_rgfDQx_OihALXizKiAjsnszS-KhATe4GuC3pJAa_vBAxsTRG8BNfCNayKOilvwLCzZbqmZBu1S2aDNU359fHa17rkby0KsRoHhN-ZfM0mAyYQxaih_1Itr8KHvEKm3WFo8CojGGZw?purpose=inline)

12

[GitHub：sirioberati/YouTube-AI-Motion-Graphics](https://github.com/sirioberati/YouTube-AI-Motion-Graphics?utm_source=chatgpt.com)

这个仓库有 74 个动画信息板，包含：

- 柱状图、折线图。
- 时间轴、流程图。
- 韦恩图。
- 热力图。
- 数据和关系可视化。
- 多种动态信息展示。

它的架构是：

`脚本 → Board Brief → Board Catalog → 动画制作 → 字幕时间同步 → 逐帧渲染`

它还有三套 Skill，分别负责画面规划、视觉系统、声音时间同步。

我特别推荐你研究它的 Board Catalog。

**它可以成为 Heron 未来视觉组件库的参考，而不是让 Heron 再增加几十个固定模板。**

例如，Agent 看到一段内容讲的是技术发展历程，它可以选时间轴组件；讲的是两个概念之间的区别，就选对比组件；讲的是复杂系统，就选关系网络。

### **第三名：Vox AI Motion Graphics Generator**

378 Stars · 视觉参考项目

[Why Every Vox Graphic Has a Torn White Border](https://images.openai.com/static-rsc-4/FL6ZwlGvZp6lcVYkUAHTt8vLRMsFkvHerNUBgCaZf6KjzJ8HE9YoUZXmpRPQQ_cjlFNmiEm4eJ4dHATcW2C8qPLh_4k6YfhxuTFYGKzQdchGzr7IsCuU4yIZT3XdHOe9VQfcTdngZlpFRfGYpCLiz4DJOcYy7v2zinA1fqmgZdE?purpose=inline)

[Two Parties - Vox :: Behance](https://images.openai.com/static-rsc-4/fE4p86cvtFBa8IKtcf86LmbF7Gv2A-xOoq1ZHqGgDVNXmlJmSK9AdTloeaZl6tTbUmg2RSlOcMMwk8ufsyvgeLqVXl-6XUOGvTeCiMie4lFduvDHF-ZnZODYlBnDTIgl1dlWmZTMsBAMeYeOGHguTVfdWg5P5_jDcV8qXrhjIIY?purpose=inline)

[Two Parties - Vox :: Behance](https://images.openai.com/static-rsc-4/dr8MD0loLqtFw7J39r9YEmcf2O6n-SJssCXO_vehQ07MNeyeSzKRzdBDsBPhLuAkz0dOCY1dZYxxHx1s1ON7dBZugPZIoX3ugwk3Zb5Vdj--C621yGWnA6HxK5uZy_bdDF1lBf77S71S6hIHCQcKEm5RYslcln6noE40qnqjbXg?purpose=inline)

14

[GitHub：Anil-matcha/vox-ai-motion-graphics-generator](https://github.com/Anil-matcha/vox-ai-motion-graphics-generator?utm_source=chatgpt.com)

它专门做 Vox 风格的纸张拼贴解释视频。

我喜欢它三个地方：

1. 通过视觉锚点保持整条视频风格一致。
2. 先确定旁白时长，再安排镜头节奏。
3. 将每个故事段落独立保存，修改某一段不用重新生成全部视频。

它可以给 Heron 增加一种很有辨识度的拼贴视觉表现方式。

不过，它使用外部图像与视频生成服务，README 给出的制作成本约为每分钟 3–8 美元。所以我建议参考它的视觉方法，不建议直接采用整条生成链路。

## **三、这些项目应该怎么排名？**

这是按 Heron 的实际目标，而不是单纯按 GitHub Stars 排名。

# **1**

**vibe-knowledge-video**

视觉叙事、导演方法论

最高

# **2**

**weilanx-claude-videos**

视觉组件、风格提取、参考视频分析

最高

# **3**

**andyhuo520/vibe-knowledge**

完整知识视频生产流程

最高

# **4**

**vibe-knowledge-film**

Vibe 视频视觉风格、镜头表现

很高

# **5**

**YouTube-AI-Motion-Graphics**

74 个动态视觉组件参考

很高

# **6**

**whiteboard-teacher-video-motion**

局部手绘与微动效

专项

# **7**

**Vox AI Motion Graphics Generator**

纸张拼贴视觉表现

专项

另外还有两个值得了解，但我不建议拿来替代 Heron 核心系统的项目：

- [OpenMontage](https://github.com/OpenMontageInc/OpenMontage?utm_source=chatgpt.com)：83k+ Stars，重点在 Agent 视频生产编排、素材搜索和制作流程。更偏完整视频工作室，不是专门解决 Vibe 视觉质量的问题。
- [Frametell](https://github.com/sitor-ai/frametell?utm_source=chatgpt.com)：值得学习它的导演 Agent、分镜和质量检查设计，但它的引擎采用 AGPL-3.0-only。

## **四、我建议 Heron 最终吸收的架构**

这里是我认为最重要的部分。

你之前一直担心 Heron 会退化成：

`选择模板 → 填充数据 → 渲染视频`

我赞同你的担忧。

我认为 Heron 应该建立三层视觉能力。

第一层

**Visual Language · 视觉语言**

定义整条视频的美术方向：色彩、字体、材质、光影、颗粒、构图习惯、声音风格。

参考：weilanx、vibe-knowledge-film。

第二层

**Visual Components & Recipes · 视觉组件与表现方法**

不只是图表组件，还要有视觉表现方式。

例如：

- 物体逐渐形成。
- 空间推拉和尺度穿越。
- 粒子聚合、扩散与模拟。
- 纸张拼贴、剪报、印章。
- 计数、对比、关系网络。
- 手绘、机械拆解、时间轴演化。

参考：andyhuo520、YouTube-AI-Motion-Graphics。

第三层

**Director · Agent 导演**

根据内容自己决定：

- 这段知识应该怎么讲？
- 观众应该看到什么？
- 使用什么视觉表达？
- 如何安排镜头和节奏？
- 哪些地方需要特别精致的主视觉？
- 如何让结尾回到开头？

参考：xiazhuo123456789、weilanx。

举个例子：Heron 要制作一期《为什么钱会越来越不值钱》。

Agent 不应该先决定使用经济学模板。

它应该先分析内容：

- 第一幕：观众日常购物时的价格变化。
- 第二幕：相同的钱，购买力如何发生变化。
- 第三幕：用一组具体数字表现货币购买力的变化。
- 第四幕：通过时间轴展示历史变化。
- 第五幕：回到观众今天的生活。

随后，导演再选择合适的视觉组件、镜头运动和表现方法。

这样才是真正由内容驱动视觉。

### **对现有 Heron 架构，我建议明确三个原则**

**1. 一个视频保持一个视觉世界，但镜头可以自由变化。**

不要把 44 套风格混在同一条视频里。保持统一的视觉识别，同时允许每一镜拥有不同构图和动效。

**2. 组件库不等于模板库。**

组件是表达能力，Recipe 是经过验证的镜头表现方法。Agent 应该能根据内容选择和组合它们，而不是简单选择一个固定成片模板。

**3. 保留 Java + LLM 的主导权。**

上述 Skill 不需要全部安装进 Heron，也不意味着要引入 AgentScope。它们的视觉方法、分镜规则、质量标准完全可以转化成 Heron 自己的知识资产、结构化决策和 Java 渲染任务。渲染层继续使用现有技术体系即可。

这与你之前确定的单视频视觉锚点方案是兼容的：保持一个 composition 的视觉上下文，由它约束整条视频的表现，而不是让不同模板互相拼接。

## **五、最终建议：不要一次性引入所有项目**

我会安排这样的研究顺序：

| 优先级 | 项目                              | 重点研究内容               |
| --- | ------------------------------- | -------------------- |
| P0  | vibe-knowledge-video            | 四份视觉方法论              |
| P0  | weilanx-claude-videos           | 风格蒸馏、组件库、完整案例        |
| P0  | andyhuo520/vibe-knowledge       | know\.js、6 种叙事方法、质量门 |
| P1  | YouTube-AI-Motion-Graphics      | 74 个动态 Board         |
| P1  | vibe-knowledge-film             | 两种视觉世界、Canvas 引擎     |
| P2  | whiteboard-teacher-video-motion | 手绘与微动效               |

**我最想强调的一点：**

Heron 不能只做到「AI 能生成知识视频」，而是要做到「AI 知道什么样的画面才能把这个知识讲好」。

这是两个完全不同的产品层次。

我建议下一步直接以这六个 P0/P1 项目为参考，为 Heron 制定一份 **Vibe 大赏视觉生产系统升级方案**，明确哪些设计思想值得吸收、哪些代码可以复用、哪些应该自主实现，以及如何在不破坏现有 Java + HyperFrames 架构的前提下实现。