import { defineConfig } from "vitest/config";

import { alias } from "./vite.shared.ts";

/**
 * 只跑判据与接线，不跑浏览器：jsdom 提供 window / history 让路由守卫能真的走一遍，
 * 组件渲染与置灰观感在真浏览器里验（T9 Step 3），两者不互相冒充。
 */
export default defineConfig({
  resolve: { alias },
  test: { environment: "jsdom" },
});
