import "reflect-metadata";

import { randomBytes } from "node:crypto";

import { Test } from "@nestjs/testing";

import {
  createCandidate,
  createTask,
  createTenant,
  createUser,
  migrate,
  openDb,
  withTenant,
} from "@heron/db/pg";
import { hashPassword } from "@heron/domain";

import { AppModule } from "../app.module";
import { API_PORT } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { applyTransport } from "../transport";

/**
 * 开发台的「真 API + 内存库」：T11 之前 Docker 没起，浏览器验证不能靠假接口。
 *
 * 它跑的是**生产同一条 transport 与全部业务路由**，只把 PgModule 的 provider 换成 pglite
 * （与 api 的 e2e 同一个做法）。数据只活在进程里：重启即清空，不写任何磁盘。
 *
 * 口令不写默认值：HERON_DEMO_PASSWORD 必须由人给（至少 12 位），缺了就拒绝启动。
 * JWT 密钥缺省时随机生成——每次重启换一把，旧会话随之作废，这是内存库下应有的行为。
 */
async function main(): Promise<void> {
  const password = process.env.HERON_DEMO_PASSWORD ?? "";
  if (password.length < 12) {
    throw new Error("HERON_DEMO_PASSWORD 必须给且至少 12 位：演示账号的口令不写默认值。");
  }
  process.env.HERON_JWT_SECRET ??= randomBytes(32).toString("hex");

  const db = openDb("memory");
  await migrate(db);

  const tenantId = await createTenant(db, { name: "演示工作空间", slug: "default" });
  const accounts = [
    ["owner@example.com", "owner", "演示所有者"],
    ["reviewer@example.com", "reviewer", "演示审核员"],
    ["viewer@example.com", "viewer", "演示只读"],
  ] as const;
  for (const [email, role, displayName] of accounts) {
    await withTenant(db, tenantId, (t) =>
      createUser(t, { email, displayName, passwordHash: hashPassword(password), role }),
    );
  }

  for (const title of ["台风“巴威”路径更新", "城市夜跑路线调整"]) {
    await withTenant(db, tenantId, async (t) => {
      const candidateId = await createCandidate(t, { title, summary: "", heat: 0 });
      await createTask(t, { capability: "p0-fake", candidateId });
    });
  }

  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(PG_DB)
    .useValue(db)
    .compile();
  const app = moduleRef.createNestApplication({ logger: ["warn", "error"] });
  applyTransport(app);
  await app.listen(API_PORT, "127.0.0.1");

  console.log(`heron 开发台（内存 pglite）：http://127.0.0.1:${String(API_PORT)}`);
  console.log(`工作空间：default（演示工作空间）`);
  for (const [email, role] of accounts) console.log(`  ${role.padEnd(8)} ${email}`);
  console.log("数据只在内存里：重启即清空；演示口令来自 HERON_DEMO_PASSWORD。");

  const stop = (): void => {
    void app.close().then(() => process.exit(0));
  };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}

main().catch((error: unknown) => {
  console.error("开发台启动失败：", error);
  process.exit(1);
});
