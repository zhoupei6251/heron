import { and, eq } from "drizzle-orm";

import { DEFAULT_RETENTION_DAYS, RETENTION_SETTING_KEY } from "../setting-keys";
import { nowIso } from "../time";
import type { TenantDb } from "./rls";
import { settings } from "./schema/index";

/**
 * 租户级设置（异步版）。这张表的主键是 (tenant_id, key)，所以「保留几天」是**每个客户各自的一档**，
 * 不是全局配置：A 台留 30 天、B 台留 7 天，互不影响。
 *
 * 值列是 text（多种设置共用一列），因此读侧必须容脏：解析不出正整数就回默认档，
 * 而不是把 NaN 交给上层——那会变成「界面上显示 NaN」或更糟「按 NaN 天清理」。
 * 写侧反过来要硬拒：0 天等于「保存即删光成片」，一个输入框不该有那个功率。
 */

async function readSetting(tdb: TenantDb, key: string): Promise<string | null> {
  const rows = await tdb.db
    .select({ value: settings.value })
    .from(settings)
    .where(
      // RLS 已经挡了别的租户，tenant_id 条件仍是明写的那道（见 ./rls.ts）
      and(eq(settings.tenantId, tdb.tenantId), eq(settings.key, key)),
    )
    .limit(1);
  return rows[0]?.value ?? null;
}

async function writeSetting(tdb: TenantDb, key: string, value: string): Promise<void> {
  const at = nowIso();
  await tdb.db
    .insert(settings)
    .values({ tenantId: tdb.tenantId, key, value, updatedAt: at })
    .onConflictDoUpdate({
      target: [settings.tenantId, settings.key],
      set: { value, updatedAt: at },
    });
}

export async function getRetentionDays(tdb: TenantDb): Promise<number> {
  const raw = await readSetting(tdb, RETENTION_SETTING_KEY);
  const parsed = raw === null ? Number.NaN : Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_RETENTION_DAYS;
}

export async function setRetentionDays(tdb: TenantDb, days: number): Promise<void> {
  if (!Number.isInteger(days) || days <= 0) {
    throw new Error(`保留天数必须是正整数，收到 ${days}`);
  }
  await writeSetting(tdb, RETENTION_SETTING_KEY, String(days));
}
