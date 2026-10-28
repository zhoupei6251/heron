import { ForbiddenException, Inject, Injectable } from "@nestjs/common";

import type { AuthUser, ConsoleSession, LoginBody, PlatformUser } from "@heron/contracts";
import { consoleAccess } from "@heron/domain";
import { listActiveTenants, noteWorkspaceView, type Db, type TenantRef } from "@heron/db/pg";

import { PG_DB } from "../db/pg";
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
 * realm 判据：`@`。运营用户名的字符集（contracts 的 PlatformUsernameSchema）容不下 `@`，
 * 而租户身份必须是邮箱，所以这一个字符就能把两张账号表分开——不需要界面再问一次。
 * 显式带了工作空间码时一律走租户分支（脚本与旧链接的用法）。
 */
function isPlatformRealm(input: LoginBody): boolean {
  return input.tenant === "" && !input.identifier.includes("@");
}

/**
 * 统一入口：一个 `/auth/login`，界面不再问工作空间——realm 由 isPlatformRealm 按形状判。
 *
 * 这里没有「先试租户再试平台」的兜底：两边的账号表、口令和 audience 互不相干，
 * 落在同一个邮箱上也仍是两个人。兜底会把「我在哪个工作空间」交给口令去猜，
 * 而猜错的代价是登进别人的工作空间。不带空间的邮箱登录只认**恰好一个**空间口令通过。
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
    @Inject(PG_DB) private readonly db: Db,
  ) {}

  async login(input: LoginBody): Promise<{ token: string; session: ConsoleSession } | null> {
    // 限流只看「工作空间码 + 账号」两样，验口令之前就拦：撞库脚本连 scrypt 都不该跑到。
    await this.throttle.assertAllowed(input.tenant, input.identifier);
    if (isPlatformRealm(input)) {
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

  /**
   * 切换器能看到的空间：平台运营与查看态拿到全部在用空间。
   * 空间成员只拿到自家那一个——而且成员压根进不到 startView（viewOperator 那道闸会拒），
   * 这里不给第二条路，只让界面少一次「这个接口对我返回什么」。
   */
  async workspaces(session: ConsoleSession): Promise<TenantRef[]> {
    if (session.kind === "platform" || session.user.scope === "platform-view") {
      return listActiveTenants(this.db);
    }
    const all = await listActiveTenants(this.db);
    return all.filter((t) => t.id === session.user.tenantId);
  }

  /**
   * 平台运营进入某个工作空间：换一张租户 audience 的票，权限面由 viewToken 决定（只读）。
   * 允许从「查看 A」直接切到「查看 B」，中间不回平台台——切换器的语义就是一个下拉。
   */
  async startView(
    session: ConsoleSession,
    tenantId: string,
  ): Promise<{ token: string; session: ConsoleSession } | null> {
    const operatorId = this.viewOperator(session);
    const signed = await this.tenants.viewToken(operatorId, tenantId);
    if (!signed) return null;
    // 从「查看 A」直接切到「查看 B」也要先合上 A：台账里 started 与 ended 必须成对，
    // 不然「在哪家待了多久」这条问法只有一半能答。
    if (session.kind === "tenant" && session.user.scope === "platform-view") {
      await noteWorkspaceView(this.db, operatorId, session.user.tenantId, false);
    }
    await noteWorkspaceView(this.db, operatorId, tenantId, true);
    return { token: signed.token, session: tenantSession(signed.user) };
  }

  /** 退出查看：换回平台票。空间没了（停租）也照样回得去，因为平台身份与那个空间无关。 */
  async endView(
    session: ConsoleSession,
  ): Promise<{ token: string; session: ConsoleSession } | null> {
    const operatorId = this.viewOperator(session);
    const reissued = await this.platform.reissue(operatorId);
    if (!reissued) return null;
    // 平台台那边调过来没有正在进行的查看，也就没有 ended 要记。
    if (session.kind === "tenant") {
      await noteWorkspaceView(this.db, operatorId, session.user.tenantId, false);
    }
    return { token: reissued.token, session: platformSession(reissued.user) };
  }

  /** 这个会话能不能作为「平台运营」来进出查看态。空间成员永远拿到 403。 */
  private viewOperator(session: ConsoleSession): string {
    if (session.kind === "platform") return session.user.id;
    if (session.user.scope === "platform-view") return session.user.id;
    throw new ForbiddenException("只有平台运营能切换工作空间");
  }

  async logout(session: ConsoleSession): Promise<void> {
    if (session.kind === "platform") await this.platform.logout(session.user);
    else await this.tenants.logout(session.user);
  }
}
