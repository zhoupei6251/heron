# heron 多租户 SaaS · 计划 1：租户数据面与身份

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）
> 或 superpowers:executing-plans 逐任务实现。步骤用 `- [ ]` 勾选跟踪。
> **本仓库覆盖项**：计划与提交节奏遵循用户指令——每任务的出口判据是 `pnpm check` + 该任务自己
> 的测试，**不自动 `git commit`**（用户未授权提交）。技能模板里的 "Commit" 步骤一律替换为
> "Run: `pnpm check`"。

**Goal:** 把 heron 的数据面换成「Postgres + Drizzle + 行级安全」，并补上真实身份（登录、租户、角色），
使十态状态机与五道闸在每租户隔离的前提下继续跑通。

**Architecture:** 一张共享 Postgres 库，所有业务表带 `tenant_id NOT NULL`，用 RLS policy 兜住跨租户
泄漏（漏写 WHERE 不再等于泄漏）；应用连接以 `heron_app` 角色运行，每个事务开头 `set_config('app.tenant_id', …, true)`。
身份走 httpOnly cookie 里的 JWT（不是 Bearer：SSE 用 `EventSource`，发不出请求头）。
渲染与发布仍是独立进程，但**全部改为异步**，这是本次最大的一笔结构性改动。

**Tech Stack:** Node 24.22.1 · TypeScript 5.9.3 · pnpm 12.14.0 · NestJS 12.2.0 · zod 4.6.7 ·
Vue 3.5.44 + Vite 8.3.5 · drizzle-orm 0.45.4 / drizzle-kit 0.31.11 · postgres.js 3.4.9 ·
@electric-sql/pglite 0.5.11（测试用嵌入式 Postgres，实测报 `PostgreSQL 18.6`）· PostgreSQL 18（服务端，compose 里钉 `postgres:18-alpine`，主版本必须与 pglite 对齐）· vitest 5.0.5

**Spec:** [docs/plans/2026-10-22-heron-news-webapp-design.md](./2026-10-22-heron-news-webapp-design.md)
（状态机、五道闸、确认三样、§8 接缝仍以它为准）+ [stack-v2](./2026-10-22-heron-stack-v2-design.md)
（§7 数据模型 v2 被本计划的 §T2 取代为 v3）

## Global Constraints

- 运行时 Node **24.22.1**，`engines.node` 与启动断言不许放宽；pnpm `engine-strict=true`。
- TypeScript 锁 **5.9.3**（`latest` 7.0.2 与 vue-tsc 3.3.13 不兼容，stack-v2 §5 实测结论）。
- 依赖一律用实测 `dist-tags.latest`，不许凭印象写版本号。
- 时间列口径不变：一律 UTC ISO-8601 带 `Z`，由 `packages/db` 的 `nowIso()` 单点写入。
- 「数据在哪」单点不变：`packages/db/src/home.ts`，api 与 worker 不许各自算路径。
- DI 必须写显式 `@Inject(Service)`：tsx/esbuild 不执行 `emitDecoratorMetadata`（stack-v2 §5 实测）。
- 失败必须报原因、不静默：闸不过要写哪条规则 + 输入片段 + 为什么拒；缺凭证/缺配置就红，不默认放行。
- 人工确认必须同时落**确认人（users 外键）+ 确认时间 + 当时联络表指纹**三样，少一样不算确认。
- `reference/` 是 oracle：不 import、不构建、不 CI。抖音登录态与 OAuth token 不进仓库。
- 每任务出口判据：`pnpm check`（format:check → lint → typecheck → test）全绿 + 该任务的测试先红后绿。

---

## 文件结构（本计划创建/修改的责任边界）

```
packages/db/
  drizzle.config.ts                  drizzle-kit 生成迁移的配置（dialect=postgresql）
  src/driver.ts                      唯一决定「连的是真库还是 pglite」的地方，返回 Db
  src/schema/                        Drizzle 表定义（取代 migrations/*.sql 的表结构地位）
    tenants.ts users.ts tasks.ts artifacts.ts usage.ts identity.ts …
  src/migrate.ts                     执行 drizzle 生成的 SQL 迁移；建角色与 RLS policy
  src/tenant.ts                      forTenant(db, tenantId) → TenantDb；set_config 单点
  src/tasks.ts candidates.ts artifacts.ts identity.ts usage.ts   仓储层（全部 async）
  src/home.ts                        新增 DATABASE_URL / ARTIFACTS_DIR，删掉 DB_FILE
  src/index.ts                       导出面
packages/contracts/src/index.ts       加 Tenant/User/Role/UsageRecord + 删 ClaimBody.owner
packages/domain/src/password.ts       scrypt 哈希与校验（stdlib，不引原生依赖）
packages/domain/src/rbac.ts           ROLES 顺序 + can(role, action) + requiredRoleFor(action)
apps/api/src/auth/                    auth.module/service/controller + jwt.strategy
apps/api/src/common/guards/           JwtAuthGuard（全局）+ RolesGuard
apps/api/src/common/decorators/       @Public() @RequireRole() @CurrentUser()
apps/api/src/models/                  GET /api/models（读 env 目录，第一期只读）
apps/worker/                          由 apps/runner 改名，pipeline/tick/p0 全链路 async
apps/web/src/views/LoginView.vue      登录页（views/ 目录第一次出现，见 T9 说明）
apps/web/src/stores/auth.ts           token 状态由 cookie 承载，store 只存 me 与角色
deploy/                               docker-compose.yml + nginx.conf + 两个 Dockerfile
```

---

## T1：驱动与迁移工具落地（Postgres + pglite 双入口）

> **顺序约定（先读这条）**：T1–T4 在 `packages/db/src/pg/` 下**新建**异步数据层，
> 旧的 SQLite 层（`connection.ts` / `tasks.ts` / `migrations/*.sql` / `home.ts` 的 `DB_FILE`）
> 一行不动、`pnpm check` 必须一路全绿。api 与 worker 在 T8 一次性切到新层，切完才删旧层。
> 理由：`openDb` 从同步变异步会让三处进程同时编译不过，任务边界就不独立了。

> **已完成（2026-10-22），三处与计划文本不同的实测结论**：
> 1. `openDb` **保持同步**——两个 driver 的构造都不需要 await，PGlite 本身是 thenable，drizzle 在执行时
>    自己等它 ready（无 `waitReady` 的前提下迁移+插入+唯一冲突全部按预期）。异步的是 T4 的仓储，不是连接构造。
>    「T1–T4 新建异步数据层」这句里，异步落在仓储层。
> 2. 类型名是 `PgliteDatabase`（小写 g），不是 `PgLiteDatabase`；写错会让 eslint 的 `no-unsafe-*` 在整层
>    炸出 50 条级联错误，看起来像「drizzle 不兼容」，其实是一个导入名。
> 3. `db.execute(sql\`…\`)` 的返回形状**按 driver 而异**：pglite 给 `{rows, fields, command, rowCount}`，
>    postgres-js 给行数组。所以业务查询一律走 Drizzle 构造器，裸 SQL 只在明确知道当前 driver 时用。
>    另外 drizzle 会把驱动错误包成 `Failed query: …`，真正的 `23505` 文案在 `error.cause` 上——
>    T2 之后区分唯一冲突与其它 SQL 错误要往 `cause` 里看。
>
> 迁移已生成：`packages/db/drizzle/0000_ambitious_electro.sql`（只建 `tenants`）。
> 测试实测的 server 版本：`PostgreSQL 18.6 (PGlite 0.5.11)`。

**Files:**
- Create: `packages/db/src/pg/{driver.ts,migrate.ts,index.ts}`, `packages/db/src/pg/schema/{index,tenants}.ts`
- Modify: `packages/db/package.json`（加 drizzle-orm/drizzle-kit/postgres/@electric-sql/pglite）
- Test: `packages/db/src/pg/driver.test.ts`

**Interfaces:**
- Produces: `openDb(target: "memory" | "url"): Promise<Db>`；`Db = PgLiteClient | PostgresJsClient`
  （统一别名 `type Db = PostgresJsDatabase<typeof schema> & { close(): Promise<void> }`，
  pglite 实例经 `drizzle-orm/pglite` 产生，形状与 postgres-js 客户端在 Drizzle 层一致）
- Produces: `migrate(db: Db): Promise<void>`

- [ ] **Step 1: 装依赖并核对版本**

```bash
pnpm --filter @heron/db add drizzle-orm@0.45.4 postgres@3.4.9 @electric-sql/pglite@0.5.11
pnpm --filter @heron/db add -D drizzle-kit@0.31.11
```
Expected: `pnpm ls --filter @heron/db --depth -1` 显示四个包版本与上面逐字一致；
`.npmrc` 的 `engine-strict=true` 未被关闭。

- [ ] **Step 2: 写失败测试**（`packages/db/src/pg/driver.test.ts`）

```ts
import { describe, expect, it } from "vitest";

import { migrate, openDb } from "./index";

describe("pg 驱动", () => {
  it("pglite 内存库能开能关，且报出真实 Postgres 版本", async () => {
    const db = await openDb("memory");
    const row = await db.execute<{ v: string }>("SELECT version() AS v").then((r) => r.rows[0]);
    expect(row?.v).toContain("PostgreSQL");
    await migrate(db);
    await db.close();
  });
});
```

- [ ] **Step 3: 跑一次确认它失败**

Run: `pnpm --filter @heron/db test`
Expected: FAIL — 找不到模块 `./index`（`src/pg/` 还不存在）；旧的 `db.test.ts` 仍 PASS

- [ ] **Step 4: 写 `packages/db/src/pg/driver.ts`**

```ts
import { drizzle as drizzlePgLite, type PgLiteDatabase } from "drizzle-orm/pglite";
import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import PGlite from "@electric-sql/pglite";

import * as schema from "./schema/index";

export type Db = PgLiteDatabase<typeof schema> & { close(): Promise<void> };

/**
 * 「连真库还是连内存库」只允许有一处判断。
 * 测试跑 pglite（不需要 Docker，也不需要每个分支起一个 Postgres），
 * 进程跑 DATABASE_URL 指的库；两边的 SQL 必须是同一份，否则测的不是生产行为。
 */
export async function openDb(target: "memory" | (string & {})): Promise<Db> {
  if (target === "memory") {
    const client = new PGlite();
    const db = drizzlePgLite(client, { schema }) as Db;
    db.close = () => client.close();
    return db;
  }
  const client = postgres(target, { max: 10, prepare: false });
  const db = drizzle(client, { schema }) as Db;
  db.close = () => client.end();
  return db;
}
```

⚠️ `prepare: false` 不能省：pglite 与部分代理/连接池下缓存语句会报错，且迁移期语句会先于连接变化。
⚠️ pglite 是**单连接**，测不出并发跨租户竞态；那类测试归集成测试（需要 Docker，见 T11）。

- [ ] **Step 5: 写 `pg/migrate.ts`、`pg/index.ts` 与 `drizzle.config.ts`**

```ts
// packages/db/src/pg/migrate.ts
import { migrate } from "drizzle-orm/pglite/migrator";
import { migrate as migratePg } from "drizzle-orm/postgres-js/migrator";

import type { Db } from "./driver";

export async function runMigrations(db: Db): Promise<void> {
  // 两个 migrator 的 SQL 是同一份生成物，只是加锁方式不同
  await migrate(db as Parameters<typeof migrate>[0], { migrationsFolder: PG_MIGRATIONS_DIR });
}
```

```ts
// packages/db/src/pg/index.ts —— 新层的导出面，先只出这两个符号
export { openDb, type Db } from "./driver";
export { runMigrations as migrate } from "./migrate";
```

`migrationsFolder` 必须是**绝对路径**（由 `packages/db` 自己的位置算，不靠进程 cwd）——
这正是 home.ts 那条「数据在哪只许一处计算」的规矩在新层里的延续。

```ts
// packages/db/drizzle.config.ts
import { defineConfig } from "drizzle-kit";

export default defineConfig({
  dialect: "postgresql",
  schema: "./src/pg/schema/index.ts",
  out: "./drizzle",
});
```

- [ ] **Step 6: 建 `pg/schema/index.ts` + `tenants.ts`（见 T2 定义），生成第一份迁移并跑绿**

```bash
pnpm --filter @heron/db exec drizzle-kit generate
pnpm --filter @heron/db test
```
Expected: 新 `pg/driver.test.ts` PASS，旧 `db.test.ts` 13 条仍 PASS，`pnpm check` 全绿。

---

## T2：schema v3——每张业务表带 tenant_id，去重键改成租户内唯一

> **已完成（2026-10-22），四处与计划文本不同的决定**：
> 1. **时间列用 `text`，不用 `timestamp({mode:"string"})`**：口径是 ISO-8601 带 Z、由 `nowIso()` 单点写入，
>    而 timestamptz 读回来是 `2026-10-22 11:00:00+00`——形状一变，前端 `new Date()` 就差一个时区。
>    同时**不给 DB 默认值**（计划里的 `default(sql\`now()\`)` 全部不要）：漏写时间就当错误炸出来，
>    比兜一个形状不同的 now() 诚实，也保住「写入只有一处」这条。
> 2. **枚举 CHECK 从 contracts 生成**（`STAGES`/`GATES` 是唯一的名单），但必须拼成 SQL 字面量：
>    CHECK 进的是 `CREATE TABLE`，Postgres 的 DDL 里不许出现 `$1`——第一版用 `sql.join` 生成参数，
>    产出的迁移文件根本跑不起来。`text("role", { enum: ROLES })` 这种写法也就没用。
> 3. **`news_*` 六张外挂表推走**：全仓库只有 `packages/db/src/db.test.ts` 读写它们，api/worker 生产路径不碰。
>    它们随 T8 删除旧 SQLite 层一起消失，等 news 流水线真的写库时在计划 4 里以 pg 形态回来。
> 4. **迁移 squash 成单个** `drizzle/0000_steep_hitman.sql`（T5 重新生成后是 `0000_neat_rogue.sql`，仍只有一个）：
>    此前没有任何真库应用过只建 `tenants` 的那一版，
>    留两级文件只会让 T10 搬数据时多一个「哪一级应用过」的问题。
>
> 还有一处明知不完整：`aigc_posture.set_by` 仍是自由文本（计划没要求改）。它与 `confirmations.confirmer`
> 同理，T5 身份落地时应一起换成 uuid 外键——代码里已写 `TODO(T5)`，不许让它烂在注释里。
>
> **测出来的形状**：`TABLES_WITH_TENANT` / `TABLES_WITHOUT_TENANT` / `TABLES_TENANT_ROOT` 三张清单
> 在 `schema.test.ts` 里与 `information_schema.tables` 做**覆盖率**对比（排除 migrator 自建表
> `__drizzle_migrations`）——将来加一张表忘了站队，测试直接红，这才是「每表都有 tenant_id」的可执行形式。

**Files:**
- Create: `packages/db/src/pg/schema/{index,tenants,users,identity,tasks,artifacts,usage}.ts`
- Test: `packages/db/src/pg/schema.test.ts`
- （旧 `migrations/*.sql` 与 `connection.ts` 此处**不删**，删除发生在 T8 切换之后）

**Interfaces:**
- Produces: `tenants`, `users`, `feedSources`, `candidates`, `tasks`, `taskEvents`, `gateResults`,
  `renders`, `confirmations`, `publishes`, `aigcPosture`, `usageRecords`, `platformAccounts`
- Produces: `DEFAULT_TENANT_ID = "00000000-0000-4000-8000-000000000001"`

- [ ] **Step 1: 写失败测试（表清单 + 每表都有 tenant_id 这件事本身是可测的）**

```ts
import { describe, expect, it } from "vitest";

import { migrate, openDb, TABLES_WITH_TENANT } from "./index";

describe("schema v3", () => {
  it("列了租户的表在库里都存在，且真的带 tenant_id", async () => {
    const db = await openDb("memory");
    await migrate(db);
    for (const table of TABLES_WITH_TENANT) {
      const cols = await db.execute<{ column_name: string }>(
        `SELECT column_name FROM information_schema.columns
          WHERE table_name = '${table}'`,
      );
      expect(cols.rows.map((c) => c.column_name), table).toContain("tenant_id");
    }
    await db.close();
  });
});
```

- [ ] **Step 2: 跑一次确认失败** → FAIL：模块找不到 `TABLES_WITH_TENANT`

- [ ] **Step 3: 写 tenants / users / platformAccounts**

```ts
// packages/db/src/schema/tenants.ts
import { sql } from "drizzle-orm";
import { pgTable, text, timestamp, uuid } from "drizzle-orm/pg-core";

export const tenants = pgTable("tenants", {
  id: uuid("id").primaryKey().defaultRandom(),
  name: text("name").notNull(),
  slug: text("slug").notNull().unique(),
  // 停租是软关闭：数据不许物理删，审计要求「当时能不能发」可回溯
  suspendedAt: timestamp("suspended_at", { mode: "string" }),
  createdAt: timestamp("created_at", { mode: "string" }).notNull().default(sql`now()`),
});

export const DEFAULT_TENANT_ID = "00000000-0000-4000-8000-000000000001";
```

```ts
// packages/db/src/schema/users.ts
export const users = pgTable("users", {
  id: uuid("id").primaryKey().defaultRandom(),
  tenantId: uuid("tenant_id").notNull().references(() => tenants.id, { onDelete: "cascade" }),
  email: text("email").notNull(),
  displayName: text("display_name").notNull(),
  // 合成 actor（p0、未来的定时抓取）写 "!" 前缀，永远不可登录——见 domain/password.ts
  passwordHash: text("password_hash").notNull(),
  role: text("role", { enum: ROLES }).notNull().default("viewer"),
  disabledAt: timestamp("disabled_at", { mode: "string" }),
  createdAt: timestamp("created_at", { mode: "string" }).notNull().default(sql`now()`),
  updatedAt: timestamp("updated_at", { mode: "string" }).notNull().default(sql`now()`),
});
// UNIQUE (tenant_id, email)：同一邮箱在不同租户是两个人，全局唯一会挡住多租户注册
```

```ts
// packages/db/src/schema/identity.ts — 发布账号归租户，不归平台进程
export const platformAccounts = pgTable("platform_accounts", {
  id: uuid("id").primaryKey().defaultRandom(),
  tenantId: uuid("tenant_id").notNull().references(() => tenants.id, { onDelete: "cascade" }),
  platform: text("platform").notNull(),
  externalAccountId: text("external_account_id").notNull(),
  // cookie = 自研页面自动化（只许自己用）；oauth = 平台官方授权（外部客户的唯一许可路径）
  authMode: text("auth_mode", { enum: ["oauth", "cookie"] }).notNull(),
  // OAuth: AES-256-GCM 密文（密钥来自 env）；cookie: 磁盘路径。二者只会有其一。
  credential: text("credential"),
  credentialPath: text("credential_path"),
  credentialSha256: text("credential_sha256"),
  verifiedAt: timestamp("verified_at", { mode: "string" }),
  createdAt: timestamp("created_at", { mode: "string" }).notNull().default(sql`now()`),
});
```

- [ ] **Step 4: 迁移其余表（逐张给出差额，形状照 stack-v2 §7 的 v2 保留）**

每张表加 `tenantId: uuid("tenant_id").notNull().references(() => tenants.id, { onDelete: "cascade" })`，
其余列与 CHECK 约束保持 v2 的判据不变，另有下列三处**语义变更**（不是机械搬运）：

| 表 | v2（SQLite） | v3（多租户） | 为什么必须改 |
|---|---|---|---|
| `candidates` | `norm_key TEXT NOT NULL UNIQUE` | `UNIQUE(tenant_id, norm_key)` | 去重是租户内的事实；两个客户发同一热点是正常业务，全局唯一会把平台做成单人工具 |
| `settings` | `key TEXT PRIMARY KEY` | `PRIMARY KEY(tenant_id, key)` | 保留天数、抓取频率是租户配置 |
| `confirmations` | `confirmer TEXT NOT NULL` | `confirmedBy uuid NOT NULL REFERENCES users(id)` | 规格书 §5 的「确认人」在没有身份表之前一直是自由文本，p0 往里写的是 `P0_FAKE` |
| `tasks` | `claimed_by TEXT` | `claimed_by uuid REFERENCES users(id)` | 同上；`lease_owner` 保持不变——它是 worker 进程身份，不是人 |
| `aigc_posture` | `open_flag` 生成列 + 唯一索引 | `UNIQUE(tenant_id) WHERE reverted_at IS NULL`（partial unique index） | Postgres 的 NULL 不相等问题不存在了，生成列那个 SQLite 补丁可以删 |

`renders` / `task_events` / `gate_results` 不加 `tenant_id`（子表靠父表 FK 继承隔离）。
**这条规则必须写成测试**：`TABLES_WITHOUT_TENANT` 也导出，两张清单加起来要覆盖 public 下全部表。

- [ ] **Step 5: usageRecords（账本先于价格）**

```ts
export const usageRecords = pgTable("usage_records", {
  id: bigint("id", { mode: "number" }).primaryKey().generatedAlwaysAsIdentity(),
  tenantId: uuid("tenant_id").notNull().references(() => tenants.id, { onDelete: "cascade" }),
  taskId: uuid("task_id").references(() => tasks.id, { onDelete: "set null" }),
  kind: text("kind", { enum: ["llm_tokens_in", "llm_tokens_out", "image_gen", "render_seconds", "publish"] }).notNull(),
  quantity: numeric("quantity").notNull(),
  unit: text("unit", { enum: ["tokens", "count", "seconds"] }).notNull(),
  detail: jsonb("detail").notNull().default(sql`'{}'::jsonb`),
  recordedAt: timestamp("recorded_at", { mode: "string" }).notNull().default(sql`now()`),
});
// 每个 kind 只允许配一个 unit；配错是记账事故，不该靠 code review 拦
table.check(sql`(${q(kinds[0])} AND ${u("tokens")}) OR ...`)
```

- [ ] **Step 6: 生成迁移并跑绿**

```bash
pnpm --filter @heron/db exec drizzle-kit generate
```
Expected: `packages/db/drizzle/0000_*.sql` 生成；`pnpm --filter @heron/db test` 全绿；
`pnpm check` 全绿。

---

## T3：行级安全——漏写 WHERE 不再是跨租户泄漏

> **已完成（2026-10-22）**，`rls.test.ts` 6 条全绿（计划里的 4 条 + 两条自加的：`tenants` 表自身隔离、
> GUC 的事务局部性）。一处**计划文本是错的**，必须记下：
>
> Step 3 说「`current_setting(..., true)` 的 missing_ok 让 GUC 没设时返回 NULL，policy 判假 → fail-closed」。
> 前半句只在**这个连接从没设过它**时成立：自定义 GUC 一旦被 `set_config` 定义过，
> `is_local=true` 的事务结束时就回退成**空字符串**而不是 NULL，于是 `''::uuid` 抛
> `2202 invalid input syntax for type uuid`——连接池复用下等于每一次查询都炸，红得很难看但不是泄漏。
> 策略表达式因此写成 `NULLIF(current_setting('app.tenant_id', true), '')::uuid`：未设与空串同等对待，
> 都是「没有租户上下文」，比较结果为 NULL，读 0 行、写被 WITH CHECK 拒。测试里那条 fail-closed 断言
> 就是为锁住这个形状而存在的。
>
> 另外三点执行事实：
> 1. **测试必须 `SET ROLE heron_app` 降权**。pglite 连的是超管，而超管无视 RLS（`FORCE` 也只管到表 owner），
>    不降权跑出来的绿灯全是假的。
> 2. `applyRls(db)` 挂在 `migrate()` 尾部、每次幂等重放（`DROP POLICY IF EXISTS` + `CREATE POLICY` +
>    `DO` 块建角色）——drizzle-kit 生成不出这些语句，而「加了一张新租户表却忘了开 RLS」只能靠
>    T2 那三张清单的覆盖率测试 + 这里的重放来兜。
> 3. **建租户是特权动作**，必须在降权之前完成（测试的 `beforeAll` 就是这个顺序）。T4 的双通道里，
>    这条走 `heron_owner`，不许出现在 `withTenant` 能到达的路径上。
>
> 还有一条是本回合自己制造的教训：测试里临时手搓 `title.replace(/[\W_]+/g, "")` 算 norm_key，
> JS 不带 `u` 标志时 `\W` 把汉字整段吃掉，所有中文标题折叠成同一个键，直接撞穿 `u_candidates_tenant_norm`。
> 生产实现 `@heron/domain` 的 `normKey`（`/[^\p{L}\p{N}]/gu`）本来是对的，还有 CJK 的 oracle 测试盯着。
> **规则不许在测试里复制第二份**——import 它。

**Files:**
- Modify: `packages/db/src/pg/schema/*.ts`（每张带租户的表声明 policy）
- Create: `packages/db/src/pg/tenant.ts`, `packages/db/src/pg/rls.ts`
- Test: `packages/db/src/pg/rls.test.ts`

**Interfaces:**
- Produces: `forTenant(db: Db, tenantId: string): TenantDb`，`TenantDb = { db: Db; tenantId: string; tx<T>(fn: (t: TxWithTenant) => Promise<T>): Promise<T> }`
- Produces: `SET_TENANT_GUC = "app.tenant_id"`

- [ ] **Step 1: 写失败测试——这是整个多租户的地基，四条断言一条都不许省**

```ts
import { beforeAll, describe, expect, it } from "vitest";

import { createTenant, createCandidate, forTenant, listCandidates, migrate, openDb, type Db } from "./index";

const A = "00000000-0000-4000-8000-0000000000aa";
const B = "00000000-0000-4000-8000-0000000000bb";

describe("RLS", () => {
  let db: Db;
  beforeAll(async () => {
    db = await openDb("memory");
    await migrate(db);
    await createTenant(db, { id: A, name: "A", slug: "a" });
    await createTenant(db, { id: B, name: "B", slug: "b" });
    // pglite 以超管身份运行，超管默认无视 RLS——所以测试必须显式降权，
    // 否则测的是「没开 RLS 的库」，绿灯是假的。
    await db.execute("SET ROLE heron_app");
  });

  it("A 写一条，B 读不到", async () => {
    await createCandidate(forTenant(db, A), { title: "台风巴威登陆", summary: "", heat: 1 });
    expect(await listCandidates(forTenant(db, B))).toHaveLength(0);
    expect(await listCandidates(forTenant(db, A))).toHaveLength(1);
  });

  it("没设 app.tenant_id 时一条都读不到（fail-closed，不是全表可见）", async () => {
    await db.execute("RESET app.tenant_id");
    expect(await listCandidates(forTenant(db, A))).toHaveLength(0);
  });

  it("把 tenant_id 写成别人的租户，INSERT 被 WITH CHECK 拒", async () => {
    await expect(
      db.execute(`INSERT INTO candidates (id, tenant_id, title, norm_key)
                  VALUES ('c-1', '${B}', 'x', 'x')`),
    ).rejects.toThrow(/row-level security|violates row-level security policy/);
  });

  it("同一 norm_key 在两个租户各一条是合法的（去重键已改为租户内唯一）", async () => {
    await createCandidate(forTenant(db, A), { title: "重复热点", summary: "", heat: 1 });
    await createCandidate(forTenant(db, B), { title: "重复热点", summary: "", heat: 1 });
    expect(await listCandidates(forTenant(db, A))).toHaveLength(2);
  });
});
```

- [ ] **Step 2: 跑一次确认失败** → FAIL（policy 还不存在，B 能读到 A 的行）

- [ ] **Step 3: 每张租户表三条语句（ENABLE + FORCE + policy）**

`migrate()` 里在建表之后追加执行（放在 `packages/db/src/migrate.ts` 的 `applyRls(db)`，
不靠 drizzle-kit 生成——drizzle 的 RLS 表达力不够）：

```sql
CREATE ROLE heron_app NOLOGIN;          -- 连接池再 SET ROLE，避免密码进 URI

ALTER TABLE candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE candidates FORCE  ROW LEVEL SECURITY;   -- 表 owner 也不许绕过
CREATE POLICY tenant_isolation ON candidates
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

`current_setting(..., true)` 的 `true` 是 missing_ok：GUC 没设时返回 NULL，比较结果为 NULL，
policy 判假 → **读不到任何行**。这条是 fail-closed 的实现处，注释必须写明，
将来谁改成 `COALESCE(...)` 兜默认值就会把隔离打开。

- [ ] **Step 4: forTenant 单点写 GUC**

```ts
export interface TenantDb {
  readonly db: Db;
  readonly tenantId: string;
}

/**
 * 租户上下文只有一个入口。
 * 每个仓储函数第一个参数收 TenantDb，SQL 里显式写 tenant_id = $n（为了走索引和可读），
 * RLS 是第二道防线（为了漏写时不泄漏）——两道防线缺一道都不算做完。
 */
export function forTenant(db: Db, tenantId: string): TenantDb {
  return { db, tenantId };
}

export async function withTenant<T>(db: Db, tenantId: string, fn: (t: TenantDb) => Promise<T>): Promise<T> {
  return db.transaction(async (tx) => {
    await tx`SELECT set_config('app.tenant_id', ${tenantId}, true)`;
    return fn(forTenant(tx as unknown as Db, tenantId));
  });
}
```

- [ ] **Step 5: 跑绿** → `pnpm --filter @heron/db test` 4 条 RLS 断言全 PASS；`pnpm check` 全绿。

---

## T4：仓储层异步化（本轮最贵的一步）

> **已完成（2026-10-22）**：`pg/{tasks,candidates,artifacts,identity,usage,admin}.ts` 落地，
> `tasks.test.ts` 15 条 + `rls.test.ts` 新增 1 条子表隔离，`pnpm check` 四闸 EXIT=0（47 条测试全绿）。
> 四处**计划文本不够或写错**的地方，全是实测换来的，后来的步骤必须按这里的口径走：
>
> 1. **`withTenant` 只设 GUC 不够，必须 `SET LOCAL ROLE heron_app`**。Step 4 的示例代码里只有 `set_config`——
>    真库的登录角色是超管/表 owner，而**超管无视 RLS，`FORCE` 也只管到表 owner**，那就是「以为隔离生效了其实没有」。
>    T3 的绿灯靠测试 `beforeAll` 里一次 session 级 `SET ROLE` 才成立，仓储层接手后降权必须进 `withTenant`；
>    且必须是 `SET LOCAL`——session 级降权会把还回连接池的那条连接一并变成降权连接（`withAdmin` 同理，方向相反更要紧）。
>    角色名只能写字面量：`SET ROLE` 后面接不了绑定参数。
> 2. **子表（`task_events` / `gate_results` / `renders`）不带 `tenant_id`，套不了 `tenant_id = GUC` 那条策略**。
>    Step 3 的 `applyRls` 循环只覆盖 `TABLES_WITH_TENANT`；少了给子表补的
>    `EXISTS (SELECT 1 FROM tasks WHERE tasks.id = <子表>.task_id AND tasks.tenant_id = GUC)`，
>    「按 task_id 查子表」这条最自然的查询就是一条跨租户读别人的行的口子——而 `artifacts.ts` 里每一条这样的查询都指望它。
> 3. **FK 完整性检查不走 RLS**：别的租户的 `users.id` 光靠外键插得进 `tasks.claimed_by` / `confirmations.confirmed_by`，
>    「确认人」会变成另一个客户的人，审计链直接接错。所以凡接受外部传入 id 的写入，归属自己查一遍——
>    落点是 `identity.ts` 的 `requireTenantUser`（本租户 + 未停用），`claimByHuman` 与 `insertConfirmation` 都先过它。
> 4. **仓储只发语句、不自开事务**（Step 4 的示例没写这条规矩，但它决定了调用方的形状）。
>    pglite 单连接且事务正占着它，回调里再用外层 `db` 句柄不是报错而是**死锁**，表现为测试超时（探针实测两次 5s）。
>    由此得一条调用方必须遵守的：`advanceTask` 抛 `GateRefusalError` 时 `gate_results` 与 `stage=failed` 已经写在
>    同一个事务里，**catch 必须在 `withTenant` 回调内**，穿出回调就是回滚，等于自己把「为什么被拒」的审计抹掉。
>
> 另外的执行事实：
>
> - `Interfaces` 里 `claimNextTask(db, owner, ttlSec)` 的第一参数实际是 `AdminDb`（Step 3 的双通道），不是裸连接；
>   `sumUsage` 同样走 `AdminDb`。特权通道的边界落成三件事：`admin.ts` 单独成模块、`pg/index.ts` **不**导出它、
>   eslint `no-restricted-imports` 按深路径 + 按名字（T8 后 barrel 直出 pg 层时才有意义）拦。
>   **flat config v10 不认 `excludedFiles`**（那是 eslintrc 的键，直接 ConfigError），块内排除要用 `ignores`。
>   拿一个临时越界文件实测过：`@heron/db` 的 `withAdmin` 与 `@heron/db/src/pg/admin` 各报一条，`forTenant` 不报。
> - 并存期间不留两份定义：`GateEvaluator` / `GateRefusalError` 从 `packages/db/src/tasks.ts` 挪进
>   `@heron/domain`（`gates/refusal.ts`），`sha256` 收进 `packages/db/src/hash.ts` 单点。
> - 枚举不再漂移：`candidates.status` / `publishes.status` / `users.role` 的 CHECK 与 TS 类型都由
>   `@heron/contracts` 里那一份常量生成（`schema/enums.ts` 的 `inEnum()` + 列上的 `$type<T>()`）。
>   `$type` 只改 TS 不改 SQL，`drizzle-kit generate` 实测报 "No schema changes"，迁移文件不动。
> - driver 的坑三个：`notInArray` 的参数签名不收 `readonly Stage[]`，所以 `TERMINAL` / `CLAIMABLE_EXCLUDED`
>   声明成 `Stage[]`；`sum(numeric)::text` 回**字符串**（钱的形状本来就该是字符串），`count(*)::int` 回数字；
>   drizzle 的 `.set()` 会跳过 `undefined` 字段，等于旧 SQLite 版的 COALESCE 语义——`updatePublish` 靠它。
> - Step 1 的 13 条断言：10 条搬进 `tasks.test.ts`。「迁移建齐表」由 `schema.test.ts` 的三张清单比对承担
>   （v2/v3 表集合不同，照抄是假对照），`fact_check 至少两源`、`news_shots 复合外键` 按计划 4 推走，
>   去向写在测试文件头。自加 5 条：租约归属、跨租户确认人、终态不被改写、双通道各自站定角色且退出即还原、
>   用量记在各自租户而账单走特权通道。
> - 搬断言时发现并修掉一个真 bug：`writeFailed` 的 UPDATE 用 `NOT IN (terminal)` 兜住终态之后，
>   仍然无条件追加一条 `to_stage=failed` 的台账——已发布任务遇到迟到异常时，台账最后一行会说它失败了。
>   现在 UPDATE 带 `.returning()`，没改到行就不写事件。

**Files:**
- Create: `packages/db/src/pg/{tasks,candidates,artifacts,identity,usage}.ts`（旧 SQLite 仓储此时仍是唯一在用的实现）
- Modify: `packages/db/src/home.ts`（**新增** `DATABASE_URL`，`DB_FILE` 保留到 T8；路径单点这条不变）
- Test: `packages/db/src/pg/tasks.test.ts`（旧 `db.test.ts` 的 13 条断言逐条在此重立一遍，见 Step 1）

**Interfaces:**
- Consumes: `TenantDb`（T3）
- Produces: `createCandidate(tdb, input): Promise<string>`、`claimByHuman(tdb, taskId, userId): Promise<Task>`、
  `advanceTask(tdb, input): Promise<Task>`、`insertConfirmation(tdb, input): Promise<string>`、
  `recordUsage(tdb, input): Promise<void>`、`claimNextTask(db, owner, ttlSec): Promise<Task | null>`
  （**worker 领任务不带租户上下文**：它是跨租户取活的调度方，用 `heron_owner` 特权角色，见 Step 3）

- [ ] **Step 1: 把旧 SQLite 的 13 条断言逐条翻成 async 版**（不许少任何一条）

`claim 审计列`、`candidate.status 随 stage 演进`、`结构化拒绝进 gate_results.rejections`、
`未提供评估器不许静默放行`、`确认指纹不匹配拒发布`、`render version 唯一`、
`publish 幂等键唯一`、`credential_path/sha256 成对 CHECK`、`aigc_posture 只有一条现行`、
`fact_check 至少两源`、`news_shots 复合外键` —— 前 10 条在本计划里保留，
`fact_check`/`news_shots` 两条随 news capability 外挂表推到计划 4（在测试文件顶部写明去向）。

```ts
it("未挂评估器的闸不许静默放行", async () => {
  const tdb = forTenant(db, A);
  const task = await seedTaskTo(tdb, "factcheck");
  await expect(advanceTask(tdb, { taskId: task.id, owner: "w1", to: "rendering" }))
    .rejects.toThrow(/gate\.no_evaluator/);
  const rows = await listGateResults(tdb, task.id);
  expect(rows.find((r) => r.gate === "contract")?.passed).toBe(false);
});
```

- [ ] **Step 2: 跑一次确认失败** → FAIL：签名仍是同步的 `openDb(path)`

- [ ] **Step 3: 特权读写分离（多租户必须有的那条线）**

| 通道 | 角色 | 用途 | RLS |
|---|---|---|---|
| `forTenant` | `heron_app`（`SET ROLE`） | 一切业务读写 | 生效，fail-closed |
| `adminDb` | `heron_owner`（`BYPASSRLS`） | 迁移、跨租户调度领任务、账单汇总 | 不生效，只在两处允许出现 |

`claimNextTask` 与 `sumUsage` 走 `adminDb`，其余函数签名的第一个参数必须是 `TenantDb`——
类型系统因此挡住"业务代码偷偷拿特权连接"。eslint 规则加一条 `no-restricted-imports`：
`adminDb` 只许 `packages/db` 内部与 `apps/worker/src/scheduler.ts` 导入。

- [ ] **Step 4: 实现 async 仓储**（照 stack-v2 §7 的列与约束逐列搬）

```ts
export async function createCandidate(
  tdb: TenantDb,
  input: { title: string; summary: string; heat: number },
): Promise<string> {
  const key = normKey(input.title);
  if (key.length === 0) throw new Error(`标题归一化后为空，无法建立候选：${input.title}`);
  const at = nowIso();
  const rows = await tdb.db
    .insert(candidates)
    .values({ tenantId: tdb.tenantId, title: input.title, summary: input.summary, normKey: key, heat: input.heat, firstSeenAt: at, lastSeenAt: at, createdAt: at, updatedAt: at })
    .returning({ id: candidates.id });
  const id = rows[0]?.id;
  if (!id) throw new Error("候选写入后没有返回 id");
  return id;
}
```

`nowIso()` 继续由 TS 侧写入（不改成 DB `now()`）：口径单点这条比省一次传参重要。

- [ ] **Step 5: 跑绿** → `pnpm --filter @heron/db test` 全 PASS；`pnpm check` 全绿。

---

## T5：contracts + domain 的身份与权限件

> **已完成（2026-10-22）**：`domain/{password,rbac}.ts` + 两份测试、contracts 的身份件与用量件落地。
> Step 1–2 按计划先跑红（`Cannot find module './password'` / `'./rbac'`），实现后转绿；
> `pnpm check` EXIT=0（domain 23 / contracts 11 / db 48）。Files 一行写「`domain/src/index.test.ts`（追加）」，
> 实际新加的是 **`packages/contracts/src/index.test.ts`**（contracts 的第一份测试）：`LoginBodySchema` 的规范化与
> 口令下界、`AuthUserSchema` 的角色名单、`UsageRecordSchema` 的配对 refine、`TaskSchema.claimedBy` 的 uuid——
> 判据长在 schema 上，测试就该写在 schema 那层；`domain/src/index.test.ts` 里的 11 条仍只管 domain 自己的闸与状态机。
>
> 与计划文本不同的四处决定：
>
> 1. **`ClaimBodySchema` 这一步没删，推到 T7**。计划 Step 6 写「删了 `tasks.controller.ts` 立刻红——那是预期的」，
>    但 T6 之前仓库里没有会话：删掉请求体里的认领人，就没有任何来源能填 `claimed_by`，那是把一次安全修复
>    做成一次功能回退，还会让 `pnpm check` 从这一步起不再是「每一步都绿」的那道闸。落点在 contracts 的
>    `TODO(T7)`：controller 读 JWT 里的 userId，`claimByHuman` 收 user id 而不是名字。
> 2. **`ROLES` 直接替掉 `USER_ROLES`，且同一步把下游全搬完**（`schema/users.ts` 的 CHECK、`pg/identity.ts` 的
>    `NewUser`、两份 pg 测试里的 `role: "editor"`）。CHECK 生成自 contracts，「新名单 + 旧消费者」的中间态
>    根本不成立。退役的 `editor` 反过来也得被拒：`schema.test.ts` 现在断言 `ck_users_role_known` 拒掉它，
>    与 `AuthUserSchema` 那条成对——两边都认旧名字，等于谁都不认。
> 3. **`RANK` 从 `ROLES` 的顺序推**（owner 最先 → 名次最大），不在 domain 里另写一份名次表。
>    两份表的漂移方向是**静默把权限翻转**，所以 `rbac.test.ts` 先钉死顺序，再断言每个动作的允许集恰是
>    `ROLES` 的前缀（单调性）。加一档角色只改 contracts 一处。
> 4. **`verifyPassword` 对形状不对的存储值返回 false，不抛**（Step 3 的示例只处理了 `!` 前缀那一种）。
>    `password_hash` 是一个 text 列：历史值、合成 actor、别的方案的串都混在同一列里，校验函数不许被脏数据带下去；
>    `timingSafeEqual` 要求等长，长度不符就是「这不是我们写出去的哈希」，先挡掉再比。
>
> 另外的执行事实：
>
> - **T2 欠的 `aigc_posture.set_by` 在这里还了**：`text` → `uuid references(users.id)`，`TODO(T5)` 从代码里删掉。
>   配套的 `insertPosture(tdb, input)` 不是顺手多加的仓储函数，而是「FK 不受 RLS 管」那条的必然结果：
>   没有归属校验的写入路径，B 租户的人 id 能写进 A 租户「谁绕过了角标」这一列。T7 之前它没有生产调用方，
>   测试是它现在的唯一消费者（`tasks.test.ts` 新增一条越界被拒；姿态两条测试因此改走仓储而不是手插）。
> - **`USAGE_KIND_UNITS` 是 kind↔unit 唯一一份配对表**：pg 的 `ck_usage_records_kind_unit_pair` 从它生成
>   （原来那段手写 CASE 删了），`UsageRecordSchema` 的 refine 也从它生成。`schema/enums.ts` 为此拆出 `lit()`；
>   拆完用 diff 验证 kind / unit 两条 CHECK 生成的 SQL **一字未变**，才敢说这次重构没夹带迁移差异。
> - **`z.string().uuid()` 不给 TS 类型加品牌**：`Task.claimedBy` 推断出来还是 `string`，旧 SQLite 层写「周佩」
>   照样编译过。这条分叉只能靠测试说话——`index.test.ts` 断言 `TaskSchema` 拒掉自由文本，T8 删旧层时它跟着退役。
> - **迁移重新生成为单个 `drizzle/0000_neat_rogue.sql`**（slug 由 drizzle-kit 挑，文件名因此变了）。
>   与旧文件 diff 只有三处：role CHECK 名单、`set_by` text→uuid + 新增 FK、pair CHECK 被压成一行。
>   延续 T2 的判断：还没有真库应用过 0000，留两级文件只会给 T10 添一个「哪一级应用过」的问题。
> - `tasks.test.ts` 的种子用户改用 `hashPassword` 的真哈希（模块作用域算一次：scryptSync 一次约 50–100ms，
>   每个用例现算就是白烧半分钟）。`!synthetic-not-loginable` 留在 `schema.test.ts`——那测的是列形状，不是登录。
> - prettier 会连 drizzle 生成的 `meta/*.json` 一起格式化：`format:check` 是闸，生成物也得守规矩。

**Files:**
- Modify: `packages/contracts/src/index.ts`
- Create: `packages/domain/src/password.ts`, `packages/domain/src/rbac.ts`, `packages/domain/src/index.test.ts`（追加）
- Test: `packages/domain/src/password.test.ts`, `packages/domain/src/rbac.test.ts`

**Interfaces:**
- Produces: `ROLES = ["owner","admin","reviewer","operator","viewer"] as const`、`Role`
- Produces: `hashPassword(pw: string): string`、`verifyPassword(pw, stored): boolean`、`UNUSABLE_PREFIX = "!"`
- Produces: `can(role, action): boolean`、`requiredRoleFor(action): Role`
- Produces: `LoginBodySchema`、`AuthUserSchema`、`UsageRecordSchema`、`TenantSchema`
- Removes: `ClaimBodySchema`（认领人从请求体改由 JWT 得出——**这是安全修复不是简化**）

- [ ] **Step 1: 写密码哈希的失败测试**

```ts
it("同一口令两次哈希不同，但都能验过", async () => {
  const h1 = hashPassword("correct horse battery staple");
  const h2 = hashPassword("correct horse battery staple");
  expect(h1).not.toBe(h2);
  expect(verifyPassword("correct horse battery staple", h1)).toBe(true);
  expect(verifyPassword("correct horse battery staplf", h1)).toBe(false);
});

it("! 前缀的 actor 永远登不进来", () => {
  expect(verifyPassword("anything", "!synthetic-p0")).toBe(false);
});
```

- [ ] **Step 2: 跑一次确认失败** → FAIL：模块不存在

- [ ] **Step 3: 实现（node:crypto scrypt，不引原生依赖）**

```ts
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";

/** 合成 actor（p0、定时抓取）用这个前缀，verify 直接拒——审计列不许变成后门。 */
export const UNUSABLE_PREFIX = "!";

export function hashPassword(password: string): string {
  const salt = randomBytes(16).toString("hex");
  const hash = scryptSync(password, salt, 64).toString("hex");
  return `scrypt$${salt}$${hash}`;
}

export function verifyPassword(password: string, stored: string): boolean {
  if (stored.startsWith(UNUSABLE_PREFIX)) return false;
  const [scheme, salt, hash] = stored.split("$");
  if (scheme !== "scrypt" || !salt || !hash) return false;
  const actual = scryptSync(password, salt, 64);
  const expected = Buffer.from(hash, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}
```

为什么不用 argon2/bcrypt：都要原生编译或 prebuilt 二进制，而本案是单人自部署、
口令校验频率以登录计；stdlib 的 scrypt 少一条供应链，也少一类 Windows 装不上的故障。
代价是抗 GPU 破解弱于 argon2，将来接公网自助注册时要换（换哈希是加法，不是重写）。

- [ ] **Step 4: RBAC 纯函数 + 测试**

```ts
export const ACTIONS = ["task.claim", "review.confirm", "settings.write", "aigc.set", "user.manage", "usage.read"] as const;
const MIN_ROLE: Record<Action, Role> = {
  "task.claim": "operator",
  "review.confirm": "reviewer",   // 规格书的「人点确认」不能是只读账号
  "settings.write": "admin",
  "aigc.set": "admin",            // AIGC 姿态改了就能绕开角标，是最高危的配置写
  "user.manage": "owner",
  "usage.read": "viewer",
};
export function can(role: Role, action: Action): boolean {
  return RANK[role] >= RANK[MIN_ROLE[action]];
}
```

测试至少断言：`can("viewer","review.confirm") === false`、`can("operator","review.confirm") === false`、
`can("reviewer","review.confirm") === true`、`can("admin","settings.write") === true`、
`requiredRoleFor("review.confirm") === "reviewer"`（界面置灰要显示"需要 reviewer 及以上"这句人话）。

- [ ] **Step 5: contracts 增删**

```ts
export const ROLES = ["owner", "admin", "reviewer", "operator", "viewer"] as const;
export type Role = (typeof ROLES)[number];

export const LoginBodySchema = z.object({
  email: z.string().email().toLowerCase(),
  password: z.string().min(12),   // 与 hashPassword 的口令强度一致，短的直接拒
});
export const AuthUserSchema = z.object({
  id: z.string().uuid(), tenantId: z.string().uuid(), email: z.string(),
  displayName: z.string(), role: z.enum(ROLES),
});
```
`TaskSchema.claimedBy` 改成 `z.string().uuid().nullable()`；`ClaimBodySchema` 删除。

- [ ] **Step 6: 跑绿** → `pnpm --filter @heron/domain test` + `pnpm check` 全绿
  （`ClaimBodySchema` 删除会让 `tasks.controller.ts` 立刻红——那是预期的，T7 修）。

---

## T6：认证传输——cookie 里的 JWT，而不是 Bearer

> **已完成（2026-10-22）**：`auth/{auth.module,auth.service,auth.controller,jwt.strategy}.ts` +
> `common/decorators/{public,require-action,current-user}.decorator.ts` +
> `common/guards/{jwt-auth,roles}.guard.ts` + `db/{pg,seed-owner}.ts` + `transport.ts` 落地，
> Step 2 的判据测试先行、实现后转绿。`pnpm check` EXIT=0：**api 37**（6 个文件，含一份真 socket 的
> e2e 8 条）/ contracts 16 / domain 24 / db 49。
>
> 下面每一条都是执行时撞出来的，不是事后补的口径——按重要度排：
>
> 1. **`@heron/db/pg` 这个子路径是这一步新开的，而且它必须先开**。SQLite 层与 pg 层都有
>    `openDb` / `migrate` / `Db`，并到包根就是让调用方靠名字前缀猜自己连的是哪个库——「以为连的是生产库
>    其实连的是内存库」这类事故的形状。子路径让「用哪条数据面」写在 import 语句上。配套三处：
>    `packages/db/package.json` 的 `exports`、`apps/api/tsconfig.json` 的 `paths`（node 解析不会从
>    node_modules 取 `.ts`）、`eslint.config.mjs` 的 `no-restricted-imports`。特权通道仍**不从出口导出**：
>    `withAdmin` / `AdminDb` / `OWNER_ROLE` 现在对 `@heron/db` 与 `@heron/db/pg` 两个入口都禁。
> 2. **api 不许 import `drizzle-orm`**（架构闸：ORM 只活在 db 包内）。这条在写 service 测试时才撞上——
>    当时测试想直接 `update(users).set({disabledAt})`。于是数据层补齐 **7 个函数**：
>    `createTenant` / `findTenantBySlug` / `setTenantSuspended` / `isTenantActive`（新建 `pg/tenants.ts`）、
>    `getUserByEmail` / `getUserById` / `disableUser`（追加进 `pg/identity.ts`）。
>    `disableUser` **不复用 `requireTenantUser`**：后者把「已停用」当作「没有这个人」，
>    用它做停用就成了重复停用必抛——判据不同的两件事不能共用一个读法。
> 3. **登录必须有第二个坐标**：`LoginBodySchema` 加 `tenant`（工作空间 slug），因为邮箱只在租户内唯一
>    （T2 的 `u_users_tenant_email`）。契约只要求非空，**不做 lower / trim**——规范化归
>    `ck_tenants_slug_lower` 与 `findTenantBySlug`，契约抢先改写过一遍，界面回填的输入就和库里对不上了。
>    那条 CHECK 是新加的，迁移因此重新生成为 `drizzle/0000_far_surge.sql`；与旧文件 diff 只有这一条约束，
>    逐字看过才敢说没夹带。`schema.test.ts` 补一条「slug 只留一种规范形」，`index.test.ts` 补「ACME / 带空格 都收」。
>    （2026-10-27 修订：`tenant` 字段仍在契约里、脚本可显式指定空间，但界面已不再发送它——realm 改由账号形状判
>    （带 `@` 走租户、不带走平台运营），不带空间码的邮箱登录只在**恰好一个**空间口令通过时放行。见根 README。）
> 4. **计划里的 `maxAge: "12h"` 删了**。不是嫌它多余，是它在 passport-jwt 4.0.1 里**根本不存在**
>    （`lib/` grep 零命中，`@types/passport-jwt` 也没有）——留着就是一个静默失效的假期限，
>    读代码的人以为有时钟，实际唯一的时钟是 `expiresIn` 写进 payload 的 `exp`。同理 `algorithms` 不写：
>    `secretOrKey` 是对称密钥，passport-jwt 默认就是 HS256 家族，写了不改变行为只增加一处漂移。
> 5. **没有 `PassportModule`，也没有 `passport.initialize()`**。核实过装在手上的版本：
>    `@nestjs/passport` 12.0.0 的 `PassportModule` 里没有 `configure()`，passport 0.7 的 `authenticate()`
>    不再要求先 initialize；策略在 `PassportStrategy` 的构造函数里就注册进 passport 单例了。
>    所以 `AuthModule` 的 `imports` 只有 `PgModule` + `JwtModule.registerAsync`——**registerAsync 是必需的**：
>    `jwtSecret()` 在读 env 时就会拒绝启动，写在模块求值期等于让「没配密钥」变成「import 就炸、
>    栈里看不出是谁 import 的」。
> 6. **`Reflector` 从 `@nestjs/core` 导出，不是 `@nestjs/common`**（Nest 12）。写成 common 的症状是
>    `TypeError: Reflector is not a constructor`，而且只在 DI 解析时炸——`APP_GUARD` 那两行同理。
> 7. **`@RequireAction(action)` 替掉计划里的 `@RequireRole(minRole)`**。声明「要 reviewer 及以上」有两条路：
>    抄一份角色下限，或引用动作让 `MIN_ROLE` 继续是唯一一份表。选后者——界面置灰读的也是那张表，
>    路由上写死角色就是第二份真相，而且它会漂向「松的那个」。名单外的动作名（拼错的字符串）红，
>    不许悄悄变成一条无门槛路由：`isAction()` 因此进 domain。
> 8. **守卫对外给 Promise，判据本身同步**。同步 `throw` 的守卫在自己的单测里不是 rejection
>    （`expect(...).rejects` 直接拿到抛出），`canActivate` 于是写成
>    `Promise.resolve().then(() => this.decide(context))`；判据留在同步的 `decide`，读起来还是一段直线逻辑。
> 9. **tsx 不执行 `emitDecoratorMetadata`**，所以每个 DI 参数都显式 `@Inject(...)`。
>    省成裸类型不是风格问题：它到启动期才炸，而炸点是别人家的模块。
> 10. **安全头用 Nest 12 原生 `app.useSecurityHeaders()`，因此本案不装 helmet**（它与 helmet 8 同一套默认值，
>     还顺手去掉 `X-Powered-By`）。CSP **只 report-only**：本案的版式链路是 SFC → 构建 → headless 截图 →
>     ffmpeg 合成，一条 `script-src 'self'` 配错的症状是渲染出来的画面全黑，没人会往安全头上想。
>     违例清单等 T9 在浏览器里收，收紧后才摘 reportOnly——在那之前 CSP 不算已生效的防线，
>     `x-frame-options: DENY`（本案没有任何正当的内嵌用途，而被嵌入的页面带着会话 cookie）与
>     `referrer-policy: same-origin`（深链里有任务 id）现在是实拦的。
> 11. **`enableCsrfProtection()` 没开**。它是按 `Sec-Fetch-Site` 判的，vite 的 dev proxy 出去的头是
>     `cross-site`，症状是开发机上登录随机 403——一个「安全功能」把开发路径打死，最后一定被整个注掉。
>     选定 `SameSite=Lax` 作为同一风险的缓解（跨站 POST 带不上这个 cookie），这是**决定**不是遗漏。
> 12. **会话 cookie 故意不带 `Max-Age` / `Expires`**：关浏览器即登出，长期有效只由 `exp` 决定，
>     两个时钟留一个。`Secure` 由 `HERON_COOKIE_SECURE` 明写，**只认 `0` / `1`，其它值启动就炸**——
>     猜错方向等于让会话走明文，而这正是没人会用眼睛看出来的那种错。
> 13. **短 TTL 的 JWT 撤销不掉，所以每次请求回库复查**（`userFor`）：人还在、没停用、**租户没停租**。
>     最后一条是执行时补的：`setTenantSuspended` 原本只挡登录，已签发出去的票在 TTL 内照旧好使，
>     「关在门外」就成了只管进门。现由 `isTenantActive(tdb)` 在同一趟复查里兜住，
>     service 与 e2e 各有一条测试钉它（「登进来之后才停租」）。停用/删号同一条路。
> 14. **口令校验前先烧一次 scrypt**（`TIMING_EQUALIZER_HASH`）：租户不存在、账号不存在、口令错三条分支
>     的响应时间原本差一次哈希（约 50–100ms），那正好是「这个邮箱注册过没有」的证词——
>     文案统一成一句话还不够，时间也得统一。
> 15. **`/api` 前缀归进 `applyTransport(app)`**，`main.ts` 不再单独调 `setGlobalPrefix`。
>     这是 e2e 逼出来的：测试自己拼 `createServer(app.getHttpServer())` 时漏了前缀，8 条全 404，
>     而其中「安全头」那条只看响应头——**404 也带安全头**，于是一个假通过混在 7 条真失败里。
>     现在主进程与测试共用同一个函数，且那条测试先 `expect(res.status).toBe(200)` 再点头。
> 16. **Step 5 的三条 curl 换成了自动化 e2e**（`auth.e2e.test.ts`：pglite + `app.listen(0)` + 真 fetch）。
>     它测的是接线性质——`@Public` 真的公开、Set-Cookie 真带那几个属性、401 与 403 分得对、
>     别的密钥签的 token 进不来、停用让旧票立刻失效——单测一份都覆盖不到。
>     连接池由 `PgConnection` 的 `onModuleDestroy` 关掉：不关它，SIGTERM 之后进程还赖着，
>     容器要等到超时才被杀；e2e 里 `app.close()` 顺带把 pglite 关了，这条也因此被测到。
> 17. **api 是本案唯一的迁移方**（`PgModule` 的 factory 里 `openDb` + `migrate`）。依据是实测
>     drizzle 的 migrator 不加咨询锁——两个进程同时迁移就是竞争建表。所以 **T8 的 worker 必须等 api 健康**
>     再起来，而不是自己也 migrate 一遍。
> 18. **`pnpm db:seed-owner` 是这一步新加的命令**：全局守卫一上，一个账号都没有的 api 谁也进不来，
>     第一个 owner 必须由人显式发起。脚本幂等、口令不回显、**已有账号时拒绝改口令**（它只该建号，
>     不该成为一把能重置别人口令的万能钥匙）。`DATABASE_URL` 没配时 factory 直接拒绝启动，
>     消息里带上该跑的那条 compose 命令。
>
> 还没做、也不是遗漏的：真 Postgres 上的 HTTP 往返（这台机器的 Docker Desktop 引擎没运行，归 **T11**；
> e2e 用的是 pglite，单连接内存态，测不出并发跨租户竞态）；**登录没有限流**，爆破窗口在 scrypt + 12h TTL
> 之下仍然开着，需要 T7/T9 之间补一条按 (tenant, email, ip) 的退避；`health` 仍只探 SQLite（T7）；
> **web 界面在 T9 之前会被全局守卫挡在门外**（`/api/tasks` 一律 401），README 已把过渡状态和 curl 入口写明。

**决策依据（已核实）**：`apps/web/src/composables/useTaskStream.ts:12` 用的是
`useEventSource("/api/events")`。`EventSource` 不能带请求头，Bearer 方案的进度流会直接失去鉴权，
而"进度流不鉴权"在多租户平台上等于把别人的任务进度广播给你。**所以传输层只能是 cookie。**

**Files:**
- Create: `apps/api/src/auth/{auth.module.ts,auth.service.ts,auth.controller.ts,jwt.strategy.ts}`
- Create: `apps/api/src/common/guards/jwt-auth.guard.ts`, `roles.guard.ts`
- Create: `apps/api/src/common/decorators/{public,require-role,current-user}.decorator.ts`
- Modify: `apps/api/src/main.ts`（cookie 解析 + 安全响应头）, `apps/api/src/app.module.ts`
- Test: `apps/api/src/auth/auth.service.test.ts`

**Interfaces:**
- Consumes: `AuthUserSchema`、`verifyPassword`、`can`、`getUserByEmail`
- Produces: `POST /api/auth/login` → `Set-Cookie: heron_session=…; HttpOnly; SameSite=Lax; Path=/`
- Produces: `GET /api/auth/me` → `AuthUser`
- Produces: `CurrentUser` 装饰器给出 `AuthUser`（`req.user`）

- [ ] **Step 1: 装依赖（实测 latest）**

```bash
pnpm --filter @heron/api add @nestjs/jwt@12.0.3 @nestjs/passport@12.0.0 passport@0.7.0 passport-jwt@4.0.1
pnpm --filter @heron/api add -D @types/passport-jwt@4.0.1 @types/passport@1.0.17
pnpm --filter @heron/api add cookie-parser@1.4.7 -D @types/cookie-parser
```
`@nestjs/passport` peer 要 `@nestjs/common ^11||^12` ✓；`@nestjs/jwt` peer `^8..^12` ✓。

- [ ] **Step 2: 写 service 的失败测试**（不发 HTTP，只测判据）

```ts
it("口令错时报的是同一句话，不区分「用户不存在」", async () => {
  await createUser(db, { tenantId: A, email: "a@b.c", passwordHash: hashPassword("right-there-12"), role: "reviewer" });
  const wrong = await service.login(A, "a@b.c", "wrong-there-123");
  const absent = await service.login(A, "nobody@b.c", "whatever-12345");
  expect(wrong).toEqual(absent);
  expect(wrong).toBeNull();
});

it("跨租户同邮箱不许登录（B 的账号不能用 A 的口令进来）", async () => {
  expect(await service.login(B, "a@b.c", "right-there-12")).toBeNull();
});

it("disabled 的账号即使口令对也不给 token", async () => { /* 置 disabledAt 后断 null */ });
```

「用户不存在」与「口令错」返回同一个东西是刻意的：前者会泄露邮箱枚举面。
这条在测试里钉住，别在 review 时被当成"错误信息不清楚"改掉——界面文案统一写「邮箱或口令不正确」。

- [ ] **Step 3: JWT 载荷与策略**

```ts
interface JwtPayload { sub: string; tid: string; role: Role; }   // 不放进 email：改名要重签
```
`JwtStrategy` 用 `passport-jwt` 的 cookie 抽取器，`secretOrKey` 来自
`JWT_SECRET`（`packages/db/src/home.ts` 导出；**未设置就启动失败**，
不许有硬编码默认密钥——本地开发由 `.env` 提供，`.env.example` 加一条 `HERON_JWT_SECRET=`）。

```ts
super(PassportStrategy(Strategy), {
  jwtFromRequest: (req) => req.cookies?.heron_session ?? null,
  secretOrKey: JWT_SECRET,
  maxAge: "12h",
});
```
`validate(payload)` 里回库读一次 `users`（拿 `disabledAt`），因为短 TTL 的 JWT 撤销不掉；
读不到就返回 null → 401。这是"token 说你有身份，DB 说你还有没有"的分工。

- [ ] **Step 4: 全局守卫 + @Public**

`app.module.ts` 用 `APP_GUARD` 依次注册 `JwtAuthGuard`、`RolesGuard`；
`health.controller.ts` 与 `auth.controller.ts#login` 挂 `@Public()`。
health 公开的理由写进注释：compose/nginx 的健康探针要能打到它，且它只报进程与库通不通，
不报任何租户数据。

- [ ] **Step 5: 跑绿 + 手工验一次**

```bash
pnpm check
pnpm api:dev   # 另一终端
curl -i -c /tmp/j -X POST http://127.0.0.1:3000/api/auth/login -H 'content-type: application/json' -d '{"email":"dev@localhost","password":"dev-password-12"}'
curl -i -b /tmp/j http://127.0.0.1:3000/api/tasks
curl -i        http://127.0.0.1:3000/api/tasks   # 期望 401
```
Expected: 带 cookie → 200 且只看到本租户任务；不带 → 401。

---

## T7：把身份接进闸与认领（替掉自由文本）

> **已完成（2026-10-23）**：api 的业务面全部切到 Postgres（tasks / review / settings / events / health），
> `main.ts` 不再打开 SQLite；`confirmByHuman`（人的闸）+ `ReviewModule`（`GET /api/reviews/pending`、
> `POST /api/reviews/:taskId/confirm`）落地；认领人只来自会话。`pnpm check` EXIT=0：
> **api 40**（7 个文件，含 `review.e2e.test.ts` 3 条真 socket 红线）/ contracts 19 / domain 24 / db 66。
>
> 下面每一条都是执行时撞出来的，不是事后补的口径——按重要度排：
>
> 1. **闸拒的审计痕差点被事务回滚吞掉，是 e2e 抓到的**。`confirmByHuman` 在拒绝时先写
>    `gate_results` + `stage=failed` 再抛 `GateRefusalError`；服务层原本在 `withTenant(...)` **外面**
>    catch 并翻成 409，异常穿出事务 → 整笔回滚 → 库里只剩「还在 review、没有任何闸记录」，
>    而 HTTP 响应看起来完全正常。现在 catch 写在事务回调里，返回 `{ok:false,message}` 标记再在外层翻 409；
>    非闸拒（任务不存在、状态机不许、人已停用）仍抛出并回滚，因为它们没有要留的痕。
>    这条与 db 层测试里的 `confirmRefused()` 是同一个坑，第二次踩才说明光在仓储层注释不够。
> 2. **events 原来的全局 Subject 是跨租户广播**，不是「先这样」的临时件：任何人都能收到别人租户的
>    `task.updated`。现在按会话里的 tenant 建每条流，游标是 `task_events.id`（identity 主键有空洞，
>    所以按 id 记而不是 `+1`），首次订阅从 `maxTaskEventId` 起，没人订阅的租户不轮询。
>    控制器去掉 `@Public`——那条守卫本身就是隔离。
> 3. **api 仍不许 import `drizzle-orm`**：health 探针要 `SELECT version()`，把它挪进 db 包成
>    `dbPing(db)`（两个 driver 的 `execute` 返回形状不同，这个差异只该 db 包知道）。
>    健康报告因此报 `pglite · PostgreSQL 18.6` / `postgres · …`（driver + 版本），
>    不是计划里写的硬编码 `db.engine=postgres`——「探针打的是内存库」必须在报告里一眼可见。
> 4. **人的闸与 worker 的转移分成两个函数**（`confirmByHuman` vs `advanceTask`）：人不持租约，
>    硬套租约检查会造出「worker 心跳一过期，人在界面上点确认被拒」；verdict 由确认动作本身产出
>    （指纹 vs 当前 render），不是调用方递一个 `passed: true`。三条 ruleId 与规格书 §5 对齐：
>    `human.render_missing` / `human.fingerprint_mismatch` / 放行时 note 带版本号。
>    `requireTenantUser` 现在同一次查询里带回 role——台账记的是「当场那个岗位上的人」。
> 5. **确认动作写四样**：`gate_results`（为什么）、`confirmations`（谁/何时/哪一版指纹）、
>    `tasks.stage`、`task_events`（台账）。少任何一样，「人审 A、系统发 B」就无从对账。
> 6. **路由收在 `/reviews` 一处**，而不是计划里的 `/tasks/:id/confirm`：待审队列与确认是同一件事的
>    两端，拆进 tasks 模块会让「谁在审、怎么确认」的接线散成两处。RBAC 用动作名不用角色名：
>    `review.confirm`（reviewer）、`settings.write`（admin）——界面置灰读同一张 `MIN_ROLE`。
> 7. **e2e 的夹具需要一条 review 态任务，而租约只有跨租户调度方（AdminDb）能发**，api 侧拿不到
>    特权通道也不该拿。做法是每个用例建一个新租户，夹具里直接 `t.db.update(tasks).set({stage:"review"})`，
>    并用「本租户此刻只有 1 条任务」的断言守住那条不带 WHERE 的 UPDATE；worker 推进到 review 的
>    状态机路径仍由 `packages/db` 的 `tasks.test.ts` 覆盖。这是夹具的取舍，不是生产捷径。
>    Step 4 的手工 curl 同样换成了 e2e（与 T6 的先例一致）：viewer 403、旧指纹 409、当前指纹 201
>    三条都在 `review.e2e.test.ts` 里，claim 的接线由 tasks 服务与守卫的单测/既有 e2e 覆盖。
> 8. **计划里的 `apps/api/src/database/{module,tokens}.ts` 由已有的 `db/pg.ts` 承担**：
>    `PG_DB` token、`createPgDb` factory（没配 `DATABASE_URL` 拒绝启动）、`PgConnection` 的销毁钩子
>    都已在 T6 就位，再建一套目录只是换个地方写同一件事。
> 9. **`main.ts` 删掉了 `getDb()` 启动调用**，api 进程不再碰 SQLite；但 **runner 到 T8 之前仍写 SQLite**，
>    所以过渡窗口里 runner 产出的任务不会出现在 api 界面上，p0 的合成 actor 也留到 T8 换掉。
>    这是计划本身的两层并存窗口（上限 T8），不是事故。
> 10. **`packages/db/vitest.config.ts` 是补出来的**：pg 层每条用例都起一份真 Postgres（pglite），
>     冷启动 1–2s，默认 5s 超时在整包跑时会抖；放宽到 30s 并写明理由。这个文件还要进
>     `tsconfig.include`，否则 typescript-eslint 的 project service 报「not found by the project」。
> 11. **`ClaimBodySchema` 删除，认领接口不再收请求体**；web 的 `tasksApi.claim(taskId)` 同步去掉 body。
>     contracts 新增 `ReviewItemSchema` / `ConfirmBodySchema`（指纹是唯一入参；确认人与时间由服务端定）。
> 12. **`settings` 的 PUT 挂 `settings.write`**，GET 仍要登录但不要求角色——保留天数不是秘密，
>     改它才是管理动作。
>
> 还没做、也不是遗漏的：真 Postgres 上的 HTTP 往返与并发 RLS（Docker Desktop 引擎未运行，归 **T11**；
> e2e 用 pglite，单连接内存态）；**登录限流**仍缺（T6 记下的同一条，爆破窗口在 scrypt + 12h TTL 下开着）；
> CSP 仍 report-only（T9 在浏览器里收违例后再收紧）；**web 还没有登录页**（T9），
> 所以界面在 T9 之前会被全局守卫挡住（`/api/tasks` 一律 401）。

**Files:**
- Create: `apps/api/src/database/{database.module.ts,tokens.ts}`（`DB` token + async `useFactory`，
  替掉现在每个 service 自己调 `getDb()` 的写法——多租户下连接是异步的，实例只能由容器提供一次）
- Modify: `apps/api/src/main.ts`（`await getDb()` 后再 `NestFactory.create`；`@Inject` 仍不许省略）
- Modify: `apps/api/src/tasks/*`（claim 用 `@CurrentUser()`；service 注入 `DB` 并把每个仓储调用 await 到底）
- Create: `apps/api/src/review/*`（确认动作今天**不在 api 里**，只在 runner 的 p0 里——审片台没有可点的接口）
- Modify: `apps/api/src/settings/settings.controller.ts`（aigc 姿态与保留天数挂 `@RequireRole`）
- Modify: `apps/api/src/health/health.controller.ts`（探针改成 `SELECT 1` + 报 `db.engine=postgres`）
- Test: `apps/api/src/tasks/tasks.controller.test.ts`, `apps/api/src/review/review.controller.test.ts`

**Interfaces:**
- Consumes: `CurrentUser`、`requireRole`、`can`、`insertConfirmation(tdb, …)`、`claimByHuman(tdb, taskId, userId)`
- Produces: `POST /api/tasks/:id/claim`（无请求体）、`POST /api/tasks/:id/confirm`、`GET /api/reviews/pending`

- [ ] **Step 1: 写失败测试（两条必须的红线）**

```ts
it("viewer 点确认得到 403，并且文案报出需要哪个角色", async () => {
  const res = await app.handle(request("POST", "/api/tasks/xxx/confirm", { fingerprint: "ab12" }, "viewer"));
  expect(res.status).toBe(403);
  expect(JSON.parse(res.body).message).toContain("需要 reviewer 及以上");
});

it("确认的指纹与当前 render 不一致时拒绝进入 approved，并留下闸记录", async () => {
  // 这是规格书 §5「人审 A、系统发 B」的守门测试
});
```

- [ ] **Step 2: 跑一次确认失败** → FAIL：路由不存在

- [ ] **Step 3: 实现**
  - `claim`：`claimed_by = user.id`，`task_events.reason` 写「认领人 {displayName}（{role}）」；
    不再有 `owner` 字段进请求体。
  - `confirm`：先 `can(role,"review.confirm")`，再校验 `fingerprint === 当前 render.fingerprint`，
    然后 `advanceTask(...,"approved")` 且 `evaluateGate` 里由**这次确认**产出 `human` 闸的 verdict
    ——闸的判定必须来自真实动作，不许写 `passed: true`。
  - runner 的 p0：把 `confirmer: "P0_FAKE"` 换成一个合成 actor（`passwordHash = "!synthetic-p0"`），
    在测试里 `ensureSyntheticUser(db, "p0@local")` 取得 uuid。

- [ ] **Step 4: 跑绿** → `pnpm check` 全绿；手工 curl 走完 claim → confirm 两次（一次对指纹、一次错指纹）。

---

## T8：worker 改名 + 全链路 async

> **已完成（2026-10-23）**：`apps/worker`（原 runner）全链路 async，SQLite 层与依赖删净；
> `pnpm check` EXIT=0：**db 54 / api 40 / worker 3 / contracts 19 / domain 24**，
> `pnpm p0` EXIT=0（十态跑完，末行仍报「P0 出口判据成立」并打印
> `tenant=00000000-0000-4000-8000-000000000001 actor=<uuid>`）。
>
> 下面是执行时定下的口径与撞出来的问题，按重要度排：
>
> 1. **`review` 不再是 worker 可领的状态**：`CLAIMABLE_EXCLUDED` 加 `review`，`walkTask` 推进到 review 后
>    在**同一个事务里** `releaseLease` 再停。租约的含义是「有个进程正在跑」，不是「任务被占用」；
>    不放租约的话，`recoverExpiredLeases` 会把正在等人审的任务当成死进程，超次数后记成失败。
>    人确认（`confirmByHuman`）把 stage 推到 approved 之后任务重新可领，worker 接着跑 publishing。
>    `tick` 的输出在 review 处明确写「租约已释放，等人确认」。
> 2. **跨租户认领必须把 tenantId 带回来**：`withAdmin` 的扫描面是全库，`Task` 里没有租户列；
>    `claimNextTask` 现在返回 `{ task, tenantId }`，worker 才能为这一条任务开 `withTenant`。
>    这不是多余字段，是把「调度方知道自己在替谁干活」显式化。
> 3. **worker 里的闸拒也必须 catch 在 `withTenant` 回调内**：T7 在 api 踩过一次（回滚吞掉审计痕），
>    worker 侧是同一个坑。`tick` 区分两种错：`GateRefusalError` 在事务内落 `gate_results` + `stage=failed`
>    并返回，其它错误让它回滚、再在新事务里 `markFailed`——两种都不吞原因。
> 4. **worker 明确拒绝人的闸**（`human.not_worker_decidable`）：不再复制一份指纹比对逻辑，
>    人闸的唯一实现留在 `confirmByHuman`。worker 撞上 human 判定就退，不猜。
> 5. **worker 永不迁移**：启动只做 `waitForSchema`（轮询 `select id from tasks limit 1`，2 分钟超时），
>    api 仍是唯一迁移方。compose 用 api 的 healthcheck 当闸（worker 的 `depends_on` 里 db 与 api 都是
>    `service_healthy`）；`waitForSchema` 是第二道保险，防的是「api 起来了但迁移没跑完」。
>    `dbPing` 的返回形状按 driver 归一（pglite 在 `.rows`，postgres-js 本身就是数组），只让 db 包知道。
> 6. **p0 默认走内存 pglite**：`pnpm p0` 不依赖外部数据库；设了 `DATABASE_URL` 就连真库，
>    但**不迁移**，只 `waitForSchema`——与 worker 同一条规矩。合成 actor（password_hash 前缀 `!`）
>    承担认领人与确认人，十态跑完后打印 tenant/actor，证明假任务在真实租户上下文里完成。
> 7. **SQLite 层删除清单**：`packages/db/src/{connection,tasks,artifacts,retention}.ts`、`db.test.ts`、
>    `packages/db/migrations/`、包导出与 `better-sqlite3` 依赖。`sweepVideoDir` 没有任何调用方，
>    随 retention 一起删；`RETENTION_SETTING_KEY` 保留（设置项还在，清扫任务未接，README 已写明），
>    等真渲染产物落盘后再以 pg 形态回来。
> 8. **锁文件里的 `better-sqlite3` 是历史解析残留，不是依赖**：drizzle-orm 把它列为 optional peer，
>    旧锁文件里留着已解析版本，`pnpm install --lockfile-only` 因「已是最新」不会清它。
>    处理：在干净目录用同一组 package.json 重新生成锁文件再替换（不删 node_modules 重建整个依赖树）。
>    随生成浮到补丁版的只有 rolldown 1.2.14→1.2.15、@oxc-project/types 0.154→0.155、
>    @types/lodash 26→27；`pnpm check` 与 `pnpm build` 均绿。现在锁文件里只剩 drizzle-orm 的 peer
>    元数据，`pnpm why better-sqlite3` 为空。
> 9. **`.npmrc` 里的 pnpm 工程设置是死键**：实测 pnpm 12 只从 `pnpm-workspace.yaml` 读
>    （临时工程设 `auto-install-peers=false` 仍自动装 peer）。三行迁到 `autoInstallPeers` /
>    `engineStrict` / `strictPeerDependencies`，`.npmrc` 留给 registry 与认证。
> 10. **compose 的 worker 门禁已在本机渲染验证**（daemon 未起，只到 `config`）：
>     `docker compose --env-file <临时> config` EXIT=0，api healthcheck 与 worker 的
>     `depends_on: {db: service_healthy, api: service_healthy}` 都在；缺 `POSTGRES_PASSWORD` 时
>     EXIT=1 并报中文理由。真 `build/up` 仍是 T11。
> 11. **`apps/runner` 是 `mv` 改名的**：仓库当前全部未跟踪，`git mv` 无意义（计划 Step 1 已注明）。
>     `AGENTS.md` / `CLAUDE.md` / `GEMINI.md` 由 harness-factory 生成，本轮不动。
>
> 还没做、也不是遗漏的：api 与 worker 的真库并发 RLS（T11）；web 登录页（T9）；
> 登录限流与 CSP 收紧（T7/T9 记下的同两条）；保留期清扫任务（见第 7 条）。

**Files:**
- Rename: `apps/runner/` → `apps/worker/`，`@heron/runner` → `@heron/worker`
- Modify: 根 `package.json`（`worker:dev`、`p0`）、`README.md`、`eslint.config.mjs`、stack-v2 §4 目录树
- Modify: `apps/worker/src/{main,pipeline,tick,p0,capabilities,config}.ts`

- [ ] **Step 1: `git mv`（不是新建再删）并改包名**

```bash
git mv apps/runner apps/worker
```
（注：仓库当前全部未跟踪，实际用 `mv apps/runner apps/worker`；这条差异在报告里说明，别假装做过 git mv。）

- [ ] **Step 2: 把 `walkTask` 变成 `async`，逐 await 到底**

`advanceTask` 现在是 Promise；`p0.ts` 里所有 `const x = f(db, …)` 补 `await`，
`main.ts` 的循环改成 `for await` / `await tick()`。
渲染与发布的占位闸保持"占位放行 + 写明占位"的语义不变。

- [ ] **Step 3: p0 出口判据不变，且必须看到租户列生效**

```bash
pnpm p0
```
Expected: 末行仍是「P0 出口判据成立」，并新增一行打印 `tenant=<DEFAULT_TENANT_ID> actor=<uuid>`，
证明假任务是在一个具体租户上下文里跑完十态的。

- [ ] **Step 4: `pnpm check` 全绿**（含 web/api/worker 三处对旧包名的引用都已改）。

---

## T9：web 的登录与角色置灰

> **已完成（2026-10-23）**：登录页、auth store、路由守卫、顶栏身份、审片台置灰全部落地，
> `pnpm check` EXIT=0（含 web 3 条新测试），`pnpm web:build` 绿，浏览器里真跑过四种身份路径。
>
> 下面是执行时定下的口径与撞出来的问题，按重要度排：
>
> 1. **`AuthUser` 补了 `tenantName` / `tenantSlug`**：计划要求顶栏显示租户名，但身份件里只有 `tenantId`，
>    否则前端要么再打一次展示查询、要么只显示 uuid。现在 login 从 `TenantRef` 取，守卫的 `userFor`
>    用 `getActiveTenant` 一次查询同时判「租户还在不在」和「租户叫什么」——停租与改名都不留窗口。
>    e2e 的精确键集合同步收紧为 `displayName/email/id/role/tenantId/tenantName/tenantSlug`。
> 2. **`@heron/domain` 的 barrel 把 `node:crypto` 漏进了浏览器**（本轮最贵的一课）：
>    `password.ts` 走 `node:crypto`，而 web 从 barrel 里取 `can()`，Vite 把该模块外置成空壳。
>    **构建是绿的，导航到审片台时才炸**（lazy chunk 求值），正好被浏览器验证抓住。
>    处理：新增 `packages/domain/src/browser.ts`（纯判定，不含 password），web 的 Vite alias 与
>    tsconfig `paths` 都指向它。规矩：**服务端专用的 Node 依赖不许从 web 用的入口漏出去**；
>    `pnpm web:build` 不能当浏览器验证的替代品。
> 3. **web 的测试环境在计划里写着「已配好」，实际不存在**：补了 `vitest.config.ts`（jsdom +
>    共享 alias）、`apps/web` 的 `test` 脚本与 `vitest 5.0.5` / `jsdom 30.1.3` 依赖，
>    tsconfig include 也带上配置文件。现在 `pnpm test` 递归覆盖 web。
> 4. **审片台原来是占位页**，置灰改用 domain 的 `can()` + `requiredRoleFor()`，前端不重写一份判定；
>    **两种灰要给两种理由**：角色不够显示「需要 reviewer 及以上」，角色够但闸未启用显示
>    「门禁位未全开：闸门点不动才算闸门」。按钮用 `ElTooltip` 包一层 span，disabled 的按钮才会出 tooltip。
> 5. **`App.vue` 拆出 `AppShell`**：`auth.user` 存在才挂 shell，任务刷新与 SSE 只在登录后启动。
>    否则登录页自己会去打 `/api/tasks`，每条 401 都把登录页刷一遍错误。
> 6. **401 是事件，不是抛错**：`http.ts` 暴露 `setUnauthorizedHandler`，`guards.ts` 注册它
>    （避免 router ↔ http 相互 import）；401 清 store 后 `replace` 到 `/login?redirect=<当前路径>`。
>    实测：重启开发台让旧 cookie 对应的用户消失，浏览器下一次 `me` 就退到
>    `/login?redirect=/candidates`，表单登录后回到原页。
> 7. **登录后的回跳只接受字符串 `redirect`**：query 可能是数组，`expose` 给 `router.replace` 前必须收窄；
>    实测 viewer 表单登录后从 `/login?redirect=/candidates` 回到候选流，顶栏显示「演示工作空间 / 只读」。
> 8. **加了一个 pglite 开发台**（`apps/api/src/dev/pglite-server.ts`，根脚本 `pnpm api:dev:pglite`）：
>    Docker daemon 没起（T11），浏览器验证需要一个能登录的真 API。它用真 `AppModule` 与真传输层，
>    只把 `PG_DB` 换成内存 pglite；**必须显式给 `HERON_DEMO_PASSWORD`（≥12 位，无默认值）**，
>    种一个租户、owner/reviewer/viewer 三个账号与两条候选；数据重启即清空。README 已登记。
> 9. **浏览器实测证据**（Chrome DevTools MCP，localhost:5173 → 3000）：
>    未登录进 `/review` 被送到 `/login?redirect=/review`；viewer 登录后顶栏「演示工作空间 / 只读 / 演示只读」，
>    候选流两条种子任务，SSE 已连接；审片台「确认」disabled，悬浮 tooltip 与行内提示都是
>    「需要 reviewer 及以上」；换成 reviewer 后角色提示消失，按钮仍因四道闸未启用而灰，
>    提示变成「门禁位未全开：闸门点不动才算闸门」；全程 console 无错误。
> 10. **停后台任务要连子进程一起收**：`TaskStop` 只杀 `pnpm` 包装，`tsx` 子进程还占着 3000，
>     再起就是 `EADDRINUSE`。重启开发台时按 PID 结束 `pglite-server.ts` 那个 node 进程。
>
> 还没做、也不是遗漏的：真 Docker 上的登录/RLS 并发验证（T11）；登录限流与 CSP 收紧仍是 T7 记下的账；
> 顶栏还没有「退出登录」按钮（需要服务端清 cookie 的接口，T9 未要求）。

**Files:**
- Create: `apps/web/src/views/LoginView.vue`, `apps/web/src/stores/auth.ts`, `apps/web/src/api/auth.ts`
- Modify: `apps/web/src/api/http.ts`（`credentials: "same-origin"` 是默认，但要把 401 变成事件）、
  `apps/web/src/router/index.ts`（导航守卫）、`apps/web/src/components/AppShell.vue`（顶栏显示租户名与角色）

- [ ] **Step 1: 先测 store**（`apps/web/src/stores/auth.test.ts`，vitest + jsdom 已在 web 配好）

```ts
it("401 之后 store 清空并把用户送去登录页", async () => { /* expect(route.path).toBe("/login") */ });
it("reviewer 及以上才允许点确认", () => { /* can(role, "review.confirm") 直接复用 domain 那份实现 */ });
```

**置灰规则不许在前端重写第二遍**：`import { can } from "@heron/domain"`。
api/worker/web 三处共用一份判定，是 §8 承重点一的同一条规矩。

- [ ] **Step 2: 路由守卫 + 未登录跳转**（`beforeEach`：`meta.public` 放行，其余无 `me` 先 `GET /api/auth/me`）。
- [ ] **Step 3: `pnpm web:build` 绿 + 浏览器里真跑一遍**：登录 → 看到本租户任务列表 → 用 viewer 账号登录后
  「确认」按钮置灰且 tooltip 报「需要 reviewer 及以上」。
  （不能只构建不看：这条是 UI，构建绿不代表功能绿。）

---

## T10：把现有 SQLite 开发库搬进 Postgres

> **已完成（2026-10-23）**：`packages/db/src/tools/migrate-sqlite.ts`（核心）+ `migrate-sqlite.cli.ts`（CLI）、
> 根脚本 `pnpm db:import-sqlite`；5 条测试全绿，`pnpm check` EXIT=0；旧库已在 WSL2 的真 Postgres 上真搬过一遍。
>
> 执行口径与撞出来的事，按重要度排：
>
> 1. **核心与 CLI 必须分开**：测试 import 核心模块，若 `main()` 写在文件底部，跑测试就会连库、改文件。
>    `migrate-sqlite.ts` 只导出 `importLegacySqlite` / `retireLegacyDb` / `IMPORTED_TABLES`；
>    `DATABASE_URL`、`openDb`、参数解析、console 输出全在 `.cli.ts`。核心的入参是 `{ db, sourcePath, tenantSlug }`。
> 2. **空目标是硬门槛**：`IMPORTED_TABLES` 十张表任何一张非空就整体拒绝（报「已有数据」并列出占用表）。
>    导入不是合并——旧行与新行混在一张表里就再也对不出账。tenant 里已有的 owner（users）不算占用，
>    合成 actor 才往里加；因此重跑会在空目标检查处停下，不会产生半份数据。
> 3. **合成 actor 覆盖两处自由文本**：`tasks.claimed_by` 与 `confirmations.confirmer`（以及 `aigc_posture.set_by`）
>    全部落成 `legacy+<名字 sha256 前 12 位>@heron.invalid`，`displayName = 旧库合成：<名字>`，role `viewer`，
>    `passwordHash` 以 `!` 开头（`verifyPassword` 永远 false）。审计写得出「是谁」，但这行永远登不进来。
>    空串处理：`confirmer` / `set_by` 是必填，空串直接报错；`claimed_by` 空串落 null。
> 4. **`task_events` 的旧 id 不保留**：新表主键是 identity，事件表没有外部引用，id 不是契约。
>    按旧 `id` 顺序逐条插，保证新 identity 的次序与旧库事件次序一致；其余九张表连 id 一起原样搬。
> 5. **JSON 列逐列解析并带标签报错**：`renders.timings`、`gate_results.rejections` 解析失败时报
>    「渲染 <id> 的 timings 的 JSON 解析失败」并截前 80 字符；`passed` 由 SQLite 的 0/1 转 boolean。
> 6. **计数断言在同一个事务里**：插完逐表 `count(*)` 与源行数比对，任何一张不一致就抛
>    「迁移计数对不上：<表> 旧库 N 行、新库 M 行。整笔导入回滚」，整个 `withTenant` 事务不提交。
>    这也顺带验了 RLS：所有写入都发生在租户上下文里，十张表都带对了 `tenant_id`。
> 7. **旧库退场连 WAL/SHM 一起改名**：`data/heron.sqlite` → `data/heron.sqlite.pre-multi-tenant`，
>    `-wal`、`-shm` 跟主文件同进退（只搬主文件会把还没 checkpoint 的已提交数据留在原地）。
>    目标已存在就拒绝，绝不覆盖——搬错了要能回头对账。
> 8. **真库证据**（WSL2 Ubuntu-22.04，postgres:18.6，租户 `default` = `5dba1e21-…`）：
>    settings 1 / feed_sources 0 / candidates 6 / tasks 6 / task_events 48 / gate_results 24 /
>    renders 6 / confirmations 6 / publishes 6 / aigc_posture 0，与旧库逐表一致；
>    2 个合成 actor（p0-script / p0-placeholder），6 条确认记录都映射到 placeholder；
>    RLS 复查：`heron_app` + 别的租户 uuid 看 `tasks` = 0，切回 `default` = 6、`task_events` = 48；
>    退场后的主文件 sha256 `2c1cfb54…`，`-wal`/`-shm` 都在。
> 9. **`node:sqlite` 的 `DatabaseSync` 足够读旧库**，没有为一次性迁移引入依赖；旧库有约 997KB WAL，
>    只读主文件会漏行，正常打开（含 WAL）才读到了 48 条事件。
> 10. **前置的 compose 坑（属 T11 账）**：postgres:18 的数据目录变成 `/var/lib/postgresql/<主版本>/docker`，
>     卷必须挂 `/var/lib/postgresql`；挂旧的 `/var/lib/postgresql/data` 会被 entrypoint 拒绝启动。
>     `deploy/docker-compose.yml` 已改，`heron-db-1` 起来后 `PostgreSQL 18.6`。
>
> 还没做、也不是遗漏的：导入不负责建租户/跑迁移（api 是唯一迁移方，先 `pnpm db:seed-owner`）；
> 旧库里 `aigc_posture` 本来就是 0 行，没有可搬的姿态记录；`feed_sources` 也是 0 行。

**Files:**
- Create: `packages/db/src/tools/migrate-sqlite.ts` + 根脚本 `"db:import-sqlite"`

- [ ] **Step 1: 读旧库、写新库，逐表打印计数**；`confirmations.confirmer` 的自由文本落成合成 actor 的 uuid。
- [ ] **Step 2: 旧库文件改名保留**（`data/heron.sqlite.pre-multi-tenant`，不删——搬错了要能回头对账）。
- [ ] **Step 3: 报告断言**：迁移前后 `tasks`/`candidates`/`task_events` 行数一致，不一致就非零退出。

---

## T11：部署形态（需要 Docker Desktop 运行）

**前置事实**：本机 `docker --version` = 28.5.1，但 **daemon 未运行**
（`docker info` 报 `open //./pipe/dockerDesktopLinuxEngine: ... file not found`）。
T11 的任何一步在 daemon 起来之前都无法验证，所以**不许提前写 compose 然后声称能用**。

**已交付（2026-10-22，用户明确要 compose 作为部署物）**：`Dockerfile`（单阶段，api 与 worker 共用一个
镜像）、`.dockerignore`、`deploy/docker-compose.yml`（`db` = postgres:18-alpine + 命名卷、`api`、`worker`，
`heron_data` 卷共享）。做到并验过的：`docker compose -f deploy/docker-compose.yml config` 渲染正确
（build context 解析到仓库根、`${POSTGRES_PASSWORD}` 插值进 `DATABASE_URL`、healthcheck 里 `$$` 正确转义），
缺口令时**拒绝启动**（`${POSTGRES_PASSWORD:?}` 生效，报错带中文理由）。
`postgres:18` 不是随手挑的：pglite 0.5.11 实测报 `PostgreSQL 18.6`，主版本对齐才叫测的就是生产行为。

**没做到，别当已做**：`up -d` 未在真 daemon 上跑过一次；镜像里没有 ffmpeg 与 Chromium，P4 接真渲染时要
么装进镜像要么把 base 换成 `mcr.microsoft.com/playwright`；`redis` 与 `minio` 属于计划 2/3，现在塞进 compose
只会起两个没人连的服务。

> **已完成（2026-10-23，WSL2）**：上面「daemon 未运行」是 2026-10-22 的事实。现在 WSL2（Ubuntu-22.04）里的
> Docker 28.5.1 + compose 2.40.0 daemon 可用，仓库挂在 `/mnt/d/zhoupei/heron`，Step 1–4 全部在真 daemon 上跑过；
> 下面按撞出来的顺序记，前三条都是**部署当天才会暴露、四个探针全绿也发现不了**的错。
>
> 1. **api 在容器里绑 127.0.0.1，宿主端口映射连不进来**（最贵的一条）：容器 healthcheck 走 loopback 是绿的，
>    `docker ps` 显示 healthy，宿主 `curl` 却得到空响应（WSL 侧 `HTTP 000`、Windows 侧 `curl: (52) Empty reply`）。
>    修法：`apps/api/src/config/runtime.ts` 新增 `apiHost()`（默认 `127.0.0.1`，保住开发机不向局域网裸奔），
>    compose 的 api 服务显式 `HOST: "0.0.0.0"`。补了 runtime 测试（默认回环、显式 0.0.0.0 两段），api 7 条测试绿。
>    **healthcheck 证明不了监听地址对了**——它和 app 活在同一个 loopback 里。
> 2. **corepack 的 pnpm 缓存按 $HOME 分，构建期 root 与运行期 node 不共享**：worker 第一次启动卡在
>    「Downloading the pnpm 12.14.0 binary」，部署启动依赖公网。修法：Dockerfile 去掉 `corepack enable`，
>    改成 `npm install -g pnpm@12.14.0`，所有容器用户共用一份；重建后两个服务启动日志不再出现下载。
> 3. **pnpm 12 的 `verifyDepsBeforeRun` 会在每次 run 前核对依赖**：镜像 `NODE_ENV=production`，它想裁掉 devDeps；
>    而这个镜像**故意**要 devDeps（进程就是靠 tsx 跑 TS），运行用户 node 又改不动构建期 root 装的 node_modules，
>    实测每次启动刷一条 `Permission denied ... /app/node_modules/vue-eslint-parser`。修法：`pnpm-workspace.yaml`
>    加 `verifyDepsBeforeRun: false`。依赖只在 install 时变，脚本执行期不该再动它。
>    （实测 `pnpm install --frozen-lockfile` 在 `NODE_ENV=production` 下仍装了 devDeps，否则 `web:build` 先炸。）
> 4. **Step 3 的并发证据**：新增 `packages/db/src/pg/rls.concurrency.test.ts`，只在显式给 `HERON_IT_DATABASE_URL`
>    时运行（不读 `.env`，默认门禁不许往开发者的真库塞数据）。真 Postgres 上两个租户各开一个事务、各插一条候选
>    并保持 300ms 重叠：两边都只读到自己的行；A 拿 B 的 `tenant_id` 写入被 RLS 拒绝（错误在 drizzle 的 `cause`
>    里，断言同时看 message 与 cause）。跑完清理租户与行，复查 6/6/48 原样、无 `rls-*` 残留。
> 5. **Step 2/4 证据**：`build` + `up -d` 后 `heron-db-1`/`heron-api-1` healthy、`heron-worker-1` 启动即领任务；
>    `heron_heron_data` 属主 `node:node`；`GET /api/health` 返回 `ok:true`、`db.detail = postgres · PostgreSQL 18.6`、
>    `webDist = /app/apps/web/dist/index.html`（四道闸仍按设计置灰）；宿主 `pnpm check` EXIT=0
>    （db 59 通过 + 1 跳过、api 41、worker 3、web 全绿），`pnpm p0` 在真库上跑完十态到 published（8 条事件）。
>    真 HTTP 往返：`POST /api/auth/login` 201 与 `GET /api/auth/me` 200 都返回 owner + `default` 租户身份。
> 6. **`pnpm p0` 的竞态**：跑 p0 前先 `docker compose stop worker`——容器 worker 与 p0 里的 `tick()` 会抢同一条假任务。
>    停 worker 时日志会出现一次 `ERR_PNPM_RECURSIVE_RUN_FIRST_FAIL`，那是 SIGTERM 的正常退场，不是崩溃；
>    `start` 回来后日志干净。
>
> 还没做、也不是遗漏的：镜像仍没有 ffmpeg/Chromium（P4）；反向代理、TLS 与备份未补（计划 2/3 的部署账）；
> worker 仍是单实例串行。T9 留下的 Windows 端 pglite 开发台（PID 46852）在起容器前已停：它占着宿主 3000，
> 会让端口映射看起来「起来了但连不上」。

- [ ] **Step 1:** 起 Docker Desktop（用户操作），`docker info` 返回 ServerVersion。
- [ ] **Step 2:** `docker compose ... build` 与 `up -d` 真跑一轮：`api`/`worker` 起得来、`GET /api/health` 绿、
      命名卷 `heron_data` 属主是 `node`（镜像里 chown 过，首次挂载才继承得到）。
- [ ] **Step 3:** 真库上跑一次 RLS 并发测试（pglite 单连接测不出的那条）：两租户同时写 candidates。
- [ ] **Step 4:** `pnpm check` + `pnpm p0`，`GET /api/health` 报 `db.engine=postgres`。
- [ ] **Step 5:** 计划 2/3 落地时再往 compose 加 `redis`、`artifacts`，同时补 `deploy/` 的反向代理与备份。

---

## 之后的计划（本计划明确不做，避免"顺手加回来"）

| 计划 | 内容 | 为什么不能塞进计划 1 |
|---|---|---|
| 2 | Redis + BullMQ 队列：租户→分片队列 + DB 配额闸门（**OSS BullMQ 6.x 无按组限速**，per-group 是 Pro） | 公平性方案要先定，写错要重来 |
| 3 | 产物进对象存储（S3/MinIO）+ 渲染池水平扩 | `ArtifactStore` 接口 + fs/s3 两实现，属独立子系统 |
| 4 | 平台账号 OAuth + 凭证加密落库 + news 外挂表回归 | 依赖平台申请结果，且有外部审批耗时 |
| 5 | 配额/账单导出 + 支付接缝 | 账本在计划 1 已有，价格是另一件事 |

## 自检（写完后回看规格书）

- 覆盖：N1 全链路（保留）、N2 服务端发布（计划 4）、N5 认领（T7 换成真人）、N7 定位（工具化在计划 4 落地，本计划先把租户边界立住）、N8 OAuth（计划 4）、N9 三负载（T8 + 计划 2）、N10 Postgres/Redis（T1–T4 + 计划 2）。
- 未覆盖且**故意**没进本计划的：`packages/ui`/`packages/config`（当前会是空壳）、`generation/` 域（生成本来就是 tasks 流水线）、在线支付。
- 类型一致性：`TenantDb`、`Role`、`AuthUser`、`forTenant`、`can` 在 T2–T9 的引用处拼写一致。
