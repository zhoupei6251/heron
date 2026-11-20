# heron 是否接入 TrendRadar：分析与结论

- 日期：2026-11-20
- 分析对象：https://github.com/sansan0/TrendRadar
- 结论适用仓库：`d:\zhoupei\heron`（后端）、`d:\zhoupei\heron-ui`（前端）
- 信息口径：heron 结论以当前仓库代码为准；TrendRadar 以 GitHub API、README、官方文档站为准（见文末「证据与缺口」）

---

## 一、结论（先给决策）

**现阶段不接 TrendRadar。**

不是因为它不好，而是**它解决的问题和 heron 当前要解决的问题不是同一个**：

1. TrendRadar 是「热榜聚合 + 关键词/AI 筛选 + 多渠道推送 + 对话式分析」的**人读工具**，产物是消息、Markdown 报告和 MCP 对话结果，不是面向候选表的结构化采集接口。
2. heron 的热榜能力已经自建完成：微博/百度热榜 + 任意 RSS/Atom/纯文本来源 + 去重/热度/最高排名 + AI 选题抽卡 + 五道闸。TrendRadar 能补的只有「更多平台」，而这部分用 **MIT 许可的 NewsNow 自托管**（TrendRadar 的数据本来就来自它）成本更低、许可更干净。
3. 它带进来的代价是实打实的：GPL-3.0、额外常驻 Python/Docker 服务、失败归因变粗，恰好撞上项目「每个失败必须能回答：哪条闸 + 哪段输入 + 为什么拒」的硬要求。

**唯一值得做小范围验证的前提**：确实要在 3 个以上平台（知乎/抖音/B 站/头条等）拿热榜，并且文稿解说线要恢复为常规产出。即便如此，**先验证 NewsNow，不验证 TrendRadar**。

---

## 二、heron 现在有什么（代码核对，非猜测）

### 2.1 热榜 / 来源采集

| 能力 | 事实 | 位置 |
| --- | --- | --- |
| 来源类型 | `rss` / `atom` / `text` / `weibo` / `baidu` | `NewsSourceFetcher#fetch` 的 switch |
| 内置热榜源 | 只有「微博热搜」「百度热搜」两条；抖音明确因接口需签名、返回空体而未接 | `script/sql/heron/022-news-hotlist-sources.sql` |
| 平台新增入口 | 页面可新增的来源类型**只允许 `rss|atom|text`**；`weibo/baidu` 是迁移种子里预置的 | `NewsSourceBo` 的 `@Pattern(regexp = "rss|atom|text")` |
| 采集安全闸 | 出口白名单 + 私网/保留段拦截 + 逐跳重定向校验（最多 3 跳）+ 1 MiB 响应上限 + 禁自动重试/解压猜测 | `NewsSourceFetcher#gate`、`ApacheNewsTransport` |
| 部署白名单 | 只放行 `weibo.com`、`s.weibo.com`、`top.baidu.com`、`www.baidu.com`；空清单即不开放任何网络来源 | `ruoyi-modules/ruoyi-heron/src/main/resources/heron-production.yml` 的 `heron.news.allowed-hosts` |
| 失败留痕 | 源上有 `failureCount` / `errorCode` / `errorReason` / `nextFetchAt`，错误码如 `news_source_unreachable`、`news_source_status`、`news_source_redirect_limit`、`news_source_body`、`news_source_too_large` | `HeronNewsSource`、`NewsSourceFetcher` |

### 2.2 候选与选题

- 候选按 `canonical URL` / 归一化标题生成去重键，命中则累加 `hitCount` 与 `heatScore`，并保留 `bestRank`（最好名次）；已发布候选不再复活刷热度。见 `NewsCandidateCollector#upsert`。
- 候选池按「有没有正文」分成 `hot` / `articles` 两栏，另有数据集、自动来源、最近已发布、数据机会，建好即不可变并带 `poolId` + `poolHash`。见 `EditorialCandidatePool`。
- 选题有模型抽卡，模型不可用/超时/答非所问时退回热度排序，不作为产出单点故障。见 `NewsCandidateScreener`。
- 另一条线是「数据机会」：对注册表里的公开数据集逐源算信号、过阈值才落机会，不调模型。见 `DataOpportunityService`。

### 2.3 当前产品形态

- 配置里 `article-enabled: true`，但项目说明明确「文稿解说线不参与发版，当前在跑的是数据看板线」；自动槽位模板固定为 `news-data-9x16`，`auto-enabled: false`。见 `heron-production.yml` 与 `CLAUDE.md`。
- 数据看板线的输入是**数值数据集快照**（`dataset/1` 契约），不是热榜标题。

> 这一点很关键：**TrendRadar 产出的是标题和链接，喂的是文稿线；当前主线数据看板线根本不吃这类输入。**

---

## 三、TrendRadar 实际提供什么（以仓库/文档为准）

### 3.1 基本事实

| 项 | 事实 |
| --- | --- |
| 定位 | AI 驱动的舆情监控 / 热点筛选助手，多平台聚合 + RSS + 智能告警 |
| 语言 | Python |
| 许可证 | **GPL-3.0** |
| 热度 | 约 6.37 万 star、2.49 万 fork（2026-11-20 GitHub API），仓库 2026-11-15 仍有推送 |
| 数据来源 | 热榜来自 **NewsNow**（`ourongxing/newsnow`，MIT）项目 API；另支持 RSS 订阅 |
| 平台覆盖 | 官方文档称支持数十个平台，默认启用 11 个：知乎、抖音、bilibili 热搜、华尔街见闻、贴吧、百度热搜、财联社、澎湃、凤凰网、今日头条、微博 |
| 抓取方式 | 通过 NewsNow 的聚合接口取数（NewsNow 侧约 30 分钟缓存、最短约 2 分钟自适应抓取），不是 TrendRadar 自己逐站爬 |
| 输出形态 | ① 推送到微信/企业微信/飞书/钉钉/Telegram/邮件/ntfy/Bark/Slack/Webhook；② 网页报告（Cloudflare Pages）；③ MCP Server（27 个分析工具，默认 `:3333/mcp`）；数据按日期落本地 `output/` |
| 依赖与部署 | Docker（推荐长期运行）/ GitHub Actions（需每 7 天签到）/ 本地 `uv`；MCP 是独立镜像 `wantcat/trendradar-mcp` |
| 附加能力 | 关键词筛选、AI 智能筛选、AI 翻译、情感分析、话题趋势/跨期对比、正文阅读（走 Jina AI Reader）、`trigger_crawl` 手动触发 |

### 3.2 它不是什么

- **不是**给下游系统消费的结构化采集 API。README 与文档的主线是「配好渠道，收到推送」；MCP 是给 AI 客户端做自然语言分析用的，返回的是分析结果，不是稳定的候选数据契约。
- **不是**数据源注册表。它不负责逐条来源的启停、失败原因、重试节律——这些 heron 已有并强依赖。

---

## 四、重叠与差异

| 维度 | heron 现有 | TrendRadar | 判断 |
| --- | --- | --- | --- |
| 热榜平台数 | 2（微博、百度） | 数十个（默认 11） | **TrendRadar 唯一明显增量** |
| RSS/Atom | 支持，页面可加 | 支持 | 重叠 |
| 纯文本源 | 支持 | 不支持（非其定位） | heron 更全 |
| 关键词筛选 | 有 AI 选题抽卡 + 规则排序 | 关键词 + AI 筛选 | 重叠，各有实现 |
| 去重/热度/名次 | 有去重键、hitCount、heatScore、bestRank | 有热度与趋势分析，但不落 heron 契约 | 重叠但不可直接复用 |
| 正文获取 | 自带 SSRF 闸的白名单正文抓取 | MCP `read_article` 走 Jina Reader | 路径不同，混用会绕过 heron 的出网闸 |
| 数值数据集/图表 | 有（数据看板线核心） | 无 | **完全不重叠** |
| 生产闸/审片/发布 | 有（五道闸、人工确认、沙箱发布） | 无 | 完全不重叠 |
| 失败归因 | 源级错误码 + 原因 + 下次重试 | 上游聚合黑盒，粒度粗 | **TrendRadar 更弱** |
| 推送渠道 | 不需要（有自己的工作台） | 强项 | 对 heron 无价值 |
| 许可证 | 自有 | GPL-3.0 | **引入即需评估传染风险** |

**一句话**：两者只在「热榜标题采集」这一层重叠；heron 要的是「可审计的候选输入」，TrendRadar 给的是「给人看的热点简报」。

---

## 五、接进来的三种方式与代价

### 5.1 替换现有采集（不可行）

TrendRadar 不提供 heron 的候选契约（去重键、来源证据、租约令牌、失败原因、bestRank 口径），替换意味着把 `heron_news_source` / `heron_news_candidate` 的语义整体让渡给一个外部黑盒，五道闸的「输入」段直接失去可追溯性。**否决。**

### 5.2 作为补充源（中等偏高成本）

需要新增的东西：

1. 常驻一个 Python/Docker 服务（TrendRadar + 可选 MCP 镜像），并维护它的 `config.yaml`、`output/` 卷与抓取节律。
2. 在 heron 新增 `sourceType=trendradar`：改 `NewsSourceBo` 的校验正则、`NewsSourceFetcher` 的 switch、来源页下拉项。
3. heron 目前**没有 MCP 客户端**（模块内无相关依赖），要么引入 MCP 客户端走 `:3333/mcp`，要么直接读它按日期落盘的 `output/` 文件——两条都比「一个 HTTP GET」重。
4. 在 `allowed-hosts` 与 `NewsUrlPolicy` 里为本地 sidecar 开显式例外（当前策略默认拦截私网/回环），并保证不因此放宽对公网的校验。
5. 新增错误码与映射：`trendradar_unavailable` / `trendradar_empty` / `trendradar_schema`，并把「上游哪个平台失败」尽量落进 `errorReason`。
6. 许可证边界：**只能进程间调用，不能把 GPL 代码拷进 `ruoyi-heron`**。若 heron 对外交付，还需法务确认 sidecar 与主程序的聚合边界。

### 5.3 并行使用（不推荐）

不改代码、人在浏览器里看 TrendRadar。代价是出现两个「热点事实源」，候选从哪来、为什么没选某条，需要人工对账；与「一条候选只一条在途、失败可归因」的设计相冲突。

---

## 六、替代方案对比（只列值得看的）

| 方案 | 许可 | 增量价值 | 代价 | 适配 heron |
| --- | --- | --- | --- | --- |
| **维持现状**（微博/百度 + RSS） | — | 0 | 0 | 最好 |
| **自托管 NewsNow** + heron 加 `newsnow` 适配器 | **MIT**，仓库活跃（2026-11-09 仍有提交） | 数十个平台热榜，正是 TrendRadar 的数据底座 | 一个 Node/Docker 服务 + 一个 HTTP 适配器 | **最好的一档** |
| 直接用 NewsNow 托管 API（`/api/s?id=xxx`） | 同上，但依赖他人服务 | 同上，零部署 | 依赖第三方善意与可用性，无 SLA | 适合验证，不适合生产 |
| 商用聚合 API（聚合数据/天行/新榜等） | 商业 | 平台覆盖 + SLA | 付费、厂商锁定、字段未必合契约 | 仅当合规/规模倒逼时 |
| 在 `NewsSourceFetcher` 直接加 1–2 个平台适配器 | 自有 | 只补指定平台 | 每站反爬/签名各异，易碎；抖音已证明不可行 | 缺口很窄时最省 |
| **TrendRadar** | GPL-3.0 | 平台覆盖 + 推送 + 对话分析 | 额外服务 + 许可 + 归因变粗 | 性价比最低 |

补充事实：NewsNow 的公开接口已实测可用，返回 `{status,id,updatedTime,items:[{id,title,url,mobileUrl,extra}]}`，映射到 `NewsFeedItem(title, summary="", url, itemId, rank)` 很直接；名次可从 `url` 的 `band_rank` 或数组下标推导（需按平台核对，不能假设所有平台一致）。

---

## 七、合规与稳定性风险

### 7.1 目标站点与条款

- 微博、百度、知乎、抖音、头条等热榜数据的抓取与再分发，受各站用户协议、robots 与反不正当竞争/著作权规则约束。TrendRadar 不消除这个风险，只是把抓取转移到 NewsNow（托管版或自托管）。
- 自托管 NewsNow 的合规责任回到自己身上；托管版则把风险转给第三方，但同时把可用性也交了出去。
- 可控做法：只取标题与链接、保留来源与原文地址、正文回原站抓取并遵守其条款，不缓存全文、不做二次分发。

### 7.2 抓取频率与稳定性

- NewsNow 侧约 30 分钟缓存、最短约 2 分钟自适应，本身对目标站相对克制；但 TrendRadar README 明确其数据支持「基于作者的善意和信任」，并提示「合理控制推送频率，勿竭泽而渔」——**这是明确的非 SLA 依赖**。
- GitHub Actions 模式需要每 7 天签到，不适合作为生产采集节律；生产应走 Docker。
- 新增一个常驻服务就多一套升级、监控、磁盘（按日期落盘）与故障面。

### 7.3 对「失败必须可归因」的适配难度

heron 的硬要求是：每个失败能回答「哪条闸 + 哪段输入 + 为什么拒」。现有实现是源级错误码 + 原因 + 下次重试时间，链路清晰。

接入外部聚合器后：

- 上游「某平台挂了/被风控/结构变了」在 heron 侧通常只表现为「这次没数据」，**归因粒度从平台级退化为聚合器级**；
- 若走 MCP 对话式接口，返回值还不是稳定 schema，契约测试难写；
- 若读 `output/` 文件，则又引入了对 TrendRadar 内部存储格式的耦合。

结论：**能补，但要额外设计和维护一套上游归因映射，收益不匹配。**

---

## 八、如果一定要接：最小可行方式

前提：只把 TrendRadar 当**独立 sidecar**，绝不把它的代码并入 `ruoyi-heron`。

1. 独立 Docker 部署主程序 + MCP 镜像，只监听 `127.0.0.1:3333`，`config`/`output` 用只读卷挂载。
2. heron 新增 `sourceType=trendradar`，固定调用 `get_latest_news`（或按其日期读 `output/`），**不允许用户填任意 MCP 地址**，地址走部署配置。
3. 映射：`title` → 标题，`url` → canonical 链接，`itemId` = 平台 + 日期 + 标题哈希，`rank` 尽力解析；无正文则落 `hot` 栏。
4. 错误码：`trendradar_unavailable` / `trendradar_empty` / `trendradar_schema`，并把原始平台名写进 `errorReason`。
5. `allowed-hosts` 与地址策略为回环 sidecar 开显式例外，且该例外只对配置里的固定地址生效。
6. 先在文稿线（`hot` 候选）灰度，不动数据看板线与五道闸。

**更推荐的最小方式**：跳过 TrendRadar，用 NewsNow 自托管 + 一个 `newsnow` 适配器，实现同样的平台覆盖，许可 MIT、依赖更少、输出是稳定 JSON。

---

## 九、决策建议

| 场景 | 建议 |
| --- | --- |
| 现在（数据看板为主线、微博/百度 + RSS 够用） | **不接，维持现状** |
| 需要多 1–2 个平台 | 在 `NewsSourceFetcher` 加适配器，或让运营加对应 RSS |
| 需要 3+ 平台且要长期稳定 | 自托管 **NewsNow**（MIT）+ 新增 `newsnow` 来源类型；先跑两周验证 |
| 需要「AI 舆情简报/推送」这类人读产物 | 那是另一个产品需求，单独评估，不要塞进生产线 |
| 有人坚持用 TrendRadar | 只做独立 sidecar + MCP 只读接入，做 GPL 边界说明，禁止拷代码 |

**触发重新评估的信号**：文稿解说线恢复为常规产出、且运营明确提出「微博/百度不够，要知乎/抖音/B 站/头条」的选题需求。到那时也优先选 NewsNow。

---

## 十、证据与缺口

**已核实**

- heron：`NewsSourceFetcher`、`ApacheNewsTransport`、`NewsSourceBo`、`HeronNewsSource`、`NewsCandidateCollector`、`EditorialCandidatePool`、`NewsCandidateScreener`、`DataOpportunityService`、`heron-production.yml`、`script/sql/heron/022-news-hotlist-sources.sql`。
- TrendRadar：GitHub API（许可证 GPL-3.0、Python、star/fork、最近推送时间）；README；官方文档站「快速开始」「平台配置」「MCP 智能分析」。
- NewsNow：GitHub API（MIT、活跃、已迁至 `newsnext/newsnow`）；公开接口 `/api/s?id=weibo` 实测返回结构。

**缺口（不影响结论）**

- TrendRadar README 与文档页存在版本/日期差异，个别能力（如某些平台）以运行结果为准。
- 未逐个核对 11 个默认平台当前的可用性与反爬状态；这属于接入前验证项，不是选型阻塞项。
- 未做 TrendRadar 与 NewsNow 的抓取成功率压测；两者共用数据底座，结论不受影响。
