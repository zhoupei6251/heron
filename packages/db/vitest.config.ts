import { defineConfig } from "vitest/config";

/**
 * pg 层的每个用例都在进程里起一份真 Postgres（pglite），冷启动就要 1~2s。
 * vitest 默认的 5s 上限在多个文件并行跑时会被 CPU 抢用压线，出现「单独跑绿、整包跑红」的假失败
 * ——那种红既不是代码的问题也查不出问题，只会让人开始忽略门禁。放宽到 30s：
 * 多花的不是等待时间，是排查假红的时间。
 *
 * hookTimeout 必须一起放：开库与迁移写在 beforeEach/beforeAll 里，它走的是另一个 10s 默认值，
 * 只改 testTimeout 时整包跑仍会报「Hook timed out in 10000ms」（2026-10-27 实测 3 个文件同时红）。
 */
export default defineConfig({
  test: { testTimeout: 30_000, hookTimeout: 30_000 },
});
