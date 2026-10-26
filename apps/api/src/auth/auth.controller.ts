import {
  Body,
  Controller,
  Get,
  Inject,
  Patch,
  Post,
  Res,
  UnauthorizedException,
  UseGuards,
} from "@nestjs/common";
import type {
  AuthUser,
  ConsoleSession,
  LoginBody,
  PasswordBody,
  ProfileBody,
} from "@heron/contracts";
import { LoginBodySchema, ProfileBodySchema, PasswordBodySchema } from "@heron/contracts";
import type { Response } from "express";

import {
  PLATFORM_COOKIE,
  SESSION_COOKIE,
  platformCookieOptions,
  sessionCookieOptions,
} from "../config/runtime";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { Public } from "../common/decorators/public.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { AuthService } from "./auth.service";
import { ConsoleAuthService } from "./console-auth.service";
import { ConsoleSessionGuard, CurrentSession } from "./console-session.guard";

/**
 * 认证的三个入口：把口令换成会话（login）、把会话换回身份（me）、把会话作废（logout）。
 * 判据一条都不在这里——登录对不对在 ConsoleAuthService，token 有效无效在守卫。
 *
 * login / me / logout 是同一个 cookie 的两面：空工作空间码进平台身份，非空进租户身份。
 * profile 与 password 只挂在租户那一面（平台账号由 HERON_PLATFORM_* 显式引导，不由界面改）。
 */
@Controller("auth")
export class AuthController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(
    @Inject(ConsoleAuthService) private readonly console: ConsoleAuthService,
    @Inject(AuthService) private readonly auth: AuthService,
  ) {}

  /**
   * 成功：token 只进 cookie，响应体里只有会话表（身份 + 角色 + 权限 + 菜单）——
   * 界面读的是这张表，而会话一旦进了 JS 能读到的地方（响应体、localStorage），XSS 就能拿走它。
   * 失败：统一一句「邮箱或口令不正确」。多一个字就是在替撞库脚本确认邮箱。
   */
  @Public()
  @Post("login")
  async login(
    @Body(new ZodValidationPipe(LoginBodySchema)) body: LoginBody,
    @Res({ passthrough: true }) res: Response,
  ): Promise<ConsoleSession> {
    const signed = await this.console.login(body);
    if (!signed) throw new UnauthorizedException("邮箱或口令不正确");
    res.cookie(SESSION_COOKIE, signed.token, sessionCookieOptions());
    // 旧平台 cookie（独立入口那一版）立即作废：留着它等于给旧 token 留一条旁路。
    res.clearCookie(PLATFORM_COOKIE, platformCookieOptions());
    return signed.session;
  }

  /**
   * 「我是谁」。挂它不是为了拿邮箱显示在右上角，而是界面的置灰判据需要一个来源：
   * 前端自己算不出 role 与菜单，它们只存在于服务端签过并回库查过的那张表里。
   */
  @Public()
  @UseGuards(ConsoleSessionGuard)
  @Get("me")
  me(@CurrentSession() session: ConsoleSession): ConsoleSession {
    return session;
  }

  @Public()
  @UseGuards(ConsoleSessionGuard)
  @Post("logout")
  async logout(
    @CurrentSession() session: ConsoleSession,
    @Res({ passthrough: true }) res: Response,
  ): Promise<{ ok: true }> {
    await this.console.logout(session);
    res.clearCookie(SESSION_COOKIE, sessionCookieOptions());
    res.clearCookie(PLATFORM_COOKIE, platformCookieOptions());
    return { ok: true };
  }

  @Patch("profile")
  profile(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(ProfileBodySchema)) body: ProfileBody,
  ): Promise<AuthUser> {
    return this.auth.profile(user, body);
  }

  @Post("password")
  async password(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(PasswordBodySchema)) body: PasswordBody,
    @Res({ passthrough: true }) res: Response,
  ): Promise<{ ok: true }> {
    await this.auth.password(user, body);
    res.clearCookie(SESSION_COOKIE, sessionCookieOptions());
    return { ok: true };
  }
}
