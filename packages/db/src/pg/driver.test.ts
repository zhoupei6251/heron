import { join } from "node:path";

import { desc, sql } from "drizzle-orm";
import { readMigrationFiles } from "drizzle-orm/migrator";
import { afterEach, describe, expect, it } from "vitest";

import { snowflakeId } from "../id";
import { DEFAULT_TENANT_ID, migrate, openDb, tenants, type Db } from "./index";

/**
 * 这一层跑的是 pglite（嵌入式真 Postgres，实测报 18.6），不是 mock。
 * 迁移与约束的行为因此与 compose 里的 pg 18 同源；测不出的是并发跨租户竞态，归 T11。
 */
describe("pg 驱动与迁移", () => {
  let db: Db | null = null;

  afterEach(async () => {
    await db?.close();
    db = null;
  });

  it("pglite 内存库是真 Postgres，且 driver 标记可用来选 migrator", async () => {
    db = openDb("memory");
    expect(db.driver).toBe("pglite");

    // execute 的返回形状按 driver 而异（pglite 给 {rows,…}，postgres-js 给数组），
    // 所以这里只在 pglite 上读版本，业务查询一律用 Drizzle 构造器。
    const raw = (await db.execute(sql`SELECT version() AS v`)) as unknown as {
      rows: { v: string }[];
    };
    expect(raw.rows[0]?.v).toMatch(/^PostgreSQL 18/);
  });

  it("迁移建出 sys_tenant，插入与查询走同一份 schema 定义", async () => {
    db = openDb("memory");
    await migrate(db);

    await db.insert(tenants).values({
      id: DEFAULT_TENANT_ID,
      name: "默认租户",
      slug: "default",
      suspendedAt: null,
      createTime: "2026-10-22T00:00:00.000Z",
    });

    const rows = await db
      .select({ id: tenants.id, slug: tenants.slug })
      .from(tenants)
      .orderBy(desc(tenants.createTime));
    expect(rows).toEqual([{ id: DEFAULT_TENANT_ID, slug: "default" }]);
  });

  it("迁移可重复执行：第二次不重建表，也不丢已有行", async () => {
    db = openDb("memory");
    await migrate(db);
    await db.insert(tenants).values({
      id: DEFAULT_TENANT_ID,
      name: "默认租户",
      slug: "default",
      suspendedAt: null,
      createTime: "2026-10-22T00:00:00.000Z",
    });

    await migrate(db);

    const rows = await db.select({ id: tenants.id }).from(tenants);
    expect(rows).toHaveLength(1);
  });

  it("已有数据的库升到新骨架：行不丢、外键还对得上、哨兵租户归一到 1", async () => {
    db = openDb("memory");
    const migrations = readMigrationFiles({ migrationsFolder: join(__dirname, "../../drizzle") });
    const baseline = migrations[0]!;
    // Reproduce the already-migrated live baseline without editing 0000 or a live DB.
    for (const statement of baseline.sql) await db.execute(sql.raw(statement));
    await db.execute(sql`CREATE SCHEMA drizzle`);
    await db.execute(
      sql`CREATE TABLE drizzle.__drizzle_migrations (id serial PRIMARY KEY, hash text NOT NULL, created_at bigint)`,
    );
    await db.execute(
      sql`INSERT INTO drizzle.__drizzle_migrations (hash, created_at) VALUES (${baseline.hash}, ${baseline.folderMillis})`,
    );
    // 老库的 id 还是 uuid：这里必须用 uuid 写进去，迁移负责把它们换成 numeric 的雪花 id
    const tenantId = "00000000-0000-4000-8000-000000000001";
    const userId = "11111111-1111-4111-8111-111111111111";
    const feedId = "22222222-2222-4222-8222-222222222222";
    const at = "2026-10-22T00:00:00.000Z";
    await db.execute(
      sql`INSERT INTO tenants (id, name, slug, created_at) VALUES (${tenantId}, 'Existing', 'existing', ${at})`,
    );
    await db.execute(
      sql`INSERT INTO users (id, tenant_id, email, display_name, password_hash, role, created_at, updated_at) VALUES (${userId}, ${tenantId}, 'old@example.com', 'Old', '!old', 'owner', ${at}, ${at})`,
    );
    await db.execute(
      sql`INSERT INTO feed_sources (id, tenant_id, name, url, created_at) VALUES (${feedId}, ${tenantId}, 'Old feed', 'https://example.com', ${at})`,
    );
    await migrate(db);
    await migrate(db);
    // 表名换了、行还在：uuid 被映射成 numeric，值不再长得像 uuid
    const tenantRows = await db.execute(
      sql`SELECT id::text, slug FROM sys_tenant WHERE slug = 'existing'`,
    );
    expect(tenantRows.rows).toHaveLength(1);
    const tenantRow = tenantRows.rows[0] as { id: string; slug: string };
    expect(tenantRow.slug).toBe("existing");
    // 哨兵租户：老库里那行固定 uuid 映射后落到 4，迁移把它归一到代码里的 DEFAULT_TENANT_ID = 1
    expect(tenantRow.id).toBe(DEFAULT_TENANT_ID);

    // 外键没断：成员的 tenant_id 与租户行指的是同一个数
    const memberRows = await db.execute(
      sql`SELECT display_name, password_hash, session_version, tenant_id::text FROM sys_user WHERE email = 'old@example.com'`,
    );
    expect(memberRows.rows).toEqual([
      {
        display_name: "Old",
        password_hash: "!old",
        session_version: 0,
        tenant_id: tenantRow.id,
      },
    ]);
    expect(
      (
        await db.execute(
          sql`SELECT name, enabled FROM news_feed_source WHERE url = 'https://example.com'`,
        )
      ).rows,
    ).toEqual([{ name: "Old feed", enabled: true }]);
    expect(
      (await db.execute(sql`SELECT hash FROM drizzle.__drizzle_migrations ORDER BY created_at`))
        .rows,
    ).toEqual(migrations.map((m) => ({ hash: m.hash })));
  });

  it("slug 唯一：同 slug 的第二行被数据库拒掉", async () => {
    db = openDb("memory");
    await migrate(db);

    const value = {
      name: "撞名",
      slug: "dup",
      suspendedAt: null,
      createTime: "2026-10-22T00:00:00.000Z",
    };
    const dup = { ...value, id: snowflakeId() };
    await db.insert(tenants).values(dup);

    type SqlError = { message: string; cause?: { message?: string } };
    let err: SqlError = { message: "同 slug 的第二行居然插进去了" };
    try {
      await db.insert(tenants).values({ ...dup, id: snowflakeId() });
    } catch (e) {
      err = e as SqlError;
    }
    // drizzle 把驱动错误包成「Failed query: …」，真正的 23505 文案在 cause 上——
    // 以后区分唯一冲突与其它 SQL 错误得往 cause 里看，别只看 message。
    expect(`${err.message} ${err.cause?.message ?? ""}`).toMatch(
      /duplicate key value violates unique constraint "u_sys_tenant_slug"/,
    );
  });
});
