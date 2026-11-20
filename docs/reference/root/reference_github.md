有，而且我筛选了一批。我觉得 Heron 现在最值得补充的，不是再找一个 AI 视频生成模型，而是补齐 **选题素材、数据知识、爆款视频分析、图文设计、视频自动生产** 这几个环节。

我按照你之前确定的方向来筛选：数据看板内容是主线，新闻只是补充；生产尽量自动化；没有本地 GPU，也不希望增加太多成本。

下面这些是截至 2026 年 11 月 20 日值得关注的仓库。

# **一、最值得研究的 7 个 GitHub 仓库**

## **1. Awesome Public Datasets：内容选题的数据宝库**

[GitHub - awesomedata/awesome-public-datasets: A topic-centric list of HQ open datasets. · GitHub](https://images.openai.com/static-rsc-4/LaNhb2LOAy2cpqIxjmzWTzP5b-cW1eal1jE_6VjvwQIf6rDi3LSP0PJ88ynNSZbuVZkoV5xxtUtdHJqrDVB48W7tPObPoGYU4EycBseba7NQ73oUEDWQ11Bhr4__YESU5ecdqFTAl-y-IlXDk_OTnYOLPrQuxtWTwqkV8lko8qM?purpose=inline)

**awesome-public-datasets**

# **79K+**

awesomedata · MIT

[GitHub 仓库](https://github.com/awesomedata/awesome-public-datasets?utm_source=chatgpt.com)

我非常推荐

它和你喜欢的 HowToLiveBetter 有一点相似：都是整理好的知识资源，只不过这个项目侧重公开数据集。

它按照主题整理大量数据资源，例如：

- 人口、经济、金融
- 医疗、健康、生物
- 教育、科学
- 交通、能源、环境
- 社会、文化

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1

**对 Heron 的价值：**

例如，你可以让 AI 从公开数据中挖掘：

- 中国人口结构正在发生什么变化？
- 全球哪些国家的生活成本最高？
- 年轻人的就业和收入有什么变化？
- 全球能源消费发生了什么变化？

这些内容可以不依赖当天新闻，而是形成长期的、可持续更新的数据知识视频。

注意：它是数据集目录，不是统一的数据 API；具体数据的授权、更新时间和统计口径还要逐个核验。

## **2. Diagram Design：自动制作专业图文和信息图**

[Cathrynlavery Diagram Design | MAGI//ARCHIVE](https://images.openai.com/static-rsc-4/OwpUHSZTJmAZa5y_PpLXAFzkSwygw21QDIqNJNsQ4z2-3r11pkX4VpB9FYFXPRyuY6pxQZDAQPAPQl44dPCxVkQoLfOikEvaI3vvsbPADBcsyElXO18JafXGxwkqhA5FvVJ42EA0KRhtFJ_GdfRxp1Czr504rroN0FBx3loSMqU?purpose=inline)

**diagram-design**

# **68K+**

cathrynlavery · MIT

[GitHub 仓库](https://github.com/cathrynlavery/diagram-design?utm_source=chatgpt.com)

[ECharts Gallery](https://images.openai.com/static-rsc-4/ERxG-V5M-y1Xl29DBvxQxyHxl8q8V5k0QKd0AHV96nq2Me_kUwbMzLoqXnGCwmf3l5yHTXbfqzDumrlKAe9tCeoG_0xRnXr-GxNk1XH7WeBG_c4jcM84mHjJoOVLjo4cPQa3moCyQMEk8xgxpFMj2InCAuO3lUqNKvtepBGtKro?purpose=inline)

[The Infographic History of the World :: Behance](https://images.openai.com/static-rsc-4/zaWEIFudq7ZACHw8nqEoFEPC0g1PyomeA90Ihf_MEzs3Tfjcuz-HWN-3AuNzqlUWuEDBDD4e8AdSrSH5zAiJ3ERt9tqWHgxJpItfClVzPjjhNKv7VTz-K7iJ5aRGLtGlVl9ERgzv1dA6kBTyPYf7D76qk8GNnSlk3cIqNNjl5Vs?purpose=inline)

[They Ask, You Answer: The Big 5 explained](https://images.openai.com/static-rsc-4/FVDuYCHbAd3xSr_8VaTRD7Dk0tYItkyttLxqvIg_OTTnXAGUTglKZ2CeKQ95FJdRczFkVYIAGgnGHkfhdwWzHeAytYIKLj2sasWb_R8lLv2_uQdtdlQgN2Xo0Wy8gonb6sPn6dH-kC42DRwD4atbPtLh7mly3uJ_vQ0jv1BqEtg?purpose=inline)

12

这是一个可以安装到 Codex 的 Agent Skill。

它可以根据文字描述生成 51 种类型的设计图，输出独立 HTML + SVG，并支持 PNG、SVG 等格式。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



对 Heron 尤其有用：

- 数据排名图
- 数据对比图
- 时间线
- 桑基图
- 用户画像
- 流程图
- 科普信息图

例如：

```
根据这份中国人口数据，制作一张专业的信息图。

主题：中国人口年龄结构变化。

要求：
1. 采用专业新闻媒体的视觉风格。
2. 重点突出人口结构变化。
3. 使用清晰的数据对比和图表。
4. 适合抖音竖屏图文发布。
5. 所有数据必须保留来源和统计年份。
```

**我的判断：这是 Heron 图文线非常值得引入的工具。**

## **3. PPT Master：把文章直接变成图文视频素材**

[ppt-master/skills/ppt-master/references/canvas-formats.md at main · hugohe3/ppt-master · GitHub](https://images.openai.com/static-rsc-4/LNOodoKCVgkZP-eUplwafrqfy2yGOlb4RHllyiXvVD1SOEg-lrdHZNPJu_wtBJ9jPRtS_cT25g03GSmaqw2j_TFu3kFzA6sbQfGgWnjw6c0yNrLezCDDB7DtDgknY1wPARYunsbiToH2k27HldCkmJbrr2Wg9Jhq5sj7_qWUgCM?purpose=inline)

**ppt-master**

# **78K+**

hugohe3 · MIT

[GitHub 仓库](https://github.com/hugohe3/ppt-master?utm_source=chatgpt.com)

[这个 GitHub 上 1.6 万人点赞 AI PPT 工具，生成的 PPT 能编辑。 - Py学习](https://images.openai.com/static-rsc-4/EgtfRPErEyccC27GlpezXZgFSEA1Ul-3RcX4MT9mi7Q7ot7gUOZvWE-b-dnjn-PH4MREKEwLfHjDU0l5I2ubyk71WwxBYvcSvWO1rWFsTQG4PFN8wzWKKNe6E8iix7t0GALiAMtkXBOwFehnubfmcU_THMRnzjVJo7-xhUFTlZg?purpose=inline)

它不是简单地将网页截图变成 PPT，而是生成原生 PowerPoint：

- 原生图形和表格
- 数据图表
- 页面转场和动画
- 讲稿和语音旁白
- 支持自己的 PPT 模板

还可以通过 PowerPoint 将带有旁白的幻灯片导出为 MP4。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



**对 Heron 的价值：**

一篇数据分析文章，可以先生成 8～12 张精美页面，再把它们转成图文内容或者讲解视频。

对于你这种没有 GPU 的场景，尤其值得研究。

不过，自动导出带旁白 MP4 的链路依赖 PowerPoint，不一定适合作为 Linux 后端的批量渲染核心。

## **4. rea-video：研究别人为什么能做出爆款视频**

[What is Video Reverse Prompt | AIStage](https://images.openai.com/static-rsc-4/mSJCtSxg8e7tr21rKSUUv91UYnTUx95WaLbVCyBxDAlWU0kYMAAgaeYWAI0pIVrDsZy4aPLBCTPRfQ2HmziZ1rT9bPz5PZ3ECGuBf9168EzGridKYhR4xFFkXzRs5ySd9izibHtPQ_Z4b6UHWrCEyGPDki3wEmCl1hCyBkGlIIw?purpose=inline)

**rea-video**

# **11K+**

JuneYaooo · Apache-2.0

[GitHub 仓库](https://github.com/JuneYaooo/rea-video?utm_source=chatgpt.com)

这个项目非常有意思。

[视频转提示词生成器 - 从视频提取 AI 提示词 | Video to Prompt](https://images.openai.com/static-rsc-4/h_r_BTgPaMsDqNNKKbu-6TTaYmBpmYA8pjmu08hWLsN3M60SESzU28T0pQRIXjKVz0Q4IuanR-Qi5A_yC4M1SYSnAPepyx9Mz3nQwQ_Aw15XhYbkdS_GM_-1Sy0auIWbLZ9y-N_wiovwKRnNs6e_ZC6v_f3VXCN5JqSVfo4dCjM?purpose=inline)

[video2prompt.io](https://video2prompt.io/zh?utm_source=chatgpt.com)

你给它一个参考视频，它可以分析：

- 每个镜头展示了什么。
- 镜头持续多长时间。
- 字幕的位置、颜色和动画。
- 画面切换节奏。
- 音乐和音效。
- 如何用代码或者素材复刻。

最后可以输出 HTML/PDF 分析报告，以及供 AI 阅读的 `VIDEO.md`。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



**对 Heron 的价值：**

建立一个爆款视频分析库。

比如收集 100 个财经数据类、知识科普类、科技解读类的优秀短视频，让 AI 分析它们的镜头结构，归纳出可复用的制作方法。

它不负责判断视频为什么爆火，但可以帮助你研究视频的视觉表达。

## **5. OpenMotion：AI 自动制作图文和视频**

[【开源】Cutia: AI Native的在线视频编辑器，使用Agent一键成片的同时还给与足够的控制权 - 开发调优 - LINUX DO](https://images.openai.com/static-rsc-4/vmBl6UiBXY2LrMHbNvdRN6pRn0KDzkKpwzHunNE22W7CYG2XvKBjtwsNTlp80ZyEk-UC68JHulKv3CZwfCOe8xAlZ9WPYlZ8MqmPTPBkDp7ry3FFmfyZxYwz1WhrMl_K5MOAsEaZ-LF4N4OIB750_E6Taejqbhk_gpbtS_uEpPM?purpose=inline)

**OpenMotion**

# **13K+**

nexu-io · Apache-2.0

[GitHub 仓库](https://github.com/nexu-io/openmotion?utm_source=chatgpt.com)

[Gemini Studio: The Agentic Video Engine | Devpost](https://images.openai.com/static-rsc-4/t37MR8yRFFWDOyKLFsAQpU9ADtMknapw5iLHoZcBuNMRQ3ZexV1EQ9-FoDoyZQFGrxD4Fx0SkdfEQ_W8N6qU9ZCpO2QyxxRWnWd5oVWnD0Dltnm3wDGqA0n27XUPEpdBj970EdQ6xs5nQ_zyKR7kQT_zTImK5ITWAY2FcB2wPjA?purpose=inline)

这个项目可以让 Codex 参与完整的视频制作。

特点：

- HTML、CSS、JavaScript 描述视频画面。
- 支持图片、视频、音乐、旁白。
- 具有 300 多份 Motion.md 动效指南。
- 可以参考现有视频复刻动效。
- 有可视化 Studio。
- 支持导出适合 YouTube Shorts、TikTok 等平台的 MP4。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1

这点很符合你之前的要求：没有 GPU，也可以让文本模型编写画面、动画和视频结构，再通过浏览器和 FFmpeg 渲染。

但它和 HyperFrames 有重叠。我建议先做对比实验，不要两个渲染体系都整合进 Heron。

另外，安装 Skill 本身还不够，需要让 Agent 完成 Studio 和 MCP 的本地安装。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub



## **6. TrendRadar：自动发现值得制作的话题**

[奶牛叔 (@WWTLitee) on X](https://images.openai.com/static-rsc-4/HA5kiF-gtGt8gTqdMOkfprlXyfN78BFfXIZtfqhrjEgDLP-yJQFfH5wgj6E3RqpFOwwGqa-RQIOzaBA6SrzlhWygs7LqydCBSHVo6YwM7qTVv1_c6iYeShgZRAxVddKwDOMLeUJaF83N7gp4to4-A5bLhIinEtcDkKJzaNOKwEk?purpose=inline)

**TrendRadar**

# **63K+**

sansan0 · GPL-3.0

[GitHub 仓库](https://github.com/sansan0/TrendRadar?utm_source=chatgpt.com)

它聚合多平台热榜、RSS，提供：

- 关键词过滤
- AI 热点分析
- 话题监控
- 定时推送
- MCP 对话分析

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1

对 Heron 的价值是补充选题发现，而不是取代你原来的数据看板。

我不会让 Heron 过分依赖它，因为你已经明确希望新闻占比低一些。它更适合做额外的热点信号源。

注意它是 GPL-3.0 许可，若计划把代码整合进商业闭源产品，需要先评估许可证义务。

## **7. Lemo-Opuscar：99 种纯代码影片风格**

[Higgsfield AI - Análisis de Datos Avanzado e Insights Predictivos | Creati.ai](https://images.openai.com/static-rsc-4/Tn0v10xNVJRQ4MKLzm6yModuNZIeCGERgdQJOOCzxmVyyoZUD_vgnOLzicutjY0AMKET4bWx_vsjpAlMpmBFSVKF2s4VkB8x-7-yGQofUQc6jDPmIwSonsduPlfaAJZNwkCSS69rGUinU4g68WbwAbGIL6V8x4oNtyLisrZV8zI?purpose=inline)

**lemo-opuscar**

# **18K+**

lemomo-ai

[GitHub 仓库](https://github.com/lemomo-ai/lemo-opuscar?utm_source=chatgpt.com)

[Le corset - Louis Clichy - critique](https://images.openai.com/static-rsc-4/5Dlc1pIMjRKQAdZfdGLY4uZ6Fujytd_E_sZioFiG3VFrcYyJYPQjEXLklENJU0hUkJeMLRVKstKie3G7QTX4x08GAiffBVYr_I0KsXiqtgdlTd4-Ua222nQnDlQPRajTMPGsf5gl98s2tfn8EmOdJtOHXwDnksSH3Td4pBjJm5I?purpose=inline)

[Kinetic Typography Video for Fintech & AI Finance | FEVR](https://images.openai.com/static-rsc-4/s6jM_XLqLUbynV_eNbH80HJHFrmfxoQEJXDucaBKhmqzeULuUBW6FhoapZu7zaQBRwLRijzIMO9W677WC8Gm8C8jddZrd7ZMLQJmtOrJaM3D8VN4ycqHg7SXFP2XK9xAHmI4jGERWYOmZAN6BBXZir8BM-6net9JiekbuAtxAcc?purpose=inline)

[This Week In Documentary - DOCOFILM](https://images.openai.com/static-rsc-4/EGdP78N7swqfrYtrYy3mbdwzNiI-gkte0DAz12JB2y-kH7K03dMaclgTNqr5wGeGpq0Og9AHgPMlS-0w3pfrjnBf652lquqXNS6YS6o3K2uG_p2zkpDyY_E7-5gU1ikbd9Ed9yhVLjcrC5uC2d-DPW0l03aZqKt6rUpJpW34HMQ?purpose=inline)

12

这个项目收集了 99 种影片风格，例如：

- 水彩动画
- 白板讲解
- 新闻纪录片
- 信息图动画
- 电影胶片
- 动态字体
- 数据地图

它的特色是完全可以通过代码绘制、合成影片，不依赖视频生成模型。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



不过有个限制：作者主要使用 Claude Opus 5.5 调校这些风格，README 明确提示其他模型不一定能很好复现；生成一部影片可能需要 30～60 分钟和较多 Token。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



所以我建议先收藏和研究，不作为你当前的核心生产引擎。

# **二、这些仓库应该怎么组合到 Heron？**

我不建议你把它们全部集成进去。应该按照生产环节选择工具。

## **Heron 图文视频生产链**

我建议的组合方案，不代表这些仓库原生互相集成。

01 · 内容来源

**Awesome Public Datasets + HowToLiveBetter**

发现长期值得讨论的话题，找到数据、知识和事实依据。

02 · 选题筛选

**Heron 数据看板 + TrendRadar**

根据真实数据、用户关注度和内容价值确定选题，热点新闻作为补充。

03 · 参考分析

**rea-video**

研究同领域优秀视频，建立镜头、字幕和节奏模板库。

04 · 内容生成

**Heron 的大模型内容引擎**

生成标题、脚本、数据解读、分镜和图文卡片文案。

**图文生产**

Diagram Design

PPT Master

**视频生产**

HyperFrames

或 OpenMotion

06 · 输出

**抖音图文 / 视频**

生成封面、图文卡片、字幕、旁白和 MP4，进入人工审核或自动发布。

# **三、我认为 Heron 还有一个值得开发的能力**

叫做：**数据驱动的图文内容引擎（Data Storytelling Engine）**。

不要只让 AI 根据一组数据生成一段视频，而是让 AI 从数据中发现故事。

例如：

[Atlas of the Invisible by James Cheshire, Oliver Uberti | HodgesFiggis](https://images.openai.com/static-rsc-4/XLO6orVvRaPV77p-ovR8b_8Kmlaf1JWTWfgyUAfwhQ84cE5o27ni4W1IxRP0wyeLmnuUUHS3qINnh7Qrp2z9lZ1OrGk6PYcafqQhZ3sSSrrRuYYJtTjd7yXolu8BtEHIohzIr7u1c-_DPlbdNFyDbSb613y-_DbyHJn-HVZ5f7w?purpose=inline)

**数据故事：人口结构变化**

图文：5 张卡片，展示年龄结构、变化趋势、区域对比。

视频：60 秒数据变化解读。

[Cost of living throughout the Gulf - graphic | The National](https://images.openai.com/static-rsc-4/ss-1gz8C5mFjJclrEFVwxd9ihLl_kBs4t_MjggPXE8EkyWnIUWNyzt0fuD4Q6jsJZKm_conFII_Xe471mkgx1Qe4mz5U0gTDE3dQGDCdOor0Zp-oKm7mjL3OWuEip1AEubhWmCBYyWpnQNeeYmgzdg-BIH1TH3lkEM-_LFWREzg?purpose=inline)

**数据故事：城市生活成本**

图文：不同城市生活成本排名、房租、收入对比。

视频：哪些城市的生活压力更大？

[The other day I shared the 2026 (B2B) forecast for the technology industry at $6.1 trillion at a 10.2% growth year over year.  Let's take it up one level to the world economy (our customers) who are… | Jay McBain](https://images.openai.com/static-rsc-4/rFwVesqg1iLmAQgclCukeCU5e1vZGXhvYkPOG3NpARWYUjr5VkR_XBn6FDnsLpjKG2ohNjSFwV-XVrXjPSb0D18FL9m70Q8LdrcE0c7NQ89w29Q_s9_ZmOeD8ZV2rcU1vnEd_CbUYMEAQcFXAp0JkYG1TwxiSfNpiPC0o3Jl08Q?purpose=inline)

**数据故事：科技产业变化**

图文：行业规模、企业变化、市场份额。

视频：一个行业正在发生什么变化？

这种方式有一个重要好处：同一份数据可以派生出不同内容形态，而不是每次生产视频都重新从头开始。

# **四、最终我建议你先研究哪几个？**

我替你缩小范围，不用七个都装。

| 优先级 | 仓库                      | 在 Heron 中的角色 |
| --- | ----------------------- | ------------ |
| P0  | Awesome Public Datasets | 扩展内容选题和数据来源  |
| P0  | Diagram Design          | 图文卡片、数据可视化   |
| P0  | rea-video               | 爆款视频分析与模板研究  |
| P1  | PPT Master              | 幻灯片式图文视频     |
| P1  | OpenMotion              | 视频制作和动效生产    |
| P2  | TrendRadar              | 额外热点发现       |
| P2  | Lemo-Opuscar            | 探索不同影片风格     |

另外，`MediaCrawler` 虽然有 71K+ Stars，支持抖音、小红书、B 站等平台的数据采集，但它自己的 README 明确声明仅供学习研究、禁止商业用途。不要直接把它作为 Heron 商业生产环境的数据采集依赖。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

知乎问答文章｜评论爬虫 · GitHub+1



**我的核心建议是：暂时不要再给 Heron 增加新的 Agent 框架，也不要增加一堆视频渲染引擎。优先建设内容知识库、数据故事发现、图文模板库、爆款视频结构库。**

这几个能力才会真正决定 Heron 能不能持续生产有人愿意看的内容。