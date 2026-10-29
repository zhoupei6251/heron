import { and, eq } from "drizzle-orm";

import {
  DEFAULT_RETENTION_DAYS,
  MODEL_API_KEY_SETTING_KEY,
  MODEL_BASE_URL_SETTING_KEY,
  MODEL_NAME_SETTING_KEY,
  RETENTION_SETTING_KEY,
} from "../setting-keys";
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
    .values({ tenantId: tdb.tenantId, key, value, updateTime: at })
    .onConflictDoUpdate({
      target: [settings.tenantId, settings.key],
      set: { value, updateTime: at },
    });
}

/**
 * 保留天数：租户自己设了就用它，否则用 fallback（平台设置的默认值），再否则用内置默认。
 * 迁进来的是坏值（"0"、"-1"、空串）也走同一条回落——坏数据不该让清理把成片全删了。
 */
export async function getRetentionDays(
  tdb: TenantDb,
  fallback: number = DEFAULT_RETENTION_DAYS,
): Promise<number> {
  const raw = await readSetting(tdb, RETENTION_SETTING_KEY);
  const parsed = raw === null ? Number.NaN : Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export async function setRetentionDays(tdb: TenantDb, days: number): Promise<void> {
  if (!Number.isInteger(days) || days <= 0) {
    throw new Error(`保留天数必须是正整数，收到 ${days}`);
  }
  await writeSetting(tdb, RETENTION_SETTING_KEY, String(days));
}

/** 三项都可能没配过：null 表示「这一项用兜底」（worker 先看环境变量，最后落到默认地址）。 */
export interface ModelSettings {
  baseUrl: string | null;
  apiKey: string | null;
  model: string | null;
}

export async function getModelSettings(tdb: TenantDb): Promise<ModelSettings> {
  return {
    baseUrl: await readSetting(tdb, MODEL_BASE_URL_SETTING_KEY),
    apiKey: await readSetting(tdb, MODEL_API_KEY_SETTING_KEY),
    model: await readSetting(tdb, MODEL_NAME_SETTING_KEY),
  };
}

/**
 * apiKey 只在给新值时才写：界面回显的是「已配置」，留空提交不能把密钥抹掉。
 * 想把密钥换掉就输入新的；清空密钥这件事没有输入框会做。
 */
export async function setModelSettings(
  tdb: TenantDb,
  input: { baseUrl: string; model: string; apiKey?: string },
): Promise<void> {
  await writeSetting(tdb, MODEL_BASE_URL_SETTING_KEY, input.baseUrl);
  await writeSetting(tdb, MODEL_NAME_SETTING_KEY, input.model);
  if (input.apiKey) await writeSetting(tdb, MODEL_API_KEY_SETTING_KEY, input.apiKey);
}
