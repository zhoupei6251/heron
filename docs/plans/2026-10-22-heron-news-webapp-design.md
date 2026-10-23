# heron — 新闻视频网页生产线 设计文档

日期：2026-10-22 · 状态：**设计待批准**（未开工，未建仓库）
出处：本设计由 harness-factory 新闻域的一次头脑风暴产生，目标是在浏览器里完成
「热点进 → 成片出 → 人确认 → 发出去」。

> **技术栈已被 v2 修订**：§2 形状 / §3 选型 / 目录结构见
> [`2026-10-22-heron-stack-v2-design.md`](./2026-10-22-heron-stack-v2-design.md)
> （NestJS 取代 Fastify、Node 锁 24.22.1、Tailwind + Element Plus 采用、pnpm workspace 布局）。
> N1–N6、五道闸、状态机、数据模型语义、分期判据与 §8 接缝仍以本文为准。

---

## 0. 一句话与不做的事

**一句话**：一台自己部署的机器，定时收热榜、由人认领、其余全自动跑到底、发布前必须人点确认。

第一期**明确不做**（这些是讨论中被否决或推迟的，写下来防止以后被"顺手加上"）：

- 不做微服务、不引 Redis / Postgres / BullMQ —— 单 runner 串行是物理事实（一次渲染 ≈ 60s 墙钟）
- 不上 Docker（第一期）
- 不做 Next.js（会把"版式与前端共用 Vue 栈"这条最省的结构拆成两副世界观）
- 不做多租户 / 多人协作鉴权
- 不沿用 31 个 pack：版式重新设计（代价见 §6）
- 不做"无人值守自动发布"：发布动作必须由人在网页上确认后才执行
- 不抽象"AI 创作平台"：只留接缝，不留抽象层（接缝定义见 §7）

---

## 1. 决策记录（六项）

| # | 决定 | 否决掉的选项 | 后果 |
|---|---|---|---|
| N1 | 全链路上网页（采集→候选→认领→写稿→核查→渲染→审核→待发布→确认→发布） | 只做最后一公里 / 看板+出片 / 只做订阅流 | 引擎必须整体搬迁；harness-factory 新闻域降级为 oracle + 只读参考 |
| N2 | 发布 = **人在网页点确认后，服务端自动执行** | 全自动直发 / 按风险分域 / 发布不进网页 | cookie 必须存在服务端；发布需幂等键；失败必须可见 |
| N3 | **全部重写 TypeScript** | 复用 Python 引擎 / 遥控器模式 / 先进容器 | 出现"旧已验、新未验"空窗期 → 用 oracle 对账压住（§6） |
| N4 | **自己部署 + 私网组网（tailscale 优先，不把端口挂公网）** | Cloudflare Pages+Workers、云函数、公网穿透 | 运行时限制消失（Node≥22 / ffmpeg / Chromium 都在这台机器上），出口 IP 是自己家宽，风控风险低；代价是机器关掉就停 |
| N5 | 输入 = **热榜定时抓取自动出候选 + 人认领才生产** | 定时全自动生产 / 完全手动 / 统一收件箱 | 未认领的候选只花文本成本；渲染与图生配额由人掌握 |
| N6 | 版式 = **重新设计一套新版式** | 全量搬 31 pack / 只搬主力 4~6 个 / MVP 单 pack | 内容层没有 oracle → 视觉质量只能靠几何闸兜底，且尺子必须先建、版式后写 |

发布那一跳单独一条：**用 Playwright 自研上传，`sau` 只当参考实现**（保持全 TS）。
副作用：抖音页面自动化由本项目独立承担维护，cookie 与登录态存储成为本项目唯一需要额外盯安全的部位。

---

## 2. 形状

两个进程，不做服务拆分：

- `server`：Node 22 + Fastify，静态托管前端构建产物 + API + SSE 进度
- `runner`：单 worker 子进程，串行领任务（渲染 / 上传 / 抓取）

数据两处：一个 SQLite 文件（状态机 + 台账）+ `data/videos/<task-id>/`（中间产物与成片，含保留策略配置）。

### 状态机（系统主干）

```
candidate → claimed → scripting → factcheck → rendering
          → review → approved → publishing → published
                                      ↘ failed(带原因)
```

`factcheck` 与 `review` 是**闸**，不是步骤：核查不过不进 rendering（省 60s 与一次图生配额）；
人没点确认不进 publishing。每次转移落库并附原因字符串。

HTTP 请求永不等待渲染：认领后立刻返回 task id，进度走 SSE。

---

## 3. 选型

| 层 | 选 | 理由（及否决对象） |
|---|---|---|
| 前端 | Vue 3 + Vite，无组件库，原生 CSS | harness-factory 已是此栈；引 React 等于两套世界观。**不引 Tailwind**：版式几何闸要精确控制尺寸与对比度，框架默认 token 会跟实现对打 |
| 版式层 | **与前端同一个 Vue 栈**：SFC → `vite build` → headless 截图 → ffmpeg 合成 | 复用本仓库已验证通路（`src/web → dist/`）；前端与渲染共用一套栈是本项目最大的一笔省心智 |
| 后端 | Fastify 5 + TS | 要 schema 校验 + 结构化日志，且这两样在本案里是**审计要求**不是舒适品。Nest 的 DI 单人项目无收益；Koa/Express 要手写上述两样；Next.js 见 N/A 否决 |
| 存储 | better-sqlite3 + 手写迁移 SQL，不引 ORM | 主干就是十态一张表，ORM 抽象收益 < "看不见生成的 SQL" |
| 队列 | 进程内 + DB 租约（`status` + `lease_owner` + `heartbeat`） | 单人单机串行，分布式队列是净负担 |
| LLM | 单一 client 模块 + OpenAI 兼容 base URL，密钥走环境变量 | 唯一 provider 抽象点，别处不许散落调用 |

目录：

```
src/shared/    zod schema + 状态机类型 + 契约判定类型（前后端同一份 = 真正的"全栈"）
src/server/    Fastify：API + SSE + 静态托管
src/web/       Vite + Vue：四个页面 + 版式 SFC 同目录
src/runner/    队列、HyperFrames 渲染、Playwright 发布
src/capabilities/news/   新闻这一个 capability（见 §7）
```

⚠️ 两个必须第一天就钉的运行时坑：

1. `engines.node: ">=22"` **且启动自检断言 `process.version`**。harness-factory 的 `engines` 写的是 `>=20.9.0`，而渲染器实际要求 ≥22；这个不一致在 2026-10-22 真实挡过一次（`HyperFrames requires Node.js >= 22 (current: 20.9.0)`）。
2. 从 harness-factory 抄 `package.json` 时**不要带上 `typescript: ^7.0.2`**，heron 自己锁稳定大版本再验证。

---

## 4. 数据模型

```
sources       热榜源、抓取频率、上次抓取时间
candidates    标题/摘要/命中词/热度/norm_key            ← 认领前只有文本
shots         逐镜 JSON（title/body/onscreen/kicker/image/person/namedSubject）
evidence      逐句出处表（哪句 / 哪个源 / 判定）        ← 核查闸的数据源
tasks         stage / lease_owner / heartbeat / attempts / error_reason
renders       版本、mp4 路径、联络表、时长、ContentProducer、花费
publishes     render_id / 幂等键 / declaration / 回执 / 状态
```

`shots` 就是今天 `t003-scenes.json` 的形状，落库不落文件；`evidence` 是
`routes/news/evidence/*.md` 那张逐句出处表的数据库版本。

> 本节是草表。落库后的表名与列以 `2026-10-22-heron-stack-v2-design.md` §7 为准，
> 其中三处修正（删 `cost`、`ContentProducer` 移出 `renders`、`hit_words` 换成台账真实列）
> 已在那节写明理由。状态机、五道闸与"确认三样"的语义仍以本节为准。

---

## 5. 五道闸

| 闸 | 位置 | 拦什么 | 来源 |
|---|---|---|---|
| 去重 | candidate 建立时 | `norm_key` 已 published → 不许再生成生产任务 | 本仓库台账 `ledger.py` |
| 契约（8 条） | factcheck → rendering | 填不出的变量、空正文（配音会停机）、title 与 onscreen 同句、imageGrade/person 形状 | `check_scene_contract.py` |
| 视觉（几何） | rendering → review | 对比度、填幅、占位 | `layout_selfcheck.py` 17 条不变量 + `audit_pack_contrast.py` |
| 合规三件套 | approved → publishing | ② mp4 元数据 `AIGC` + ③ 声明 `内容由AI生成` 必须成立；① 角标按姿态文件 | `check_publishable.py` |
| 人工确认 | review → approved | 唯一的非代码闸 | 本案 N2 |

**人工确认必须记三样：确认人、确认时间、当时那张联络表的版本指纹。**
少了第三样就会出现"人审的是 A、系统发的是 B"（重渲了但确认没重做）。这一条会让"改一版重新确认"
成为必须动作，稍微烦，但它是防错发的唯一硬手段。

密钥：cookie 不进仓库、不进 DB；落工作目录之外（`~/.heron/state/douyin/`，目录权限收紧），
DB 只存**路径 + sha256 指纹**。发布前断言指纹匹配，不匹配直接红并要求重新登录——
**绝不静默用一份来源不明的旧登录态去发东西**。

---

## 6. 验证：把 oracle 做成一个命令，不是一种态度

- `fixtures/`：从 harness-factory 导出 golden（输入 → 判定结果），覆盖契约 8 条的正反例、
  `norm_key` 计算、三件套判定、版式变量 resolve 的"填不出清单"。
- `npm run oracle`：同一 fixture 同时跑 TS 与 Python，**diff 非空即红**。
- **新版式没有 oracle，所以尺子先建、版式后写。** 顺序反了就会得到一套"看着不错但无量化证据"
  的版式，而且届时补的尺子一定会被补成"刚好放得过现有版式"的形状。
- **断言密度是接真账号的门票**：Python 侧现为 253（path_b）+ 41（curate）+ 17（layout）。
  TS 侧未达同等密度前仓库标 `experimental`，并且**把这条治理规则做成界面约束**——
  门禁位未过则"发布"按钮置灰。闸门点不动，才算闸门。
- ⚠️ 搬尺子之前要先解决它自带的一处冲突：harness-factory 现在
  `audit_pack_contrast.py` 退出码 1 与断言 `t_no_pack_has_off_palette_after_repair` 绿色互相打脸，
  导致硬规则 6 字面上不可满足。**别把这个"红绿同时成立"带进 heron。**

---

## 7. 界面与失败处理

四个页面 + 设置页：

| 页面 | 干什么 | 关键约束 |
|---|---|---|
| 候选流 | 热榜候选按热度排，人点「认领」 | 此页不花钱：无稿、无渲染 |
| 生产台 | 进度（SSE）+ 稿件与分镜可编辑 + 核查单视图 | 编辑后必须重跑契约闸，不许手改绕过 |
| 审片台 | 联络表 + 成片预览 + 逐镜文案 + 「确认」 | 确认绑定联络表版本指纹 |
| 发布台 | approved 队列、幂等状态、失败原因、发布回执 | 重试前必须先查外部真实状态 |

设置页：AIGC 姿态（①/②/③）、信源与频率、cookie 状态与指纹、磁盘保留策略。

失败四条，都不许静默：

1. 每个 stage 失败落 `error_reason` = **哪条闸 + 输入片段 + 为什么拒**，界面展开全文。
   （本仓库法则：报不出原因的拒绝等于没有拒绝。）
2. **发布失败重试前先确认它是不是其实已经发出去了。** 上传成功但回执丢失 → 直接重试 = 发两遍。
   顺序必须是：查平台列表/回执 → 判定 → 再决定重发或只补记录。
3. 渲染失败保留部分产物（截图、日志、`check.json`）供诊断，不自动清理。
4. **LLM 产出不合契约时不自动改稿**——退回 `scripting`，把不过的几条列给人。
   自动改写会把闸门降级成建议。

重启恢复：租约过期任务回到可领取，`attempts+1`；超过 N 次标 `failed` 并停止自动重试，等人处理。
自部署必然重启，这不是异常分支。

---

## 8. 为"第二个 capability"留的接缝（只留接缝，不留抽象）

现在只有 news 一个 capability，但三条接缝几乎不额外花工时：

1. **capability 注册表**：`src/capabilities/news/` 自己声明 pipeline、闸门、版式、提示词；通用层不认识"新闻"这个词。
2. **表分层**：`tasks / renders / publishes` 里不许出现任何新闻字段；`shots`、`evidence` 是 news 外挂表。
3. **发布适配器按平台切，不按内容类型切**：抖音那段代码只知道"一个 mp4 + 标题 + 描述 + 合规声明"，不知道自己在发新闻。

这三条做到，将来加"图文卡片""口播带货""合集"是新增一个 capability 目录；做不到，就会变成
"一个通用平台，但只跑得通新闻"。抽象本身仍然等第二次出现再抽（harness-factory R8）。

---

## 9. 分期

| 期 | 内容 | 出口判据 |
|---|---|---|
| P0 | Fastify + Vite + SQLite + 队列 + SSE；`engines>=22` 与版本断言 | 空 capability 也能跑完一条假任务 |
| P1 | news capability：稿 → 契约闸 → 渲染（**不含发布**） | TS 出片与 Python 出片人工比对一致；oracle 全绿 |
| P2 | 审片台 + 人工确认闸 + 联络表版本指纹 | 改一版未重确认时系统拒绝进入 `approved` |
| P3 | Playwright 抖音上传 + 幂等 + 失败先查状态 | 用测试号或草稿模式验，**不碰真发布** |
| P4 | 热榜抓取 + 候选 + 去重 + 认领 | 造一条重复新闻，去重闸拦住并给出原因 |
| P5 | 新版式（几何闸先行） | 17 条不变量的等价物全绿 |

**退役评审**：P1 的 oracle 与 P5 的尺子都达标之后，harness-factory 新闻域才降级为只读参考；
在那之前它是在用的 oracle，不许删。

---

## 10. 未决 / 风险登记

- **实时口径要按住**：一次渲染 ≈ 60s，加图生与核查，诚实承诺是**分钟级**出片，不是秒级直播式。
- 抖音页面自动化 = 独立维护负担，页面改版即失效；P3 之前不接真账号。
- 出口 IP 用家宽：换网络 / 重拨导致 IP 变化时，登录态可能失效，需要显式重新扫码流程。
- 磁盘：mp4 几十 MB × 每天若干条，保留策略必须在 P0 就有，否则三个月后先炸磁盘。
- 本机常开 = 无高可用；这是接受的代价（N4），不是待修的缺陷。
- Cloudflare Workers 方案被否的技术原因留档：Worker 是 V8 isolate，不能 spawn 子进程、无 ffmpeg /
  Chromium，且数据中心出口 IP 对平台风控不利。以后再有人提"上 serverless"，先读这一条。
- 命名：`heron` 已知最重的同名是 Twitter 的流计算引擎 Heron（已归档停维）；仓库 description
  承担"报"的语义，名字本身只承担"等待—出手"的节奏。
