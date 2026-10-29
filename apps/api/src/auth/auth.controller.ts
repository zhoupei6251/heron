import {
  Body,
  Controller,
  Get,
  Headers,
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
  WorkspaceViewBody,
} from "@heron/contracts";
import {
  LoginBodySchema,
  ProfileBodySchema,
  PasswordBodySchema,
  WorkspaceViewBodySchema,
} from "@heron/contracts";
import type { TenantRef } from "@heron/db/pg";
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
 * 认证的入口：把口令换成会话（login）、把会话换回身份（me）、把会话作废（logout），
 * 以及平台运营进出某个工作空间查看（workspaces / workspace-view）。
 * 判据一条都不在这里——登录对不对在 ConsoleAuthService，token 有效无效在守卫。
 *
 * login / me / logout 是同一个 cookie 的两面：按账号形状分平台身份与租户身份。
 * workspace-view 换出来的也是这个 cookie，只是多带一个 scope=platform-view，写动作由 RolesGuard 拒。
 * profile 与 password 只挂在租户成员那一面（平台账号由 HERON_PLATFORM_* 显式引导，不由界面改）。
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
   * 失败：统一一句「账号或口令不正确」。多一个字就是在替撞库脚本确认账号存在。
   */
  @Public()
  @Post("login")
  async login(
    @Body(new ZodValidationPipe(LoginBodySchema)) body: LoginBody,
    @Headers("user-agent") userAgent: string | undefined,
    @Res({ passthrough: true }) res: Response,
  ): Promise<ConsoleSession> {
    const signed = await this.console.login(body, userAgent);
    if (!signed) throw new UnauthorizedException("账号或口令不正确");
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

  /**
   * 切换器那一个下拉的数据源。返回的是工作空间坐标（id/name/slug），不是租户业务数据：
   * 进了哪家之后能读什么，仍由那张新签的租户票决定。
   */
  @Public()
  @UseGuards(ConsoleSessionGuard)
  @Get("workspaces")
  workspaces(@CurrentSession() session: ConsoleSession): Promise<TenantRef[]> {
    return this.console.workspaces(session);
  }

  /**
   * 进入某个工作空间查看：换一张服务端签的租户票（`tid` 取自库里的行，不由客户端报），
   * cookie 就此变成租户身份，13 个租户页面与它们的接口一行都不用改。
   * 代价写在 RolesGuard 上：这张票只读。
   */
  @Public()
  @UseGuards(ConsoleSessionGuard)
  @Post("workspace-view")
  async startView(
    @CurrentSession() session: ConsoleSession,
    @Body(new ZodValidationPipe(WorkspaceViewBodySchema)) body: WorkspaceViewBody,
    @Res({ passthrough: true }) res: Response,
  ): Promise<ConsoleSession> {
    const switched = await this.console.startView(session, body.tenantId);
    if (!switched) throw new UnauthorizedException("工作空间不可用或运营账号已停用，请重新登录");
    res.cookie(SESSION_COOKIE, switched.token, sessionCookieOptions());
    return switched.session;
  }

  /** 退出查看：换回平台票。成员调它拿到的是 403，不是「把自己登出了」。 */
  @Public()
  @UseGuards(ConsoleSessionGuard)
  @Post("workspace-view/end")
  async endView(
    @CurrentSession() session: ConsoleSession,
    @Res({ passthrough: true }) res: Response,
  ): Promise<ConsoleSession> {
    const switched = await this.console.endView(session);
    if (!switched) throw new UnauthorizedException("运营账号已停用，请重新登录");
    res.cookie(SESSION_COOKIE, switched.token, sessionCookieOptions());
    return switched.session;
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
