# Verification — 表结构对齐参考项目：雪花 id · 域前缀表名 · 逻辑删除 · 审计四件套 · 对象键年月日

**Task tier**: Tier 2+（动 schema、跨 packages/db / contracts / storage / api / worker / web）
**方案**: `docs/plans/2026-10-29-schema-snowflake-softdelete.md`（用户确认后按它实现；偏离见该文 §12）
**日期**: 2026-10-29
**分支**: `dev`（**未提交、未推送**——按项目规则等用户点头）
**参考项目**: `D:\work\xinyue\aigc_platfrom_back`（RuoYi-Vue-Plus 系）

## 1. 目标

把 22 张表整体换骨架：主键与外键从 `uuid` 换成 `numeric(19,0)` 的雪花 id；表名按
`<域>_<实体单数>`；每张表补上 `create_by` / `create_time` / `update_by` / `update_time`；
9 张业务实体表加 `del_flag char(1) NOT NULL DEFAULT '0'`；对象键多一层年月日。

## 2. 改动规模

```
git diff --shortstat          →  100 files changed, 1636 insertions(+), 1032 deletions(-)
新增文件（6 个）               →  packages/db/src/id.ts
                                  packages/db/src/pg/schema/columns.ts
                                  packages/db/drizzle/0012_domain_schema.sql
                                  packages/db/drizzle/meta/0012_snapshot.json
                                  apps/api/src/common/pipes/snowflake-id.pipe.ts
                                  docs/plans/2026-10-29-schema-snowflake-softdelete.md
```

| 落点                                         | 内容                                                                                                                                             |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `packages/db/src/id.ts`                      | 雪花生成器（41+10+12 位，节点号 `HERON_NODE_ID` / hostname+pid 兜底，时钟回拨不后退）+ `legacyUuidToId()`（与迁移同一套 uuid→numeric 映射）      |
| `packages/db/src/pg/schema/columns.ts`       | **列构造器**：`idCol()` / `fkCol()` / `tsCol()` / `auditCols()` / `softDeleteCols()` / `alive()`——四件套与软删列由构造器保证，不靠 22 份复制粘贴 |
| `packages/db/src/pg/schema/*.ts`             | 22 张表重写：表名、列类型、四件套、软删列、partial 唯一索引                                                                                      |
| `packages/db/src/pg/schema/index.ts`         | 四张表清单改名 + 新增 `TABLES_WITH_SOFT_DELETE`                                                                                                  |
| `packages/db/src/pg/rls.ts`                  | GUC 转换 `::uuid` → `::numeric`；子表父表映射换成新表名；授权语句换新表名                                                                        |
| `packages/db/drizzle/0012_domain_schema.sql` | 一条迁移：类型转换 → 表/索引/约束改名 → 审计四件套 → 软删列与 partial 唯一索引 → 触发器                                                          |
| `packages/contracts/src/index.ts`            | `z.uuid()` × 33 → `snowflakeIdSchema`（十进制数字串 + 1..2^63-1）；`createdAt/updatedAt` → `createTime/updateTime`；`updatedBy` → `updateBy`     |
| `apps/api`                                   | `ParseUUIDPipe` × 14 → 新写的 `SnowflakeIdPipe`；台账游标改字符串；`markFailed` 等带上操作人                                                     |
| `apps/worker`                                | `listTaskEventsSince` 游标改字符串；proto 流补操作人参数；对象键加日期层                                                                         |
| `apps/web`                                   | 19 处 `createdAt/updatedAt` → `createTime/updateTime`（11 个 Vue 视图，纯改名，界面不动）                                                        |
| `packages/storage/src/key.ts`                | 对象键：`tenants/<租户>/<年>/<月>/<日>/tasks/<任务>/v<版本>/<kind>.<ext>`，日期取 UTC                                                            |

## 3. 证据（from search_graph / trace_path / search_code）

### 触及的核心符号

| 符号                  | qualified_name                                       | 角色                          |
| --------------------- | ---------------------------------------------------- | ----------------------------- |
| `objectKey`           | `D-zhoupei-heron.packages.storage.src.key.objectKey` | 直接改（加日期层）            |
| `withTenant`          | `packages/db/src/pg/rls.ts`                          | 直接改（GUC 转换类型 + 表名） |
| `listTaskEventsSince` | `packages/db/src/pg/tasks.ts`                        | 直接改（游标类型）            |
| `applyRls`            | `packages/db/src/pg/rls.ts`                          | 直接改（清单与授权换新表名）  |

### 影响面（trace_path / search_code）

- **`objectKey` 的 inbound callers**（2 个）：`apps.worker.src.pipeline.uploadArtifacts`（1 跳）、
  `apps.worker.src.pipeline.onEnter`（2 跳）——两处都在同一个函数里改掉了。
- **`withTenant` 的 inbound callers**：**55 处**，横跨 `apps/api`（auth / events / manage / proto / review /
  settings / tasks）、`apps/worker`（cleanup / collect / p0 / proto / tick）、`packages/db`（seed 工具）。
  改的是 GUC 的转换类型与表名，调用点签名不变（除 `markFailed` 多一个显式操作人）。
- **`randomUUID`**：27 个文件、**126 处** → 全部换成 `snowflakeId()`。
- **`z.uuid()`**：33 处（全在 `packages/contracts/src/index.ts`）→ `snowflakeIdSchema`。
- **`createdAt` / `updatedAt`**：全仓 **167 + 104** 处 → `createTime` / `updateTime`；
  `apps/web/src` 占 19 处、涉及 14 个文件（11 个 Vue 视图 + 2 个测试 + 1 个 store）。
- **UPDATE 语句**：43 处（`packages/db/src/pg/` 39 + `apps/api` 与 `apps/worker` 4）→ 每处显式写 `update_by`。

## 4. 验证断言（逐条核）

| #   | 断言                                                                                                            | 结果 | 证据                                                                                                                               |
| --- | --------------------------------------------------------------------------------------------------------------- | ---- | ---------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 22 张表在 `information_schema` 里都是新名，且每张都有 `create_by` / `create_time` / `update_by` / `update_time` | ✅   | 迁移后的库与 drizzle 快照**逐列对账**：库里有快照没有 = 无，快照有库里没有 = 无（§6.2）                                            |
| 2   | 主键与外键列都是 `numeric(19,0)`                                                                                | ✅   | 同上逐列对账 + `drizzle-kit generate` 报「No schema changes」                                                                      |
| 3   | 通用审计列是 `create_time` / `update_time`，领域列仍是 `_at`                                                    | ✅   | 迁移 §5 与 §4.2；`time.ts` 的口径注释仍在                                                                                          |
| 4   | 列名 ↔ drizzle 属性名 ↔ JSON 字段名三者一致                                                                     | ✅   | `create_time` ↔ `createTime` ↔ `createTime`；`update_by` ↔ `updateBy` ↔ `updateBy`                                                 |
| 5   | 9 张业务表有 `del_flag char(1) NOT NULL DEFAULT '0'`，软删行查不到；证据类表没有                                | ✅   | `TABLES_WITH_SOFT_DELETE` 与 `alive()` 过滤；`packages/db` 125 测全绿                                                              |
| 6   | 6 个唯一索引是 partial（软删后同名可重建）                                                                      | ✅   | 迁移 §6 逐个 DROP + CREATE … `WHERE del_flag = '0'`；`onConflictDoUpdate` 同步带 `targetWhere`（否则 Postgres 找不到可推断的索引） |
| 7   | `update_time` 由触发器维护，形状与 `nowIso()` 逐字符一致                                                        | ✅   | pglite 实测触发器写入 `2026-10-29T03:04:10.755Z`（24 位、毫秒、Z 结尾，且是 UTC）                                                  |
| 8   | 操作人为 `NULL`（系统写入）与有值（人工操作）都能落库并读回                                                     | ✅   | 43 处 UPDATE 显式写 `update_by`；系统路径传 `null`                                                                                 |
| 9   | 存量 uuid 已映射，FK 全部对得上（无孤儿行）                                                                     | ✅   | `driver.test.ts`「已有数据的库升到新骨架」：老库插 uuid → 迁移 → 行还在、外键指向同一个数、哨兵租户归一到 `DEFAULT_TENANT_ID`      |
| 10  | 新建 id 单调递增、同毫秒不重复、跨进程不撞号                                                                    | ✅   | `packages/db/src/id.ts` 单测（41+10+12 位布局 + 序列回绕 + 时钟回拨不后退）                                                        |
| 11  | `withTenant` 的租户隔离仍然 fail-closed                                                                         | ✅   | `rls.test.ts` / `rls.concurrency.test.ts` / `audit-permissions.test.ts` 全绿（改了 GUC 的转换类型与全部表名）                      |
| 12  | `snowflakeIdSchema` 拒绝非数字串、拒绝 0、拒绝超 2^63-1                                                         | ✅   | `packages/contracts` 27 测；脏值不再把校验变成崩溃（`BigInt` 抛错已兜住）                                                          |
| 13  | `objectKey` 产出 `tenants/<租户>/2026/10/29/tasks/<任务>/v1/mp4.mp4`，且拒绝含 `..` 的段                        | ✅   | `packages/storage/src/key.test.ts`（含 UTC 分桶与月日补零两条）                                                                    |
| 14  | 一次上传的三个产物落在同一层日期里                                                                              | ✅   | `apps/worker/src/pipeline.test.ts`：日期层相同 + 键形状断言（不写死日期，零点前后都稳）                                            |
| 15  | `drizzle-kit generate` 无差异（快照与 schema 一致）                                                             | ✅   | 连跑两次都是「No schema changes, nothing to migrate 😴」                                                                           |
| 16  | `pnpm check` 全绿（format → lint → typecheck → test）                                                           | ✅   | 见 §5                                                                                                                              |

## 5. 命令与输出

```
$ pnpm check                      # Node 24.22.1，仓库根
All matched files use Prettier code style!
$ eslint .                        # 无输出
$ pnpm -r run typecheck           # 全部包 Done
$ pnpm -r run test
packages/contracts test:  Tests  27 passed (27)
packages/storage   test:  Tests  28 passed (28)
packages/domain    test:  Tests  47 passed (47)
packages/redis     test:  Tests  11 passed | 1 skipped (12)
packages/db        test:  Tests 125 passed | 1 skipped (126)
apps/web           test:  Tests 148 passed (148)
apps/api           test:  Tests 115 passed (115)
apps/worker        test:  Tests  74 passed (74)
```

合计 **575 通过 / 2 跳过**，0 失败。

## 6. 真实跑过的验证（不是只跑单测）

### 6.1 迁移在 pglite 上真跑过

`packages/db` 的测试跑的是嵌入式真 Postgres（pglite，PG 18.6）。迁移 0012 从 0000 一路应用到
0012 全绿：类型转换 → 表与索引改名 → 审计四件套 → 软删列与 partial 唯一索引 → 触发器，
一次事务内完成。**存量数据不丢**（见 §4 第 9 条）。

### 6.2 迁移后的库与代码里的 schema 逐列对账

写了个临时探针，把迁移后的 `information_schema.columns` 与 drizzle 快照
（`packages/db/drizzle/meta/0012_snapshot.json`）逐列比对：

```
库里有、快照没有： 无
快照有、库里没有： 无
```

**零漂移**——迁移产出的库结构与代码里声明的结构一字不差。（探针跑完即删。）

### 6.3 快照链自检

`drizzle-kit generate` 在新快照装好之后连跑两次都是「No schema changes, nothing to migrate」——
这是「快照与 schema 一致」的自检，也是下一次 `drizzle-kit generate` 不会把整库当成要重建的保证。

### 6.4 那段最巧的 SQL 单独验过

uuid → numeric 的映射表达式（`('x' || substr(replace(id::text,'-',''),1,13))::bit(52)::bigint`）
在 pglite 上单独跑过：uuid `a1b2c3d4-e5f6-4789-a0b1-c2d3e4f50617` → `2844626588163940`（52 bit，
< 4.5e15），外键在改类型后仍然成立，**孤儿行 0**。

### 6.5 软删列从 `deleted_at` 改成 `del_flag` 之后又全跑了一遍

改的是一个列名与四处谓词（`alive()`、6 个 partial 唯一索引、2 个 `onConflictDoUpdate` 的
`targetWhere`、迁移 DDL），改完把快照按旧办法重生成，`drizzle-kit generate` 仍报
「No schema changes」；然后 `pnpm check` 全仓重跑 **EXIT=0，575 通过 / 2 跳过**，
迁移列对账（§6.2 的探针）与 pglite 全链迁移（§6.1）都重跑通过。

这次改名还**抓到一处真 bug**：`createProtoProject` 里显式构造的 `ProtoProjectRecord` 字面量缺
`delFlag`——`tsc` 报 `Property 'delFlag' is missing`，补上 `delFlag: "0"` 后 typecheck 归零。
这正是「换列名要把类型检查真跑一遍」的价值：单测那一刻是绿的，缺列只有类型系统看得见。

## 7. 与方案文档的偏离

见 `docs/plans/2026-10-29-schema-snowflake-softdelete.md` §12（10 条 + 3 个坑）。最要紧的四条：

1. **迁移写成一条而不是三条**：一条迁移是一个事务，要么全成要么全不成；三条会留下「改了一半」的库。
2. **键值表（`news_setting` / `plat_setting`）不加软删**：主键就是键本身，软删的行仍占着键。
3. **TS 导出名不动**（`tenants` / `users` / `tasks`…）：导出名同时是 API 出参的来源，改名会连 API 契约一起改。
4. **软删列最终叫 `del_flag char(1)` 而不是 `deleted_at`**：与参考项目同名同形，跨项目查表不必翻译
   （§6.5 记了改完后的全量重跑）。

## 8. 不可验证的盲点（主动声明）

- **真 Postgres 上还没跑过这条迁移**：本地 `127.0.0.1:5432` 的 `heron` 库用 `.env` 里的
  `DATABASE_URL` **认证失败**（`password authentication failed for user "heron"`），
  Docker Desktop 也没在跑，所以拿不到一个能连的本地库。迁移本身在 pglite（PG 18.6）上跑通了，
  但真库上的 `ALTER TYPE` 锁与耗时、以及真库里的存量数据，都**未实测**。
  下次 api/worker 用能连的凭据启动时会自动应用它——第一次连真库时要多看一眼。
- **量级差异**：Snowflake id 让 id 从 36 字符变成 19 字符，但 `numeric` 的比较与存储比 `bigint` 略慢，
  主键索引在十万行级无感，**未压测**。
- **`numeric` 与将来换 `bigint`**：这次为 JS 侧零精度损失选了 `numeric(19,0)`；
  要换回 `bigint` 需要再迁一次列类型（数据无损，映射同样可逆）。
- **前端只做了机械改名**：11 个 Vue 视图里的 `createdAt` → `createTime` 是纯改名，
  **没有在浏览器里看过**渲染结果。
- **软删的读过滤是分档的**：只加在「现在是什么」类查询（列表、详情、登录），
  「发生过什么」类（去重判据、额度统计、审计、发布记录）刻意不过滤。
  这条分档只有一条单测（会话审计的 `ORDER BY`）间接覆盖，**没有专门的软删端到端用例**——
  因为现在还没有任何软删入口（软删列是地基）。
- **对象键的日期层只验了形状**：桶里的真实目录（`tenants/<租户>/2026/10/29/…`）没有在七牛上真放过一份。
- **`update_by` 不加外键**的取舍：人软删后审计行里留着一个查不到人的 id，界面要能表达「操作人已不存在」。
  这条没做界面验证。

## 9. Git

- **本批全部未提交、未推送**。工作区 = 本批改动 + 上一批的七牛 S3 取链改动（`packages/storage`、
  `.env.example`、`deploy/docker-compose.yml`、验证文档）。
- 提交需要用户点头（项目规则：未经授权不 commit / 不 push）。
