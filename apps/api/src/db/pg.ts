import { Global, Inject, Injectable, Module, type OnModuleDestroy } from "@nestjs/common";

import { DATABASE_URL } from "@heron/db";
import { type Db, migrate, openDb } from "@heron/db/pg";

/**
 * 多租户数据面（Postgres）在 api 进程里的唯一连接。
 *
 * 为什么在 T6 就建：登录要读 users / tenants，这两张表只活在 pg 里——认证这一层做完了，
 * 剩下的业务接口还在 SQLite 上（计划 T7 才切）。所以这两层并存一段时间是计划本身的形状，
 * 不是过渡期事故；并存的上限是 T8 删掉 SQLite 层。
 *
 * 「这个进程连的是哪个库」由 DATABASE_URL 一处决定，不给默认值（理由见 home.ts：
 * 默认值会把「忘了配」变成「连到一个谁都不知道的库」，而多租户库里那叫跨租户事故）。
 */
export const PG_DB = "PG_DB";

/**
 * 开连接并跑迁移。迁移放在这里而不是另起一个一次性容器：
 * api 是本案唯一的迁移方——drizzle 的 migrator 不加咨询锁（实测其源码无 advisory_lock），
 * 两个进程同时迁移就是竞争建表。T8 的 worker 因此必须等 api 健康再起来，
 * 而不是自己也 migrate 一遍。
 */
export async function createPgDb(): Promise<Db> {
  if (!DATABASE_URL) {
    throw new Error(
      "DATABASE_URL 没配：api 的会话与成员只存在 Postgres 里。本机先跑 " +
        "`docker compose -f deploy/docker-compose.yml --env-file .env up -d db`，再把连接串写进 .env。",
    );
  }
  const db = openDb(DATABASE_URL);
  await migrate(db);
  return db;
}

@Injectable()
export class PgConnection implements OnModuleDestroy {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(PG_DB) private readonly db: Db) {}

  /** 连接池里的 socket 是 ref 的：不关它，SIGTERM 之后进程还赖在那里，容器要等到超时才被杀。 */
  async onModuleDestroy(): Promise<void> {
    await this.db.close();
  }
}

@Global()
@Module({
  providers: [{ provide: PG_DB, useFactory: createPgDb }, PgConnection],
  exports: [PG_DB],
})
export class PgModule {}
