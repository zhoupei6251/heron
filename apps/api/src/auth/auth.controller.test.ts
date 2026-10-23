import type { AuthUser } from "@heron/contracts";
import { UnauthorizedException } from "@nestjs/common";
import type { Response } from "express";
import { describe, expect, it, vi } from "vitest";

import { AuthController } from "./auth.controller";
import type { AuthService, SignedIn } from "./auth.service";

/**
 * 控制器只管两件事：把 token 放进 cookie、把身份放进响应。判据在 service，验签在守卫。
 * 这里盯的是**cookie 的属性**——传输层的安全全部落在这几个键上，写错一个就没有 HttpOnly 了。
 * e2e（auth.e2e.test.ts）看的是同一组属性落到真 Set-Cookie 头上，两边不重复：
 * `HERON_COOKIE_SECURE=1` 那一支只有在这里测得出，环境变量在进程起来之后就固定了。
 * `@Public` 是否在 login 上也不在这里测：e2e 里「无 cookie 也能 201」是同一个性质的强证——
 * 元数据读对了不代表守卫真的放行，反过来才是要防的事故。
 */

const USER: AuthUser = {
  id: "11111111-1111-4111-8111-111111111111",
  tenantId: "00000000-0000-4000-8000-000000000001",
  tenantName: "A 台",
  tenantSlug: "acme",
  email: "zhoupei@example.com",
  displayName: "周佩",
  role: "reviewer",
};
const BODY = { tenant: "acme", email: USER.email, password: "correct-horse-battery" };

function makeController(login: unknown) {
  const cookie = vi.fn();
  const controller = new AuthController({ login } as unknown as AuthService);
  return { controller, cookie, res: { cookie } as unknown as Response };
}

describe("POST /api/auth/login", () => {
  it("成功：token 进 HttpOnly 的会话 cookie，响应体里只有身份", async () => {
    const signed: SignedIn = { token: "jwt-token", user: USER };
    const { controller, cookie, res } = makeController(() => Promise.resolve(signed));

    await expect(controller.login(BODY, res)).resolves.toEqual(USER);

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
  });

  it("HERON_COOKIE_SECURE=1 时 cookie 带 Secure：TLS 部署上不许走明文", async () => {
    process.env.HERON_COOKIE_SECURE = "1";
    try {
      const signed: SignedIn = { token: "jwt-token", user: USER };
      const { controller, cookie, res } = makeController(() => Promise.resolve(signed));
      await controller.login(BODY, res);
      const options = cookie.mock.calls[0]?.[2] as { secure?: boolean };
      expect(options.secure).toBe(true);
    } finally {
      delete process.env.HERON_COOKIE_SECURE;
    }
  });

  it("口令错：401 + 统一文案，且一个 cookie 都不下发", async () => {
    const { controller, cookie, res } = makeController(() => Promise.resolve(null));
    await expect(controller.login(BODY, res)).rejects.toBeInstanceOf(UnauthorizedException);
    expect(cookie).not.toHaveBeenCalled();
    // 「用户不存在」与「口令错」必须是同一句话，否则响应就成了「这个邮箱注册过」的证词
    const error = (await controller.login(BODY, res).catch((e: unknown) => e)) as {
      response?: { message?: string };
    };
    expect(error.response?.message).toBe("邮箱或口令不正确");
  });
});

describe("GET /api/auth/me", () => {
  it("把守卫解出来的身份原样交回，界面据此渲染角色", () => {
    const { controller } = makeController(() => Promise.resolve(null));
    expect(controller.me(USER)).toEqual(USER);
  });
});
