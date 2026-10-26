import { BadRequestException, Inject, Injectable, UnauthorizedException } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";

import {
  JwtPayloadSchema,
  type AuthUser,
  type JwtPayload,
  type Role,
  type ProfileBody,
  type PasswordBody,
} from "@heron/contracts";
import {
  type Db,
  findTenantBySlug,
  getActiveTenant,
  getUserByEmail,
  getUserById,
  updateProfile,
  changePassword,
  revokeUserSessions,
  withTenant,
} from "@heron/db/pg";
import { hashPassword, verifyPassword } from "@heron/domain";

import { PG_DB } from "../db/pg";
import { TENANT_AUDIENCE } from "../config/runtime";

/** Tenant authentication result; the console facade adds access metadata, never another actor. */
export interface SignedIn {
  token: string;
  user: AuthUser;
}

/**
 * 没有这个账号时也烧一次 scrypt。
 *
 * scrypt 一次约 50–100ms，而「查不到人」是几毫秒的索引查询：不补这一下，
 * 响应时间本身就成了「这个邮箱有没有账号」的证词——我们刚在错误文案上堵住的枚举面，
 * 会从时序里漏出去。这个串只是拿来烧时间的，它对应的口令不存在于任何账号上。
 */
const TIMING_EQUALIZER_HASH = hashPassword("heron-timing-equalizer-not-a-login");

/**
 * 库里那一行 → 界面那一张表：password_hash 与 disabled_at 到这一步为止，一步都不许往外走。
 * 租户名与 slug 来自同一次请求里已经读过的 tenants 行（login 由 slug 查得，
 * userFor 由 getActiveTenant 同查询带回），不额外开一次「补显示信息」的读。
 */
function toAuthUser(
  user: {
    id: string;
    tenantId: string;
    email: string;
    displayName: string;
    role: Role;
  },
  tenant: { name: string; slug: string },
): AuthUser {
  return {
    id: user.id,
    tenantId: user.tenantId,
    tenantName: tenant.name,
    tenantSlug: tenant.slug,
    email: user.email,
    displayName: user.displayName,
    role: user.role,
  };
}

/**
 * 登录的判据与「每次请求回库复查」的判据都在这一个类里，因为它们是同一个问题的两次问法：
 * 这个人此刻还进得来吗？
 *
 * 两层分工是有意的：token 只证明「这张票是我签出去的」，库里那一行才证明「票的主人还在」。
 * JWT 发出去就撤销不掉，所以 short TTL 不是撤销机制，只是漏网时的上限——
 * 真正的撤销是 userFor 这次读（停号、停租都靠它立刻生效）。
 */
@Injectable()
export class AuthService {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(JwtService) private readonly jwt: JwtService,
  ) {}

  /**
   * 三件事按「先便宜后贵、先判环境后判人」排：租户 → 人 → 口令。
   * 任何一步不成立都返回同一个 null——调用方（控制器）因此拿不到「是没这个人还是口令错」这个信息，
   * 也就没法把它写进响应。这是刻意的：错误消息统一成「邮箱或口令不正确」。
   */
  async login(tenant: string, email: string, password: string): Promise<SignedIn | null> {
    const ref = await findTenantBySlug(this.db, tenant);
    if (!ref || ref.suspendedAt !== null) {
      verifyPassword(password, TIMING_EQUALIZER_HASH);
      return null;
    }
    return withTenant(this.db, ref.id, async (t) => {
      // The slug lookup only locates the workspace. Recheck and hold its identity lock
      // through signing; suspension, password and role changes serialize on this row.
      const tenant = await getActiveTenant(t, { forUpdate: true });
      const user = tenant ? await getUserByEmail(t, email) : null;
      const passwordOk = verifyPassword(password, user?.passwordHash ?? TIMING_EQUALIZER_HASH);
      if (!tenant || !user || !passwordOk || user.disabledAt !== null) return null;

      // tid comes from the locked database snapshot, never from a client-supplied UUID.
      const payload: JwtPayload = {
        sub: user.id,
        tid: user.tenantId,
        role: user.role,
        ver: user.sessionVersion,
      };
      const token = await this.jwt.signAsync(payload, { audience: TENANT_AUDIENCE });
      return { token, user: toAuthUser(user, tenant) };
    });
  }

  logout(user: AuthUser): Promise<number> {
    return withTenant(this.db, user.tenantId, (t) => revokeUserSessions(t, user.id));
  }

  profile(user: AuthUser, input: ProfileBody): Promise<AuthUser> {
    return withTenant(this.db, user.tenantId, async (t) => {
      const member = await updateProfile(t, user.id, input);
      const tenant = await getActiveTenant(t);
      if (!tenant) throw new UnauthorizedException("工作空间不可用，请重新登录");
      return toAuthUser({ ...member, tenantId: user.tenantId }, tenant);
    });
  }

  async password(user: AuthUser, input: PasswordBody): Promise<void> {
    const current = await withTenant(this.db, user.tenantId, (t) => getUserById(t, user.id));
    const valid = verifyPassword(
      input.currentPassword,
      current?.passwordHash ?? TIMING_EQUALIZER_HASH,
    );
    if (!current || !valid) throw new BadRequestException("当前口令不正确");
    const nextHash = hashPassword(input.newPassword);
    await withTenant(this.db, user.tenantId, (t) =>
      changePassword(t, user.id, current.passwordHash, nextHash),
    );
  }

  /**
   * 统一登录入口的租户分支：先验签（audience + 算法都是硬条件），再走 userFor 的库内复查。
   *
   * 两种 actor 各签各的 audience，所以平台 token 在这里必然验不过——
   * 不需要先猜「这张票是谁的」，也就不存在把平台身份映射成租户身份的那一步。
   */
  async userForToken(token: string): Promise<AuthUser | null> {
    let payload: unknown;
    try {
      payload = await this.jwt.verifyAsync(token, {
        audience: TENANT_AUDIENCE,
        algorithms: ["HS256"],
      });
    } catch {
      return null;
    }
    // jsonwebtoken 的 audience 判据接受「数组里包含期望值」，这里再钉一次单值：
    // 一张同时声明两个 audience 的票存在解释分歧，统一入口必须明确拒掉，而不是猜它属于谁。
    if ((payload as { aud?: unknown }).aud !== TENANT_AUDIENCE) return null;
    return this.userFor(payload);
  }

  /**
   * 守卫每次请求的库内复查。载荷来自已验签的 token，但**不可信它此刻还成立**，
   * 也不可信它是本服务在 T6 这一版签的（旧载荷形状、旧角色名单都可能在库里对不上）。
   * 所以先按 schema 判形状，再回库读人：任何一环不成立就是 null → 401，不抛异常。
   */
  async userFor(payload: unknown): Promise<AuthUser | null> {
    const parsed = JwtPayloadSchema.safeParse(payload);
    if (!parsed.success) return null;
    const { sub, tid, ver } = parsed.data;
    return withTenant(this.db, tid, async (t) => {
      // 租户先判：停租的租户里每一个人都该立刻失效，
      // 不然「停租的租户整个关在门外」只挡住了进门的人，没挡住已经登进来的人。
      const tenant = await getActiveTenant(t);
      if (!tenant) return null;
      const user = await getUserById(t, sub);
      if (!user || user.disabledAt !== null || user.sessionVersion !== ver) return null;
      return toAuthUser(user, tenant);
    });
  }
}
