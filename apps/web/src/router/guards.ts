import { watch } from "vue";
import type { RouteRecordRaw, Router } from "vue-router";

import type { ConsoleMenu, ConsoleSession } from "@heron/contracts";

import { setUnauthorizedHandler } from "../api/http";
import { useAuthStore } from "../stores/auth";
import { MENU_ROUTES, authorizedMenus, isMenuPath } from "./menu-routes";

interface GuardState {
  registered: Set<ConsoleMenu["id"]>;
  /** 已经为这个 fullPath 重试过一次：注册成功也只有一次重试的机会，不再来回跳。 */
  retried: string | null;
}

const states = new WeakMap<Router, GuardState>();

function stateOf(router: Router): GuardState {
  let state = states.get(router);
  if (!state) {
    state = { registered: new Set(), retried: null };
    states.set(router, state);
  }
  return state;
}

/**
 * 把「这次会话能去的页面」注册进路由表，并撤掉上一次会话留下的。
 *
 * 路由名就是菜单 id，守卫用 `to.name` 判断命中的是动态页还是兜底页；
 * 撤路由同样按名字撤，不按路径猜——路径是后端给的，名字是本地白名单的。
 */
function syncRoutes(router: Router, menus: ConsoleMenu[]): void {
  const state = stateOf(router);
  const wanted = new Map(menus.map((menu) => [menu.id, menu]));
  for (const id of [...state.registered]) {
    if (wanted.has(id)) continue;
    router.removeRoute(id);
    state.registered.delete(id);
  }
  for (const id of wanted.keys()) {
    if (state.registered.has(id)) continue;
    const record = MENU_ROUTES[id];
    if (!record.component) throw new Error(`菜单 ${id} 没有对应的页面组件`);
    const route: RouteRecordRaw = {
      path: record.path,
      name: id,
      component: record.component,
      meta: { ...record.meta, platform: record.path.startsWith("/platform") },
    };
    router.addRoute(route);
    state.registered.add(id);
  }
}

/**
 * 登录后的落点只接受「本身份已注册的菜单路径」。
 *
 * 这不是防 CSRF 的那种信任边界——它防的是把 `?redirect=` 当成任意跳板：
 * 一个租户链接把人送去 /platform/tenants，界面会先跳到 404，再被守卫弹回登录，
 * 看起来像系统坏了。这里直接归到本人身份的第一个菜单。
 */
export function safeRedirect(value: unknown, session: ConsoleSession | null): string {
  const menus = authorizedMenus(session);
  const home = session
    ? (menus.find((menu) => !menu.hidden)?.path ?? menus[0]?.path ?? "/not-found")
    : "/login";
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//")) return home;
  if (value.includes("\\") || [...value].some((char) => char.charCodeAt(0) <= 32)) return home;
  const path = value.split(/[?#]/)[0] ?? "";
  return menus.some((menu) => menu.path === path) ? value : home;
}

export function installAuthGuards(router: Router): void {
  const state = stateOf(router);
  let watching = false;

  router.beforeEach(async (to) => {
    const auth = useAuthStore();
    if (!watching) {
      watching = true;
      // Pinia 在 router 模块加载时还没装，所以 watcher 等到第一次真正导航再建。
      // 会话一变（登录、退出、换身份）就同步路由，哪怕这次变化不是由导航触发的。
      watch(
        () => auth.menus,
        (menus) => syncRoutes(router, menus),
        {
          flush: "sync",
          immediate: true,
        },
      );
    }
    syncRoutes(router, auth.menus);

    if (to.path === "/") {
      // 根路径是给人的入口，不是页面：先问清身份，再决定去首页还是登录页。
      const session = auth.session ?? (await auth.fetchMe());
      return { path: session ? auth.homePath : "/login" };
    }

    const known = isMenuPath(to.path);
    if (to.meta.public && !known) {
      // 带着有效票据硬打开 /login（书签、旧链接），直接回本人首页，不再让人重填一次口令。
      if (to.path === "/login") {
        const session = auth.session ?? (await auth.fetchMe());
        if (session) return { path: auth.homePath };
      }
      return true;
    }

    const session = auth.session ?? (await auth.fetchMe());
    syncRoutes(router, auth.menus);
    if (!session) return { path: "/login", query: { redirect: to.fullPath } };

    const menu = auth.menus.find((item) => item.path === to.path);
    if (!menu) return known ? { path: "/not-found" } : true;
    if (to.name === menu.id) {
      state.retried = null;
      return true;
    }
    // 深链接在会话恢复之前就匹配到了兜底路由：身份到手后再按新路由表解析一次。
    if (state.retried === to.fullPath) return true;
    state.retried = to.fullPath;
    // 只给 path 会把 fullPath 里的 ?q&page 一并塞进路径而丢掉：查询条件要原样带过去
    return { path: to.path, query: to.query, hash: to.hash, replace: true };
  });

  setUnauthorizedHandler(() => {
    const auth = useAuthStore();
    auth.clear();
    const current = router.currentRoute.value;
    if (current.meta.public) return;
    void router.replace({ path: "/login", query: { redirect: current.fullPath } });
  });
}
