import { fileURLToPath } from "node:url";

export const alias = {
  "@heron/contracts": fileURLToPath(
    new URL("../../packages/contracts/src/index.ts", import.meta.url),
  ),
  // web 必须走 browser 入口：index.ts 还导出 password.ts（node:crypto），
  // 外置后的空壳只会在页面导航到这里时才报错，构建期是绿的。
  "@heron/domain": fileURLToPath(new URL("../../packages/domain/src/browser.ts", import.meta.url)),
};
