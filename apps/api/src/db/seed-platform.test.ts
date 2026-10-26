import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { type Db, openDb, migrate, findPlatformOperatorByEmail } from "@heron/db/pg";
import { verifyPassword } from "@heron/domain";
import { parsePlatformBootstrap, seedPlatform } from "./seed-platform";

describe("explicit platform bootstrap (PGlite only)", () => {
  let db: Db;
  beforeAll(async () => {
    db = openDb("memory");
    await migrate(db);
  });
  afterAll(async () => {
    await db.close();
  });
  it("requires valid email and at least 12 password characters, without printing values", () => {
    for (const env of [
      {},
      { HERON_PLATFORM_EMAIL: "wrong", HERON_PLATFORM_PASSWORD: "secret-value" },
      { HERON_PLATFORM_EMAIL: "ops@example.com", HERON_PLATFORM_PASSWORD: "short" },
    ]) {
      expect(() => parsePlatformBootstrap(env)).toThrow("HERON_PLATFORM");
      try {
        parsePlatformBootstrap(env);
      } catch (error) {
        expect(String(error)).not.toMatch(/secret-value|short/);
      }
    }
  });
  it("creates an independent operator, then never changes an existing account", async () => {
    const env = {
      HERON_PLATFORM_EMAIL: " Ops@Example.COM ",
      HERON_PLATFORM_PASSWORD: "explicit-bootstrap-password",
    };
    const seeded = await seedPlatform(db, env);
    expect(seeded).toMatchObject({
      created: true,
      user: { displayName: "平台管理员", email: "ops@example.com" },
    });
    expect(seeded.user).not.toHaveProperty("passwordHash");
    const repeated = await seedPlatform(db, {
      ...env,
      HERON_PLATFORM_PASSWORD: "different-bootstrap-password",
      HERON_PLATFORM_NAME: "Different",
    });
    expect(repeated).toEqual({ ...seeded, created: false });
    const operator = await findPlatformOperatorByEmail(db, "ops@example.com");
    expect(verifyPassword(env.HERON_PLATFORM_PASSWORD, operator!.passwordHash)).toBe(true);
    expect(verifyPassword("different-bootstrap-password", operator!.passwordHash)).toBe(false);
    expect(operator!.sessionVersion).toBe(0);
  });
});
