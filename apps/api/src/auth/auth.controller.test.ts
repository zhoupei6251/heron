import type { AuthUser, ConsoleSession } from "@heron/contracts";
import { UnauthorizedException } from "@nestjs/common";
import type { Response } from "express";
import { describe, expect, it, vi } from "vitest";

import { AuthController } from "./auth.controller";
import type { AuthService } from "./auth.service";
import type { ConsoleAuthService } from "./console-auth.service";

/**
 * 控制器只管两件事：把 token 放进 cookie、把会话表放进响应。判据在 service，验签在守卫。
 * 这里盯的是**cookie 的属性**——传输层的安全全部落在这几个键上，写错一个就没有 HttpOnly 了。
 * e2e（auth.e2e.test.ts）看的是同一组属性落到真 Set-Cookie 头上，两边不重复：
 * `HERON_COOKIE_SECURE=1` 那一支只有在这里测得出，环境变量在进程起来之后就固定了。
 */

const USER: AuthUser = {
  id: "102",
  tenantId: "101",
  tenantName: "A 台",
  tenantSlug: "acme",
  email: "zhoupei@example.com",
  displayName: "周佩",
  role: "reviewer",
};
const SESSION: ConsoleSession = {
  kind: "tenant",
  user: USER,
  roles: ["reviewer"],
  permissions: ["task.claim", "review.confirm", "usage.read"],
  menus: [],
};
const BODY = { tenant: "acme", identifier: USER.email, password: "correct-horse-battery" };
const UA = "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0";

function makeController(login: unknown) {
  const cookie = vi.fn();
  const clearCookie = vi.fn();
  const controller = new AuthController(
    { login } as unknown as ConsoleAuthService,
    {} as unknown as AuthService,
  );
  return {
    controller,
    cookie,
    clearCookie,
    res: { cookie, clearCookie } as unknown as Response,
  };
}

describe("POST /api/auth/login", () => {
  it("成功：token 进 HttpOnly 的会话 cookie，响应体里只有会话表", async () => {
    const signed = { token: "jwt-token", session: SESSION };
    const login = vi.fn(() => Promise.resolve(signed));
    const { controller, cookie, clearCookie, res } = makeController(login);

    await expect(controller.login(BODY, UA, res)).resolves.toEqual(SESSION);
    // UA 只随登录进 sessions 行（「这台设备是哪台」），不进票、不参与判据
    expect(login).toHaveBeenCalledWith(BODY, UA);

    const [name, value, options] = cookie.mock.calls[0] as unknown as [
      string,
      string,
      Record<string, unknown>,
    ];
    expect(name).toBe("heron_session");
    expect(value).toBe("jwt-token");
    // HttpOnly：脚本读不到会话，XSS 就拿不到通行证；响应体里也不许再出现一次 token
    expect(options.httpOnly).toBe(true);
    // SameSite=Lax：跨站的 POST 带不上这个 cookie，等于免了 CSRF token；GET 导航仍然带
    expect(options.sameSite).toBe("lax");
    expect(options.path).toBe("/");
    // 不给 maxAge / Expires：关掉浏览器就登出，长期有效交给 JWT 自己的 exp
    expect(options.maxAge).toBeUndefined();
    expect(options.expires).toBeUndefined();
    expect(options.secure).toBe(false);
    // 旧的独立平台 cookie 必须当场作废，否则旧票还有一条旁路
    expect(clearCookie.mock.calls[0]?.[0]).toBe("heron_platform_session");
    // clearCookie 的签名是 (name, options)，没有 value 那一位
    expect(clearCookie.mock.calls[0]?.[1]).toMatchObject({ path: "/api/platform" });
  });

  it("HERON_COOKIE_SECURE=1 时 cookie 带 Secure：TLS 部署上不许走明文", async () => {
    process.env.HERON_COOKIE_SECURE = "1";
    try {
      const signed = { token: "jwt-token", session: SESSION };
      const { controller, cookie, res } = makeController(() => Promise.resolve(signed));
      await controller.login(BODY, UA, res);
      const options = cookie.mock.calls[0]?.[2] as { secure?: boolean };
      expect(options.secure).toBe(true);
    } finally {
      delete process.env.HERON_COOKIE_SECURE;
    }
  });

  it("口令错：401 + 统一文案，且一个 cookie 都不下发", async () => {
    const { controller, cookie, clearCookie, res } = makeController(() => Promise.resolve(null));
    await expect(controller.login(BODY, UA, res)).rejects.toBeInstanceOf(UnauthorizedException);
    expect(cookie).not.toHaveBeenCalled();
    expect(clearCookie).not.toHaveBeenCalled();
    // 「用户不存在」与「口令错」必须是同一句话，否则响应就成了「这个账号注册过」的证词
    const error = (await controller.login(BODY, UA, res).catch((e: unknown) => e)) as {
      response?: { message?: string };
    };
    expect(error.response?.message).toBe("账号或口令不正确");
  });
});

describe("GET /api/auth/me", () => {
  it("把守卫解出来的会话原样交回，界面据此渲染角色与菜单", () => {
    const { controller } = makeController(() => Promise.resolve(null));
    expect(controller.me(SESSION)).toEqual(SESSION);
  });
});
