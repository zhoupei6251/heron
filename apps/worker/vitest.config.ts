import { defineConfig } from "vitest/config";

/** 每个用例起一份进程内 Postgres（pglite）并跑生成迁移，冷启动 1~2s；放宽到 30s 避免并行时假红。 */
export default defineConfig({
  test: { testTimeout: 30_000 },
});
