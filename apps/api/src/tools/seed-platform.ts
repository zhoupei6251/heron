import {
  MIN_PASSWORD_LENGTH,
  PlatformLoginBodySchema,
  PlatformUserSchema,
  ProfileBodySchema,
  type PlatformUser,
} from "@heron/contracts";
import { createPlatformOperator, findPlatformOperatorByUsername, type Db } from "@heron/db/pg";
import { hashPassword } from "@heron/domain";

/** 用户名不是机密，默认就是内置的 admin；口令没有默认值。 */
export function parsePlatformBootstrap(env: NodeJS.ProcessEnv) {
  const result = PlatformLoginBodySchema.safeParse({
    username: env.HERON_PLATFORM_USERNAME?.trim() || "admin",
    password: env.HERON_PLATFORM_PASSWORD,
  });
  const profile = ProfileBodySchema.safeParse({
    displayName: env.HERON_PLATFORM_NAME?.trim() || "平台管理员",
  });
  if (!result.success || !profile.success)
    throw new Error(
      `HERON_PLATFORM_USERNAME 必须为 2–64 位小写字母、数字或 . _ -（默认 admin）；HERON_PLATFORM_PASSWORD 必须为 ${String(MIN_PASSWORD_LENGTH)}–128 位；HERON_PLATFORM_NAME 至多 80 字符`,
    );
  return { ...result.data, displayName: profile.data.displayName };
}

/** Explicit CLI only. Existing accounts are immutable through bootstrap. */
export async function seedPlatform(
  db: Db,
  env: NodeJS.ProcessEnv,
): Promise<{ created: boolean; user: PlatformUser }> {
  const input = parsePlatformBootstrap(env);
  const existing = await findPlatformOperatorByUsername(db, input.username);
  if (existing) return { created: false, user: PlatformUserSchema.parse(existing) };
  const user = await createPlatformOperator(db, {
    username: input.username,
    displayName: input.displayName,
    passwordHash: hashPassword(input.password),
  });
  return { created: true, user };
}
