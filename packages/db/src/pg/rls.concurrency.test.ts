import { randomUUID } from "node:crypto";

import { inArray } from "drizzle-orm";
import { afterAll, describe, expect, it } from "vitest";

import { nowIso } from "../time";
import { withAdmin } from "./admin";
import { openDb, type Db } from "./driver";
import { withTenant } from "./rls";
import { candidates, tenants } from "./schema/index";
import { createTenant } from "./tenants";

/**
 * pglite 是单连接，跑不出「两个事务同时存在」；这条只在真 Postgres 上跑。
 * 显式给 HERON_IT_DATABASE_URL 才启用（不读 .env）：默认门禁不许往开发者的真库里塞数据。
 */
const URL = process.env.HERON_IT_DATABASE_URL ?? "";

function candidateRow(tenantId: string, title: string): typeof candidates.$inferInsert {
  const at = nowIso();
  return {
    id: randomUUID(),
    tenantId,
    title,
    normKey: title,
    firstSeenAt: at,
    lastSeenAt: at,
    createdAt: at,
    updatedAt: at,
  };
}

describe.skipIf(!URL)("真库并发租户隔离", () => {
  const db: Db | null = URL ? openDb(URL) : null;
  const created: string[] = [];

  afterAll(async () => {
    if (!db) return;
    try {
      if (created.length > 0) {
        await withAdmin(db, (a) =>
          a.db.delete(candidates).where(inArray(candidates.tenantId, created)),
        );
        await withAdmin(db, (a) => a.db.delete(tenants).where(inArray(tenants.id, created)));
      }
    } finally {
      await db.close();
    }
  });

  it("两个租户同时写 candidates：各写各的、互相读不到、越界写被拒", async () => {
    if (!db) throw new Error("HERON_IT_DATABASE_URL 没配");
    const suffix = `${String(Date.now())}-${randomUUID().slice(0, 8)}`;
    const tenantA = await createTenant(db, { name: "并发检查 A", slug: `rls-a-${suffix}` });
    created.push(tenantA);
    const tenantB = await createTenant(db, { name: "并发检查 B", slug: `rls-b-${suffix}` });
    created.push(tenantB);

    const writeAndRead = (tenantId: string, title: string) =>
      withTenant(db, tenantId, async (t) => {
        await t.db.insert(candidates).values(candidateRow(tenantId, title));
        // 事务里停一下，确保两个事务真的重叠，而不是排队跑完
        await new Promise((resolve) => setTimeout(resolve, 300));
        return t.db
          .select({ title: candidates.title, tenantId: candidates.tenantId })
          .from(candidates);
      });

    const [rowsA, rowsB] = await Promise.all([
      writeAndRead(tenantA, "A 的候选"),
      writeAndRead(tenantB, "B 的候选"),
    ]);

    expect(rowsA).toEqual([{ title: "A 的候选", tenantId: tenantA }]);
    expect(rowsB).toEqual([{ title: "B 的候选", tenantId: tenantB }]);

    const crossWrite = await withTenant(db, tenantA, (t) =>
      t.db.insert(candidates).values(candidateRow(tenantB, "A 替 B 写")),
    ).then(
      () => null,
      (error: unknown) => error,
    );
    expect(crossWrite).toBeInstanceOf(Error);
    const err = crossWrite as Error & { cause?: Error };
    expect([err.message, err.cause?.message].filter(Boolean).join(" | ")).toMatch(
      /row-level security/i,
    );
  });
});
