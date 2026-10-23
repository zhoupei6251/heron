import js from "@eslint/js";
import eslintConfigPrettier from "eslint-config-prettier";
import pluginVue from "eslint-plugin-vue";
import globals from "globals";
import tseslint from "typescript-eslint";

/**
 * 一张网：heron 自己的 ts/vue 源码。
 * reference/ 与 harness-factory/ 是 oracle 和生成器，不是本案代码——不 lint、不 import、CI 不跑。
 */
export default tseslint.config(
  {
    name: "heron/ignores",
    ignores: [
      "**/node_modules/**",
      "**/dist/**",
      "**/coverage/**",
      "data/**",
      "reference/**",
      "harness-factory/**",
      "docs/**",
      "**/*.d.ts",
    ],
  },
  {
    name: "heron/base",
    files: ["**/*.{ts,mts,cts,vue}"],
    extends: [js.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
      globals: { ...globals.node, ...globals.browser },
    },
    rules: {
      // 类型只能出现在类型位置时必须写 import type，产物里就不会捎带运行时依赖
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { prefer: "type-imports", fixStyle: "inline-type-imports" },
      ],
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
      // 数字进模板字符串是本案常态（次数、版本号）；nullish 直接拼进文案才是 bug
      "@typescript-eslint/restrict-template-expressions": [
        "error",
        { allowNumber: true, allowBoolean: true, allowNullish: false },
      ],
    },
  },
  {
    name: "heron/vue",
    files: ["**/*.vue"],
    extends: [...pluginVue.configs["flat/recommended"]],
    languageOptions: {
      parser: pluginVue.parser,
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: [".vue"],
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
        sourceType: "module",
      },
    },
    rules: {
      // 页面与版式都用中文说明性文案，单文件组件名允许非 PascalCase 注册
      "vue/multi-word-component-names": "off",
      "vue/max-attributes-per-line": "off",
      "vue/singleline-html-element-content-newline": "off",
      "vue/html-self-closing": "off",
    },
  },
  {
    /**
     * tsserver 不解析 .vue，所以从 SFC 拿来的导出在 lint 程序里是 error type，
     * `no-unsafe-argument` 会在 createApp(App) 这行误报。判据交回真正看得懂 SFC 的那一层：
     * `pnpm typecheck` 跑的是 vue-tsc。只有挂载入口这一类文件享受这个豁免。
     */
    name: "heron/sfc-mount-entries",
    files: ["apps/web/src/main.ts", "apps/web/src/entries/**/main.ts"],
    rules: { "@typescript-eslint/no-unsafe-argument": "off" },
  },
  {
    /**
     * 特权通道（heron_owner / BYPASSRLS）只许出现在两处：packages/db 内部与跨租户调度的 scheduler。
     * 判据是「拿到 AdminDb 就等于拿到全部租户的数据」，靠 code review 拦不住多租户事故。
     * admin 模块因此不进 pg barrel（见 packages/db/src/pg/index.ts），这条规则按路径拦；
     * paths 那一段是给 T8 之后 `@heron/db` 直接导出 pg 层时准备的——那时路径拦不住，只能按名字拦。
     */
    name: "heron/privileged-channel",
    files: ["apps/**/*.ts", "packages/**/*.ts"],
    // flat config 里块内的 ignores 就是本块的排除项（excludedFiles 是 eslintrc 的键，v10 直接报错）
    ignores: ["packages/db/**", "apps/worker/src/scheduler.ts"],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              group: ["**/pg/admin", "**/pg/admin.ts", "**/pg/admin.js"],
              message:
                "特权通道（withAdmin / AdminDb）只能在 packages/db 内部与 scheduler.ts 里用。",
            },
          ],
          paths: [
            {
              name: "@heron/db",
              importNames: ["withAdmin", "AdminDb", "OWNER_ROLE"],
              message: "同上：业务代码请走 withTenant / TenantDb。",
            },
            {
              // pg 子路径已经能 import 了（T6），这条是给「admin 哪天被并进 barrel」准备的
              name: "@heron/db/pg",
              importNames: ["withAdmin", "AdminDb", "OWNER_ROLE"],
              message: "同上：业务代码请走 withTenant / TenantDb。",
            },
          ],
        },
      ],
    },
  },
  eslintConfigPrettier,
);
