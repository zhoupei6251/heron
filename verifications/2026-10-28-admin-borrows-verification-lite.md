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

- **七牛链路没有真实联网验证。** AK/SK 尚未提供；`qiniu` 驱动只验了「注入 fake 客户端的调用形状」
  （令牌 scope、区域、私有链接签名）。真上传 / 取私有链 / 删除**必须**由用户拿 AK/SK 手工冒烟一次，
  在此之前不写成「已验证」。
- **每个受保护请求多一次库读 +（60s 一次）写。** pglite 单连接下测试变慢可见；真实 Postgres 的吞吐影响未实测。
- **登出语义变了**（只踢当前设备）。多设备用户会以为「已经退了」而另一台还在——靠在线会话页的文案补认知，
  但这是**行为改变**，不是纯内修。
- **私有桶链接是 bearer 凭据**：拿到链接的人在 TTL（默认 10 分钟）内可读该成片。
- **额度按 UTC 自然月**：中国用户看到的「本月」在月初有 8 小时偏差（本轮取「界面写明 UTC」）。
- **对象存储不等于跨实例可发布**：发布路径仍读本地 `HERON_HOME`，「A 生成、B 发布」仍会失败。
- **前端只用 typecheck + 单测覆盖**：`SessionsView` / `PlatformSettingsView` 没有浏览器截图验证，
  两个页面的实际渲染（表格列宽、按钮置灰、确认弹窗）**未在真浏览器里看过**。

## 8. Git

未提交、未推送。改动全在 `dev` 工作区，等用户显式授权后再 commit / push。
