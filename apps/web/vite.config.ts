import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";

import { alias } from "./vite.shared.ts";

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: { alias },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.HERON_DEV_API_URL ?? "http://127.0.0.1:3000",
        changeOrigin: false,
      },
    },
  },
});
