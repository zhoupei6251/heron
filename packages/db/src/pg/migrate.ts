import { join } from "node:path";

import { migrate as migratePglite } from "drizzle-orm/pglite/migrator";
import { migrate as migratePg } from "drizzle-orm/postgres-js/migrator";

import type { Db } from "./driver";
import { applyRls } from "./rls";

/**
 * 迁移目录按 packages/db 自己的位置算，不靠进程 cwd——
 * 与 home.ts 那条「数据在哪只许一处计算」是同一条规矩：api 与 worker 的 cwd 不同。
 */
const MIGRATIONS_DIR = join(__dirname, "..", "..", "drizzle");

export async function migrate(db: Db): Promise<void> {
  if (db.driver === "pglite") {
    await migratePglite(db, { migrationsFolder: MIGRATIONS_DIR });
  } else {
    await migratePg(db as unknown as Parameters<typeof migratePg>[0], {
      migrationsFolder: MIGRATIONS_DIR,
    });
  }
  // RLS 不靠 drizzle-kit 生成（它的表达力不够），而且必须每次幂等重放：
  // 加一张新租户表时旧迁移不会重跑，只有这一步会。
  await applyRls(db);
}
