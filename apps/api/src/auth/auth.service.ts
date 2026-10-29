import { randomUUID } from "node:crypto";
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
  createSession,
  findLiveSession,
  findLoginTenantsByEmail,
  findPlatformOperatorById,
  findTenantBySlug,
  getActiveTenant,
  getUserByEmail,
  getUserById,
  keepSessionAlive,
  revokeSession,
  revokeUserSessions,
  updateProfile,
  changePassword,
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
  sessionId?: string,
): AuthUser {
  return {
    id: user.id,
    tenantId: user.tenantId,
    tenantName: tenant.name,
    tenantSlug: tenant.slug,
    email: user.email,
    displayName: user.displayName,
    role: user.role,
    ...(sessionId === undefined ? {} : { sessionId }),
  };
}

/**
 * 平台运营的查看身份：email 那格放用户名（只用于展示，判据一律不读它），
 * role 给 owner 是因为这个空间里的一批**读**接口按写动作设门槛（GET /manage/audit 要 settings.write）；
 * 真正的写入由 scope 在 RolesGuard 里拒掉，所以这里的「像 owner」只到看得见为止。
 */
function toViewUser(
  operator: { id: string; username: string; displayName: string },
  tenant: { id: string; name: string; slug: string },
): AuthUser {
  return {
    id: operator.id,
    tenantId: tenant.id,
    tenantName: tenant.name,
    tenantSlug: tenant.slug,
    email: operator.username,
    displayName: operator.displayName,
    role: "owner",
    scope: "platform-view",
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
   *
   * tenant 为空 = 界面那一步（不选工作空间）：先按邮箱把他可能在的空间列出来，再逐个走下面这段。
   */
  async login(
    tenant: string,
    email: string,
    password: string,
    userAgent?: string,
  ): Promise<SignedIn | null> {
    if (tenant === "") return this.loginByEmail(email, password, userAgent);
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

      // 会话行与票在同一个事务里：签出票却没有那一行，下一个请求就是 401。
      // id 同时是票里的 sid——逐条强退要知道「踢的是哪一条」，版本号只能整户踢。
      const sessionId = randomUUID();
      await createSession(t, { id: sessionId, userId: user.id, userAgent });
      // tid comes from the locked database snapshot, never from a client-supplied UUID.
      const payload: JwtPayload = {
        sub: user.id,
        tid: user.tenantId,
        role: user.role,
        ver: user.sessionVersion,
        sid: sessionId,
      };
      const token = await this.jwt.signAsync(payload, { audience: TENANT_AUDIENCE });
      return { token, user: toAuthUser(user, tenant, sessionId) };
    });
  }

  /**
   * 不带工作空间码的登录：同名邮箱在多个空间是允许的（唯一键是「租户内邮箱」），
   * 所以判据只有一个——恰好一个空间的口令通过。两个以上通过说明这些空间共用口令，
   * 挑任何一个都是拿口令猜工作空间，宁可 401 也不登进去。
   * shortcut: 只验前 4 个候选（每个一次 scrypt，约 50–100ms），空间更多且邮箱重名时要显式带工作空间码。
   */
  private async loginByEmail(
    email: string,
    password: string,
    userAgent?: string,
  ): Promise<SignedIn | null> {
    const candidates = (await findLoginTenantsByEmail(this.db, email)).slice(0, 4);
    if (!candidates.length) {
      verifyPassword(password, TIMING_EQUALIZER_HASH);
      return null;
    }
    const matched: SignedIn[] = [];
    for (const ref of candidates) {
      const signed = await this.login(ref.slug, email, password, userAgent);
      if (signed) matched.push(signed);
    }
    return matched.length === 1 ? matched[0]! : null;
  }

  /**
   * 退出只作废自己这一条。以前是一把拉高 session_version——那等于「退出登录顺手把手机上的我也踢了」，
   * 多设备登录就成了互相顶号。没有 sid 的旧票退回整户撤销（那时也确实没有更细的粒度）。
   */
  async logout(user: AuthUser): Promise<void> {
    const sid = user.sessionId;
    if (sid === undefined) {
      await withTenant(this.db, user.tenantId, (t) => revokeUserSessions(t, user.id));
      return;
    }
    await withTenant(this.db, user.tenantId, (t) => revokeSession(t, user.id, sid));
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
    const { sub, tid, ver, act, sid } = parsed.data;
    // 查看票的主语在另一张表上。这次读必须开在租户事务之前：pglite 只有一条连接，
    // 事务里再进特权通道就是「在事务里开事务」。
    const operator = act === "platform" ? await findPlatformOperatorById(this.db, sub) : null;
    if (act === "platform") {
      if (!operator || operator.disabledAt !== null || operator.sessionVersion !== ver) return null;
    }
    return withTenant(this.db, tid, async (t) => {
      // 租户先判：停租的租户里每一个人都该立刻失效，
      // 不然「停租的租户整个关在门外」只挡住了进门的人，没挡住已经登进来的人。
      const tenant = await getActiveTenant(t);
      if (!tenant) return null;
      // 停租同样立刻收回查看票；运营账号已在这次读里判过，不再回库。
      if (operator) return toViewUser(operator, tenant);
      const user = await getUserById(t, sub);
      if (!user || user.disabledAt !== null || user.sessionVersion !== ver) return null;
      // 逐条撤销在每次请求生效，靠的是这次读而不是 token 的有效期。
      // 没有 sid 的票是按旧格式签的：整户撤销才作废得了它，所以要求重新登录。
      if (sid === undefined) return null;
      const live = await findLiveSession(t, { id: sid, userId: user.id });
      if (!live) return null;
      // last_seen_at 一分钟写一次：控制台一轮询就把这张表写成最热表，不值。
      await keepSessionAlive(t, live);
      return toAuthUser(user, tenant, sid);
    });
  }

  /**
   * 平台运营进入某个工作空间查看。票仍旧是租户 audience——`tid` 由服务端按库里的行签进去，
   * 每条业务查询照旧走 withTenant 的 RLS，查看态得到的租户坐标和成员一样不可自报。
   * ver 取运营账号的 sessionVersion：登出或停号 bumps 它，正在看的票当场失效。
   */
  async viewToken(
    operatorId: string,
    tenantId: string,
  ): Promise<{ token: string; user: AuthUser } | null> {
    const row = await findPlatformOperatorById(this.db, operatorId);
    if (!row || row.disabledAt !== null) return null;
    const tenant = await withTenant(this.db, tenantId, (t) => getActiveTenant(t));
    if (!tenant) return null;
    const payload: JwtPayload = {
      sub: row.id,
      tid: tenant.id,
      role: "owner",
      ver: row.sessionVersion,
      act: "platform",
    };
    const token = await this.jwt.signAsync(payload, { audience: TENANT_AUDIENCE });
    return { token, user: toViewUser(row, tenant) };
  }
}
