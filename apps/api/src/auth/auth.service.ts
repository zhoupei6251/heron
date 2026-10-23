import { Inject, Injectable } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";

import { JwtPayloadSchema, type AuthUser, type JwtPayload, type Role } from "@heron/contracts";
import {
  type Db,
  findTenantBySlug,
  getActiveTenant,
  getUserByEmail,
  getUserById,
  withTenant,
} from "@heron/db/pg";
import { hashPassword, verifyPassword } from "@heron/domain";

import { PG_DB } from "../db/pg";

/** 登录成功的确切产物：token 进 cookie，user 进响应体。除此之外不发第二份身份。 */
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
    const user = await withTenant(this.db, ref.id, (t) => getUserByEmail(t, email));
    const passwordOk = verifyPassword(password, user?.passwordHash ?? TIMING_EQUALIZER_HASH);
    if (!user || !passwordOk || user.disabledAt !== null) return null;

    // tid 由这里查库后签进去：客户端报的是 slug，它永远说不出自己属于哪个租户 uuid
    const payload: JwtPayload = { sub: user.id, tid: user.tenantId, role: user.role };
    const token = await this.jwt.signAsync(payload);
    return { token, user: toAuthUser(user, ref) };
  }

  /**
   * 守卫每次请求的库内复查。载荷来自已验签的 token，但**不可信它此刻还成立**，
   * 也不可信它是本服务在 T6 这一版签的（旧载荷形状、旧角色名单都可能在库里对不上）。
   * 所以先按 schema 判形状，再回库读人：任何一环不成立就是 null → 401，不抛异常。
   */
  async userFor(payload: unknown): Promise<AuthUser | null> {
    const parsed = JwtPayloadSchema.safeParse(payload);
    if (!parsed.success) return null;
    const { sub, tid } = parsed.data;
    return withTenant(this.db, tid, async (t) => {
      // 租户先判：停租的租户里每一个人都该立刻失效，
      // 不然「停租的租户整个关在门外」只挡住了进门的人，没挡住已经登进来的人。
      const tenant = await getActiveTenant(t);
      if (!tenant) return null;
      const user = await getUserById(t, sub);
      if (!user || user.disabledAt !== null) return null;
      return toAuthUser(user, tenant);
    });
  }
}
