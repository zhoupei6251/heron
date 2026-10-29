import {
  ConsoleMenuConfigSchema,
  DEFAULT_CONSOLE_MENU_CONFIG,
  type ConsoleMenuConfig,
  type ConsoleMenuConfigView,
} from "@heron/contracts";
import { menuConfigProblem } from "@heron/domain";
import { eq } from "drizzle-orm";

import { CONSOLE_MENU_SETTING_KEY } from "../setting-keys";
import { nowIso } from "../time";
import { audit, requireOperator, withAdmin, type AdminDb } from "./admin";
import { ManagementError } from "./console-query";
import type { Db } from "./driver";
import { platformSettings } from "./schema/index";

/**
 * 菜单配置是平台级设置的一行（JSON），不新开表：它只有一份、没有第二层身份，
 * 和 `plat_setting` 里其它键是同一种东西。坏数据一律回落到产品默认菜单——
 * 读侧宽松是为了「一行脏数据不该让所有人登录后没有导航」。
 */
function parseConfig(raw: string | null): ConsoleMenuConfig | null {
  if (raw === null) return null;
  try {
    const parsed = ConsoleMenuConfigSchema.safeParse(JSON.parse(raw));
    if (!parsed.success) return null;
    return menuConfigProblem(parsed.data) === null ? parsed.data : null;
  } catch {
    return null;
  }
}

async function readStored(a: AdminDb): Promise<string | null> {
  const [row] = await a.db
    .select({ value: platformSettings.value })
    .from(platformSettings)
    .where(eq(platformSettings.key, CONSOLE_MENU_SETTING_KEY))
    .limit(1);
  return row?.value ?? null;
}

export async function getConsoleMenuConfig(db: Db): Promise<ConsoleMenuConfigView> {
  return withAdmin(db, async (a) => {
    const raw = await readStored(a);
    const config = parseConfig(raw);
    if (config) return { config, isDefault: false };
    if (raw !== null) {
      // 不把坏值吞成静默：配置页会显示「默认」，日志里得留着为什么
      console.error(`平台菜单配置不合法，已回落到默认菜单：${raw.slice(0, 200)}`);
    }
    return { config: DEFAULT_CONSOLE_MENU_CONFIG, isDefault: true };
  });
}

/** 签名会话只要树本身：没配过或存坏了都回默认。 */
export async function readConsoleMenuConfig(db: Db): Promise<ConsoleMenuConfig> {
  return (await getConsoleMenuConfig(db)).config;
}

/**
 * 写整棵配置树。语义闸在 domain（每个角色至少一条可见菜单、可见角色不能越过动作门槛），
 * 这里拒绝的是「配置页能保存一份自己都进不去的导航」这类事故。
 */
export async function writeConsoleMenuConfig(
  db: Db,
  operatorId: string,
  input: ConsoleMenuConfig,
): Promise<ConsoleMenuConfigView> {
  const config = ConsoleMenuConfigSchema.parse(input);
  const problem = menuConfigProblem(config);
  if (problem) throw new ManagementError("invalid", problem);
  const value = JSON.stringify(config);
  await withAdmin(db, async (a) => {
    await requireOperator(a, operatorId);
    const before = parseConfig(await readStored(a));
    if (before && JSON.stringify(before) === value) return;
    const at = nowIso();
    await a.db
      .insert(platformSettings)
      .values({
        key: CONSOLE_MENU_SETTING_KEY,
        value,
        createBy: operatorId,
        createTime: at,
        updateBy: operatorId,
        updateTime: at,
      })
      .onConflictDoUpdate({
        target: platformSettings.key,
        set: { value, updateBy: operatorId, updateTime: at },
      });
    await audit(a, operatorId, "console_menu.updated", CONSOLE_MENU_SETTING_KEY, {
      from: before,
      to: config,
    });
  });
  return getConsoleMenuConfig(db);
}
