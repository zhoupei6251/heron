import { join } from "node:path";

import type { CookieOptions } from "express";

import { REPO_ROOT } from "@heron/db";

/** 数据在哪、库怎么开都在 `@heron/db`。这里只留 api 自己才有的事实。 */
export const WEB_DIST = join(REPO_ROOT, "apps", "web", "dist");
export const API_PORT = Number.parseInt(process.env.PORT ?? "3000", 10);

/**
 * api 绑在哪个网卡上。默认回环：开发机上不许同一个 Wi-Fi 的人直接扫到管理台。
 *
 * 容器里必须由 compose 显式给 0.0.0.0。容器内绑 127.0.0.1 时，宿主映射进来的连接
 * 会被内核 reset（实测 curl: (52) Empty reply from server），而容器自己的 healthcheck
 * 走同一个 loopback 仍然是绿的——这个错不会让任何一个探针变红，只会让整个服务连不上。
 */
export function apiHost(): string {
  return process.env.HOST?.trim() || "127.0.0.1";
}

/**
 * 会话 cookie 的名字只有一个来源：写它的是 AuthController，读它的是 JwtStrategy。
 * 两边各写一遍字符串，将来一处改了另一处没改，症状是「所有人都登不进来」而代码看起来全对。
 */
export const SESSION_COOKIE = "heron_session";
export const TENANT_AUDIENCE = "heron-tenant";
export const PLATFORM_COOKIE = "heron_platform_session";
export const PLATFORM_AUDIENCE = "heron-platform";
export function platformCookieOptions(): CookieOptions {
  return { ...sessionCookieOptions(), path: "/api/platform" };
}

// TTL limits token lifetime; per-request sessionVersion checks revoke sessions immediately.
export const SESSION_TTL = "12h";

const MIN_SECRET_LENGTH = 32;

/**
 * 签名密钥。没有默认值，缺了或太短就拒绝启动。
 *
 * 默认密钥不是「方便本地开发」，是「任何人都能给自己签一张 owner 的 token」：
 * HS256 验签只认这把钥匙，钥匙公开等于门禁形同虚设。本地开发把值写进 .env，
 * 容器由 compose 从宿主的 .env 注入——两条路都必须有一条真值。
 *
 * 每次调用都读当前环境，不缓存成模块常量：`.env` 与进程注入的先后由 home.ts 的
 * loadEnv() 判（真环境变量优先），这里再缓存一份就等于绕过那个判定。
 */
export function jwtSecret(): string {
  const secret = process.env.HERON_JWT_SECRET ?? "";
  if (!secret) {
    throw new Error(
      "HERON_JWT_SECRET 没配：会话签名密钥不许有默认值。开发机 cp .env.example .env 填一条，容器由 compose 注入。",
    );
  }
  if (secret.length < MIN_SECRET_LENGTH) {
    throw new Error(
      `HERON_JWT_SECRET 太短（${String(secret.length)} 字符，至少 ${String(MIN_SECRET_LENGTH)}）：HS256 的离线爆破按密钥熵算，一句 dev 口令撑不住。`,
    );
  }
  return secret;
}

/**
 * cookie 要不要带 Secure。只认 0 / 1，其它写法直接炸。
 *
 * 默认关：本机 http://127.0.0.1 上开了 Secure，cookie 一个都存不下，
 * 症状是「登录页转圈说口令错」，没人会往传输配置上想。TLS 部署（挂 nginx）必须显式给 1。
 * 认 "true"/"yes"/"on" 等于把拼错的配置读成「关」——那是最贵的一种静默失败。
 */
export function cookieSecure(): boolean {
  const raw = process.env.HERON_COOKIE_SECURE;
  if (raw === undefined || raw === "") return false;
  if (raw === "1") return true;
  if (raw === "0") return false;
  throw new Error(`HERON_COOKIE_SECURE 只认 0 或 1，现在是 "${raw}"：猜错方向的代价是会话走明文。`);
}

// SameSite supplements Origin validation; HttpOnly does not prevent XSS from issuing requests.
export function sessionCookieOptions(): CookieOptions {
  return {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    secure: cookieSecure(),
  };
}
