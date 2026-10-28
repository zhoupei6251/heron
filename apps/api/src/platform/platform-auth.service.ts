import { Inject, Injectable } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";
import { z } from "zod";
import type { PlatformUser } from "@heron/contracts";
import {
  findPlatformOperatorById,
  findPlatformOperatorByUsername,
  revokePlatformSessions,
  type Db,
} from "@heron/db/pg";
import { hashPassword, verifyPassword } from "@heron/domain";
import { PLATFORM_AUDIENCE } from "../config/runtime";
import { PG_DB } from "../db/pg";

const DUMMY_HASH = hashPassword("platform-timing-equalizer-not-a-login");
const PayloadSchema = z.object({
  sub: z.uuid(),
  ver: z.number().int().nonnegative(),
  aud: z.literal(PLATFORM_AUDIENCE),
});
const safeUser = (row: PlatformUser): PlatformUser => ({
  id: row.id,
  username: row.username,
  displayName: row.displayName,
});

@Injectable()
export class PlatformAuthService {
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(JwtService) private readonly jwt: JwtService,
  ) {}

  async login(
    username: string,
    password: string,
  ): Promise<{ user: PlatformUser; token: string } | null> {
    const operator = await findPlatformOperatorByUsername(this.db, username);
    const valid = verifyPassword(password, operator?.passwordHash ?? DUMMY_HASH);
    if (!operator || !valid || operator.disabledAt !== null) return null;
    const token = await this.jwt.signAsync(
      { sub: operator.id, ver: operator.sessionVersion },
      { audience: PLATFORM_AUDIENCE },
    );
    return { user: safeUser(operator), token };
  }

  async authenticate(token: string): Promise<PlatformUser | null> {
    let payload: unknown;
    try {
      payload = await this.jwt.verifyAsync(token, {
        audience: PLATFORM_AUDIENCE,
        algorithms: ["HS256"],
      });
    } catch {
      return null;
    }
    const parsed = PayloadSchema.safeParse(payload);
    if (!parsed.success) return null;
    const operator = await findPlatformOperatorById(this.db, parsed.data.sub);
    if (!operator || operator.disabledAt !== null || operator.sessionVersion !== parsed.data.ver)
      return null;
    return safeUser(operator);
  }

  /**
   * 从查看态回到平台台：按库里此刻的 sessionVersion 重发一张平台票。
   * 不复用旧票——查看期间这张 cookie 已经换成了租户票；重新走一遍口令登录
   * 既不可能（界面没有口令）也不该（登出、停号会当场把这条去路切断）。
   */
  async reissue(operatorId: string): Promise<{ token: string; user: PlatformUser } | null> {
    const operator = await findPlatformOperatorById(this.db, operatorId);
    if (!operator || operator.disabledAt !== null) return null;
    const token = await this.jwt.signAsync(
      { sub: operator.id, ver: operator.sessionVersion },
      { audience: PLATFORM_AUDIENCE },
    );
    return { token, user: safeUser(operator) };
  }

  logout(user: PlatformUser): Promise<number> {
    return revokePlatformSessions(this.db, user.id);
  }
}
