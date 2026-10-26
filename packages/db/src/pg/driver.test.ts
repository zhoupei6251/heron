import { randomUUID } from "node:crypto";
import { join } from "node:path";

import { desc, sql } from "drizzle-orm";
import { readMigrationFiles } from "drizzle-orm/migrator";
import { afterEach, describe, expect, it } from "vitest";

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

  it("迁移建出 tenants，插入与查询走同一份 schema 定义", async () => {
    db = openDb("memory");
    await migrate(db);

    await db.insert(tenants).values({
      id: DEFAULT_TENANT_ID,
      name: "默认租户",
      slug: "default",
      suspendedAt: null,
      createdAt: "2026-10-22T00:00:00.000Z",
    });

    const rows = await db
      .select({ id: tenants.id, slug: tenants.slug })
      .from(tenants)
      .orderBy(desc(tenants.createdAt));
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
      createdAt: "2026-10-22T00:00:00.000Z",
    });

    await migrate(db);

    const rows = await db.select({ id: tenants.id }).from(tenants);
    expect(rows).toHaveLength(1);
  });

  it("0000 已有数据只追加 0001：默认列回填，旧数据与迁移记账保持", async () => {
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
    const tenantId = randomUUID();
    const userId = randomUUID();
    const feedId = randomUUID();
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
    expect(
      (
        await db.execute(
          sql`SELECT display_name, password_hash, session_version FROM users WHERE id = ${userId}`,
        )
      ).rows,
    ).toEqual([{ display_name: "Old", password_hash: "!old", session_version: 0 }]);
    expect(
      (await db.execute(sql`SELECT name, enabled FROM feed_sources WHERE id = ${feedId}`)).rows,
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
      createdAt: "2026-10-22T00:00:00.000Z",
    };
    const dup = { ...value, id: randomUUID() };
    await db.insert(tenants).values(dup);

    type SqlError = { message: string; cause?: { message?: string } };
    let err: SqlError = { message: "同 slug 的第二行居然插进去了" };
    try {
      await db.insert(tenants).values({ ...dup, id: randomUUID() });
    } catch (e) {
      err = e as SqlError;
    }
    // drizzle 把驱动错误包成「Failed query: …」，真正的 23505 文案在 cause 上——
    // 以后区分唯一冲突与其它 SQL 错误得往 cause 里看，别只看 message。
    expect(`${err.message} ${err.cause?.message ?? ""}`).toMatch(
      /duplicate key value violates unique constraint "tenants_slug_unique"/,
    );
  });
});
