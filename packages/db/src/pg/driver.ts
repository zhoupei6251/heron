import { drizzle as drizzlePgLite, type PgliteDatabase } from "drizzle-orm/pglite";
import { drizzle } from "drizzle-orm/postgres-js";
import { PGlite } from "@electric-sql/pglite";
import { sql } from "drizzle-orm";
import postgres from "postgres";

import * as schema from "./schema/index";

/**
 * 两个 driver 的查询面同形（都是 drizzle 的 PgDatabase），所以按 pglite 的形状标一次。
 * 生产路径（postgres-js）由 compose 起来后的集成测试验，见计划 T11。
 */
export type Db = PgliteDatabase<typeof schema> & {
  close(): Promise<void>;
  /** migrator 有两个入口（pglite / postgres-js），靠这个标记选，不靠猜构造函数。 */
  driver: "pglite" | "postgres";
};

/**
 * 「连内存库还是连真库」只允许有一处判断。
 * 测试跑 pglite（不需要 Docker，也不需要每个分支起一个 Postgres），进程连 DATABASE_URL；
 * 两边执行的是同一份生成迁移，否则测的就不是生产行为。
 * pglite 是单连接，测不出并发跨租户竞态——那类断言归 T11。
 *
 * 同步返回：两个 driver 的构造都不需要 await，而 PGlite 本身是个 thenable，
 * drizzle 在执行时自己等它 ready（迁移与插入在没显式 waitReady 的前提下跑通，已实测）。
 */
export function openDb(target: "memory" | (string & {})): Db {
  if (target === "memory") {
    const client = new PGlite();
    const db = drizzlePgLite(client, { schema }) as unknown as Db;
    db.close = () => client.close();
    db.driver = "pglite";
    return db;
  }
  const client = postgres(target, { max: 10, prepare: false });
  const db = drizzle(client, { schema }) as unknown as Db;
  db.close = () => client.end();
  db.driver = "postgres";
  return db;
}

/**
 * 健康探针：一句 version()，答上来就说明「池活着、库答话」。放在这一层而不是 api 里，
 * 是因为 `execute` 的返回形状按 driver 不同——pglite 给 QueryResult（行在 .rows 上），
 * postgres-js 给 RowList（本身就是行数组）。api 不该知道这种差别，也不该为此引 drizzle-orm。
 * 空串表示「答了话但没报版本」，调用方按异常处理。
 */
export async function dbPing(db: Db): Promise<string> {
  type VersionRow = { v: string };
  const result = (await db.execute(sql`SELECT version() AS v`)) as unknown;
  const rows: VersionRow[] = Array.isArray(result)
    ? (result as VersionRow[])
    : ((result as { rows?: VersionRow[] }).rows ?? []);
  return rows[0]?.v ?? "";
}

/**
 * 等 schema 就位。**api 是唯一的迁移方**（理由见 apps/api/src/db/pg.ts：migrator 不加咨询锁），
 * worker 不能自己也 migrate，只能等表建出来。compose 里的 depends_on 只管「进程起来了」，
 * 管不到「迁移跑完了」——所以这层等待必须存在，否则 worker 先连上就是一句 relation 不存在。
 *
 * 用查询构建器而不是 `to_regclass(...)` 裸 SQL：`execute` 的返回形状两个 driver 不同（见 dbPing），
 * 而「读到 tasks 一行」在两边都是同一个形状；表在迁移里建出来之前，这里每次都抛，重试到超时为止。
 */
export async function waitForSchema(db: Db, timeoutMs = 120_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastError: string;
  for (;;) {
    try {
      await db.select({ id: schema.tasks.id }).from(schema.tasks).limit(1);
      return;
    } catch (cause) {
      lastError = cause instanceof Error ? cause.message : String(cause);
      if (Date.now() >= deadline) {
        throw new Error(
          `等了 ${Math.round(timeoutMs / 1000)} 秒 schema 仍未就位（最后一次：${lastError}）。` +
            "api 是唯一迁移方：先让它起来并跑完迁移，worker 不自己 migrate。",
          { cause },
        );
      }
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }
  }
}
