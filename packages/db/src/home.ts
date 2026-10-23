import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

/**
 * 「数据在哪」只允许有一处计算。
 * api 与 worker 各自数 `..` 的话，任何一次挪文件都会让两个进程指向两个不同的数据目录——
 * 那是不报错的那种错，比崩掉更难查。
 */
export const REPO_ROOT = resolve(join(__dirname, "..", "..", ".."));

/** 真环境变量优先于 .env 文件：生产上由 systemd/shell 注入，本地开发才读文件。 */
export function loadEnv(): void {
  const file = join(REPO_ROOT, ".env");
  if (existsSync(file)) process.loadEnvFile(file);
}

loadEnv();

export const HERON_HOME = process.env.HERON_HOME ?? join(REPO_ROOT, "data");

/** 成片与诊断产物。DB 只存路径，文件本体落在这里（api 与 worker 共享同一个卷）。 */
export const VIDEOS_DIR = join(HERON_HOME, "videos");

export const LEASE_TTL_SEC = 60;

/**
 * pg 层的连接串（`postgres://user:pass@host:5432/db`）。空串表示「这个进程还没打算连库」。
 * 不给本地默认值：默认值会把「忘了配」变成「连到一个谁都不知道的库」，
 * 而多租户库里那叫跨租户事故。真读它的是 api（api/src/db/pg.ts）与 worker（worker/src/db.ts）。
 */
export const DATABASE_URL = process.env.DATABASE_URL ?? "";
