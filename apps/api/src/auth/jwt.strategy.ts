import { Inject, Injectable } from "@nestjs/common";
import { PassportStrategy } from "@nestjs/passport";
import { Strategy } from "passport-jwt";

import type { AuthUser, JwtPayload } from "@heron/contracts";

import { SESSION_COOKIE, TENANT_AUDIENCE, jwtSecret } from "../config/runtime";
import { AuthService } from "./auth.service";

/**
 * 会话只从 cookie 读。这不是「先支持一种，以后再加 Bearer」，而是把 Bearer 这条路**否掉**：
 * apps/web 的任务进度流用 EventSource，它带不了请求头，所以头里的 token 到不了那条流；
 * 反过来若为了 SSE 把 token 放进 localStorage 供 fetch 加头，就把会话交到了 XSS 的手上——
 * 而 cookie + HttpOnly 恰恰是为了让脚本拿不到它。两件事必须一起成立，所以只留 cookie。
 *
 * 写成纯函数是为了单测：这个判据值得比「在真 socket 上试一次」更强的断言。
 */
export function extractSessionCookie(req: unknown): string | null {
  const cookies = (req as { cookies?: Record<string, string | undefined> } | undefined)?.cookies;
  const value = cookies?.[SESSION_COOKIE];
  return value ? value : null;
}

@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy) {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(AuthService) private readonly auth: AuthService) {
    super({
      jwtFromRequest: extractSessionCookie,
      audience: TENANT_AUDIENCE,
      // 没有默认密钥：构造发生在启动期，没配 HERON_JWT_SECRET 就死在这里（见 runtime.ts）
      secretOrKey: jwtSecret(),
      // passport-jwt 4.0.1 自己不给 algorithms 默认值，它只是把这个选项原样交给 jsonwebtoken；
      // 字符串密钥下 jsonwebtoken 默认放行 HS256/HS384/HS512——同一把密钥签的 HS384 票
      // 会被当成有效会话。所以这里必须写死 HS256，别把「默认只认一种」当成事实。
      algorithms: ["HS256"],
      // 不写 maxAge。计划里那一版抄了它，但实测 passport-jwt 4.0.1 的 lib/ 里没有这个选项
      // （grep 零命中），写了就是「一条看着像期限、其实什么都不做」的配置——最贵的那种假防线。
      // 会话上限只有一个来源：签名时的 expiresIn 写进 exp，验签时由 jsonwebtoken 判。
    });
  }

  /**
   * 返回 null 就是 401——Passport 把「没有用户」当失败，不需要这里抛异常。
   * 判据全在 AuthService.userFor：这里只是把策略接到那次回库复查上。
   */
  async validate(payload: JwtPayload & { aud?: unknown }): Promise<AuthUser | null> {
    if (payload.aud !== TENANT_AUDIENCE) return null;
    return this.auth.userFor(payload);
  }
}
