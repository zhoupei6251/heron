import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

import { alias } from "./vite.shared.ts";

/** jsdom 验证路由与组件异步接线；真实布局与媒体显示仍由浏览器验收。 */
export default defineConfig({
  plugins: [vue()],
  resolve: { alias },
  test: { environment: "jsdom" },
});
