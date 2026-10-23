import { existsSync } from "node:fs";

import { DATABASE_URL } from "../index";
import { openDb } from "../pg/driver";
import { IMPORTED_TABLES, importLegacySqlite, retireLegacyDb } from "./migrate-sqlite";

/**
 * CLI 与核心分开：测试 import 核心时不许跑 main。
 * `.env` 由 ../index 在模块加载时读（home.ts 的 loadEnv），所以 DATABASE_URL 这一句必有值或空串。
 */

function usage(): string {
  return [
    "用法：pnpm db:import-sqlite [--source data/heron.sqlite] [--tenant default]",
    "环境变量：DATABASE_URL（必填；也读仓库根 .env）",
    "导入成功后会旧库改名为 <source>.pre-multi-tenant，不删除。",
  ].join("\n");
}

function parseArgs(argv: string[]): { sourcePath: string; tenantSlug: string } {
  let sourcePath = "data/heron.sqlite";
  let tenantSlug = process.env.HERON_TENANT_SLUG?.trim() || "default";
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--source") {
      const value = argv[i + 1];
      if (!value) throw new Error(`--source 后面要给路径\n${usage()}`);
      sourcePath = value;
      i += 1;
    } else if (arg === "--tenant") {
      const value = argv[i + 1];
      if (!value) throw new Error(`--tenant 后面要给工作空间 slug\n${usage()}`);
      tenantSlug = value;
      i += 1;
    } else if (arg === "--help" || arg === "-h") {
      console.log(usage());
      process.exit(0);
    } else {
      throw new Error(`不认识的参数：${String(arg)}\n${usage()}`);
    }
  }
  return { sourcePath, tenantSlug };
}

async function main(): Promise<void> {
  if (!DATABASE_URL) {
    throw new Error(
      "DATABASE_URL 没配：导入必须指向真实的 Postgres（先起 db 并写好 .env）。内存 pglite 不能当导入目标。",
    );
  }
  const { sourcePath, tenantSlug } = parseArgs(process.argv.slice(2));
  if (!existsSync(sourcePath)) {
    throw new Error(`旧库文件不存在：${sourcePath}（用 --source 指定别的路径）`);
  }
  if (existsSync(`${sourcePath}.pre-multi-tenant`)) {
    throw new Error(
      `退场目标已经存在：${sourcePath}.pre-multi-tenant。看起来这份旧库已经搬过；` +
        "核对后手工处理，导入不会覆盖旧文件。",
    );
  }

  const db = openDb(DATABASE_URL);
  try {
    const report = await importLegacySqlite({ db, sourcePath, tenantSlug });
    console.log(`导入完成：工作空间 ${report.tenant.slug}（${report.tenant.id}）`);
    for (const table of IMPORTED_TABLES) {
      const { source, target } = report.tables[table];
      console.log(`  ${table.padEnd(14)} ${String(source).padStart(5)} → ${target}`);
    }
    if (report.syntheticActors.length > 0) {
      console.log("合成 actor（不可登录）：");
      for (const actor of report.syntheticActors) {
        console.log(`  ${actor.name.padEnd(20)} ${actor.id}`);
      }
    }
    const retired = await retireLegacyDb(sourcePath);
    console.log(`旧库已改名保留：${retired}`);
  } finally {
    await db.close();
  }
}

main().catch((error: unknown) => {
  console.error("导入失败：", error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
