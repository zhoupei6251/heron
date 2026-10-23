import { Body, Controller, Get, Inject, Post, Res, UnauthorizedException } from "@nestjs/common";
import type { AuthUser, LoginBody } from "@heron/contracts";
import { LoginBodySchema } from "@heron/contracts";
import type { Response } from "express";

import { SESSION_COOKIE, sessionCookieOptions } from "../config/runtime";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { Public } from "../common/decorators/public.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { AuthService } from "./auth.service";

/**
 * 认证的两个入口：把口令换成会话（login），把会话换回身份（me）。
 * 判据一条都不在这里——登录对不对在 AuthService，token 有效无效在守卫。
 */
@Controller("auth")
export class AuthController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(AuthService) private readonly auth: AuthService) {}

  /**
   * 成功：token 只进 cookie，响应体里只有那张 AuthUser——界面读的是身份，
   * 而会话一旦进了 JS 能读到的地方（响应体、localStorage），XSS 就能拿走它。
   * 失败：统一一句「邮箱或口令不正确」。多一个字就是在替撞库脚本确认邮箱。
   */
  @Public()
  @Post("login")
  async login(
    @Body(new ZodValidationPipe(LoginBodySchema)) body: LoginBody,
    @Res({ passthrough: true }) res: Response,
  ): Promise<AuthUser> {
    const signed = await this.auth.login(body.tenant, body.email, body.password);
    if (!signed) throw new UnauthorizedException("邮箱或口令不正确");
    res.cookie(SESSION_COOKIE, signed.token, sessionCookieOptions());
    return signed.user;
  }

  /**
   * 「我是谁」。挂它不是为了拿邮箱显示在右上角，而是界面的置灰判据需要一个来源：
   * 前端自己算不出 role，role 只存在于服务端签过并回库查过的那张表里。
   */
  @Get("me")
  me(@CurrentUser() user: AuthUser): AuthUser {
    return user;
  }
}
