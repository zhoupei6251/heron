import { createParamDecorator, type ExecutionContext, UnauthorizedException } from "@nestjs/common";

import type { AuthUser } from "@heron/contracts";

/**
 * 取「当前登录的人」。
 *
 * 值来自 JwtAuthGuard → Passport → `req.user`，而 `req.user` 是 AuthService.userFor
 * 回库读出来的那一张表（不是 token 里的字段的直接透传）。所以参数类型是 AuthUser，
 * 而不是「可能没有」——没有会话的请求根本走不到控制器，走到也说明接线错了，
 * 这里抛 401 就是把接线错误说成一句人话，而不是让控制器拿到 undefined 之后 500。
 */
export const CurrentUser = createParamDecorator(
  (_data: unknown, ctx: ExecutionContext): AuthUser => {
    const request = ctx.switchToHttp().getRequest<{ user?: AuthUser }>();
    if (!request.user) throw new UnauthorizedException("没有会话身份：守卫没解出人来");
    return request.user;
  },
);
