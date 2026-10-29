import { PLATFORM_SETTING_DEFS, PLATFORM_SETTING_KEYS } from "@heron/contracts";
import { sql } from "drizzle-orm";
import { afterEach, describe, expect, it } from "vitest";
import { withAdmin } from "./admin";
import * as repo from "./index";
import type { Db } from "./index";

let db: Db | undefined;
afterEach(async () => {
  await db?.close();
  db = undefined;
});

async function fresh() {
  db = repo.openDb("memory");
  await repo.migrate(db);
  const operator = await repo.createPlatformOperator(db, {
    username: "ops",
    displayName: "Ops",
    passwordHash: "unusable:test",
  });
  return { database: db, operator };
}

interface SettingAudit {
  action: string;
  target_id: string;
  detail: { from: string | null; to: string | null };
}

async function settingAudits(database: Db): Promise<SettingAudit[]> {
  const result = await withAdmin(database, (a) =>
    a.db.execute(
      sql`SELECT action, target_id, detail FROM plat_audit_log
          WHERE action = 'platform_setting.updated' ORDER BY target_id`,
    ),
  );
  return result.rows as unknown as SettingAudit[];
}

/**
 * drizzle 把驱动错误包成 `Failed query: …`，真正的 `permission denied` 在 cause 上，
 * 直接 rejects.toThrow 只会匹配到 SQL 文本（见 ./rls.test.ts）。
 */
async function rejectText(run: () => Promise<unknown>): Promise<string> {
  try {
    await run();
  } catch (e) {
    const err = e as { message: string; cause?: { message?: string } };
    return `${err.message} ${err.cause?.message ?? ""}`;
  }
  throw new Error("期望被数据库拒掉，结果读到了");
}

describe("平台级默认值", () => {
  it("一行都没有时四项都是默认值，默认值只有契约那一份", async () => {
    const { database } = await fresh();
    const settings = await repo.getPlatformSettings(database);
    expect(settings.items.map((item) => item.key)).toEqual([...PLATFORM_SETTING_KEYS]);
    for (const item of settings.items) {
      expect(item).toMatchObject({ isDefault: true, updateTime: null, updateBy: null });
      expect(item.value).toBe(PLATFORM_SETTING_DEFS[item.key].fallback);
    }
    expect(await repo.platformDefaults(database)).toEqual({
      modelBaseUrl: null,
      modelName: null,
      retentionDays: null,
      plan: "free",
    });
  });

  it("写进去的值按各自的 schema 归一，审计一项一行、带改前改后", async () => {
    const { database, operator } = await fresh();
    const view = await repo.writePlatformSettings(database, operator.id, {
      default_model_base_url: " https://api.example.com/v1/ ",
      default_retention_days: "07",
      default_plan: "standard",
    });
    const byKey = new Map(view.items.map((item) => [item.key, item]));
    // 结尾斜杠被抹平：请求拼的是 `${baseUrl}/chat/completions`，多一个就多一个空段
    expect(byKey.get("default_model_base_url")).toMatchObject({
      value: "https://api.example.com/v1",
      isDefault: false,
      updateBy: operator.id,
    });
    expect(byKey.get("default_retention_days")?.value).toBe("7");
    expect(byKey.get("default_plan")?.value).toBe("standard");
    expect(byKey.get("default_model_name")).toMatchObject({ isDefault: true });

    const audits = await settingAudits(database);
    expect(audits.map((row) => row.target_id)).toEqual([
      "default_model_base_url",
      "default_plan",
      "default_retention_days",
    ]);
    expect(audits[0]?.detail).toEqual({ from: null, to: "https://api.example.com/v1" });
    expect(await repo.platformDefaults(database)).toEqual({
      modelBaseUrl: "https://api.example.com/v1",
      modelName: null,
      retentionDays: 7,
      plan: "standard",
    });
  });

  it("一路点保存不该刷审计：值没变的项既不写库也不留痕", async () => {
    const { database, operator } = await fresh();
    await repo.writePlatformSettings(database, operator.id, { default_plan: "standard" });
    const before = await settingAudits(database);
    await repo.writePlatformSettings(database, operator.id, { default_plan: "standard" });
    expect(await settingAudits(database)).toEqual(before);
  });

  it("空串等于恢复默认：行没了、界面标回默认、审计里 to 是 null", async () => {
    const { database, operator } = await fresh();
    await repo.writePlatformSettings(database, operator.id, { default_plan: "enterprise" });
    const view = await repo.writePlatformSettings(database, operator.id, { default_plan: "" });
    expect(view.items.find((item) => item.key === "default_plan")).toMatchObject({
      value: PLATFORM_SETTING_DEFS.default_plan.fallback,
      isDefault: true,
    });
    expect(
      await withAdmin(database, (a) => a.db.select().from(repo.platformSettings)),
    ).toHaveLength(0);
    expect((await settingAudits(database)).at(-1)?.detail).toEqual({
      from: "enterprise",
      to: null,
    });
    expect((await repo.platformDefaults(database)).plan).toBe("free");
  });

  it("库里是坏值时按未配置处理，但仍给出上次谁写的——修的人得知道该问谁", async () => {
    const { database, operator } = await fresh();
    // 绕过写侧的硬校验直接塞一行，模拟历史脏数据（写侧不允许 "0"）
    await withAdmin(database, (a) =>
      a.db.insert(repo.platformSettings).values({
        key: "default_retention_days",
        value: "0",
        updateTime: "2026-10-01T00:00:00.000Z",
        updateBy: operator.id,
      }),
    );
    const item = (await repo.getPlatformSettings(database)).items.find(
      (entry) => entry.key === "default_retention_days",
    );
    // 0 天等于「保存即删光成片」，坏值不能当成真的 0 用
    expect(item).toMatchObject({
      value: PLATFORM_SETTING_DEFS.default_retention_days.fallback,
      isDefault: true,
      updateBy: operator.id,
    });
    expect((await repo.platformDefaults(database)).retentionDays).toBeNull();
  });

  it("新空间按平台设置的默认档位开局；把默认值改回 free 也照办", async () => {
    const { database, operator } = await fresh();
    await repo.writePlatformSettings(database, operator.id, { default_plan: "enterprise" });
    const input = {
      name: "Acme",
      slug: "acme",
      ownerEmail: "owner@acme.com",
      ownerDisplayName: "Owner",
      ownerPasswordHash: "unusable:test",
    };
    expect((await repo.provisionTenant(database, operator.id, input)).plan).toBe("enterprise");

    await repo.writePlatformSettings(database, operator.id, { default_plan: "free" });
    expect(
      (await repo.provisionTenant(database, operator.id, { ...input, slug: "beta" })).plan,
    ).toBe("free");
  });

  it("这张表不走租户通道：heron_app 连 SELECT 都没有", async () => {
    const { database } = await fresh();
    const tenant = await repo.createTenant(database, { name: "Tenant", slug: "tenant" });
    expect(
      await rejectText(() =>
        repo.withTenant(database, tenant, (t) => t.db.select().from(repo.platformSettings)),
      ),
    ).toMatch(/permission denied/i);
    // 对照：同一张表走特权通道读得到，说明拒绝的是权限而不是语法
    expect(await withAdmin(database, (a) => a.db.select().from(repo.platformSettings))).toEqual([]);
  });
});
