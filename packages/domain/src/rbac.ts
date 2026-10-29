import {
  DEFAULT_CONSOLE_MENU_CONFIG,
  MENU_PAGES,
  ROLES,
  type ConsoleAccess,
  type ConsoleGroup,
  type ConsoleMenu,
  type ConsoleMenuConfig,
  type ConsoleMenuItemConfig,
  type Role,
  type TenantMenuId,
} from "@heron/contracts";

/**
 * 判据是纯函数：api 用来挡住请求，web 用来置灰按钮并显示「需要 reviewer 及以上」，
 * worker 将来判断某次自动动作有没有人授权。一份名单三处用，权限就不会出现「界面挡住了但接口没挡」。
 */
export const ACTIONS = [
  "task.claim",
  "review.confirm",
  "settings.write",
  "aigc.set",
  "user.manage",
  "usage.read",
  "session.manage",
] as const;

export type Action = (typeof ACTIONS)[number];

/**
 * 名单校验。装饰器的参数类型在编译期就挡住了写错的动作名，但守卫拿到的是**运行时元数据**：
 * 一个字符串拼错（"task.rename"）如果不判，can() 里的 MIN_ROLE[action] 是 undefined，
 * RANK[undefined] 也是 undefined，比较结果 NaN → false，看着也像拒绝——
 * 但那是靠巧合，不是靠判据。显式判一次，拒的时候才说得出「这个动作不存在」。
 */
export function isAction(value: string): value is Action {
  return (ACTIONS as readonly string[]).includes(value);
}

/**
 * 名次从 contracts 的 ROLES 顺序推（owner 最先 → 名次最大）。
 * 所以加一档角色只改 contracts 一处；顺序错了 rbac.test.ts 会红，而不是静默地把权限翻转。
 */
const RANK = Object.fromEntries(ROLES.map((role, i) => [role, ROLES.length - i])) as Record<
  Role,
  number
>;

/** 每个动作的最低角色。这张表必须写满：新增动作而不配角色，编译期就过不去。 */
const MIN_ROLE: Record<Action, Role> = {
  "task.claim": "operator",
  // 规格书的「人点确认」不能是只读账号，也不能是只管生产的操作账号
  "review.confirm": "reviewer",
  "settings.write": "admin",
  // AIGC 姿态一改就能把角标和声明绕掉，它是最高危的一次写
  "aigc.set": "admin",
  "user.manage": "owner",
  "usage.read": "viewer",
  // 强退别人的登录：admin 起。自己的那条不需要这个动作，谁都能退掉自己。
  "session.manage": "admin",
};

export function can(role: Role, action: Action): boolean {
  return RANK[role] >= RANK[MIN_ROLE[action]];
}

/** 界面置灰时那句人话的主语：给最低角色，而不是给一句写死的文案。 */
export function requiredRoleFor(action: Action): Role {
  return MIN_ROLE[action];
}

/**
 * 菜单条目的动作门槛。菜单可配置，但「这个页面要什么角色才进得去」不是配置项——
 * 它和接口上的 @RequireAction 是同一份判据，写进库里就会出现「菜单看得见、接口 403」。
 */
export const MENU_ACTIONS: Partial<Record<TenantMenuId, Action>> = {
  members: "user.manage",
  feeds: "settings.write",
  settings: "settings.write",
  usage: "usage.read",
  audit: "settings.write",
};

/** 某个页面允许出现的可见角色。菜单配置只能从这里收窄，不能越过动作门槛加人。 */
export function allowedMenuRoles(id: TenantMenuId): Role[] {
  const action = MENU_ACTIONS[id];
  return action ? ROLES.filter((role) => can(role, action)) : [...ROLES];
}

function itemVisible(role: Role, item: ConsoleMenuItemConfig): boolean {
  const action = MENU_ACTIONS[item.id];
  if (action && !can(role, action)) return false;
  return item.roles.length === 0 || item.roles.includes(role);
}

/**
 * 保存前的语义闸：结构合法（zod）还不够，还要保证**每一档角色都至少有一条看得见的菜单**。
 * 允许把共同页面全部藏起来，换来的是一档角色登录后落在 404——配置页不该有那个功率。
 * 同时拒绝把页面动作门槛之下的人塞进可见角色，避免配出「看得见、点进去 403」。
 */
export function menuConfigProblem(config: ConsoleMenuConfig): string | null {
  for (const group of config.groups) {
    for (const item of group.items) {
      const allowed = allowedMenuRoles(item.id);
      const extra = item.roles.filter((role) => !allowed.includes(role));
      if (extra.length > 0) {
        return `「${item.label}」的可见角色超出该页面的动作门槛：${extra.join("、")} 不能放进来`;
      }
    }
  }
  for (const role of ROLES) {
    const visible = config.groups.some((group) =>
      group.items.some((item) => !item.hidden && itemVisible(role, item)),
    );
    if (!visible) return `角色 ${role} 会一条菜单都看不到；每个角色至少要保留一个可见页面`;
  }
  return null;
}

const PLATFORM_GROUPS: ConsoleGroup[] = [
  { key: "platform-operations", label: "平台运营", icon: "operations" },
  { key: "platform-config", label: "平台配置", icon: "configuration" },
  { key: "platform-monitor", label: "平台监控", icon: "monitor" },
];
const PLATFORM_MENUS: ConsoleMenu[] = (
  [
    ["platform-overview", "platform-operations"],
    ["tenants", "platform-operations"],
    ["platform-settings", "platform-config"],
    ["platform-menus", "platform-config"],
    ["platform-usage", "platform-monitor"],
    ["platform-audit", "platform-monitor"],
    ["health", "platform-monitor"],
  ] as const
).map(([id, group]) => ({
  id,
  group,
  path: MENU_PAGES[id].path,
  label: MENU_PAGES[id].label,
  hidden: false,
}));

/**
 * 服务端不存「角色 → 菜单」的交叉表：角色只有五档且是固定的，菜单是按角色动作门槛过滤出来的。
 * 平台台的菜单固定；租户台读配置（缺省即 DEFAULT），过滤后仍然是 id/path/label 的白名单形状。
 */
export function consoleAccess(
  role: Role | "platform",
  config: ConsoleMenuConfig = DEFAULT_CONSOLE_MENU_CONFIG,
): ConsoleAccess {
  if (role === "platform") {
    return {
      roles: [role],
      permissions: [
        "platform.tenants.manage",
        "platform.usage.read",
        "platform.audit.read",
        "platform.health.read",
      ],
      groups: PLATFORM_GROUPS,
      menus: PLATFORM_MENUS.map((menu) => ({ ...menu })),
    };
  }
  const groups: ConsoleGroup[] = [];
  const menus: ConsoleMenu[] = [];
  for (const group of config.groups) {
    const items = group.items.filter((item) => itemVisible(role, item));
    if (items.length === 0) continue;
    groups.push({ key: group.key, label: group.label, icon: group.icon });
    for (const item of items) {
      menus.push({
        id: item.id,
        path: MENU_PAGES[item.id].path,
        label: item.label,
        group: group.key,
        hidden: item.hidden,
      });
    }
  }
  return {
    roles: [role],
    permissions: ACTIONS.filter((action) => can(role, action)),
    groups,
    menus,
  };
}
