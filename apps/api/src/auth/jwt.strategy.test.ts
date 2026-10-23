import { describe, expect, it } from "vitest";

import { SESSION_COOKIE } from "../config/runtime";
import { extractSessionCookie } from "./jwt.strategy";

/**
 * 抽取器是「传输层只能是 cookie」这条决定的落点：
 * apps/web 的进度流用 EventSource，它带不了请求头，所以验签只看 cookie——
 * 顺手也别接 Authorization：那等于给存在 localStorage 里的 token 开第二扇门。
 */

describe("extractSessionCookie", () => {
  it("从 cookie 里取会话 token", () => {
    expect(extractSessionCookie({ cookies: { [SESSION_COOKIE]: "jwt-token" } })).toBe("jwt-token");
  });

  it("没有 cookie、没有 cookie 解析器、cookie 为空串，一律当未登录", () => {
    expect(extractSessionCookie({})).toBeNull();
    expect(extractSessionCookie({ cookies: {} })).toBeNull();
    expect(extractSessionCookie({ cookies: { [SESSION_COOKIE]: "" } })).toBeNull();
    expect(extractSessionCookie(undefined)).toBeNull();
  });

  it("不看 Authorization 头：Bearer 方案在这里不是备选，是被否掉的", () => {
    expect(extractSessionCookie({ headers: { authorization: "Bearer jwt-token" } })).toBeNull();
  });
});
