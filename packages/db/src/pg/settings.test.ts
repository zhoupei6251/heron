import { eq } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { DEFAULT_RETENTION_DAYS, RETENTION_SETTING_KEY } from "../setting-keys";
import { nowIso } from "../time";
import {
  type Db,
  createTenant,
  getRetentionDays,
  migrate,
  openDb,
  settings,
  setRetentionDays,
  withTenant,
} from "./index";

/**
 * settings 是「每个租户各存一份」的第一张被业务读到的表：主键 (tenant_id, key)。
 * 这里盯的不是读写通不通，而是**边界形状**——同 key 跨租户互不影响的断言必须由租户来做，
 * 用特权通道一次插两行再读，测的是 schema 而不是这条仓储路径。
 */

const ISO_Z = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

describe("pg settings 仓储", () => {
  let db: Db;
  let tenantA: string;
  let tenantB: string;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    tenantA = await createTenant(db, { name: "A 台", slug: "a" });
    tenantB = await createTenant(db, { name: "B 台", slug: "b" });
  });

  afterEach(async () => {
    await db.close();
  });

  it("没写过就是默认档：界面读到的是天数，不是 undefined", async () => {
    expect(await withTenant(db, tenantA, (t) => getRetentionDays(t))).toBe(DEFAULT_RETENTION_DAYS);
  });

  it("A 改成 7 天不动 B：租户各存一份是这张表的存在理由", async () => {
    await withTenant(db, tenantA, (t) => setRetentionDays(t, 7));
    expect(await withTenant(db, tenantA, (t) => getRetentionDays(t))).toBe(7);
    expect(await withTenant(db, tenantB, (t) => getRetentionDays(t))).toBe(DEFAULT_RETENTION_DAYS);
  });

  it("重复设置是覆盖不是多一行，时间戳按单点口径写", async () => {
    await withTenant(db, tenantA, (t) => setRetentionDays(t, 7));
    await withTenant(db, tenantA, (t) => setRetentionDays(t, 30));
    expect(await withTenant(db, tenantA, (t) => getRetentionDays(t))).toBe(30);
    const rows = await withTenant(db, tenantA, (t) =>
      t.db
        .select({ key: settings.key, updatedAt: settings.updatedAt })
        .from(settings)
        .where(eq(settings.key, RETENTION_SETTING_KEY)),
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]?.updatedAt).toMatch(ISO_Z);
  });

  it("0 天与小数都被拒：0 等于「立刻删光成片」，不许由一个输入框写出来", async () => {
    // catch 必须在回调里：让错误穿出 withTenant 等于回滚，测试就分不清是谁拒的
    for (const bad of [0, -1, 1.5, Number.NaN]) {
      await expect(withTenant(db, tenantA, (t) => setRetentionDays(t, bad))).rejects.toThrow(
        /正整数/,
      );
    }
    expect(await withTenant(db, tenantA, (t) => getRetentionDays(t))).toBe(DEFAULT_RETENTION_DAYS);
  });

  it("脏值不作数：value 是 text 列，写进非数字要读回默认而不是 NaN", async () => {
    await withTenant(db, tenantA, (t) =>
      t.db.insert(settings).values({
        tenantId: t.tenantId,
        key: RETENTION_SETTING_KEY,
        value: "abc",
        updatedAt: nowIso(),
      }),
    );
    expect(await withTenant(db, tenantA, (t) => getRetentionDays(t))).toBe(DEFAULT_RETENTION_DAYS);
  });
});
