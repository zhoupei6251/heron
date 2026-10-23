import "reflect-metadata";

import { existsSync } from "node:fs";
import { join } from "node:path";
import type { NextFunction, Request, Response } from "express";

import { NestFactory } from "@nestjs/core";
import type { NestExpressApplication } from "@nestjs/platform-express";

import { AppModule } from "./app.module";
import { API_PORT, WEB_DIST, apiHost } from "./config/runtime";
import { applyTransport } from "./transport";

const MIN_NODE_MAJOR = 24;

/**
 * vue-router 用 history 模式，深链（/review）必须由服务端回 index.html，否则刷新即 404。
 * 静态资源与 host.html 这类真实文件已被 useStaticAssets 接走，带点的路径交给它报 404。
 */
function spaFallback(req: Request, res: Response, next: NextFunction): void {
  if (
    req.method !== "GET" ||
    req.path === "/" ||
    req.path.startsWith("/api") ||
    req.path.includes(".")
  ) {
    next();
    return;
  }
  res.sendFile(join(WEB_DIST, "index.html"));
}

function assertNode(): void {
  const major = Number.parseInt(process.versions.node, 10);
  if (Number.isNaN(major) || major < MIN_NODE_MAJOR) {
    console.error(
      `heron requires Node.js >= ${MIN_NODE_MAJOR} (current: ${process.versions.node})`,
    );
    process.exit(1);
  }
}

async function main(): Promise<void> {
  assertNode();

  const app = await NestFactory.create<NestExpressApplication>(AppModule, {
    logger: ["log", "warn", "error"],
  });
  applyTransport(app);
  app.useStaticAssets(WEB_DIST);
  app.use(spaFallback);
  app.enableShutdownHooks();

  const host = apiHost();
  await app.listen(API_PORT, host);

  console.log(
    `heron api 已监听 http://${host}:${API_PORT}（前端产物：${
      existsSync(WEB_DIST) ? "已构建" : "未构建，先跑 pnpm web:build"
    }）`,
  );
}

main().catch((error: unknown) => {
  console.error("api 启动失败：", error);
  process.exit(1);
});
