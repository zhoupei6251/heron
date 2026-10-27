import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { AuthUser, ConsoleSession, Role } from "@heron/contracts";
import { can, consoleAccess, requiredRoleFor } from "@heron/domain";

import { request, setUnauthorizedHandler } from "../api/http";
import { installAuthGuards, safeRedirect } from "../router/guards";
import { useAuthStore } from "./auth";
import { useTasksStore } from "./tasks";

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

const tenantSession = (role: Role = "viewer"): ConsoleSession => ({
  kind: "tenant",
  user: { ...USER, role },
  ...consoleAccess(role),
});
const platformSession = (): ConsoleSession => ({
  kind: "platform",
  user: { id: USER.id, username: "platform-admin", displayName: USER.displayName },
  ...consoleAccess("platform"),
});
const Blank = { template: "<div />" };

/** 用内存 history 与空组件：这里验的是守卫与 401 接线，不是页面长什么样。 */
function makeRouter(): Router {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", component: Blank, meta: { public: true } },
      { path: "/platform/login", redirect: "/login" },
      { path: "/not-found", component: Blank, meta: { public: true } },
      { path: "/:pathMatch(.*)*", component: Blank, meta: { public: true } },
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
  it("平台接口失效通知唯一会话", async () => {
    answer401();
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    await expect(request("/platform/tenants")).rejects.toMatchObject({ status: 401 });
    expect(handler).toHaveBeenCalledWith();
  });

  it("登录失败不触发全局跳转，以免覆盖表单错误", async () => {
    answer401();
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    await expect(request("/auth/login", { method: "POST" })).rejects.toMatchObject({ status: 401 });
    expect(handler).not.toHaveBeenCalled();
  });

  it("401 之后 store 清空并把用户送去登录页，且记住原来要去哪", async () => {
    const router = makeRouter();
    answer401();
    const auth = useAuthStore();
    auth.session = tenantSession();
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

  it("根路径在未登录时去登录页，不露出 404", async () => {
    const router = makeRouter();
    answer401();
    await router.push("/");
    expect(router.currentRoute.value.path).toBe("/login");
  });

  it("已登录时直接打开 /login 会回到本人首页", async () => {
    const router = makeRouter();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(tenantSession())));
    await router.push("/login");
    expect(router.currentRoute.value.path).toBe("/overview");
  });
});

describe("后端菜单驱动统一路由", () => {
  it.each(["owner", "admin", "reviewer", "operator", "viewer"] as const)(
    "只注册 %s 收到的菜单",
    async (role) => {
      const router = makeRouter();
      const auth = useAuthStore();
      auth.session = tenantSession(role);
      await router.push("/");
      expect(router.currentRoute.value.path).toBe("/overview");
      expect(router.hasRoute("members")).toBe(role === "owner");
      for (const id of ["feeds", "settings", "audit"]) {
        expect(router.hasRoute(id)).toBe(role === "owner" || role === "admin");
      }
      for (const menu of auth.session.menus) {
        await router.push(menu.path);
        expect(router.currentRoute.value.path).toBe(menu.path);
      }
      if (role !== "owner") {
        await router.push("/members");
        expect(router.currentRoute.value.path).toBe("/not-found");
      }
    },
  );

  it("不按本地角色补菜单，拒绝伪路径与跨身份菜单，变更会话后移除旧路由", async () => {
    const router = makeRouter();
    const auth = useAuthStore();
    auth.session = {
      ...tenantSession("owner"),
      menus: [
        { id: "review", path: "/review", label: "后端审片", group: "授权" },
        { id: "members", path: "/evil", label: "错误映射", group: "授权" },
        ...platformSession().menus,
      ],
    };
    await router.push("/");
    expect(router.currentRoute.value.path).toBe("/review");
    expect(auth.menus.map((menu) => menu.path)).toEqual(["/review"]);
    expect(router.hasRoute("members")).toBe(false);
    expect(router.hasRoute("tenants")).toBe(false);
    auth.session = platformSession();
    expect(router.hasRoute("review")).toBe(false);
    await router.push("/platform/tenants");
    expect(router.currentRoute.value.path).toBe("/platform/tenants");
    expect(auth.user).toBeNull();
    auth.clear();
    expect(router.hasRoute("tenants")).toBe(false);
  });
  it("退出后迟到的任务请求不会把上个租户的数据放回缓存", async () => {
    let resolveResponse!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      () =>
        new Promise<Response>((resolve) => {
          resolveResponse = resolve;
        }),
    );
    useAuthStore().session = tenantSession();
    const tasks = useTasksStore();
    const pending = tasks.refresh();
    tasks.clear();
    resolveResponse(new Response(JSON.stringify({ tasks: [{ id: "old-tenant-task" }] })));
    await pending;
    expect(tasks.tasks).toEqual([]);
  });

  it("租户 owner 不能凭租户身份进入平台后台", async () => {
    answer401();
    const router = makeRouter();
    useAuthStore().session = tenantSession("owner");
    await router.push("/platform/tenants");
    expect(router.currentRoute.value.path).toBe("/not-found");
    expect(useAuthStore().user?.id).toBe(USER.id);
  });

  it("平台会话过期清除唯一身份并回到统一登录", async () => {
    answer401();
    const router = makeRouter();
    useAuthStore().session = platformSession();
    await router.push("/platform/tenants");
    await expect(request("/platform/tenants")).rejects.toMatchObject({ status: 401 });
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
    expect(useAuthStore().session).toBeNull();
  });

  it("登录跳转只接受本身份已授权的注册路径", () => {
    for (const invalid of [
      "//outside.invalid",
      "/\\\\outside.invalid",
      "/platform/tenants",
      "/members",
      "/unknown",
      "/login",
      "/review/../members",
      "/%72eview",
      "/review\n",
    ]) {
      expect(safeRedirect(invalid, tenantSession())).toBe("/overview");
    }
    expect(safeRedirect("/review?task=123#sheet", tenantSession())).toBe("/review?task=123#sheet");
    expect(safeRedirect("/review", platformSession())).toBe("/platform");
    expect(safeRedirect("/platform/tenants", platformSession())).toBe("/platform/tenants");
    expect(safeRedirect("/review", { ...tenantSession(), menus: [] })).toBe("/not-found");
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const credentials = { tenant: "acme", identifier: USER.email, password: "password-long-enough" };
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });

describe.each(["tenant", "platform"] as const)("%s session interleavings", (scope) => {
  const store = () => useAuthStore();
  const session = () => (scope === "tenant" ? tenantSession() : platformSession());
  const path = scope === "tenant" ? "/tasks" : "/platform/tenants";

  it("ignores an old request's 401 after a new login, but still rejects it", async () => {
    const old = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(response(session())),
    );
    const handler = vi.fn(() => store().clear());
    setUnauthorizedHandler(handler);
    const pending = request(path).catch((cause: unknown) => cause);
    await store().login(credentials);
    old.resolve(response({}, 401));
    expect(await pending).toMatchObject({ status: 401 });
    expect(handler).not.toHaveBeenCalled();
    expect(store().session?.user?.id).toBe(USER.id);
  });

  it("does not let a request sent during login invalidate the completed session", async () => {
    const login = deferred<Response>();
    const old = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockReturnValueOnce(login.promise).mockReturnValueOnce(old.promise),
    );
    const handler = vi.fn(() => store().clear());
    setUnauthorizedHandler(handler);
    const signingIn = store().login(credentials);
    const pending = request(path).catch((cause: unknown) => cause);
    login.resolve(response(session()));
    await signingIn;
    old.resolve(response({}, 401));
    await pending;
    expect(handler).not.toHaveBeenCalled();
    expect(store().session?.user?.id).toBe(USER.id);
  });

  it.each([200, 401, 500])("ignores stale me completion (%s) after login", async (status) => {
    const old = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(response(session())),
    );
    const pending = store().fetchMe();
    await store().login(credentials);
    old.resolve(
      response({ ...session(), user: { ...session().user, displayName: "old" } }, status),
    );
    await pending;
    expect(store().session?.user?.displayName).toBe(USER.displayName);
    expect(store().error).toBeNull();
  });

  it("clear invalidates me and starts a new independent probe", async () => {
    const old = deferred<Response>();
    const current = deferred<Response>();
    const fetch = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    vi.stubGlobal("fetch", fetch);
    const pending = store().fetchMe();
    store().clear();
    const next = store().fetchMe();
    expect(fetch).toHaveBeenCalledTimes(2);
    old.resolve(response({ ...session(), user: { ...session().user, displayName: "old" } }));
    await pending;
    expect(store().session).toBeNull();
    const shared = store().fetchMe();
    expect(fetch).toHaveBeenCalledTimes(2);
    current.resolve(response(session()));
    await Promise.all([next, shared]);
    expect(store().session?.user?.displayName).toBe(USER.displayName);
  });

  it.each([200, 401])("stale login (%s) cannot change identity, busy or error", async (status) => {
    const old = deferred<Response>();
    const current = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise),
    );
    const pending = store()
      .login(credentials)
      .catch((cause: unknown) => cause);
    store().clear();
    const next = store().login(credentials);
    old.resolve(
      response(
        { ...session(), user: { ...session().user, displayName: "old" }, message: "old failure" },
        status,
      ),
    );
    await pending;
    expect(store().session).toBeNull();
    expect(store().busy).toBe(true);
    expect(store().error).toBeNull();
    current.resolve(response(session()));
    await next;
    expect(store().session?.user?.displayName).toBe(USER.displayName);
    expect(store().busy).toBe(false);
  });

  it("late logout cannot clear a later login", async () => {
    const old = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(response(session())),
    );
    store().session = session();
    const pending = store().logout();
    await store().login(credentials);
    old.resolve(response({ ok: true }));
    await pending;
    expect(store().session?.user?.id).toBe(USER.id);
  });

  it("a cross-kind login invalidates the previous kind's 401", async () => {
    const old = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(response(session())),
    );
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    const pending = request(path).catch((cause: unknown) => cause);
    store().session = scope === "tenant" ? platformSession() : tenantSession();
    await store().login(credentials);
    old.resolve(response({}, 401));
    await pending;
    expect(handler).not.toHaveBeenCalled();
    expect(store().session?.kind).toBe(scope);
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
