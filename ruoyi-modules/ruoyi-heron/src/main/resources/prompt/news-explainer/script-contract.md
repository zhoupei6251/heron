# Heron 稿件 JSON 创作契约

本文件是 Heron 侧写死口径，来源为 `docs/plans/2026-11-02-heron-two-line-requirements.md` §3.2/§3.6。改这里等于改业务契约，需人工同步文档。

## 输出形态

只输出一个 JSON 对象，不带 Markdown 代码块、不带解释文字、不输出 HTML。字段与取值范围：

| 字段 | 必填 | 约束 |
|---|---|---|
| `source_url` | 系统覆盖 | 正文出处的 http(s) 链接，不必自己填 |
| `fetched_at` | 系统覆盖 | 正文抓取时间，不必自己填 |
| `body_sha256` | 系统覆盖 | 抓取正文的完整 SHA-256，不必自己填 |
| `title` | 是 | 屏幕标题，不超过 15 字 |
| `scenes` | 是 | 3–6 段，每段字段见下方「场景字段」 |
| `closing` | 是 | 结尾一句话 |
| `source_name` | 是 | 画面「来源：xxx」文案 |

- 每段 `lead` ≤ 20 字、`body` ≤ 70 字、`duration_s` 为正数秒；全部段落 `duration_s` 合计 30–75 秒。
- 只允许上述字段。出现未知字段直接退回。
- `title` 不得与任何一段的 `lead` 或 `body` 相同。

## 场景字段

每段必须给 `lead`、`body`、`duration_s`、`scene_id`；画面不是纯文字时再加 `scene_type`，`image` 场景还要给 `visual.assets`。模板启用分镜时，每段还必须同时给 `scene_type`、`beat`、`on_screen_text`。

| 字段 | 必填 | 说明 |
|---|---|---|
| `scene_id` | 是 | `scene_001` 起的稳定标识，三段就是 001/002/003，全篇唯一 |
| `scene_type` | 分镜模式必填 | 本段画面形态，只能取本次模板声明支持的类型 |
| `beat` | 分镜模式必填 | 本段承担的叙事节点，只能取 `HOOK` / `CONTEXT` / `QUESTION` / `EVIDENCE` / `COMPARISON` / `REVEAL` / `EXPLANATION` / `INSIGHT` / `CONCLUSION` |
| `on_screen_text` | 分镜模式必填 | 上屏短句，不超过 18 字；与旁白解耦，不复制 `lead` 或 `body` |
| `visual.mediaAssetId` | 素材库图片可用 | 已登记素材库的正整数 ID；也可写等价的 `media_asset_id` / `asset_id` |
| `visual.assets` | image 场景可用 | 从给定 `asset_id` 里选；`media_asset_id` 可直接指向素材库，`source_url` 会按登记地址核对 |
| `visual.orientation` | 可选 | `PORTRAIT` / `LANDSCAPE` / `SQUARE`；非法值直接拒绝，不扩大自动选材 |
| `visual.keywords` | 可选 | 不超过 20 个素材关键词字符串，用于素材库筛选 |

`scene_type` 的使用条件：

| 类型 | 什么时候用 |
|---|---|
| `headline` | 开场那一屏，放最强的单一事实 |
| `text` | 纯文字信息，默认选择 |
| `image` | 原文有配图，且这张图直接对应本段事实 |
| `quote` | 本段主体是原文的直接引语 |
| `rank` | 本段是几条并列信息的高低排序 |

上面没列出的类型（如 `compare`、`timeline`、`number`）当前没有模板能渲染，**不要输出**。

输入末尾会追加本次模板支持的 `scene_type`；与上表冲突时服从模板能力。未启用分镜的模板不要写 `beat` / `on_screen_text`，避免把旧版式误判成分镜稿。

## 分镜模式

1. 每段三件套必须同时出现：`scene_type`、`beat`、`on_screen_text`。
2. 第一段 `beat` 必须是 `HOOK`；最后一段必须是 `CONCLUSION`、`INSIGHT` 或 `REVEAL`。
3. 整篇至少使用两种 `scene_type`，但只能从本次模板声明支持的类型里选。
4. `on_screen_text` 是观众一眼读完的屏幕短句，不是旁白复制；省略旁白信息、改数字或改主体都不允许。
5. `body` 始终是本段完整口播，不得为了分镜留空或删短；`title` 始终是独立标题卡，不得用某段正文替代。

## 事实口径

- 数字、日期、主体、引语只能来自给定正文；正文没有的就不写，不要用参数知识补齐。
- 正文不足以下结论时，宁可缩短段落，也不要编造。出处三字段（`source_url` / `fetched_at` / `body_sha256`）由系统抓取后覆盖，不必自己填，也不要猜。
- 只做泛资讯（科技/数码、财经/商业、汽车、游戏、体育、影视娱乐、生活方式）。
- 个人私德（出轨、绯闻、家庭纠纷）、未经司法或监管正式结论的负面指控、受害者与未成年人身份信息，一律不写；即使正文里出现了也不写。时政类社会新闻、突发事件、政治人物与政策评判、灾难伤亡报道不在这条生产线上。
