import { Inject, Injectable } from "@nestjs/common";

import type { AuthUser, ConsoleSession, LoginBody, PlatformUser } from "@heron/contracts";
import { consoleAccess } from "@heron/domain";

import { PlatformAuthService } from "../platform/platform-auth.service";
import { AuthService } from "./auth.service";
import { LoginThrottleService } from "./login-throttle.service";

function tenantSession(user: AuthUser): ConsoleSession {
  return { kind: "tenant", user, ...consoleAccess(user.role) };
}

function platformSession(user: PlatformUser): ConsoleSession {
  return { kind: "platform", user, ...consoleAccess("platform") };
}

/**
 * 统一入口：一个 `/auth/login`，空工作空间码只查平台账号，非空只查那一个租户。
 *
 * 这里没有「先试租户再试平台」的兜底：两边的账号表、口令和 audience 互不相干，
 * 落在同一个邮箱上也仍是两个人。兜底会把「我在哪个工作空间」这个问题交给口令去猜，
 * 而猜错的代价是登进别人的工作空间。
 *
 * 会话的权限表在签发的这一刻从 domain/rbac.ts 算出来：它只依赖角色，
 * 所以不需要额外一次读；角色变了由 userFor 的版本复查让旧票立刻失效。
 */
@Injectable()
export class ConsoleAuthService {
  constructor(
    @Inject(AuthService) private readonly tenants: AuthService,
    @Inject(PlatformAuthService) private readonly platform: PlatformAuthService,
    @Inject(LoginThrottleService) private readonly throttle: LoginThrottleService,
  ) {}

  async login(input: LoginBody): Promise<{ token: string; session: ConsoleSession } | null> {
    // 限流只看「工作空间码 + 账号」两样，验口令之前就拦：撞库脚本连 scrypt 都不该跑到。
    await this.throttle.assertAllowed(input.tenant, input.identifier);
    if (input.tenant === "") {
      const signed = await this.platform.login(input.identifier, input.password);
      await this.throttle.settle(input.tenant, input.identifier, Boolean(signed));
      return signed ? { token: signed.token, session: platformSession(signed.user) } : null;
    }
    const signed = await this.tenants.login(input.tenant, input.identifier, input.password);
    await this.throttle.settle(input.tenant, input.identifier, Boolean(signed));
    return signed ? { token: signed.token, session: tenantSession(signed.user) } : null;
  }

  /** 一次请求只读一张票：先按租户 audience 验，验不过再按平台 audience 验。 */
  async sessionFor(token: string): Promise<ConsoleSession | null> {
    const user = await this.tenants.userForToken(token);
    if (user) return tenantSession(user);
    const operator = await this.platform.authenticate(token);
    return operator ? platformSession(operator) : null;
  }

  async logout(session: ConsoleSession): Promise<void> {
    if (session.kind === "platform") await this.platform.logout(session.user);
    else await this.tenants.logout(session.user);
  }
}
