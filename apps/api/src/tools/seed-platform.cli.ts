import { MIN_PASSWORD_LENGTH } from "@heron/contracts";
import { DATABASE_URL } from "@heron/db";
import { migrate, openDb } from "@heron/db/pg";
import { parsePlatformBootstrap, seedPlatform } from "./seed-platform";

async function main(): Promise<void> {
  // Validate before opening any connection. No default password and no automatic startup seed.
  parsePlatformBootstrap(process.env);
  if (!DATABASE_URL) throw new Error("DATABASE_URL 未配置");
  const db = openDb(DATABASE_URL);
  try {
    await migrate(db);
    const result = await seedPlatform(db, process.env);
    console.log(
      result.created
        ? "已创建独立平台管理员（凭据不回显）"
        : "平台账号已存在，未更改任何资料或口令",
    );
  } finally {
    await db.close();
  }
}

main().catch(() => {
  // Raw database exceptions may carry SQL values, so never log the exception here.
  console.error(
    `平台种子失败：请检查 HERON_PLATFORM_USERNAME / PASSWORD（${String(MIN_PASSWORD_LENGTH)}–128 位）/ NAME 和数据库连接配置；未输出凭据或 SQL`,
  );
  process.exitCode = 1;
});
