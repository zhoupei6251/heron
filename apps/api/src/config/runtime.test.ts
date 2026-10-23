import { afterEach, describe, expect, it } from "vitest";

import { SESSION_COOKIE, apiHost, cookieSecure, jwtSecret } from "./runtime";

/**
 * 这一层是唯一读认证相关环境变量的地方。两条规矩都在这里钉：
 * 签名密钥没有默认值（缺配置就拒绝启动），会话 cookie 的名字只有一个来源。
 */

const ORIGINAL = process.env.HERON_JWT_SECRET;
const ORIGINAL_SECURE = process.env.HERON_COOKIE_SECURE;
const ORIGINAL_HOST = process.env.HOST;

afterEach(() => {
  if (ORIGINAL === undefined) delete process.env.HERON_JWT_SECRET;
  else process.env.HERON_JWT_SECRET = ORIGINAL;
  if (ORIGINAL_SECURE === undefined) delete process.env.HERON_COOKIE_SECURE;
  else process.env.HERON_COOKIE_SECURE = ORIGINAL_SECURE;
  if (ORIGINAL_HOST === undefined) delete process.env.HOST;
  else process.env.HOST = ORIGINAL_HOST;
});

describe("jwtSecret", () => {
  it("没配就炸：默认密钥等于所有人都能给自己签一张 owner 的 token", () => {
    delete process.env.HERON_JWT_SECRET;
    expect(() => jwtSecret()).toThrow(/HERON_JWT_SECRET/);
    process.env.HERON_JWT_SECRET = "";
    expect(() => jwtSecret()).toThrow(/HERON_JWT_SECRET/);
  });

  it("短于 32 字符也算没配：HS256 的离线爆破按密钥长度算，三位数的 dev 口令撑不住", () => {
    process.env.HERON_JWT_SECRET = "dev";
    expect(() => jwtSecret()).toThrow(/至少 32/);
  });

  it("够长就用它，且每次读的是当前环境——.env 与进程注入谁在前都由 home.ts 判", () => {
    process.env.HERON_JWT_SECRET = "x".repeat(32);
    expect(jwtSecret()).toBe("x".repeat(32));
  });
});

describe("cookieSecure", () => {
  it("默认不 Secure：本机 http://127.0.0.1 上设了 Secure 反而谁都登不进来", () => {
    delete process.env.HERON_COOKIE_SECURE;
    expect(cookieSecure()).toBe(false);
  });

  it("HERON_COOKIE_SECURE=1 才带 Secure：TLS 部署上这一条必须打开", () => {
    process.env.HERON_COOKIE_SECURE = "1";
    expect(cookieSecure()).toBe(true);
    process.env.HERON_COOKIE_SECURE = "0";
    expect(cookieSecure()).toBe(false);
    // 只认 0 / 1：true、yes、on 这类「差不多」的写法留给下一任运维猜，是事故源头
    process.env.HERON_COOKIE_SECURE = "true";
    expect(() => cookieSecure()).toThrow(/HERON_COOKIE_SECURE/);
  });
});

describe("SESSION_COOKIE", () => {
  it("会话 cookie 的名字是导出的常量，不是散在三处的字符串", () => {
    expect(SESSION_COOKIE).toBe("heron_session");
  });
});

describe("apiHost", () => {
  it("默认只绑回环；容器里必须显式给 0.0.0.0，否则端口映射连不进来", () => {
    delete process.env.HOST;
    expect(apiHost()).toBe("127.0.0.1");
    process.env.HOST = "0.0.0.0";
    expect(apiHost()).toBe("0.0.0.0");
  });
});
