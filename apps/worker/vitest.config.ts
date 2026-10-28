import { defineConfig } from "vitest/config";

/**
 * 每个用例起一份进程内 Postgres（pglite）并跑生成迁移，冷启动 1~2s；和 packages/db 同一个坑：
 * 开库与迁移写在 beforeEach/beforeAll 里，走的是 hookTimeout 的 10s 默认值。
 * 只放宽 testTimeout 时，整包并行跑仍会报「Hook timed out in 10000ms」。
 */
export default defineConfig({
  test: { testTimeout: 30_000, hookTimeout: 30_000 },
});
