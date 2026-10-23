import { randomUUID } from "node:crypto";

import { desc, sql } from "drizzle-orm";
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
