import { defineConfig } from "vitest/config";

/**
 * pg 层的每个用例都在进程里起一份真 Postgres（pglite），冷启动就要 1~2s。
 * vitest 默认的 5s 上限在多个文件并行跑时会被 CPU 抢用压线，出现「单独跑绿、整包跑红」的假失败
 * ——那种红既不是代码的问题也查不出问题，只会让人开始忽略门禁。放宽到 30s：
 * 多花的不是等待时间，是排查假红的时间。
 */
export default defineConfig({
  test: { testTimeout: 30_000 },
});
