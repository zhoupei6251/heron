import {
  PLATFORM_SETTING_DEFS,
  PLATFORM_SETTING_KEYS,
  PlanSchema,
  isPlatformSettingKey,
  type PlanId,
  type PlatformSettingItem,
  type PlatformSettingKey,
  type PlatformSettings,
} from "@heron/contracts";
import { eq } from "drizzle-orm";

import { CONSOLE_MENU_SETTING_KEY } from "../setting-keys";
import { nowIso } from "../time";
import { audit, requireOperator, withAdmin, type AdminDb } from "./admin";
import type { Db } from "./driver";
import { platformSettings } from "./schema/index";

/**
 * 平台级默认值：一台机器一份，租户自己没配时才轮到它。
 *
 * 这张表只有三四行，全读一遍比写四个按 key 的查询更省事，也不会因为漏了某个键而让
 * 「解析链」少一环——少一环的症状是租户全都没配时流水线报「未配置模型名」，而设置页上明明填了。
 */

/**
 * 读并逐项校验。认不出的键、非法值都跳过而不是抛：设置是「锦上添花」，
 * 一行脏数据不该让 worker 领不了活、让设置页打不开。跳过时点名是哪一项、值长什么样（这几项都不是密钥）。
 */
async function storedValues(a: AdminDb): Promise<Map<PlatformSettingKey, string>> {
  const rows = await a.db.select().from(platformSettings);
  const values = new Map<PlatformSettingKey, string>();
  for (const row of rows) {
    // 菜单树与标量设置共用一张表，但它有自己的结构，不属于 PLATFORM_SETTING_DEFS
    if (row.key === CONSOLE_MENU_SETTING_KEY) continue;
    if (!isPlatformSettingKey(row.key)) {
      console.error(`平台设置里有不认识的键，已忽略：${row.key}`);
      continue;
    }
    const parsed = PLATFORM_SETTING_DEFS[row.key].schema.safeParse(row.value);
    if (!parsed.success) {
      console.error(`平台设置 ${row.key} 的值不合法，按未配置处理：${row.value.slice(0, 200)}`);
      continue;
    }
    values.set(row.key, parsed.data);
  }
  return values;
}

/** worker 用的那一份：租户没配时依次落到它、环境变量、内置默认。 */
export interface PlatformDefaults {
  modelBaseUrl: string | null;
  modelName: string | null;
  retentionDays: number | null;
  /** 开通新工作空间时用它开局；读不到就是 free（fail closed 到最小额度）。 */
  plan: PlanId;
}

export async function readPlatformDefaults(a: AdminDb): Promise<PlatformDefaults> {
  const values = await storedValues(a);
  const days = values.get("default_retention_days");
  return {
    modelBaseUrl: values.get("default_model_base_url") ?? null,
    modelName: values.get("default_model_name") ?? null,
    retentionDays: days === undefined ? null : Number(days),
    plan: PlanSchema.parse(values.get("default_plan") ?? "free"),
  };
}

/** 特权通道读一次；worker 每条任务读一次（不缓存，改了设置下一单就生效）。 */
export async function platformDefaults(db: Db): Promise<PlatformDefaults> {
  return withAdmin(db, readPlatformDefaults);
}

/** 设置页要的一整块。没存过的项回落到兜底值并标 isDefault，界面因此不必自己再写一份默认值。 */
export async function getPlatformSettings(db: Db): Promise<PlatformSettings> {
  return withAdmin(db, async (a) => {
    const rows = await a.db.select().from(platformSettings);
    const byKey = new Map(rows.map((row) => [row.key, row]));
    const items: PlatformSettingItem[] = PLATFORM_SETTING_KEYS.map((key) => {
      const def = PLATFORM_SETTING_DEFS[key];
      const row = byKey.get(key);
      if (!row) {
        return { key, value: def.fallback, isDefault: true, updateTime: null, updateBy: null };
      }
      const parsed = def.schema.safeParse(row.value);
      if (!parsed.success) {
        // 值坏了也照样把「上次谁在什么时候写过」给出来：修的人需要知道该找谁问
        return {
          key,
          value: def.fallback,
          isDefault: true,
          updateTime: row.updateTime,
          updateBy: row.updateBy,
        };
      }
      return {
        key,
        value: parsed.data,
        isDefault: false,
        updateTime: row.updateTime,
        updateBy: row.updateBy,
      };
    });
    return { items };
  });
}

/**
 * 改一项或几项。空串表示恢复默认（删掉那一行）。
 *
 * 值没变就不写、也不记审计——一路点「保存」不该刷出一串「改成了同样的值」，
 * 那会把真正的变更埋掉。审计里存改前与改后：这几项都不是密钥，事后要能一眼看出改了什么。
 */
export async function writePlatformSettings(
  db: Db,
  operatorId: string,
  values: Partial<Record<PlatformSettingKey, string>>,
): Promise<PlatformSettings> {
  await withAdmin(db, async (a) => {
    await requireOperator(a, operatorId);
    const rows = await a.db.select().from(platformSettings);
    const previous = new Map(rows.map((row) => [row.key, row.value]));
    for (const [key, raw] of Object.entries(values)) {
      if (raw === undefined || !isPlatformSettingKey(key)) continue;
      const def = PLATFORM_SETTING_DEFS[key];
      const next = raw.trim() === "" ? null : def.schema.parse(raw);
      const before = previous.get(key) ?? null;
      if (next === before) continue;
      const at = nowIso();
      if (next === null) {
        await a.db.delete(platformSettings).where(eq(platformSettings.key, key));
      } else {
        await a.db
          .insert(platformSettings)
          .values({
            key,
            value: next,
            createBy: operatorId,
            createTime: at,
            updateBy: operatorId,
            updateTime: at,
          })
          .onConflictDoUpdate({
            target: platformSettings.key,
            set: { value: next, updateBy: operatorId, updateTime: at },
          });
      }
      await audit(a, operatorId, "platform_setting.updated", key, { from: before, to: next });
    }
  });
  return getPlatformSettings(db);
}
