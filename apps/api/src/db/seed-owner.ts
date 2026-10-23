import { DATABASE_URL } from "@heron/db";
import {
  type Db,
  createTenant,
  createUser,
  findTenantBySlug,
  getUserByEmail,
  migrate,
  openDb,
  withTenant,
} from "@heron/db/pg";
import { MIN_PASSWORD_LENGTH } from "@heron/contracts";
import { hashPassword } from "@heron/domain";

/**
 * 建第一个工作空间与第一个 owner——登录链上唯一需要「还没有任何人」的那一步。
 *
 * 为什么要有这个脚本而不是让人手写 SQL：全局守卫一上，没有任何账号的 api 谁也进不来，
 * 包括第一次登录。手工 INSERT 要人自己拼 scrypt 哈希，拼错的症状是「口令明明对却登不进」。
 *
 * 为什么口令只能从环境来：镜像、compose、.env.example 都不带默认口令（本案的硬规矩）。
 * 这个脚本读不到 HERON_OWNER_PASSWORD 就直接失败，不替你编一个。
 * 跑法（项目根，PowerShell 用 $env: 语法）：
 *   HERON_TENANT_SLUG=acme HERON_OWNER_EMAIL=you@example.com HERON_OWNER_PASSWORD='…' pnpm db:seed-owner
 */

const slug = process.env.HERON_TENANT_SLUG?.trim() || "default";
const email = process.env.HERON_OWNER_EMAIL?.trim() ?? "";
const password = process.env.HERON_OWNER_PASSWORD ?? "";
const displayName = process.env.HERON_OWNER_NAME?.trim() || email.split("@")[0] || "管理员";

function fail(message: string): never {
  console.error(`种子失败：${message}`);
  process.exit(1);
}

async function main(): Promise<void> {
  if (!DATABASE_URL) fail("DATABASE_URL 没配（先 docker compose ... up -d db）");
  if (!email) fail("HERON_OWNER_EMAIL 没给");
  if (password.length < MIN_PASSWORD_LENGTH) {
    fail(
      `HERON_OWNER_PASSWORD 少于 ${String(MIN_PASSWORD_LENGTH)} 字符：不给一个弱口令兜底，宁可失败`,
    );
  }

  const db: Db = openDb(DATABASE_URL);
  try {
    await migrate(db);
    const tenant = await findTenantBySlug(db, slug);
    const tenantId = tenant?.id ?? (await createTenant(db, { name: slug, slug }));
    if (!tenant) console.log(`建了工作空间 ${slug}`);

    const existing = await withTenant(db, tenantId, (t) => getUserByEmail(t, email));
    if (existing) {
      // 已存在就一个字都不改：静默重置一个在册 owner 的口令，是「谁能跑脚本谁就能接管」的形状
      console.log(`${email} 已在 ${slug} 里（角色 ${existing.role}），口令未改动`);
      return;
    }
    await withTenant(db, tenantId, (t) =>
      createUser(t, {
        email,
        displayName,
        passwordHash: hashPassword(password),
        role: "owner",
      }),
    );
    console.log(`已建 owner：${slug} / ${email}（口令只在环境里，不回显）`);
  } finally {
    await db.close();
  }
}

main().catch((error: unknown) => {
  console.error("种子失败：", error instanceof Error ? error.message : String(error));
  process.exit(1);
});
