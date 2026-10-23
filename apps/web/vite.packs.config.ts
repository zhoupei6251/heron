import { copyFileSync, existsSync, readdirSync, rmSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import vue from "@vitejs/plugin-vue";
import { defineConfig, type Plugin } from "vite";

import { alias } from "./vite.shared.ts";

const ROOT = dirname(fileURLToPath(import.meta.url));
const ENTRIES_DIR = join(ROOT, "src", "entries");
const DIST = join(ROOT, "dist");

function scanEntries(): string[] {
  return readdirSync(ENTRIES_DIR, { withFileTypes: true })
    .filter((dir) => dir.isDirectory())
    .filter((dir) => existsSync(join(ENTRIES_DIR, dir.name, "main.ts")))
    .map((dir) => dir.name)
    .sort();
}

function findHtmlByEntryDir(root: string, entry: string): string | null {
  if (!existsSync(root)) return null;
  for (const name of readdirSync(root)) {
    const path = join(root, name);
    if (statSync(path).isDirectory()) {
      if (name === entry) {
        const candidate = join(path, "main.html");
        if (existsSync(candidate)) return candidate;
      }
      const nested = findHtmlByEntryDir(path, entry);
      if (nested) return nested;
    }
  }
  return null;
}

/**
 * Vite 把 HTML 入口按源码相对路径落到 dist/src/entries/<name>/main.html，
 * 不经 generateBundle，所以只能在 closeBundle 搬运。
 */
function flattenHtmlEntries(entryNames: string[]): Plugin {
  return {
    name: "flatten-html-entries",
    closeBundle() {
      for (const entry of entryNames) {
        const source = findHtmlByEntryDir(join(DIST, "src"), entry);
        if (!source) continue;
        copyFileSync(source, join(DIST, `${entry}.html`));
      }
      rmSync(join(DIST, "src"), { recursive: true, force: true });
    },
  };
}

const entries = scanEntries();
const input: Record<string, string> = {};

for (const name of entries) {
  // main.ts 必须是入口：只把 HTML 当入口时产物里没有 export{}，渲染期会报 mod.mount is not a function
  input[name] = join(ENTRIES_DIR, name, "main.ts");
  const preview = join(ENTRIES_DIR, name, "main.html");
  if (existsSync(preview)) input[`${name}-preview`] = preview;
}

export default defineConfig({
  plugins: [vue(), flattenHtmlEntries(entries)],
  resolve: { alias },
  build: {
    outDir: "dist",
    emptyOutDir: false,
    rollupOptions: {
      input,
      preserveEntrySignatures: "strict",
      output: {
        entryFileNames: "vue/[name].js",
        chunkFileNames: "vue/chunks/[name]-[hash].js",
        assetFileNames: "vue/assets/[name]-[hash][extname]",
      },
    },
  },
  esbuild: { target: "es2022" },
});
