import { CONSOLE_MENU_IDS, type ConsoleMenu, type ConsoleSession } from "@heron/contracts";
import type { RouteRecordRaw } from "vue-router";

/**
 * 后端菜单只给 id、路径、文案；组件表在前端，是一张白名单。
 *
 * 两道判据缺一不可：id 必须在这张表里，路径必须与这里的规范路径逐字相同。
 * 否则后端（或任何伪造的会话响应）说「members 在 /evil」，前端就会把成员页
 * 挂到一个守卫之外的地方——菜单可下发，路由落点不可下发。
 */
export const MENU_ROUTES: Record<ConsoleMenu["id"], RouteRecordRaw> = {
  overview: { path: "/overview", component: () => import("../pages/console/OverviewView.vue") },
  candidates: {
    path: "/candidates",
    component: () => import("../pages/console/CandidateFeed.vue"),
  },
  production: { path: "/production", component: () => import("../pages/console/Production.vue") },
  review: { path: "/review", component: () => import("../pages/console/Review.vue") },
  publish: { path: "/publish", component: () => import("../pages/console/Publish.vue") },
  library: { path: "/library", component: () => import("../pages/console/LibraryView.vue") },
  proto: { path: "/proto", component: () => import("../pages/console/ProtoWorkshop.vue") },
  members: { path: "/members", component: () => import("../pages/console/MembersView.vue") },
  roles: { path: "/roles", component: () => import("../pages/console/RolesView.vue") },
  feeds: { path: "/feeds", component: () => import("../pages/console/FeedsView.vue") },
  settings: { path: "/settings", component: () => import("../pages/console/Settings.vue") },
  usage: { path: "/usage", component: () => import("../pages/console/UsageView.vue") },
  audit: { path: "/audit", component: () => import("../pages/console/AuditView.vue") },
  sessions: { path: "/sessions", component: () => import("../pages/console/SessionsView.vue") },
  profile: { path: "/profile", component: () => import("../pages/console/ProfileView.vue") },
  "platform-overview": {
    path: "/platform",
    component: () => import("../pages/platform/PlatformOverviewView.vue"),
  },
  tenants: {
    path: "/platform/tenants",
    component: () => import("../pages/platform/PlatformTenantsView.vue"),
  },
  "platform-settings": {
    path: "/platform/settings",
    component: () => import("../pages/platform/PlatformSettingsView.vue"),
  },
  "platform-usage": {
    path: "/platform/usage",
    component: () => import("../pages/console/UsageView.vue"),
  },
  "platform-audit": {
    path: "/platform/audit",
    component: () => import("../pages/console/AuditView.vue"),
  },
  health: {
    path: "/platform/health",
    component: () => import("../pages/platform/PlatformHealthView.vue"),
  },
};

const PLATFORM_IDS: ReadonlySet<ConsoleMenu["id"]> = new Set([
  "platform-overview",
  "tenants",
  "platform-settings",
  "platform-usage",
  "platform-audit",
  "health",
]);

export function isMenuId(value: unknown): value is ConsoleMenu["id"] {
  return typeof value === "string" && Object.hasOwn(MENU_ROUTES, value);
}

export function isMenuPath(path: string): boolean {
  return CONSOLE_MENU_IDS.some((id) => MENU_ROUTES[id].path === path);
}

/**
 * 可注册的菜单 = 后端下发的菜单 ∩ 本地白名单 ∩ 本会话身份那一侧。
 *
 * 平台身份拿不到租户菜单，租户 owner 也拿不到平台菜单；即便服务端把两边的菜单
 * 混在一张响应里（或者有人伪造了响应），这里也只留下属于当前 kind 的那一半。
 * 重复 id 与重复路径都丢掉：同名路由注册两次，后一次会静默覆盖前一次。
 */
export function authorizedMenus(session: ConsoleSession | null): ConsoleMenu[] {
  if (!session) return [];
  const platform = session.kind === "platform";
  const ids = new Set<string>();
  const paths = new Set<string>();
  return session.menus.filter((menu) => {
    if (!isMenuId(menu.id) || PLATFORM_IDS.has(menu.id) !== platform) return false;
    if (MENU_ROUTES[menu.id].path !== menu.path) return false;
    if (ids.has(menu.id) || paths.has(menu.path)) return false;
    ids.add(menu.id);
    paths.add(menu.path);
    return true;
  });
}
