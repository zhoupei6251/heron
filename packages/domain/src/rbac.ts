import { ROLES, type ConsoleAccess, type ConsoleMenu, type Role } from "@heron/contracts";

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
};

export function can(role: Role, action: Action): boolean {
  return RANK[role] >= RANK[MIN_ROLE[action]];
}

/** 界面置灰时那句人话的主语：给最低角色，而不是给一句写死的文案。 */
export function requiredRoleFor(action: Action): Role {
  return MIN_ROLE[action];
}

const TENANT_MENUS: (ConsoleMenu & { action?: Action })[] = [
  { id: "overview", path: "/overview", label: "工作概览", group: "内容工作室" },
  { id: "candidates", path: "/candidates", label: "候选管理", group: "内容工作室" },
  { id: "production", path: "/production", label: "生产任务", group: "内容工作室" },
  { id: "review", path: "/review", label: "审片工作台", group: "内容工作室" },
  { id: "publish", path: "/publish", label: "发布记录", group: "内容工作室" },
  { id: "library", path: "/library", label: "成片库", group: "内容工作室" },
  // 第二条业务线自成一组：它的表、状态机与页面都不与新闻线共用，菜单也不该混进「内容工作室」
  { id: "proto", path: "/proto", label: "原型工坊", group: "原型工坊" },
  { id: "members", path: "/members", label: "成员管理", group: "工作空间", action: "user.manage" },
  { id: "roles", path: "/roles", label: "角色与权限", group: "工作空间" },
  { id: "feeds", path: "/feeds", label: "数据源配置", group: "工作空间", action: "settings.write" },
  {
    id: "settings",
    path: "/settings",
    label: "工作空间设置",
    group: "工作空间",
    action: "settings.write",
  },
  { id: "usage", path: "/usage", label: "用量统计", group: "安全与治理", action: "usage.read" },
  { id: "audit", path: "/audit", label: "操作审计", group: "安全与治理", action: "settings.write" },
  { id: "profile", path: "/profile", label: "个人与安全", group: "安全与治理" },
];
const PLATFORM_MENUS: ConsoleMenu[] = [
  { id: "platform-overview", path: "/platform", label: "运营概览", group: "平台管理" },
  { id: "tenants", path: "/platform/tenants", label: "租户管理", group: "平台管理" },
  { id: "platform-usage", path: "/platform/usage", label: "全局用量", group: "平台管理" },
  { id: "platform-audit", path: "/platform/audit", label: "平台审计", group: "平台管理" },
  { id: "health", path: "/platform/health", label: "服务健康", group: "平台管理" },
];

export function consoleAccess(role: Role | "platform"): ConsoleAccess {
  if (role === "platform") {
    return {
      roles: [role],
      permissions: [
        "platform.tenants.manage",
        "platform.usage.read",
        "platform.audit.read",
        "platform.health.read",
      ],
      menus: PLATFORM_MENUS.map((menu) => ({ ...menu })),
    };
  }
  return {
    roles: [role],
    permissions: ACTIONS.filter((action) => can(role, action)),
    menus: TENANT_MENUS.filter((menu) => !menu.action || can(role, menu.action)).map(
      ({ id, path, label, group }) => ({ id, path, label, group }),
    ),
  };
}
