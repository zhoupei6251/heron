import { DATABASE_URL } from "@heron/db";
import { type Db, openDb, waitForSchema } from "@heron/db/pg";

/**
 * worker 的库句柄。**不跑迁移**：api 是唯一迁移方（drizzle 的 migrator 不加咨询锁，
 * 两个进程同时迁移就是竞争建表），worker 只能等 schema 就位。
 * 连不上或表还没建出来都先等——compose 里 depends_on 只保证进程起来了，管不到迁移跑完。
 */
export async function connectWorkerDb(): Promise<Db> {
  if (!DATABASE_URL) {
    throw new Error(
      "DATABASE_URL 没配：worker 领活要跨租户调度，没有库就没有租户坐标。按 .env.example 填一条。",
    );
  }
  const db = openDb(DATABASE_URL);
  try {
    await waitForSchema(db);
  } catch (cause) {
    await db.close();
    throw cause;
  }
  return db;
}
