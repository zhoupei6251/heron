# Verification — 借鉴 RuoYi-Vue-Plus 四项治理能力 + 七牛对象存储

**Task tier**: Tier 2+（> 3 文件、动 schema、跨 api / worker / web / db；合并文档 `docs/plans/2026-10-28-admin-borrows-and-object-storage.md` 已确认）
**Date**: 2026-10-29
**Branch**: `dev`（未提交、未推送——按项目规则等用户点头）
**Author**: Claude Fable 5（主 Agent 直做，A→B→C→D 四批依次）

## 1. 目标

把 RuoYi-Vue-Plus 里值得借的四件搬进 heron，并按用户要求补七牛 Kodo（华南-广东 `z2`）适配：

1. **对象存储适配器**（`packages/storage`）：local / qiniu 两驱动，环境变量选，运行时不切。
2. **租户套餐与额度闸**：free / standard / enterprise 三档，席位 + 月度渲染/发布计数。
3. **平台级 typed settings**：`platform_settings` 表 + 平台台页面 + worker 解析链（租户 > 平台 > env > 默认）。
4. **在线会话 + 逐条强退**：`sessions` 表，登录落行，每次请求复查，`GET/DELETE /manage/sessions`，租户台页面。

**AK/SK 只活环境变量**：`.env`（gitignore + dockerignore）、`.env.example` 只留占位、`platform_settings` 表里没有任何密钥、镜像里没有。

## 2. 改动

### 2.1 按批次

| 批次       | 主要落点                                                                                                                                                                                                                           |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A 存储     | `packages/storage/**`（新包，6 源/类型 + 3 测）、`0008_artifact_objects.sql`、`renders` 三列、`pipeline` 上传、`cleanup` 删远端、`ArtifactService` 302、`.env.example` / `deploy/docker-compose.yml`                               |
| B 套餐     | `PLAN_IDS` / `PLAN_LIMITS`、`domain/plan.ts`、`0009_tenant_plan.sql`、`quota.ts`、`PATCH /platform/tenants/:id/plan`、`PlatformTenantsView.vue`                                                                                    |
| C 平台设置 | `0010_platform_settings.sql`、`platform-settings.ts`、`GET/PUT /platform/settings`、`PlatformSettingsView.vue`、`worker/model.ts` 解析链（`scripting.ts` + `proto.ts`）、菜单四处                                                  |
| D 会话     | `0011_sessions.sql`、`sessions.ts` + `sessions.test.ts`、`JwtPayload.sid` / `AuthUser.sessionId` / `SessionSchema`、`auth.service` 登录落行 + 复查 + 登出语义、`GET/DELETE /manage/sessions`、`SessionsView.vue`、`session.manage` |

### 2.2 变更规模

```
git diff --shortstat  →  57 files changed, 1973 insertions(+), 168 deletions(-)
git ls-files --others --exclude-standard | wc -l  →  32 个新增文件
```

新增的 32 个：`packages/storage` 11 个（6 源/类型 + 3 测 + `package.json` / `tsconfig.json`）、
迁移与快照 8 个（`0008`–`0011` 各一条 sql + 一份 snapshot）、
`quota` / `platform-settings` / `sessions` 各 2 个（源 + 测）、`schema/sessions.ts`、
`domain/plan.ts` + 测、两份页面、计划文档、本文件。

## 3. 验证断言（逐条核）

| #   | 断言                                                                                                            | 结果 | 证据                                                                                                         |
| --- | --------------------------------------------------------------------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------ |
| 1   | `HERON_STORAGE` 未配 = 接存储之前的行为                                                                         | ✅   | `packages/storage` 19 测 + `artifact.e2e` / `cleanup` / `pipeline` 断言只加不改                              |
| 2   | `qiniu` 缺任一 `QINIU_*` → 启动即失败并点名缺项                                                                 | ✅   | `packages/storage/src/config.test.ts`                                                                        |
| 3   | `objectKey()` 稳定、对租户/任务/版本/kind 敏感、不含 `..` 与反斜杠                                              | ✅   | `key.test.ts`                                                                                                |
| 4   | qiniu `put` 用 `scope: bucket:key` 令牌、`Zone_z2`、`useHttpsDomain`；取址走 `privateDownloadUrl`               | ✅   | `qiniu.test.ts`（注入 fake 客户端，不打网络）                                                                |
| 5   | `ArtifactService`：本地有 → `sendFile`；本地无但有 key → 302 + `Cache-Control: private, no-store`；都无 → 404   | ✅   | `apps/api/src/manage/artifact.e2e.test.ts`                                                                   |
| 6   | 上传失败 → 任务 failed、`error_reason` 带上传错误片段、`renders` 无半条记录                                     | ✅   | `apps/worker/src/pipeline.test.ts`                                                                           |
| 7   | 清理先删远端再删本地；远端失败计入 `objectsFailed` 且下轮重试同一 key                                           | ✅   | `apps/worker/src/cleanup.test.ts`（fake store）                                                              |
| 8   | 额度：free 第 4 个成员被拒并给出「3/3 席位」；第 101 次渲染 / 第 31 次发布被拒且任务带原因                      | ✅   | `packages/db/src/pg/quota.test.ts`、`pipeline.test.ts`                                                       |
| 9   | 改档落 `platform_audit_logs`，`{from,to}` 可查                                                                  | ✅   | `platform.test.ts`、`console.e2e.test.ts`                                                                    |
| 10  | 平台设置写入即刻生效；worker 读取顺序 租户 > 平台 > env > 默认                                                  | ✅   | `platform-settings.test.ts`、`model.test.ts`（三条链各一例）                                                 |
| 11  | 会话：登录有行；踢单条 → 该票立刻 401、同用户另一条仍 200；改口令 → 全部 401                                    | ✅   | `sessions.test.ts`、`auth.service.test.ts`、`console.e2e.test.ts`（新用例「在线会话：成员只看得见自己的…」） |
| 12  | 没有 `sid` 的旧票 → 401（强制重登一次换带 sid 的票）                                                            | ✅   | `auth.service.test.ts`「没有 sid 的旧票不认…」                                                               |
| 13  | 逐条强退要 `session.manage`：成员退别人 403（理由是被权限挡，不是「不存在」），退自己 200；重复退不写第二条审计 | ✅   | `sessions.test.ts`、`console.e2e.test.ts`                                                                    |
| 14  | 强退留痕：`/manage/audit?q=session.revoked` 能查到，`targetId` 是那条会话                                       | ✅   | `console.e2e.test.ts`                                                                                        |
| 15  | 菜单：`platform-settings` 只在平台台；`sessions` 对全体租户成员出现                                             | ✅   | `rbac.test.ts`、`console.e2e.test.ts`（五个角色的菜单与权限名单）                                            |
| 16  | `pnpm check`（format:check → lint → typecheck → test）通过                                                      | ✅   | 见 §4，`EXIT=0`                                                                                              |

## 4. 命令与输出（关键段）

```
$ pnpm check                      # Node 24.22.1，仓库根
All matched files use Prettier code style!
$ eslint .                        # 无输出
$ pnpm -r run typecheck           # 全部包 Done
$ pnpm -r run test
packages/contracts test:  Tests  27 passed (27)
packages/storage   test:  Tests  19 passed (19)
packages/domain    test:  Tests  47 passed (47)
packages/redis     test:  Tests  11 passed | 1 skipped (12)
packages/db        test:  Tests 125 passed | 1 skipped (126)
apps/api           test:  Tests 115 passed (115)
apps/web           test:  Tests 148 passed (148)
apps/worker        test:  Tests  74 passed (74)
EXIT=0
```

合计 **566 通过 / 2 跳过**，0 失败。

## 5. 收尾时顺带修的一处（不属于 A–D 任何一批）

`apps/worker/src/proto-engine.test.ts` 的清理函数在 Windows 上稳定报
`EPERM ... \Temp\heron-engine-tree-*`：`taskkill` 之后进程要过一两百毫秒才放开目录句柄，而 `rmSync` 默认不重试。
这条用例本身是「故意留一个活着的子进程，断言工具不谎称已终止」，清理失败会让被测行为看起来像失败的。
修法是一行：`rmSync(home, { recursive: true, force: true, maxRetries: 30, retryDelay: 100 })`。
**在修之前 `pnpm check` 是红的**，这与本次四批改动无关（属于既有 Windows 环境问题），但为了第 16 条断言必须修。

## 6. 与计划的偏差

见 `docs/plans/2026-10-28-admin-borrows-and-object-storage.md` §10.2，摘要：

1. 平台设置**不做** api 侧缓存（读取方只有 worker 的两条链，摊到一次渲染/一轮清理，不构成热路径）。
2. 平台默认模型同时进了 `proto.ts`（计划只点了 `scripting.ts`，漏了工坊这条调用点）。
3. `sessions` 菜单**不设门槛**：成员看自己的、退自己的；`session.manage` 只决定能不能动别人的。
   列表 SQL 里 `eq(sessions.userId, viewer.id)` 兜底，不靠界面藏。
4. 迁移编号：平台设置 `0010`、会话 `0011`（计划原写 0009 / 0010）。
5. 登出只作废当前这条；`session_version` 退居整户撤销（改口令 / 停用 / 改角色 / 停租 / 一键踢全部 / 无 sid 旧票）。
6. 平台运营账号仍不逐条会话（只有几个账号，仍用 `session_version` 全量踢）。

## 7. 不可验证的盲点（主动声明）

- **七牛链路已真实联网验证过**（2026-10-29，用户提供 AK/SK 之后，见 §9、§10）：CDN 与 S3 两条取链都真跑过，
  真上传 1 MB → 签名签发 → 下载字节一致 → 删除后取不到。最终**取链改走 S3 端点**（§10）。
  仍未验证的是**真渲染产物**上传（`walkTask` 的上传段目前只有单测）与跨实例发布。
- **每个受保护请求多一次库读 +（60s 一次）写。** pglite 单连接下测试变慢可见；真实 Postgres 的吞吐影响未实测。
- **登出语义变了**（只踢当前设备）。多设备用户会以为「已经退了」而另一台还在——靠在线会话页的文案补认知，
  但这是**行为改变**，不是纯内修。
- **私有桶链接是 bearer 凭据**：拿到链接的人在 TTL（默认 10 分钟）内可读该成片。
- **额度按 UTC 自然月**：中国用户看到的「本月」在月初有 8 小时偏差（本轮取「界面写明 UTC」）。
- **对象存储不等于跨实例可发布**：发布路径仍读本地 `HERON_HOME`，「A 生成、B 发布」仍会失败。
- **前端只用 typecheck + 单测覆盖**：`SessionsView` / `PlatformSettingsView` 没有浏览器截图验证，
  两个页面的实际渲染（表格列宽、按钮置灰、确认弹窗）**未在真浏览器里看过**。

## 8. Git

- A–D 四批与本文（写于提交前）已合成 `0e717d9`，并推到 `origin/dev`（`a43ec6f..0e717d9`）。
- 本文写成之后的联网冒烟又修了 `packages/storage/src/qiniu.ts` 的签名截止时刻（§9.1），
  并按用户「哪个能通就用哪个」把取链换成 S3 端点（§10）。这批**尚未提交**：
  `packages/storage/src/{config,qiniu}{,.test}.ts`、`.env.example`、`deploy/docker-compose.yml`、本文件。

## 9. 真实联网冒烟（2026-10-29）

凭据只放在被 gitignore 的 `.env`（`.gitignore:7`，`git check-ignore` 已验证）：空间 `oss-heron`、区域 `z2`、
绑定域名 `tnjyf0hmp.hn-bkt.clouddn.com`（七牛测试域名）。临时脚本跑完即删，工作区没有留下它。

| 步骤                                                                  | 结果                               |
| --------------------------------------------------------------------- | ---------------------------------- |
| `put` 1 MB 随机内容 → `tenants/smoke-tenant/tasks/smoke-*/v1/mp4.mp4` | ✅ 返回 `bytes=1048576`            |
| Kodo `stat` 该 key                                                    | ✅ 200                             |
| 裸链（未签名，**且是第一个请求**）                                    | ✅ 401（空间 `private: 1`）        |
| `url(key, 600)` 签名直链                                              | ✅ 200，sha256 与本地文件一致      |
| `remove` 两次                                                         | ✅ 第二次也成功；之后 `stat` = 612 |
| 残留清理（`listPrefix tenants/smoke-tenant/`）                        | ✅ 空                              |

### 9.1 签名截止时刻传错（已修）

SDK 的 `BucketManager.privateDownloadUrl(domain, key, deadline)` 第三个参数是**绝对截止时刻**
（原样拼进 `?e=`），不是剩余秒数。原实现把 `ttlSec = 600` 原样传进去，等于签出一张 1970 年就到期的票，
**所有成片直链必 401**。现在换算成 `Math.floor(Date.now() / 1000) + ttlSec`。

单测以前是绿的，因为 `qiniu.test.ts` 注入的 fake port 把真 SDK 整个换掉了，`e=\d+` 对 600 也成立；
现已收紧为 `e ≈ now + ttl`（区间 599–601）。这条断言在原实现下会红。

### 9.2 边缘缓存会绕过签名（部署必须处理）

实测同一路径的四种状态：

```
裸链（第一次，未签名）  401      ← 私有在源站确实生效
签名直链                200      ← 拿到内容
裸链（签名之后）        200      ← 边缘缓存命中，跳过了签名校验
删掉对象后裸链          200      ← 缓存里还在
```

只要域名开着「忽略查询参数」，10 分钟的私有 TTL 就只在缓存未命中时成立。
部署时必须二选一：**关掉该域名的「忽略查询参数」**，或让 api 反代取流、不把直链交给浏览器。
`.env.example` 里已写明这条前提。

### 9.3 七牛测试域名不支持 HTTPS

`https://tnjyf0hmp.hn-bkt.clouddn.com` 握手失败：证书 altnames 里没有该域名
（`ERR_TLS_CERT_ALTNAME_INVALID`）。`.env` 里暂时写 `http://`；上线必须绑定自有域名 + 证书，
否则 HTTPS 页面里的签名直链会被浏览器按混合内容拦掉。

## 10. 取链改用 S3 端点（2026-10-29，用户指示「哪个能通就用哪个」）

用户在控制台看到空间还有一条 **S3 协议的外网访问**：`http(s)://oss-heron.s3.cn-south-1.qiniucs.com`。
把四条路都真打了一遍（同一对象、同一 AK/SK）：

| #   | 取链方式                                            | 结果                                                 |
| --- | --------------------------------------------------- | ---------------------------------------------------- |
| 1   | `tnjyf0hmp.hn-bkt.clouddn.com` + Kodo token（HTTP） | ✅ 200——但 §9.2 的缓存绕过签名、§9.3 的无 HTTPS 都在 |
| 2   | 同上，HTTPS                                         | ❌ TLS 证书 altnames 不含该域名                      |
| 3   | `oss-heron.s3.cn-south-1.qiniucs.com` 裸链          | ❌ 400 `NotSupportAnonymous`（这个端点不接受匿名）   |
| 4   | 同上 + Kodo 的 `?e=&token=`                         | ❌ 400 `MissingSecurityElement`（只认 AWS SigV4）    |
| 5   | 同上 + AWS SigV4 预签名                             | ✅ 200，字节与本地一致                               |

**结论：走 5。** S3 端点自带七牛签发的合法证书（不必先绑自有域名）、不经过 CDN（删掉即 404，
签名每次都真校验）；代价是没有 CDN 加速——成片给的是 10 分钟直链，影响有限。

### 10.1 实现

- `QiniuConfig.download` 变成判别联合：`{kind:"cdn",domain}` / `{kind:"s3",s3Region}`，二选一由环境变量决定。
- `presignS3Url()`（`packages/storage/src/qiniu.ts`）用 `node:crypto` 手写 SigV4 query 签名：
  canonical request → string-to-sign → `AWS4<SK>`→date→region→`s3`→`aws4_request` 逐级 HMAC。
  `X-Amz-Expires` 截到 7 天上限（超过它签名会被判非法，截断而不是抛，免得渲染完才炸）。
- `qiniuPort()` 按 `download.kind` 分派：s3 走 `presignS3Url`，cdn 仍走 `privateDownloadUrl`（§9.1 的换算保留）。
- `readDownload()`：配了 `QINIU_S3_REGION` 就以 S3 为准（留着一个不生效的 `QINIU_DOMAIN` 只会让
  「为什么改了没反应」变成谜）；两个都没给 → 启动即失败并点名。
- `deploy/docker-compose.yml` 的 api 与 worker 两个块都加了 `QINIU_S3_REGION`。

### 10.2 联网冒烟（真跑，脚本跑完即删）

```
config.download = {"kind":"s3","s3Region":"cn-south-1"}
1 put          = 1048576 bytes  expect 1048576
2 sign         = https://oss-heron.s3.cn-south-1.qiniucs.com/tenants/smoke-s3/tasks/smoke-…/v1/mp4.mp4?X-Amz-Algori…
3 signed GET   = 200 sha256 一致: true (1048576 bytes)
4 bare GET     = 400 （未签名，应被拒）
5 tampered GET = 403 （改签名，应被拒）
6 remove       = done
7 signed GET   = 404 （删后，应 404）
8 remove again = done（对象不在也算成功）
```

第 7 行是这条路相对 CDN 的实质收益：删掉就是 404，不再有边缘缓存里还能取到的窗口。

### 10.3 单测

`config.test.ts` 扩到 11 条（S3 区域优先、S3 区域写了整条 URL 要抛、两者全缺要抛）；
`qiniu.test.ts` 新增 4 条：固定时钟下签名稳定、`X-Amz-*` 全参与 64 位十六进制签名、7 天截断、
cdn 配置下拒绝走 S3，以及 `qiniuPort` 按配置分派。**单测只证形状与稳定性，算法正确性的证据是上面那次联网 200。**

`pnpm check` 全绿：**573 通过 / 2 跳过**（存储包 19 → 26）。

### 10.4 本地 `.env` 已切到 S3

`HERON_STORAGE=qiniu`、`QINIU_S3_REGION=cn-south-1`，`QINIU_DOMAIN` 注释保留以备切回。
`.env` 被 gitignore + dockerignore 排除，AK/SK 仍只活在那里。
