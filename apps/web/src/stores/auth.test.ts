import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { AuthUser } from "@heron/contracts";
import { can, requiredRoleFor } from "@heron/domain";

import { request } from "../api/http";
import { installAuthGuards } from "../router/guards";
import { useAuthStore } from "./auth";

/**
 * 会话这一层的红线，和 api 的 e2e 分工不同：api 证明「没票进不来」，
 * 这里证明「票失效之后界面真的退出去」——401 若只落成一条错误文案，
 * 用户会停在一个永远读不到数据的页面上，看着像系统坏了。
 */

const USER: AuthUser = {
  id: "11111111-1111-4111-8111-111111111111",
  tenantId: "00000000-0000-4000-8000-000000000001",
  tenantName: "A 台",
  tenantSlug: "acme",
  email: "viewer@example.com",
  displayName: "只读的人",
  role: "viewer",
};

const Blank = { template: "<div />" };

/** 用内存 history 与空组件：这里验的是守卫与 401 接线，不是页面长什么样。 */
function makeRouter(): Router {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", redirect: "/candidates" },
      { path: "/candidates", component: Blank },
      { path: "/review", component: Blank },
      { path: "/login", component: Blank, meta: { public: true } },
    ],
  });
  installAuthGuards(router);
  return router;
}

function answer401(): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => Promise.resolve(new Response("{}", { status: 401 }))),
  );
}

beforeEach(() => {
  setActivePinia(createPinia());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("会话边界", () => {
  it("401 之后 store 清空并把用户送去登录页，且记住原来要去哪", async () => {
    const router = makeRouter();
    answer401();
    const auth = useAuthStore();
    auth.user = USER;
    await router.push("/review");
    expect(router.currentRoute.value.path).toBe("/review");

    await expect(request("/tasks")).rejects.toMatchObject({ status: 401 });
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
    expect(auth.user).toBeNull();
    expect(router.currentRoute.value.query.redirect).toBe("/review");
  });

  it("没有会话就进不了业务页：守卫先问一次 /auth/me，再送去登录页", async () => {
    const router = makeRouter();
    answer401();
    await router.push("/review");
    expect(router.currentRoute.value.path).toBe("/login");
    expect(useAuthStore().user).toBeNull();
  });
});

describe("置灰判据", () => {
  it("reviewer 及以上才允许点确认：web 直接复用 domain 的 can()", () => {
    expect(can("viewer", "review.confirm")).toBe(false);
    expect(can("operator", "review.confirm")).toBe(false);
    expect(can("reviewer", "review.confirm")).toBe(true);
    expect(can("admin", "review.confirm")).toBe(true);
    expect(can("owner", "review.confirm")).toBe(true);
    expect(requiredRoleFor("review.confirm")).toBe("reviewer");
  });
});
