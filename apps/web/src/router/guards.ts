import type { Router } from "vue-router";

import { setUnauthorizedHandler } from "../api/http";
import { useAuthStore } from "../stores/auth";

/**
 * 守卫与 401 是同一个判据的两端：进页面前问一次「我是谁」，用着用着票失效了也要立刻退出去。
 * 两处都只做「清空身份 + 去登录页」，权限判定（谁能点确认）不在这里——那是 domain 的 can()。
 */
export function installAuthGuards(router: Router): void {
  router.beforeEach(async (to) => {
    const auth = useAuthStore();
    if (to.meta.public) {
      // 已登录还去登录页：送回去，别让人对着一个已经没用的表单再输一遍
      return to.path === "/login" && auth.user ? { path: "/" } : true;
    }
    const user = auth.user ?? (await auth.fetchMe());
    if (user) return true;
    return {
      path: "/login",
      query: to.fullPath === "/" ? {} : { redirect: to.fullPath },
    };
  });

  setUnauthorizedHandler(() => {
    const auth = useAuthStore();
    auth.clear();
    const current = router.currentRoute.value;
    if (current.path === "/login") return;
    void router.replace({
      path: "/login",
      query: current.fullPath === "/" ? {} : { redirect: current.fullPath },
    });
  });
}
