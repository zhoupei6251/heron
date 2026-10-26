import { Inject, Injectable } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";
import { z } from "zod";
import type { PlatformUser } from "@heron/contracts";
import {
  findPlatformOperatorByEmail,
  findPlatformOperatorById,
  revokePlatformSessions,
  type Db,
} from "@heron/db/pg";
import { hashPassword, verifyPassword } from "@heron/domain";
import { PLATFORM_AUDIENCE } from "./config/runtime";
import { PG_DB } from "./db/pg";

const DUMMY_HASH = hashPassword("platform-timing-equalizer-not-a-login");
const PayloadSchema = z.object({
  sub: z.uuid(),
  ver: z.number().int().nonnegative(),
  aud: z.literal(PLATFORM_AUDIENCE),
});
const safeUser = (row: PlatformUser): PlatformUser => ({
  id: row.id,
  email: row.email,
  displayName: row.displayName,
});

@Injectable()
export class PlatformAuthService {
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(JwtService) private readonly jwt: JwtService,
  ) {}

  async login(
    email: string,
    password: string,
  ): Promise<{ user: PlatformUser; token: string } | null> {
    const operator = await findPlatformOperatorByEmail(this.db, email);
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

  logout(user: PlatformUser): Promise<number> {
    return revokePlatformSessions(this.db, user.id);
  }
}
