import { CreateMemberBodySchema, PlatformUserSchema, type PlatformUser } from "@heron/contracts";
import { createPlatformOperator, findPlatformOperatorByEmail, type Db } from "@heron/db/pg";
import { hashPassword } from "@heron/domain";

export function parsePlatformBootstrap(env: NodeJS.ProcessEnv) {
  const result = CreateMemberBodySchema.omit({ role: true }).safeParse({
    email: env.HERON_PLATFORM_EMAIL,
    password: env.HERON_PLATFORM_PASSWORD,
    displayName: env.HERON_PLATFORM_NAME?.trim() || "平台管理员",
  });
  if (!result.success)
    throw new Error(
      "HERON_PLATFORM_EMAIL 必须为有效邮箱；HERON_PLATFORM_PASSWORD 必须为 12–128 位；HERON_PLATFORM_NAME 至多 80 字符",
    );
  return result.data;
}

/** Explicit CLI only. Existing accounts are immutable through bootstrap. */
export async function seedPlatform(
  db: Db,
  env: NodeJS.ProcessEnv,
): Promise<{ created: boolean; user: PlatformUser }> {
  const input = parsePlatformBootstrap(env);
  const existing = await findPlatformOperatorByEmail(db, input.email);
  if (existing) return { created: false, user: PlatformUserSchema.parse(existing) };
  const user = await createPlatformOperator(db, {
    email: input.email,
    displayName: input.displayName,
    passwordHash: hashPassword(input.password),
  });
  return { created: true, user };
}
