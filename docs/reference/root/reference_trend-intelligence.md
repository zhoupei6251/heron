可以。这次对话的需求已经比较明确，我建议把它整理成 Heron 的一份**正式产品需求与实施计划（PRD）**，而不是直接列几个新页面或新增类。

这次的核心目标是让 Heron 从“根据现有热点、数据和素材生产视频”，进一步升级为：

> **Agent 主动发现热门视频 → 自动筛选趋势 → 获取符合授权条件的素材 → 使用 rea-video 分析制作方式 → 提炼视觉策略 → 结合 Heron 自己的内容重新生产视频 → 质检、发布、反馈学习。**

其中，你希望尽量自动化，发现热门视频这件事不需要你手动导入参考视频。

# **Heron Trend Intelligence — 需求计划**

产品需求文档 · PRD + 技术实施路线 · 2026-11-23

## **一、产品定位**

### **1.1 产品名称**

**Trend Intelligence — 趋势情报与视频再创作系统**

不是简单的视频下载器，也不是普通的视频分析工具。它是 Heron 的一个主动式 Agent 系统，负责观察外部短视频生态，发现值得研究的内容，并将视频制作经验转化为 Heron 的生产能力。

### **1.2 核心目标**

- 不需要人工导入参考视频，Agent 自主发现热门视频。
- 不只看视频播放量，而是识别具有潜在传播价值的作品。
- 自动分析优秀视频的创作方法。
- 将参考视频拆解结果转换为 Heron 能使用的视觉策略。
- 在授权允许的情况下获取原视频或素材，进行素材级或工程级再创作。
- 结合 Heron 现有生产引擎重新制作、质检、发布。
- 通过自己发布的视频数据，反向验证哪些学到的策略真正有效。

### **1.3 不做什么**

- 不新建一套与 Heron 原有生产引擎平行的视频渲染系统。
- 不让 Agent 自由生成并执行 HTML、JavaScript、Three.js、Blender 脚本。
- 不将热门视频直接等同于 Heron 的生产内容。
- 不因为一条视频爆火，就自动把其策略升级为全局生产策略。
- 不绕过平台访问限制、下载限制、版权保护或授权要求。
- 不把未经授权的原视频仅替换少量元素后冒充原创发布。

## **二、整体架构规划**

[Blog — Video infrastructure for AI agents — VideoDB](https://images.openai.com/static-rsc-4/RT3esmS_bf3aEtK3-5INTbFUR_ZV7bSdGsVob-Lf2WfqtOD-YUwwVMxEcQTjkadEImnNsQrZAVpHPGF8jjHgeHOgNu9qAxsKEgvLzm7LNtlrDZIvdgHf6I9xxp9Eml79RyQwW_ifQy0W2j3Sx8Ov5iXmyzEaCwDHdvPXIU6HpW4?purpose=inline)

[Building a Video Analysis Pipeline That Actually Works | by Sridhar | Jan, 2026 | Medium](https://images.openai.com/static-rsc-4/yPRLAFgH7H27GT7T_UfJyDBsclu0sA3ER5u-NhUv6upf9HmY3xzJtHsN-NVDtkEYredl97B0Bdpu1ezAkhJ4Hkd7JidXJVCyOVowYETMBGlHHm6_VgvpjWGQYEmAPcIDq4qmcGMAcWAOnLd2SKK_wV2y_M9lcGNyf3GnJiKJw6M?purpose=inline)

[视频转提示词 AI - 逐镜头拆解与分镜提示词 | PixMind](https://images.openai.com/static-rsc-4/pF19dRsruPIOo-F-fnSFCtTGc_NhhcN71hgQwWwB5SSbx6XuizqERNk6xTgM56wTNYmbYzqlY0nsVwYsn9J3a5CkGiefYBFc_HQiHPazy_PhrKfmp6tmAgvayLd0l4iTGwnLSKF18bm0pQTi1C474CrwFQONS4l7ciq4N7g8JqM?purpose=inline)

12

系统分为 6 个业务层，建议独立治理、相互连接。

| 层级 | 模块                           | 核心职责              |
| -- | ---------------------------- | ----------------- |
| L1 | Trend Discovery              | 主动发现热门视频和趋势       |
| L2 | Trend Intelligence           | 热度、趋势、内容价值分析      |
| L3 | Reference Acquisition        | 来源获取、授权检查、素材管理    |
| L4 | Video Analysis               | rea-video 视频拆解和分析 |
| L5 | Visual Strategy Intelligence | 提炼、验证、管理视觉配方      |
| L6 | Heron Production             | 生成新内容、渲染、质检、发布    |

闭环：发布表现数据回流 → 策略验证 → 视觉策略迭代 → 新作品生产。

## **三、功能需求**

### **P0 — 热门视频自动发现（Trend Discovery）**

核心基础能力

这是整个系统的起点，完全不依赖人工导入视频。

#### **3.1 数据来源**

支持多个发现渠道，后续逐步扩展。

| 来源                      | 发现方式        | 优先级 |
| ----------------------- | ----------- | --- |
| 抖音热点榜                   | 热点词、趋势榜、热度  | P0  |
| 抖音热门视频                  | 根据热点关键词搜索视频 | P0  |
| YouTube Trending / 热门内容 | API 或合法数据源  | P1  |
| TikTok Trends           | 趋势发现与公开信息   | P1  |
| 其他平台                    | 插件化接入       | P2  |

数据源必须采用合规、稳定的接入方式。某平台如果不允许下载，就仅保留它允许使用的发现信息，不强行获取视频文件。

#### **3.2 定时任务**

支持后台自动扫描，不需要人工触发。

建议初始调度策略：

| 任务   | 频率   | 说明          |
| ---- | ---- | ----------- |
| 热点扫描 | 每2小时 | 获取当前热点及关键词  |
| 视频发现 | 每2小时 | 根据热点寻找视频    |
| 趋势复查 | 每4小时 | 观察候选视频变化    |
| 深度分析 | 每日   | 对高价值候选执行分析  |
| 策略评估 | 每周   | 汇总策略效果和晋级候选 |

频率全部可配置，支持任务暂停、恢复、立即执行。

#### **3.3 候选视频信息**

每条视频候选至少记录：

- 平台、平台视频ID、原始链接。
- 标题、描述、作者公开信息。
- 发布时间、发现时间。
- 封面、视频时长、分辨率（若可获得）。
- 点赞、评论、分享、播放量（若平台提供）。
- 热点关联、主题、分类。
- 数据抓取时间、数据来源和完整性。
- 版权、授权及下载状态。

候选视频不能重复入库。相同平台 ID 为主去重，同时对可获取的素材指纹进行辅助去重。

### **P0 — 热门视频智能筛选（Trend Scoring）**

不能只按播放量排序。Agent 应判断这个视频有没有研究价值。

#### **3.4 趋势评分**

建议建立 100 分制的趋势评分体系：

| 指标       | 权重 | 说明               |
| -------- | -- | ---------------- |
| 热度表现     | 25 | 点赞、评论、分享、播放等     |
| 增长趋势     | 25 | 近期热度变化、增长速度      |
| 内容吸引力    | 20 | Hook、叙事、情绪、悬念    |
| 视觉创新     | 15 | 画面、动画、剪辑、表现形式    |
| Heron适配度 | 15 | 是否适合数据线、知识线、趋势线等 |

0%7%14%21%28%热度表现增长趋势内容吸引力视觉创新Heron适配度

以上权重是初始产品设计建议，并非已验证的最佳模型；后续应使用实际效果校准。

#### **3.5 筛选规则**

建议初始分级：

| 分数     | 等级 | 后续动作     |
| ------ | -- | -------- |
| 85–100 | S  | 优先进入深度分析 |
| 70–84  | A  | 进入分析候选队列 |
| 55–69  | B  | 观察热度变化   |
| 0–54   | C  | 暂不处理     |

额外过滤：

- 视频过旧，趋势已衰退。
- 低质量内容、垃圾内容。
- 重复内容。
- 与 Heron 目标内容方向完全无关。
- 无法获取足够信息进行分析。
- 来源限制不允许进一步获取素材。

### **P0 — 自动获取视频及素材治理（Reference Acquisition）**

这部分对应你最初希望的“Agent 发现热门视频后，自己下载下来”。

这里保留自动获取的能力，但将下载许可和再创作许可分开判断。热门视频即使可观看，也不意味着可以下载、剪辑、改编或商业发布。

#### **3.6 自动获取能力**

当候选进入高价值队列后，Agent 自动判断来源支持的操作：

- 允许下载的自有视频、授权素材、可改编素材：自动获取。
- 允许分析但不允许下载的内容：通过合法可用的方式获取分析信息，禁止保存原片。
- 授权状态不明确：进入待确认状态。
- 明确禁止下载或改编：拒绝获取，保留发现记录。

#### **3.7 获取任务状态**

DISCOVERED

CHECKING

RESTRICTED

PENDING_AUTH

ACQUIRING

ACQUIRED

FAILED

需要支持：

- 下载任务独立队列。
- 重试与失败原因。
- 文件完整性检查。
- 下载进度。
- 下载超时。
- 任务取消。
- 并发限制。
- 文件大小限制。
- 存储容量限制。
- 来源限制和访问规则。

#### **3.8 资产存储**

建议：

- 原始素材：MinIO 独立 bucket 或 prefix。
- 视频元信息：MySQL。
- 视频分析报告：MinIO。
- 视频预览：低码率代理文件。
- 缩略图：独立存储。
- 原始素材 hash：用于完整性验证和去重。
- 授权凭证：保存来源、许可类型、授权范围、有效期和记录。

不将大量视频二进制文件写入 MySQL。

### **P0 — rea-video 自动分析引擎（Reference Analyzer）**

核心AI能力

这是整个项目中最关键的 AI 分析模块。

#### **3.9 自动分析内容**

| 分析维度    | 具体内容              |
| ------- | ----------------- |
| 基础信息    | 时长、分辨率、帧率、画幅      |
| 镜头拆分    | 场景切换、镜头开始结束       |
| 画面内容    | 人物、场景、物体、背景       |
| 视频节奏    | 镜头时长、变化密度、快慢节奏    |
| 开场 Hook | 开头如何吸引观众          |
| 字幕分析    | 文案、位置、大小、出现时机     |
| 动画分析    | 运动方向、速度、动画类型      |
| 转场分析    | 切换方式、视觉衔接         |
| 音频分析    | 配音、音乐、音效、情绪       |
| 视觉风格    | 纪录片、3D、信息图、实拍、漫画等 |
| 叙事结构    | Hook、铺垫、冲突、解释、结尾  |
| 制作方式    | 实拍、素材、AI生成、程序动画等  |

#### **3.10 分析产物**

每条视频应形成标准化分析数据，而不是只有一份 Markdown 报告。

```
{
  "referenceId": "ref_123",
  "analysisVersion": "1.0",
  "duration": 42,
  "style": "data_visualization",
  "narrative": "hook_explanation_reveal",
  "hook": {
    "duration": 3,
    "type": "visual_shock"
  },
  "editing": {
    "averageShotDuration": 2.8,
    "density": "high"
  },
  "visualElements": [
    "kinetic_typography",
    "map_animation",
    "data_counter",
    "camera_zoom"
  ],
  "scenes": [],
  "recipeCandidates": []
}
```

示例结构。实际字段应由 rea-video 能力、Heron 现有契约及测试决定，不应提前固化过多无用字段。

分析结果应包括：

- `VIDEO.md`：AI易理解的完整拆解报告。
- `report.html`：可视化审片报告。
- `analysis.json`：结构化数据，供后端使用。
- 镜头时间轴。
- 镜头截图/缩略图。
- 可复用的视觉策略候选。
- 制作难度与工具建议。

### **P1 — 视觉策略提取（Visual Recipe Intelligence）**

rea-video 分析出一个优秀视频后，Heron 不应该直接把报告交给生成模型，而是需要进一步提炼成自己的“视觉配方”。

#### **3.11 配方提取**

从多个优秀视频中，Agent 归纳出具有共性的制作方法。

例如：

[Vertical Stat Typography Pack | After Effects Promo Titles | Social Media Reels & Shorts Motion Graphics - Etsy](https://images.openai.com/static-rsc-4/aH04tsJih-tmePAMTYYK0J4o1RqbpZaJ_gbEo9q21lIsZ_sm0GjZyC5ndVv1wwbAFxfRNCXCGxTPbb7L-j-3vFcGqQkmFo0OqeV8Xs0hbpHazDcsWIz8abnV9edUlLydfOILzMyj2_0QBSfcjy-ORHLMjSAadUtiJ8TfYP8GChY?purpose=inline)

**数字冲击型开场**

3秒内呈现核心数字，快速建立冲击力

[Capital Flow | Times Commerce](https://images.openai.com/static-rsc-4/B9KmL-Hz2NUZEApel79qCHqiTSixq19FCWY75UmVHW8LdmedfEGueo63Lo7ajhw-sJXlayWyvmu27gOPPiVZuaPBIpeZRjrDiOnldh9C7bZCiMi2-NDMzGq8SjsdZuXuHbWWTZWUVbyTzTfA3dKNxGt5W5VCV9iYEUNEGFmF89I?purpose=inline)

**地图动态叙事**

利用地图、路径、区域变化呈现空间关系

[Predict Protocol (@PredictFDN) on X](https://images.openai.com/static-rsc-4/_MBwB8jt-KB51xQ7nDErkEB1Z3u3jboBI1tjpUHT_47apPdeX-IoeRwxPhNoT6YokC0Riiwb7aNKbp8xjOhg9RHOLZSMhFIhg3lxnzbSwp40tzPwlf5ZHAWo1T3ua6IgRn2vAjauYBpsWKwL68GMKcQWekBIOXOw18tQe2Mfnqc?purpose=inline)

**对比揭示型**

利用前后、大小、趋势对比制造认知反差

[The Art of Work (Short 2022) - IMDb](https://images.openai.com/static-rsc-4/gSIIjPUkelim6l5DuLjmoiwd9ejWDp8hVq4zYlyZm8KobOOw-cWp5zDysRgvWgsYQ3cr3xspiQBE7t9DJjh7A9rv3HC67joMgFP2c34X5gKU07J5RBvvrsxNEHJaa2rAq1KqkmMikgN_jJ2r_i1itlMRulNx7DuqUDdZZAWYm6k?purpose=inline)

**纪录片叙事型**

画面推进、故事递进、情绪引导

#### **3.12 Visual Recipe 数据**

每个配方包含：

| 字段                    | 说明                                        |
| --------------------- | ----------------------------------------- |
| recipeId              | 唯一ID                                      |
| name                  | 配方名称                                      |
| description           | 配方说明                                      |
| applicableLines       | 适用生产线                                     |
| visualMode            | 2D / 3D                                   |
| narrativeTypes        | 适用叙事类型                                    |
| hookPattern           | 开场规则                                      |
| pacing                | 节奏参数                                      |
| sceneSequence         | 场景排列策略                                    |
| motionRules           | 动效规则                                      |
| typographyRules       | 字幕规则                                      |
| audioRules            | 音频规则                                      |
| supportedSceneTypes   | 所需组件                                      |
| templateCompatibility | 兼容模板                                      |
| referenceIds          | 参考视频证据                                    |
| status                | candidate / validated / active / archived |
| version               | 配方版本                                      |

#### **3.13 配方治理机制**

**Candidate**

Agent 从分析结果中提取，尚未验证

**Validated**

在 Heron 真实作品中完成验证，确认兼容和质量

**Active**

正式允许生产引擎选择使用

不允许模型直接生成任意执行代码。

视觉策略只能通过 Heron 已注册的：

- 模板
- scene_type
- storyboard beat
- motion primitive
- 字幕组件
- 图表组件
- 转场组件
- 2D/3D visualMode

来表达。

没有组件支持的效果，必须先进入模板开发与测试流程。

### **P1 — 视频再创作引擎（Video Recreation）**

这是你最初提出的核心想法：热门视频被发现后，能够自动进入再创作流程，而不是只看分析报告。

但这部分需要把“参考视频素材”和“新作品素材”区分开。对于有授权的素材，可以进一步实现自动化编辑；对没有改编许可的热门视频，系统只提炼制作方法，重新使用自己的内容和素材。

#### **3.14 再创作模式**

| 模式                    | 说明                       | 优先级 |
| --------------------- | ------------------------ | --- |
| Visual Recreation     | 学习视觉风格，使用自己的内容重新制作       | P0  |
| Licensed Remix        | 对有改编授权的视频，进行素材替换与重新编排    | P1  |
| Project-Level Editing | 有原始工程文件时，替换文字、图片、人物等工程元素 | P2  |
| Full Regeneration     | 从分析报告重新生成全部场景            | P1  |

#### **3.15 自动再创作流程**

\#chatgpt-mermaid-\_r_2fs\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_2fs\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2fs\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2fs\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_2fs\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_2fs\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_2fs\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_2fs\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_2fs\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_2fs\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2fs\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2fs\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_2fs\_ p{margin:0;}#chatgpt-mermaid-\_r_2fs\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ .cluster-label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ .cluster-label span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ .cluster-label span p{background-color:transparent;}#chatgpt-mermaid-\_r_2fs\_ .label text,#chatgpt-mermaid-\_r_2fs\_ span{fill:rgb(237, 237, 237);color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ .node rect,#chatgpt-mermaid-\_r_2fs\_ .node circle,#chatgpt-mermaid-\_r_2fs\_ .node ellipse,#chatgpt-mermaid-\_r_2fs\_ .node polygon,#chatgpt-mermaid-\_r_2fs\_ .node path{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ .rough-node .label text,#chatgpt-mermaid-\_r_2fs\_ .node .label text,#chatgpt-mermaid-\_r_2fs\_ .image-shape .label,#chatgpt-mermaid-\_r_2fs\_ .icon-shape .label{text-anchor:middle;}#chatgpt-mermaid-\_r_2fs\_ .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ .rough-node .label,#chatgpt-mermaid-\_r_2fs\_ .node .label,#chatgpt-mermaid-\_r_2fs\_ .image-shape .label,#chatgpt-mermaid-\_r_2fs\_ .icon-shape .label{text-align:center;}#chatgpt-mermaid-\_r_2fs\_ .node.clickable{cursor:pointer;}#chatgpt-mermaid-\_r_2fs\_ .root .anchor path{fill:rgb(175, 175, 175)!important;stroke-width:0;stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2fs\_ .arrowheadPath{fill:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2fs\_ .edgePath .path{stroke:rgb(175, 175, 175);stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ .flowchart-link{stroke:rgb(175, 175, 175);fill:none;}#chatgpt-mermaid-\_r_2fs\_ .edgeLabel{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_2fs\_ .edgeLabel p{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2fs\_ .edgeLabel rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2fs\_ .labelBkg{background-color:rgba(0, 0, 0, 0.5);}#chatgpt-mermaid-\_r_2fs\_ .cluster rect{fill:rgb(48, 48, 48);stroke:rgba(255, 255, 255, 0.15);stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ .cluster text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ .cluster span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:12px;background:rgb(48, 48, 48);border:1px solid rgba(255, 255, 255, 0.15);border-radius:2px;pointer-events:none;z-index:100;}#chatgpt-mermaid-\_r_2fs\_ .flowchartTitleText{text-anchor:middle;font-size:18px;fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2fs\_ rect.text{fill:none;stroke-width:0;}#chatgpt-mermaid-\_r_2fs\_ .icon-shape,#chatgpt-mermaid-\_r_2fs\_ .image-shape{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_2fs\_ .icon-shape p,#chatgpt-mermaid-\_r_2fs\_ .image-shape p{background-color:rgb(0, 0, 0);padding:2px;}#chatgpt-mermaid-\_r_2fs\_ .icon-shape .label rect,#chatgpt-mermaid-\_r_2fs\_ .image-shape .label rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2fs\_ .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#chatgpt-mermaid-\_r_2fs\_ .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#chatgpt-mermaid-\_r_2fs\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_2fs\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_2fs\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_2fs\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_2fs\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2fs\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_2fs\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2fs\_ .node text{font-size:14px;font-weight:600;letter-spacing:normal;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_2fs\_ .edgeLabels text{font-size:13px;font-weight:600;letter-spacing:-0.08px;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_2fs\_ .node tspan[font-weight="normal"],#chatgpt-mermaid-\_r_2fs\_ .edgeLabels tspan[font-weight="normal"]{font-weight:600;}#chatgpt-mermaid-\_r_2fs\_ .edgeLabel .label rect{opacity:1;rx:13px;ry:13px;fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ .node rect,#chatgpt-mermaid-\_r_2fs\_ .node circle,#chatgpt-mermaid-\_r_2fs\_ .node ellipse,#chatgpt-mermaid-\_r_2fs\_ .node polygon,#chatgpt-mermaid-\_r_2fs\_ .node path{fill:rgb(0, 40, 77);stroke:rgba(255, 255, 255, 0.1);stroke-width:1px;}#chatgpt-mermaid-\_r_2fs\_ .node rect{rx:16px;ry:16px;}#chatgpt-mermaid-\_r_2fs\_ .mermaid-decision .label-container{fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-dasharray:2,2;}#chatgpt-mermaid-\_r_2fs\_ .edgePaths .flowchart-link{stroke:rgb(175, 175, 175);stroke-width:1px;stroke-linecap:round;stroke-linejoin:round;}#chatgpt-mermaid-\_r_2fs\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2fs\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}热门视频发现趋势筛选rea-video分析提取视觉策略匹配Heron内容生成新的故事与脚本生成分镜选择模板与组件生成新素材渲染视频质量审核发布效果反馈

#### **3.16 自动替换和编辑能力**

在有授权、可编辑工程或自有素材的场景下，支持：

- 替换标题和文案。
- 替换图片与背景。
- 替换人物角色。
- 替换配音与字幕。
- 替换数据和图表。
- 调整镜头时间。
- 调整动画节奏。
- 替换音乐和音效。
- 保留工程中允许复用的结构、镜头或组件。

当只有一份合成 MP4 时，不应假设文字、人物和背景是可独立编辑的图层。无法精确替换时，系统应使用新的素材重新生成场景，而不是强行覆盖造成画面瑕疵。

### **P1 — 与 Heron 现有生产引擎连接**

这部分非常重要：不要再造一套生产系统。

根据目前已检查的 Heron 后端 `dev` 分支结构，推荐接入点仍然是现有的：

`EditorialLineEditor → NewsTemplatePicker → NewsProductionPipeline`

#### **3.17 接入原则**

| 现有模块                       | 本次改造                    |
| -------------------------- | ----------------------- |
| `NewsAutoService`          | 保持原有自动生产调度，不塞入媒体下载与分析流程 |
| `EditorialDecisionService` | 保持生产线/内容决策职责            |
| `EditorialLineEditor`      | 在故事策划完成后解析适用的视觉配方       |
| `NewsTemplatePicker`       | 保留现有最终模板选择职责            |
| `VisualMode`               | 保留 2D/3D 判断机制           |
| `FrozenContextService`     | 冻结本次生产使用的视觉配方版本和参数      |
| `NewsProductionPipeline`   | 按冻结策略执行已有渲染、检查与质检       |
| `QualityReviewer`          | 继续负责质量评估，不让参考视频热度代替质量审核 |

#### **3.18 生产决策顺序**

\#chatgpt-mermaid-\_r_2g3\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_2g3\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2g3\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2g3\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_2g3\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_2g3\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_2g3\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_2g3\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_2g3\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_2g3\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2g3\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2g3\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_2g3\_ p{margin:0;}#chatgpt-mermaid-\_r_2g3\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ .cluster-label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ .cluster-label span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ .cluster-label span p{background-color:transparent;}#chatgpt-mermaid-\_r_2g3\_ .label text,#chatgpt-mermaid-\_r_2g3\_ span{fill:rgb(237, 237, 237);color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ .node rect,#chatgpt-mermaid-\_r_2g3\_ .node circle,#chatgpt-mermaid-\_r_2g3\_ .node ellipse,#chatgpt-mermaid-\_r_2g3\_ .node polygon,#chatgpt-mermaid-\_r_2g3\_ .node path{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ .rough-node .label text,#chatgpt-mermaid-\_r_2g3\_ .node .label text,#chatgpt-mermaid-\_r_2g3\_ .image-shape .label,#chatgpt-mermaid-\_r_2g3\_ .icon-shape .label{text-anchor:middle;}#chatgpt-mermaid-\_r_2g3\_ .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ .rough-node .label,#chatgpt-mermaid-\_r_2g3\_ .node .label,#chatgpt-mermaid-\_r_2g3\_ .image-shape .label,#chatgpt-mermaid-\_r_2g3\_ .icon-shape .label{text-align:center;}#chatgpt-mermaid-\_r_2g3\_ .node.clickable{cursor:pointer;}#chatgpt-mermaid-\_r_2g3\_ .root .anchor path{fill:rgb(175, 175, 175)!important;stroke-width:0;stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2g3\_ .arrowheadPath{fill:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2g3\_ .edgePath .path{stroke:rgb(175, 175, 175);stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ .flowchart-link{stroke:rgb(175, 175, 175);fill:none;}#chatgpt-mermaid-\_r_2g3\_ .edgeLabel{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_2g3\_ .edgeLabel p{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2g3\_ .edgeLabel rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2g3\_ .labelBkg{background-color:rgba(0, 0, 0, 0.5);}#chatgpt-mermaid-\_r_2g3\_ .cluster rect{fill:rgb(48, 48, 48);stroke:rgba(255, 255, 255, 0.15);stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ .cluster text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ .cluster span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:12px;background:rgb(48, 48, 48);border:1px solid rgba(255, 255, 255, 0.15);border-radius:2px;pointer-events:none;z-index:100;}#chatgpt-mermaid-\_r_2g3\_ .flowchartTitleText{text-anchor:middle;font-size:18px;fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2g3\_ rect.text{fill:none;stroke-width:0;}#chatgpt-mermaid-\_r_2g3\_ .icon-shape,#chatgpt-mermaid-\_r_2g3\_ .image-shape{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_2g3\_ .icon-shape p,#chatgpt-mermaid-\_r_2g3\_ .image-shape p{background-color:rgb(0, 0, 0);padding:2px;}#chatgpt-mermaid-\_r_2g3\_ .icon-shape .label rect,#chatgpt-mermaid-\_r_2g3\_ .image-shape .label rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2g3\_ .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#chatgpt-mermaid-\_r_2g3\_ .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#chatgpt-mermaid-\_r_2g3\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_2g3\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_2g3\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_2g3\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_2g3\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2g3\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_2g3\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2g3\_ .node text{font-size:14px;font-weight:600;letter-spacing:normal;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_2g3\_ .edgeLabels text{font-size:13px;font-weight:600;letter-spacing:-0.08px;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_2g3\_ .node tspan[font-weight="normal"],#chatgpt-mermaid-\_r_2g3\_ .edgeLabels tspan[font-weight="normal"]{font-weight:600;}#chatgpt-mermaid-\_r_2g3\_ .edgeLabel .label rect{opacity:1;rx:13px;ry:13px;fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ .node rect,#chatgpt-mermaid-\_r_2g3\_ .node circle,#chatgpt-mermaid-\_r_2g3\_ .node ellipse,#chatgpt-mermaid-\_r_2g3\_ .node polygon,#chatgpt-mermaid-\_r_2g3\_ .node path{fill:rgb(0, 40, 77);stroke:rgba(255, 255, 255, 0.1);stroke-width:1px;}#chatgpt-mermaid-\_r_2g3\_ .node rect{rx:16px;ry:16px;}#chatgpt-mermaid-\_r_2g3\_ .mermaid-decision .label-container{fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-dasharray:2,2;}#chatgpt-mermaid-\_r_2g3\_ .edgePaths .flowchart-link{stroke:rgb(175, 175, 175);stroke-width:1px;stroke-linecap:round;stroke-linejoin:round;}#chatgpt-mermaid-\_r_2g3\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2g3\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}Heron选题EditorialDecisionEditorialLineEditor策划故事获取适用Visual RecipeStory + Recipe约束生成脚本生成StoryboardNewsTemplatePicker选择模板冻结Production ContextNewsProductionPipelineHyperFrames / GSAP /FFmpeg质检

配方选择不是替代模板选择，而是告诉现有生产系统“如何制作”。模板则决定具体由哪些已实现的组件来呈现。

## **四、前端需求规划**

建议增加一个一级菜单：**趋势情报**。

不要将所有功能挤在现有数据看板或视频工作台中。

[Poiiky YTB — POIIKY](https://images.openai.com/static-rsc-4/bkb15FYY2O-Q2gnOwVqe7dthrdf-LNEwHv7uCNLYpy68w4UYutLdb2KcjepVoHYxhQau00XlYEpSXSekT7PxMiwzetp4_qM5WH-RavS5jrPcrG87XjCnrOjCYLYlI3IoGEF77GNFN3Vu9tqKyZ93W76RmcNJFNJe1zd81ehnTJU?purpose=inline)

[Vadoo AI Dashboard - Streamline Your Video Creation with Vadoo AI](https://images.openai.com/static-rsc-4/NlbADgYDACakOLUvAfkllthngxFQ0JLqvtJRtduq2lzSSBF39RhiBK2VUJ799tWArY_WEtz8ISI3IBO89jsOTeHQaN2gqKPyAAPNU-tp6jSbXqj8jfJXP_lLm-rcL7X6IqxlPykEXjht1dHyFGeNxp9nFSTy-FEnT_b2peH9Id8?purpose=inline)

[Higgsfield Alternatives: Top 12 AI Video Generators & Similar Websites | AlternativeTo](https://images.openai.com/static-rsc-4/lq_mdBOAtpoPNVjYKNKtGWBsGFPs2gsc7YsV3GFiGE9fyKBgwhpsyiLQPtm1EEhZmY3E90qDoVK28ekagZ9UaYar7eDMkuGEtt-9wSz2Dr-pwO9uEGB0G96hwPssCWh57pSE1nJH8UFJSMCd_jVOzX6NYfvzFY_RKt4Q2cjyw1U?purpose=inline)

13

### **4.1 页面规划**

[Breakwave.ai — The Fastest Way to Go Viral on TikTok](https://images.openai.com/static-rsc-4/O7dPZViMuanllrQUtxbcegeLSYRVdQHNor_inFgxa3cTVXCuiC3uWIN_SvbeRJ6Kh5u-UrEIOAbDCthZ8VoJfUmsgbYAc0pWeueSirY6N5pxG9MulkX_mOMfw_OtQfBRgowpMWTWou1AmZwJ7nrWvlmcgQbT1Hik0bcqPjqLqvQ?purpose=inline)

**① 趋势发现**

热门视频列表、趋势榜、评分、热度变化、自动发现任务、来源平台。

P0

[Vadoo AI Dashboard - Streamline Your Video Creation with Vadoo AI](https://images.openai.com/static-rsc-4/NlbADgYDACakOLUvAfkllthngxFQ0JLqvtJRtduq2lzSSBF39RhiBK2VUJ799tWArY_WEtz8ISI3IBO89jsOTeHQaN2gqKPyAAPNU-tp6jSbXqj8jfJXP_lLm-rcL7X6IqxlPykEXjht1dHyFGeNxp9nFSTy-FEnT_b2peH9Id8?purpose=inline)

**② 视频分析**

视频详情、镜头时间轴、rea-video报告、字幕、音频、画面分析、制作方式。

P0

[MediaSilo | Secure Cloud Media Asset Management](https://images.openai.com/static-rsc-4/MilkbT8JP2dSZijHMqy-OXrvNkeIM0uhIQaP8Zh0z0fxvpDTC-k6J3isPFjNgqudNUGvDkZHI_rIgOqqjqNCZYtc0qJoh_3oTAfrmGHqmno4gmTUMgWodIYWVgSN97jHOdHQVuBibU_QQ5vzp6gzqggQnfCU67jTQzAkf6G-izw?purpose=inline)

**③ 参考视频库**

视频来源、素材、下载状态、授权信息、分析状态、报告归档、搜索与过滤。

P0

[Dashboard Builder · Claude Code Accelerator](https://images.openai.com/static-rsc-4/Sc7Hw5L8uJn3Jz0th_Qr-SrG752kGgQo-TeF-Ncc_bTWstXPb24XHc3v-VdTq-nkG6nL0H5iR53dLc-jmnid9FvJKd86HV02C0t6IS20qYGPywRsoFm0V7pjdytrz9aYz-HjF6N36k3nj6cmXNJS_E-paHxtnY-wqbywtZFj7Y4?purpose=inline)

**④ 视觉策略库**

配方列表、适用生产线、2D/3D、模板兼容、策略案例、版本、启用与停用。

P1

[INTERWA | Pioneering Advanced Productivity](https://images.openai.com/static-rsc-4/b2E-95c0qZchiDQB-xXZUzCQviLi6ti0MzJesg7t6kxEs5zKNxJGVszdldfQehbMoUKskU5WtMXS8_4t8QsbzWwYV4KEYfKAU1WoRFBPYJdbUvTGDHwZyiH9YdAaP2x35CckcCgCKSqvoNRQSApiB9zBPSI-PPyfgHYtCioF12Y?purpose=inline)

**⑤ 再创作工作台**

新内容、原始授权素材、编辑计划、分镜、预览、质检、发布状态。

P1

[AI Video Analytics Dashboard UI, YouTube Performance SaaS Design by Dhruvik Lathiya on Dribbble](https://images.openai.com/static-rsc-4/yPAPQ7c6i8q_BiGyBbsnbJz6I2oZC_TuGWLZ2Ka_P4n006xIeMxnheZE33fJWn5-nUfghO_oxaAbOl6LjPvTrgePXOOhCZfoUhwc7xc5tRMtbEwotSsCoa7We9Vik4OSv26aO68zeXVkWkfgZoqh_z3AZKpc7c9fj3XUCQe8YVQ?purpose=inline)

**⑥ 策略效果分析**

不同配方的视频表现、对照数据、成功与失败案例、策略晋级依据。

P1

### **4.2 趋势发现页面重点**

这个页面要让你一眼看到 Agent 正在做什么。

显示：

- 今日发现视频数量。
- 待筛选数量。
- 候选分析队列。
- 已分析视频。
- 新发现视觉配方。
- 当前热点分类。
- 趋势分数排行榜。
- 每条视频的发现理由。
- 自动任务运行状态。

例如：

| 视频       | 热度评分 | 增长   | 视觉价值 | 状态  |
| -------- | ---- | ---- | ---- | --- |
| 城市人口迁移   | 92   | 快速增长 | 高    | 已分析 |
| AI未来十年变化 | 88   | 上升   | 高    | 分析中 |
| 全球经济对比   | 79   | 稳定   | 中    | 待分析 |

以上为界面示例，不是实际抓取数据。

## **五、后端数据结构建议**

不要一开始就建立几十张表。建议按核心实体逐步扩展，避免过度设计。

### **5.1 核心实体**

| 实体                            | 作用           |
| ----------------------------- | ------------ |
| `heron_trend_source`          | 平台及数据源配置     |
| `heron_trend_candidate`       | 自动发现的热门视频候选  |
| `heron_trend_snapshot`        | 视频热度历史快照     |
| `heron_reference_video`       | 参考视频与素材资产    |
| `heron_reference_task`        | 下载、分析、处理任务   |
| `heron_video_analysis`        | 分析结果、报告版本    |
| `heron_visual_recipe`         | 视觉策略配方       |
| `heron_visual_recipe_version` | 配方版本与参数快照    |
| `heron_recipe_evaluation`     | 配方验证和效果评估    |
| `heron_recreation_task`       | 再创作任务与关联生产作品 |

初期可以合并部分实体，例如 `heron_reference_video` 和 `heron_trend_candidate`，但趋势快照和任务生命周期最好独立保留。

### **5.2 关键关联**

\#chatgpt-mermaid-\_r_2it\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_2it\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2it\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2it\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_2it\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2it\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_2it\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_2it\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_2it\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_2it\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_2it\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_2it\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2it\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2it\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_2it\_ p{margin:0;}#chatgpt-mermaid-\_r_2it\_ .entityBox{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_2it\_ .relationshipLabelBox{fill:rgb(48, 48, 48);opacity:0.7;background-color:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_2it\_ .relationshipLabelBox rect{opacity:0.5;}#chatgpt-mermaid-\_r_2it\_ .labelBkg{background-color:rgba(48, 48, 48, 0.5);}#chatgpt-mermaid-\_r_2it\_ .edgeLabel{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2it\_ .edgeLabel .label rect{fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2it\_ .edgeLabel .label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2it\_ .edgeLabel .label{fill:rgb(31, 78, 148);font-size:14px;}#chatgpt-mermaid-\_r_2it\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2it\_ .edge-pattern-dashed{stroke-dasharray:8,8;}#chatgpt-mermaid-\_r_2it\_ .node rect,#chatgpt-mermaid-\_r_2it\_ .node circle,#chatgpt-mermaid-\_r_2it\_ .node ellipse,#chatgpt-mermaid-\_r_2it\_ .node polygon{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_2it\_ .relationshipLine{stroke:rgb(175, 175, 175);stroke-width:1px;fill:none;}#chatgpt-mermaid-\_r_2it\_ .marker{fill:none!important;stroke:rgb(175, 175, 175)!important;stroke-width:1;}#chatgpt-mermaid-\_r_2it\_ [data-look=neo].labelBkg{background-color:rgba(48, 48, 48, 0.5);}#chatgpt-mermaid-\_r_2it\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_2it\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_2it\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_2it\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_2it\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2it\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_2it\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2it\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}TREND_CANDIDATETREND_SNAPSHOTREFERENCE_VIDEOREFERENCE_TASKVIDEO_ANALYSISVISUAL_RECIPERECIPE_VERSIONRECIPE_EVALUATIONRECREATION_TASKHERON_WORKhistoryacquiredanalysisreportsextractsversionsvalidatesappliesproduces

### **5.3 任务治理**

参考现有 `HeronResearchTask` 和 `NewsProductionTask` 的任务治理经验，但不要直接共用它们的 AgentScope 执行线程。

建议新增独立 Worker：

- `TrendDiscoveryWorker`
- `ReferenceAcquisitionWorker`
- `VideoAnalysisWorker`
- `VisualRecipeWorker`

初期可将 `VisualRecipeWorker` 与 `VideoAnalysisWorker` 合并，避免为了命名创建过多独立进程。

任务至少支持：

- 状态机。
- 租约。
- 心跳。
- 超时。
- 重试。
- 取消。
- 幂等。
- 错误日志。
- 并发限制。
- 资源限制。

视频分析是 CPU、内存、磁盘 I/O 都较重的任务，不能让它无限并发。尤其你的生产服务器比较敏感，建议初期只开放 1 个视频分析 Worker，并单独限制容器 CPU、内存和存储。

## **六、P1 — 效果反馈闭环**

这是 Heron 与普通视频批量生成工具的关键区别。

不能只分析别人做得好的视频，最终还要验证自己的作品。

### **6.1 策略效果指标**

- 3秒留存（平台提供时）。
- 完播率。
- 平均播放时长。
- 点赞率。
- 评论率。
- 分享率。
- 收藏率。
- 视频播放量增长。
- 不同 Hook 的表现。
- 不同视觉配方的表现。
- 不同生产线的表现。

### **6.2 配方验证机制**

\#chatgpt-mermaid-\_r_2j4\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_2j4\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2j4\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_2j4\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_2j4\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_2j4\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_2j4\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_2j4\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_2j4\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_2j4\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2j4\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2j4\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_2j4\_ p{margin:0;}#chatgpt-mermaid-\_r_2j4\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ .cluster-label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ .cluster-label span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ .cluster-label span p{background-color:transparent;}#chatgpt-mermaid-\_r_2j4\_ .label text,#chatgpt-mermaid-\_r_2j4\_ span{fill:rgb(237, 237, 237);color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ .node rect,#chatgpt-mermaid-\_r_2j4\_ .node circle,#chatgpt-mermaid-\_r_2j4\_ .node ellipse,#chatgpt-mermaid-\_r_2j4\_ .node polygon,#chatgpt-mermaid-\_r_2j4\_ .node path{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ .rough-node .label text,#chatgpt-mermaid-\_r_2j4\_ .node .label text,#chatgpt-mermaid-\_r_2j4\_ .image-shape .label,#chatgpt-mermaid-\_r_2j4\_ .icon-shape .label{text-anchor:middle;}#chatgpt-mermaid-\_r_2j4\_ .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ .rough-node .label,#chatgpt-mermaid-\_r_2j4\_ .node .label,#chatgpt-mermaid-\_r_2j4\_ .image-shape .label,#chatgpt-mermaid-\_r_2j4\_ .icon-shape .label{text-align:center;}#chatgpt-mermaid-\_r_2j4\_ .node.clickable{cursor:pointer;}#chatgpt-mermaid-\_r_2j4\_ .root .anchor path{fill:rgb(175, 175, 175)!important;stroke-width:0;stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2j4\_ .arrowheadPath{fill:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2j4\_ .edgePath .path{stroke:rgb(175, 175, 175);stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ .flowchart-link{stroke:rgb(175, 175, 175);fill:none;}#chatgpt-mermaid-\_r_2j4\_ .edgeLabel{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_2j4\_ .edgeLabel p{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2j4\_ .edgeLabel rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2j4\_ .labelBkg{background-color:rgba(0, 0, 0, 0.5);}#chatgpt-mermaid-\_r_2j4\_ .cluster rect{fill:rgb(48, 48, 48);stroke:rgba(255, 255, 255, 0.15);stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ .cluster text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ .cluster span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:12px;background:rgb(48, 48, 48);border:1px solid rgba(255, 255, 255, 0.15);border-radius:2px;pointer-events:none;z-index:100;}#chatgpt-mermaid-\_r_2j4\_ .flowchartTitleText{text-anchor:middle;font-size:18px;fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_2j4\_ rect.text{fill:none;stroke-width:0;}#chatgpt-mermaid-\_r_2j4\_ .icon-shape,#chatgpt-mermaid-\_r_2j4\_ .image-shape{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_2j4\_ .icon-shape p,#chatgpt-mermaid-\_r_2j4\_ .image-shape p{background-color:rgb(0, 0, 0);padding:2px;}#chatgpt-mermaid-\_r_2j4\_ .icon-shape .label rect,#chatgpt-mermaid-\_r_2j4\_ .image-shape .label rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_2j4\_ .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#chatgpt-mermaid-\_r_2j4\_ .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#chatgpt-mermaid-\_r_2j4\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_2j4\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_2j4\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_2j4\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_2j4\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2j4\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_2j4\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_2j4\_ .node text{font-size:14px;font-weight:600;letter-spacing:normal;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_2j4\_ .edgeLabels text{font-size:13px;font-weight:600;letter-spacing:-0.08px;fill:rgb(153, 206, 255);}#chatgpt-mermaid-\_r_2j4\_ .node tspan[font-weight="normal"],#chatgpt-mermaid-\_r_2j4\_ .edgeLabels tspan[font-weight="normal"]{font-weight:600;}#chatgpt-mermaid-\_r_2j4\_ .edgeLabel .label rect{opacity:1;rx:13px;ry:13px;fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ .node rect,#chatgpt-mermaid-\_r_2j4\_ .node circle,#chatgpt-mermaid-\_r_2j4\_ .node ellipse,#chatgpt-mermaid-\_r_2j4\_ .node polygon,#chatgpt-mermaid-\_r_2j4\_ .node path{fill:rgb(0, 40, 77);stroke:rgba(255, 255, 255, 0.1);stroke-width:1px;}#chatgpt-mermaid-\_r_2j4\_ .node rect{rx:16px;ry:16px;}#chatgpt-mermaid-\_r_2j4\_ .mermaid-decision .label-container{fill:rgb(0, 14, 26);stroke:rgb(26, 62, 95);stroke-dasharray:2,2;}#chatgpt-mermaid-\_r_2j4\_ .edgePaths .flowchart-link{stroke:rgb(175, 175, 175);stroke-width:1px;stroke-linecap:round;stroke-linejoin:round;}#chatgpt-mermaid-\_r_2j4\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_2j4\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}参考视频分析Recipe CandidateHeron生成作品作品发布采集表现数据样本和周期够吗继续收集对照评估表现是否稳定改善晋级Validated/Active保持Candidate或归档否是是否

**不建议根据单条爆款视频直接改变自动生产策略。**

建议每个配方的效果评估记录：

- 使用配方的作品数。
- 对比组作品数。
- 数据采样周期。
- 适用内容类型。
- 置信度或稳定性评估。
- 失败案例。
- 是否值得推广。

## **七、实施阶段与优先级**

我建议不要一次把所有功能交给 Codex 做。这个项目跨数据发现、媒体处理、AI 分析、策略、生产链、前端，直接一次性开发风险非常大。

Phase 1

**趋势自动发现**

建立数据源、自动扫描、候选视频、趋势评分、趋势页面。先让 Agent 能自己找到值得研究的视频。

Phase 2

**参考视频资产与分析**

建立资产管理、许可记录、独立 Worker、rea-video 集成、报告存储、镜头分析页面。

Phase 3

**视觉策略提取**

从分析报告生成 Visual Recipe，建立配方版本、适用性、验证机制和策略库。

Phase 4

**连接 Heron 生产引擎**

将配方接入 EditorialLineEditor、模板选择、冻结上下文、分镜和现有渲染流水线。

Phase 5

**视频再创作**

针对自有或授权视频，实现素材替换、人物/文字替换、场景重建、自动渲染和质检。

Phase 6

**反馈学习与自动优化**

收集发布数据，自动比较配方表现，逐步形成稳定的生产策略。

### **实施顺序建议**

| 阶段      | 核心交付                                 | 优先级   |
| ------- | ------------------------------------ | ----- |
| Phase 1 | Discovery + Trend Scoring + 页面       | P0    |
| Phase 2 | Reference Library + rea-video Worker | P0    |
| Phase 3 | Visual Recipe Library + 状态治理         | P1    |
| Phase 4 | 接入现有生产链                              | P1    |
| Phase 5 | 授权视频自动再创作                            | P1/P2 |
| Phase 6 | 性能反馈闭环                               | P1/P2 |

## **八、每阶段验收标准**

这是避免 Codex 做出“看起来完成、实际没有真正自动化”的关键。

### **Phase 1 验收**

- Agent 可以定时运行。
- 无需用户上传视频即可发现候选视频。
- 数据源可以启停。
- 候选视频有去重机制。
- 热度评分有明确依据。
- 能识别值得进一步分析的候选。
- 失败任务不会导致整个调度器停止。
- 前端可以看到发现过程和候选结果。

### **Phase 2 验收**

- 资产来源可追溯。
- 授权状态有独立记录。
- 只有符合许可条件的素材才能下载。
- 视频获取任务与研究任务分离。
- rea-video 可以被 Worker 调用。
- 能产生 `VIDEO.md`、`report.html`、`analysis.json`。
- 报告和媒体资产可以持久化。
- 可以查看镜头时间轴和抽帧。

### **Phase 3 验收**

- 能从分析结果提取视觉配方。
- 配方结构化保存。
- 配方可版本化。
- 配方关联参考案例。
- 配方可验证。
- 配方可以启用和停用。
- 无效配方不会进入生产。
- 配方不会绕过模板能力。

### **Phase 4 验收**

- 视觉配方可以被现有生产线选择。
- 生产时冻结 recipeId、版本、参数。
- 历史生产不受新配方修改影响。
- 现有 2D/3D 模式仍有效。
- 模板兼容性检查正常。
- HyperFrames/GSAP/FFmpeg 现有链路不被破坏。
- 现有新闻、数据、知识生产功能无回归。

### **Phase 5 验收**

- 能基于授权素材创建再创作任务。
- 能替换文案、图片、人物和音频等素材。
- 能重新生成需要重建的镜头。
- 有完整的任务记录。
- 自动质检。
- 支持失败重试。
- 不会因单个任务失败影响原生产链。

### **Phase 6 验收**

- 发布作品可以关联使用的配方版本。
- 有足够数据时才生成效果结论。
- 支持对照分析。
- 能发现表现不佳的配方。
- 自动策略升级有门槛。
- 所有策略变化可以追溯与回滚。

## **九、与当前 Heron 代码的兼容要求**

本计划不是推倒现有 Heron 重做。基于本次对话中已检查的两个 `dev` 分支版本：

| 仓库                                                                                     | 本次基线       |
| -------------------------------------------------------------------------------------- | ---------- |
| [heron 后端](https://github.com/zhoupei6251/heron/tree/dev?utm_source=chatgpt.com)       | `5beb42fc` |
| [heron-ui 前端](https://github.com/zhoupei6251/heron-ui/tree/dev?utm_source=chatgpt.com) | `a4c26fef` |

开发时必须遵守以下边界：

- 保留现有 News、Dataset、Knowledge、Opportunity 生产体系。
- 不改变 `NewsAutoService` 当前主流程的稳定性。
- 不重构现有生产引擎，只增加新的趋势情报模块和连接点。
- 不在 `EditorialDecisionService` 中混入视频分析或下载逻辑。
- 最终模板仍由 `NewsTemplatePicker` 负责。
- 冻结生产上下文需要兼容历史 Schema 1.0。
- 视觉配方必须有版本、状态、适用范围和验证结果。
- 新增媒体 Worker 必须独立，不能拖垮现有 AgentScope 任务。
- 所有自动任务支持暂停、恢复、重试、取消。
- 生产发布机制保持现有安全与质量约束。

## **十、最终目标：Heron 自主学习的创作闭环**

[GPT Image 2 + Seedance 2.0: The Complete Storyboard Workflow](https://images.openai.com/static-rsc-4/fAzfe5BJf4F4BV45fzG-r-mFAAmyvf8fcdFkVi96bLHM5MMxj4QJ-Guaqxz9vs0Nj5ILPqU-uh3JoFV_Zm3VAdnUareNb7mXnYd4uwqDuro_KfzcOCpWhBGNXb-cjR2K27Mcb25eFBVpORqcbVXhD9HAk4rXehA_s8BPAz_rgoE?purpose=inline)

[TikTok Symphony Agent 2026: AI ad creation workflow, reporting & KPIs](https://images.openai.com/static-rsc-4/dq4TS1vaGlm_-fh_G8_5apeqBsaOHkxQ2jQRC6BMlIgPmHWRHye1IySrPpA4t4vhYFmTQRNCbnPHebuUujSYGGjrE1ZWrfAbbKlhwS9FfQut3F_csBZKMPBdmjd4cHbdzi1PC3lIj-7m1vUHFziRAmDc6-YKP15uFK5gv1nN6SA?purpose=inline)

[YouTube Shorts Script Templates (2026) | SwipeStory](https://images.openai.com/static-rsc-4/oIpDBlZ8d0teXW5zilEKeca7PPilwm2RMAhE39fpc3dFFjivjaFJpvwcr0I-kLB1UMMPggl736PAL2m8dEPoaKbvfN4Xp4MyinwLuImFe8O9ZRWPd4dQaaFLCyNjzdo4NujZs5z0Mr3YK0UHI-0xtYt5GMdHb4xnuSEy6zJ_W9s?purpose=inline)

15

最终 Heron 应该具备这样的能力：

1. **自己发现**：每天主动扫描热门视频，不需要我手动找视频、导入视频。
2. **自己判断**：哪些视频值得学习，哪些只是短暂热点，哪些与 Heron 内容方向有关。
3. **自己分析**：自动拆解优秀视频的镜头、文案、节奏、动画、人物、音频。
4. **自己学习**：把有效制作方法提炼成 Visual Recipe。
5. **自己创作**：拿自己的选题、数据和素材，选择合适的视觉策略。
6. **自己生产**：调用 Heron 现有的模板、分镜、渲染和质检系统。
7. **自己验证**：通过真实发布表现判断策略是否有效。
8. **持续进化**：优秀的策略不断积累，低效的策略逐渐淘汰。

而对于有改编许可的视频，还可以额外进入自动素材替换和再创作流程。

**我认为这次应该优先做 Phase 1 + Phase 2。** 先让 Heron 真正具备“自主发现热门视频”和“自动分析视频”的能力，跑通后再开发视觉策略提取。这样每一步都能实际运行、验证，而不是一上来做一个庞大的空架子。

如果你认可这份计划，下一步我可以把它进一步整理成一份**可直接交给 Codex 执行的分阶段开发任务书**，包含具体后端模块、前端页面、数据库设计、API 契约、任务状态机、实施顺序和每阶段测试要求。


可以，而且我认为这次 Heron 的需求计划应该增加一部分 **GitHub 开源项目选型与复用清单**。

不过不应该为了堆 GitHub 仓库而引入依赖。你的 Heron 已经有自己的 Java 后端、Vue 前端、AgentScope、视频生产流水线（HyperFrames / GSAP / FFmpeg）以及现有任务体系。引入的新仓库必须解决实际缺口，优先直接复用，能用现有能力解决就不新增。

我建议把开源项目分为 **必选候选、按需引入、暂不引入** 三档，并在需求计划中要求 AI 开发者先评估仓库再决定是否集成。

## **一、建议写入需求计划的 GitHub 项目**

### **P0：核心候选，值得集成评估**

[GitHub - M4n3kiNek0/ai-video-analyzer: AI-powered media analyzer , extracts visual video keyframes, transcribes audio and generates structured PDF/JSON reports from videos and audio files. · GitHub](https://images.openai.com/static-rsc-4/_kvdNJsgHLhBHkI3PYc4_pcX5Eqsb22wQfFaPYMxBa-JLoo2k261_C7jYugjimGz6U_NuspRnC5OXXGescfE4V8fBFGPt1waxU6_IIzh8S0Xmz4TQ4HluISjnYSxMUtMlzuhyrK9jKO4uy_1t67AsApDTKnRDcVgYYG7nynkRjA?purpose=inline)

**1. rea-video — 视频逆向分析核心**

核心项目

[GitHub · JuneYaooo/rea-video](https://github.com/JuneYaooo/rea-video?utm_source=chatgpt.com)

用于逐镜头分析视频，识别拍摄、AI生成、代码动画等方式，拆解字幕、剪辑、音频和制作手法，并形成 `VIDEO.md` 分析报告。

**用途：** Heron 视频分析 Worker 的主要分析引擎。优先复用，不重新造轮子。

[yt-dlp for Windows - Download it from Uptodown for free](https://images.openai.com/static-rsc-4/mGcmkxFek1ZsSNn4A1V41__cPINBXYZlzKr6b_aUwKCX9wwVKrfZrVjT0BCw6uAfi9fdjwJL1O9BhKEZfj1ZZrhM9Y0zmEjMU8DXh3bbHK6GWpwHJpmdVDArGcjE82yfBiIR3NgqVmxd9kcJV6tqKbx5TcfEKgs2rF-GeG8PU40?purpose=inline)

**2. yt-dlp — 视频获取工具**

核心候选

[GitHub · yt-dlp](https://github.com/yt-dlp/yt-dlp?utm_source=chatgpt.com)

支持大量视频平台的媒体获取、格式选择、元信息解析等功能。

**用途：** 授权可获取的源视频下载、媒体信息提取。必须做来源权限判断、平台规则校验，不应将其当作绕过平台限制的工具。

[PySceneDetect：シーン単位で分割できるPythonライブラリ #Python - Qiita](https://images.openai.com/static-rsc-4/FdSVJl90jNu2a6GF9wkSS7_S5CJTlfSdIvgfSMWibPxu_bRmljwPZ0mKLIHcGStwRRqikQfrYGKC5Q1q9chDHJSuNUaHMkgF00U3W53QDJkD0ok5qL6Npko-LyqkD1CVwTuusY2WvLQJn6ieKXrqTTSGmNy5wVX25faNa7RBs7Y?purpose=inline)

**3. PySceneDetect — 镜头切分与场景检测**

按需复用

[GitHub · PySceneDetect](https://github.com/Breakthrough/PySceneDetect?utm_source=chatgpt.com)

检测视频中的镜头切换、场景边界、时间区间，支持关键帧提取与场景切分。

**用途：** 补充 rea-video 的镜头分析能力，尤其是需要更准确的镜头边界、时间轴和场景截图时。

rea-video 已覆盖较多视频逆向分析能力，PySceneDetect 不建议一开始强制部署，先确认 rea-video 的镜头切分质量是否满足需求，再决定是否增加。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+2



## **二、P1：备用或补充项目，不作为初始依赖**

[GitHub - bradautomates/claude-video: Give Claude the ability to watch any video. /watch downloads, extracts frames, transcribes, hands it all to Claude. · GitHub](https://images.openai.com/static-rsc-4/2ro4ZiWK4TXu7_G1TzRHnE54VA-NVRhjtxltaiBR6nJHRRR7UuICE2ma--r7wOOhRGN_iFdA0U8nbMkEh_z0q4J1Tv-CQ-diQdJ625lnqhRATZknIIwoMgOaTmN2l3nhHhAi77JXWI_O3lVu_7VnN96vPFF0oOk3Ag-5u1_r7k8?purpose=inline)

**4. claude-real-video — 视频理解辅助工具**

[GitHub · claude-real-video](https://github.com/HUANGCHIHHUNGLeo/claude-real-video?utm_source=chatgpt.com)

让 AI 通过去重关键帧、场景信息、字幕转录等方式理解完整视频。

**用途：** 当 rea-video 对长视频的理解不足，或需要补充时间轴、字幕、上下文时，作为备选工具。

**决策：** 暂不集成，避免两套视频分析工具重复工作。

这个项目与 rea-video 的定位有部分重合，先作为备用方案即可。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub



## **三、现有能力，不需要额外引入 GitHub 仓库**

这部分我反而建议在需求计划中明确写出来，防止 Codex/Claude 开发时又引入一套重复工具。

| 功能       | Heron 现有方案                     | 处理方式   |
| -------- | ------------------------------ | ------ |
| 视频渲染     | HyperFrames + GSAP             | 继续使用   |
| 视频合成、转码  | FFmpeg                         | 继续使用   |
| 任务调度     | Heron 现有 Worker / DB任务机制       | 复用     |
| AI Agent | AgentScope                     | 复用     |
| 视频模板     | Heron Template体系               | 复用     |
| 视频生产     | NewsProductionPipeline         | 复用     |
| 视频质量审核   | QualityReviewer                | 复用     |
| 视频存储     | MinIO                          | 复用     |
| 前端       | Vue现有项目                        | 复用     |
| 镜头切分     | rea-video 优先，必要时 PySceneDetect | 不重复造轮子 |

尤其是 **不建议为了这个需求引入 Remotion**。你现在已经有 HyperFrames + GSAP + FFmpeg 生产体系，再引入 Remotion 就意味着多一套渲染技术栈，当前收益不足以抵消维护成本。

## **四、GitHub 仓库接入策略**

建议直接将以下规则加入开发任务书：

| 仓库                                                                                                | 优先级  | 集成方式            | 是否立即部署 |
| ------------------------------------------------------------------------------------------------- | ---- | --------------- | ------ |
| [rea-video](https://github.com/JuneYaooo/rea-video?utm_source=chatgpt.com)                        | P0   | 视频分析能力，封装独立调用接口 | 是，先验证  |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp?utm_source=chatgpt.com)                                 | P0候选 | 授权素材获取适配器       | 条件启用   |
| [PySceneDetect](https://github.com/Breakthrough/PySceneDetect?utm_source=chatgpt.com)             | P1   | 补充镜头切分          | 暂不     |
| [claude-real-video](https://github.com/HUANGCHIHHUNGLeo/claude-real-video?utm_source=chatgpt.com) | P2   | 分析能力备选          | 不部署    |

### **集成约束**

- 不允许直接将第三方仓库代码大量复制进 Heron 后端。
- 优先独立 Docker Worker 或命令行子进程调用。
- 第三方项目需要明确许可证、维护活跃度、依赖、资源占用及安全风险。
- 必须先做小规模 POC，验证输出质量、稳定性和 CPU / RAM 占用。
- 第三方工具异常不得导致现有 Heron 自动生产、发布流程故障。
- 不得为了引入项目而重构现有生产链。
- 若 rea-video 已具备相应能力，则不额外部署 PySceneDetect。
- 素材获取遵循授权、平台规则和来源限制，不实现绕过限制的下载功能。

## **五、我建议增加到开发任务书中的专门章节**

最终交给 Codex 执行时，增加一个独立的章节：

**开源项目调研、POC 与依赖决策**

每个功能模块在开发前必须评估成熟的 GitHub 开源项目，优先复用成熟方案，而不是重复实现。

评估内容：

1. GitHub 地址及项目维护情况。
2. Star、最近提交、Release 状态。
3. License 与商业使用限制。
4. 是否可以 Docker 化、是否支持独立运行。
5. 是否与 Heron 现有功能重复。
6. CPU、内存、磁盘、GPU要求。
7. API / CLI 接入成本。
8. POC 验证结果。
9. 最终决定：引入、按需引入、不引入。

**原则：只有能够明确改善 Heron 的能力、效率或生产质量，并且通过验证的开源项目才允许成为实际依赖。**

我的建议是：**将 rea-video 作为本次功能最重要的外部开源项目，yt-dlp 作为受授权素材获取的技术候选，PySceneDetect 作为补充工具。其余暂时不加。**

这样整个 Heron 趋势情报系统就不会变成一堆开源工具的拼装，而是让 Heron 自己负责趋势判断、创作决策、视觉策略、生产和发布，外部项目只承担它们最擅长的专业能力。

如果你要继续，我可以把上一版完整需求计划直接升级成 **最终版 V2（含 GitHub 项目选型、分阶段实施、数据库/API、前后端任务、测试验收及 Codex 执行约束）**，整合成一份完整文档，不再分散补充。