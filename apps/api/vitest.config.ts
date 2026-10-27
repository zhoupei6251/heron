import { defineConfig } from "vitest/config";

// e2e 用例在 beforeAll 里起 pglite 并跑完整迁移，走的是 hookTimeout（默认 10s）；
// 和 packages/db 同一个坑——多文件并行时 10s 会被 CPU 抢用压线，整包跑出假红。
export default defineConfig({
  test: { testTimeout: 30_000, hookTimeout: 30_000 },
});
