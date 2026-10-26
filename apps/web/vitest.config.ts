import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

import { alias } from "./vite.shared.ts";

/** jsdom 验证路由与组件异步接线；真实布局与媒体显示仍由浏览器验收。 */
export default defineConfig({
  plugins: [vue()],
  resolve: { alias },
  // 与 packages/db 同一条理由：整包并行跑时默认 5s 会被 CPU 抢用压线，
  // 出现「单独跑绿、整包跑红」的假失败。放宽只影响失败的上限，不拖慢通过的用例。
  test: { environment: "jsdom", testTimeout: 30_000 },
});
