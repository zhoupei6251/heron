import type { Router } from "vue-router";

import { type SessionScope, setUnauthorizedHandler } from "../api/http";
import { useAuthStore } from "../stores/auth";
import { usePlatformAuthStore } from "../stores/platform-auth";

export function safeRedirect(value: unknown, scope: SessionScope): string {
  const fallback = scope === "platform" ? "/platform" : "/";
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//")) {
    return fallback;
  }
  if (value.includes("\\") || [...value].some((char) => char.charCodeAt(0) <= 32)) return fallback;
  const path = value.split(/[?#]/)[0] ?? "";
  const platformPath = path === "/platform" || path.startsWith("/platform/");
  if (platformPath !== (scope === "platform") || path.endsWith("/login")) return fallback;
  return value;
}

export function installAuthGuards(router: Router): void {
  router.beforeEach(async (to) => {
    const platform = to.meta.platform === true;
    const auth = platform ? usePlatformAuthStore() : useAuthStore();
    const login = platform ? "/platform/login" : "/login";
    if (to.meta.public) {
      return to.path === login && auth.user ? { path: platform ? "/platform" : "/" } : true;
    }
    const user = auth.user ?? (await auth.fetchMe());
    if (user) return true;
    return { path: login, query: { redirect: to.fullPath } };
  });

  setUnauthorizedHandler((scope) => {
    const platform = scope === "platform";
    const auth = platform ? usePlatformAuthStore() : useAuthStore();
    auth.clear();
    const current = router.currentRoute.value;
    if ((current.meta.platform === true) !== platform || current.meta.public) return;
    void router.replace({
      path: platform ? "/platform/login" : "/login",
      query: { redirect: current.fullPath },
    });
  });
}
