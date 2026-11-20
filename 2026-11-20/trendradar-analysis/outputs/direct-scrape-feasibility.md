# 43 个热榜平台：heron 直连可行性实测

> 实测时间：2026-11-20。环境：本机 Windows + PowerShell 7，`User-Agent` 伪装 Chrome 131，**不带 Cookie、不带签名、不走浏览器渲染**。
> 目的：回答「43 个平台的抓取和反爬，heron 自己干行不行、值不值」。本轮不写业务代码。

## 一、实测结果

| 平台 | 请求地址 | 状态 | 返回 | 条数 | 前提 |
|---|---|---|---|---|---|
| 微博 | `https://weibo.com/ajax/side/hotSearch` | 200 | JSON | 51（`data.realtime`） | Referer 必须是 `https://weibo.com/`；用接口自身当 Referer 直接 403 `{"error":"Forbidden"}` |
| 知乎（api 域） | `https://api.zhihu.com/topstory/hot-list` | 200 | JSON | 30（`data`） | 无 Cookie、无签名 |
| 知乎（www 域） | `https://www.zhihu.com/api/v3/feed/topstory/hot-lists/total?limit=50` | 401 | JSON 错误体 | 0 | `{"error":{"code":101,"name":"AuthenticationError","message":"身份未经过验证"}}`，必须登录 Cookie |
| 抖音（老接口） | `https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/` | 200 | JSON | 50（`word_list`） | 无签名 |
| 抖音（web 主接口） | `https://www.douyin.com/aweme/v1/web/hot/search/list/` | 200 | JSON | `data.word_list` 存在 | 本次未带 `a_bogus` 也通了；历史上该接口按版本要求签名，属不稳定项 |
| B 站 | `https://api.bilibili.com/x/web-interface/search/square?limit=10` | 200 | JSON | 10（`data.trending.list`） | 无 Cookie、无 wbi 签名；条数受 `limit` 控制 |
| 头条 | `https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc` | 200 | JSON | 50（`data`） | 无 Cookie、无签名 |
| 澎湃 | `https://cache.thepaper.cn/contentapi/wwwIndex/rightSidebar` | 200 | JSON | `data.hotNews` 等分组 | 无 Cookie、无签名 |
| 财联社 | `https://www.cls.cn/v3/depth/home/assembled/1000` | 200 | JSON 错误体 | 0 | `{"errno":"10012","msg":"签名错误"}`，**必须算 sign** |
| 财联社 | `https://www.cls.cn/nodeapi/telegraphList` | 404 | HTML | — | 端点已不存在 |
| 对照组 NewsNow | `https://newsnow.busiyi.world/api/s?id=zhihu` | 200 | JSON | 20（`items`） | 聚合层已处理好上述差异 |
| 对照组 NewsNow | `https://newsnow.busiyi.world/api/s?id=cls-hot` | 200 | JSON | 13（`items`） | 同上 |

关键观察：

1. **7 个平台里 5 个能直连拿 JSON**，不需要签名或 Cookie；但每一个都有隐含前提（Referer、特定子域、老端点、`limit` 参数）。
2. **同一平台不同入口差异极大**：知乎 `api.zhihu.com` 通、`www.zhihu.com/api/v3` 要登录；财联社换个路径就 404，另一个路径明确报「签名错误」。
3. **微博的 403 证明风控是真实存在的**：只改一个 Referer 头就从 403 变 200。这类规则不公开，随时会加严。
4. 这些端点**都不是对外承诺的开放 API**，是各站网页端自用接口。今天能跑不等于下个月能跑。

## 二、三类平台与维护频率

| 类型 | 代表 | 特征 | 维护频率（经验判断） |
|---|---|---|---|
| A. 一次解析、相对稳定 | 微博、百度、头条、B 站、抖音老接口、澎湃、知乎 api 域 | GET + JSON，无签名，字段简单 | 每个端点每 3–6 个月可能遇到一次字段/风控调整；改版当天挂，需要当天跟进 |
| B. 签名/加密参数会变 | 财联社（`sign`）、抖音 web 主接口（`a_bogus`/`X-Bogus`）、快手 | 必须按前端算法生成参数，算法随版本变 | 数周到数月一次算法变更；每次都要重新逆向或补 JS 运行时 |
| C. 必须浏览器渲染或强风控 | 小红书、淘宝/京东类电商榜、部分需登录 Cookie 的源 | 依赖 JS 执行、验证码、登录态 | 纯 Java 单进程基本不可行；要么上 Playwright/JCEF，要么放弃 |

NewsNow 的 43 个源里，A 类约占一半，B/C 类占另一半——这正是它要跑 Node 并带浏览器依赖的原因。

## 三、工程侧的结构差异

### 1. 解析器数量

现状：[NewsSourceFetcher.java](file:///d:/zhoupei/heron/ruoyi-modules/ruoyi-heron/src/main/java/org/dromara/heron/service/impl/NewsSourceFetcher.java#L68-L76) 只有 5 类解析：`rss/atom`、`text`、`weibo`、`baidu`、`newsnow`。
全量直连要写 43 个解析器，每个平均 50–150 行，加上单测与样本固化，是一次持续投入，不是一次性开发。

### 2. 出网闸是否需要重做

不需要。[ApacheNewsTransport.java](file:///d:/zhoupei/heron/ruoyi-modules/ruoyi-heron/src/main/java/org/dromara/heron/service/impl/ApacheNewsTransport.java#L68-L72) 已经固定 `User-Agent` + 同源 `Referer`，逐跳执行、禁自动重定向；[NewsUrlPolicy.java](file:///d:/zhoupei/heron/ruoyi-modules/ruoyi-heron/src/main/java/org/dromara/heron/service/impl/NewsUrlPolicy.java#L82-L89) 的白名单与地址策略对任意平台通用。
真正要加的是：按站点区分 Referer/UA（微博已证明 Referer 是开关）、少量站点要 Cookie，以及白名单从现在的 9 条扩到 60+ 条。

### 3. 并发与频控

现有节奏是 30 秒轮询、单源 600 秒、每批 2 个源（`NewsSourceRunner`）。
43 源 × 600 秒 ≈ 每分钟 4.3 次请求，本地并发压力很小。
真正的风险不是并发，而是**同一出口 IP 被单站封禁**——一旦发生就得引入代理池，这是纯新增的运维成本。

### 4. 失败归因是否兼容

**兼容，不拉扯。** 现有错误码已经覆盖直连的主要失败面：

| 失败面 | 现有错误码 |
|---|---|
| 域名不在白名单 | `news_source_host` |
| 地址结构非法 | `news_source_url` / `news_source_scheme` / `news_source_port` |
| 连不上 / DNS 失败 | `news_source_unreachable` |
| 非 200 / 重定向异常 | `news_source_status` / `news_source_redirect_limit` |
| JSON/HTML 解析失败 | `news_source_body`（XML 走 `news_source_xml`） |
| 响应过大 / 编码不支持 | `news_source_too_large` / `news_source_encoding` |

只需要补两类语义（可用现有码承载，也可各加一个码）：
- **签名失效**：财联社 `errno=10012` 这类，归 `news_source_platform` 更准确；
- **风控/需登录**：401/403/验证码，归 `news_source_status` 并在 reason 里写明。

### 5. 风控对抗与改版跟进

- 纯 Java 单进程**没有 JS 运行时**，遇到 `a_bogus` 这类要么补 GraalJS/Node 子进程，要么持续逆向；两者都会把「一个采集模块」变成「一个爬虫子系统」。
- B/C 类平台需要浏览器内核，等于在 heron 里再塞一个 Playwright 依赖。
- A 类平台也不是零维护：字段改名、加风控头、换子域，任何一次都要当天修，否则该源持续报错。

## 四、结论

**能，但不值。** heron 直连 43 个平台，本质上是在内部长期养一个爬虫团队：43 个解析器、签名逆向、代理池、改版跟进。自托管一个 NewsNow 只是运维一个 Node 服务，成本低一个数量级。

三种路线的代价：

| 路线 | 开发成本 | 持续成本 | 风险 | 判断 |
|---|---|---|---|---|
| 全量直连 | 43 个解析器 + 2 套 JS 运行时 | 每周改版跟进、签名逆向、代理池 | 单站封 IP、算法变更、法律与 ToS | 不推荐 |
| 全走聚合 | 近乎为零（已完成） | 一个聚合服务可用性 | 单点依赖；自托管可消除 | 可接受，建议自托管 |
| 混合 | 直连 3–6 个 A 类平台 | 少量改版跟进 | 低 | **推荐** |

推荐落法：

1. **保持已跑通的核心直连**：微博、百度已经在 `news_source` 里直连，不动。
2. **只对 A 类平台按需追加直连**：头条、B 站、澎湃、知乎 `api.zhihu.com` 都属于「GET+JSON+无签名」，加一个解析器即可，收益明确时再加。
3. **长尾（B/C 类）交给 NewsNow**：财联社、小红书、电商榜这类不要自己碰。
4. **自托管 NewsNow**：用 `news.heronaigc.top` 承载聚合层，把「单点依赖公共实例」变成「依赖自己的服务」，同时保留 `newsnow-base-url` 一键切回公共实例的能力。

一句话：**43 个全自己干是负收益；核心几个自己干 + 长尾聚合、聚合层自托管，才是这套系统该有的形状。**
