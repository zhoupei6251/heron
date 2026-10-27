import type { AuthUser, ConsoleSession, ReviewItem } from "@heron/contracts";
import { consoleAccess } from "@heron/domain";
import { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createApp, nextTick, type Component } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { setUnauthorizedHandler } from "../api/http";
import AppShell from "../components/AppShell.vue";
import { useAuthStore } from "../stores/auth";
import LoginView from "./LoginView.vue";
import Production from "./console/Production.vue";
import ProfileView from "./console/ProfileView.vue";
import Review from "./console/Review.vue";
import Settings from "./console/Settings.vue";

const user: AuthUser = {
  id: "11111111-1111-4111-8111-111111111111",
  tenantId: "00000000-0000-4000-8000-000000000001",
  tenantName: "Original workspace",
  tenantSlug: "acme",
  email: "owner@example.com",
  displayName: "Original owner",
  role: "owner",
};
const tenantSession = (identity = user): ConsoleSession => ({
  kind: "tenant",
  user: { ...identity },
  ...consoleAccess(identity.role),
});
const platformSession = (): ConsoleSession => ({
  kind: "platform",
  user: { id: user.id, username: "platform-admin", displayName: "Platform operator" },
  ...consoleAccess("platform"),
});
const credentials = { tenant: "acme", identifier: user.email, password: "password-long-enough" };
const gates = { open: true, blockers: [], locations: [] };
// Element Plus types intersect the prompt object with Action; confirm resolves an Action.
const confirmed = "confirm" as Awaited<ReturnType<typeof ElMessageBox.confirm>>;
const item = (taskId: string, fingerprint = `${taskId}-fingerprint`): ReviewItem => ({
  taskId,
  title: taskId,
  renderVersion: 1,
  contactSheetFingerprint: fingerprint,
  contactSheetPath: "/private/sheet.png",
  mp4Path: "/private/video.mp4",
});
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const cleanups: (() => void)[] = [];
async function mount(component: unknown, path: string, platform = false) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.session = platform ? platformSession() : tenantSession();
  const blank = { template: "<div />" };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", component: blank },
      { path: "/platform/login", component: blank, meta: { platform: true } },
      { path: "/:pathMatch(.*)*", component: blank },
    ],
  });
  await router.push(path);
  await router.isReady();
  const root = document.createElement("div");
  document.body.append(root);
  // The lint TS program cannot resolve SFC exports; Vue compiles these real components.
  const app = createApp(component as Component)
    .use(pinia)
    .use(router);
  app.mount(root);
  let active = true;
  const unmount = () => {
    if (active) app.unmount();
    active = false;
    root.remove();
  };
  cleanups.push(unmount);
  return { app, root, router, unmount, auth };
}
function input(root: HTMLElement, selector: string, value: string) {
  const element = root.querySelector<HTMLInputElement>(selector)!;
  element.value = value;
  element.dispatchEvent(new Event("input", { bubbles: true }));
}
function submit(root: HTMLElement, index = 0) {
  root
    .querySelectorAll("form")
    [index]!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
}
function button(root: HTMLElement, text: string) {
  const match = [...root.querySelectorAll("button")].find((element) =>
    element.textContent?.includes(text),
  );
  expect(match, `button ${text}`).toBeDefined();
  return match!;
}
async function flush() {
  // Drain response.json(), Vue updates and Element Plus's follow-up microtasks.
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await nextTick();
}
beforeEach(() => {
  setUnauthorizedHandler(null);
  vi.spyOn(ElMessage, "success").mockReturnValue({ close: vi.fn() });
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue(confirmed);
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe = vi.fn();
      unobserve = vi.fn();
      disconnect = vi.fn();
    },
  );
});
afterEach(() => {
  cleanups
    .splice(0)
    .reverse()
    .forEach((cleanup) => cleanup());
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe.each(["tenant", "platform"])("%s logout navigation ownership", (scope) => {
  beforeEach(() => {
    vi.stubGlobal(
      "EventSource",
      class extends EventTarget {
        close = vi.fn();
      },
    );
  });
  it.each(["session", "route", "none"])("handles a newer %s", async (change) => {
    const pending = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn((path: string) => {
        if (path.endsWith("/logout")) return pending.promise;
        if (path.endsWith("/login"))
          return Promise.resolve(json(scope === "tenant" ? tenantSession() : platformSession()));
        return Promise.resolve(json({ tasks: [] }));
      }),
    );
    const view = await mount(
      AppShell,
      scope === "tenant" ? "/overview" : "/platform",
      scope === "platform",
    );
    const auth = view.auth;
    view.root.querySelector<HTMLButtonElement>("button[aria-label='退出登录']")!.click();
    if (change === "session") await auth.login(credentials);
    else if (change === "route") await view.router.push("/unrelated");
    const replace = vi.spyOn(view.router, "replace");
    pending.resolve(json({ ok: true }));
    await flush();
    if (change === "none") {
      expect(view.router.currentRoute.value.path).toBe("/login");
      expect(auth.user).toBeNull();
    } else expect(replace).not.toHaveBeenCalled();
  });
});

describe("login completion ownership", () => {
  it.each([200, 401])(
    "does not redirect or erase a changed workspace's form (%s)",
    async (status) => {
      const pending = deferred<Response>();
      vi.stubGlobal("fetch", vi.fn().mockReturnValue(pending.promise));
      const view = await mount(LoginView, "/login?redirect=/review");
      view.auth.clear();
      input(view.root, "#login-identifier", user.email);
      input(view.root, "#login-password", credentials.password);
      submit(view.root);
      input(view.root, "#login-tenant", "other-workspace");
      await nextTick();
      expect(view.root.querySelector<HTMLInputElement>("#login-password")!.value).toBe("");
      input(view.root, "#login-identifier", "platform");
      input(view.root, "#login-password", "other-form-password");
      const replace = vi.spyOn(view.router, "replace");
      pending.resolve(json(tenantSession(), status));
      await flush();
      expect(replace).not.toHaveBeenCalled();
      expect(view.root.querySelector<HTMLInputElement>("#login-password")!.value).toBe(
        "other-form-password",
      );
      expect(view.auth.session).toBeNull();
    },
  );

  it("uses the destination captured at submission", async () => {
    const pending = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(pending.promise));
    const view = await mount(LoginView, "/login?redirect=/review");
    view.auth.clear();
    input(view.root, "#login-identifier", user.email);
    input(view.root, "#login-password", credentials.password);
    submit(view.root);
    await view.router.push("/login?redirect=/production");
    pending.resolve(json(tenantSession()));
    await vi.waitFor(() => expect(view.router.currentRoute.value.path).toBe("/review"));
  });

  it("does not navigate after unmount", async () => {
    const pending = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(pending.promise));
    const view = await mount(LoginView, "/login");
    view.auth.clear();
    input(view.root, "#login-identifier", user.email);
    input(view.root, "#login-password", credentials.password);
    submit(view.root);
    const replace = vi.spyOn(view.router, "replace");
    view.unmount();
    pending.resolve(json(tenantSession()));
    await flush();
    expect(replace).not.toHaveBeenCalled();
  });
});

describe("account mutation ownership", () => {
  it.each(["session", "unmount"])(
    "profile cannot write global state after %s changes",
    async (change) => {
      const pending = deferred<Response>();
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockReturnValueOnce(pending.promise)
          .mockResolvedValueOnce(json(tenantSession({ ...user, displayName: "New session" }))),
      );
      const view = await mount(ProfileView, "/profile");
      input(view.root, "#profile-name", "Old request");
      submit(view.root);
      if (change === "unmount") view.unmount();
      else await view.auth.login(credentials);
      const expected = view.auth.user;
      pending.resolve(json({ ...user, displayName: "Old request" }));
      await flush();
      expect(view.auth.user).toEqual(expected);
      expect(ElMessage.success).not.toHaveBeenCalled();
    },
  );

  it.each(["session", "unmount"])(
    "password completion cannot logout or navigate after %s changes",
    async (change) => {
      const pending = deferred<Response>();
      const fetch = vi
        .fn()
        .mockReturnValueOnce(pending.promise)
        .mockResolvedValueOnce(json(tenantSession()));
      vi.stubGlobal("fetch", fetch);
      const view = await mount(ProfileView, "/profile");
      input(view.root, "#current-password", credentials.password);
      input(view.root, "#new-password", "new-password-long-enough");
      input(view.root, "#confirm-password", "new-password-long-enough");
      submit(view.root, 1);
      await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
      if (change === "unmount") view.unmount();
      else await view.auth.login(credentials);
      const replace = vi.spyOn(view.router, "replace");
      pending.resolve(json({ ok: true }));
      await flush();
      expect(view.auth.user?.id).toBe(user.id);
      expect(replace).not.toHaveBeenCalled();
      expect(ElMessage.success).not.toHaveBeenCalled();
    },
  );

  it("does not send a password change after its confirmation dialog loses session ownership", async () => {
    const confirmation = deferred<typeof confirmed>();
    vi.mocked(ElMessageBox.confirm).mockReturnValue(confirmation.promise);
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    const view = await mount(ProfileView, "/profile");
    input(view.root, "#current-password", credentials.password);
    input(view.root, "#new-password", "new-password-long-enough");
    input(view.root, "#confirm-password", "new-password-long-enough");
    submit(view.root, 1);
    view.auth.clear();
    confirmation.resolve(confirmed);
    await flush();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("workspace rename cannot update a newer tenant's global name", async () => {
    const pending = deferred<Response>();
    const fetch = vi.fn((path: string, init?: RequestInit) => {
      if (path === "/api/manage/workspace" && init?.method === "PATCH") return pending.promise;
      if (path === "/api/manage/workspace")
        return Promise.resolve(json({ name: "Old tenant", slug: "acme" }));
      if (path === "/api/auth/login")
        return Promise.resolve(json(tenantSession({ ...user, tenantName: "New tenant" })));
      return Promise.resolve(json({ ...gates, retentionDays: 30 }));
    });
    vi.stubGlobal("fetch", fetch);
    const view = await mount(Settings, "/settings");
    await flush();
    input(view.root, "#workspace-name", "Old rename");
    submit(view.root);
    await view.auth.login(credentials);
    pending.resolve(json({ name: "Old rename", slug: "acme" }));
    await flush();
    expect(view.auth.user?.tenantName).toBe("New tenant");
    expect(ElMessage.success).not.toHaveBeenCalled();
  });
});

function reviewFetch(reviews: ReviewItem[]) {
  return vi.fn((path: string) =>
    Promise.resolve(json(path === "/api/reviews/pending" ? { reviews } : gates)),
  );
}
function sheet(root: HTMLElement) {
  return root.querySelector<HTMLImageElement>(".sheet-panel img")!;
}
function checkbox(root: HTMLElement) {
  return root.querySelector<HTMLInputElement>(".review-confirm input[type=checkbox]")!;
}

describe("review artifact and queue ownership", () => {
  it("obsolete load/error events never affect the selected artifact", async () => {
    vi.stubGlobal("fetch", reviewFetch([item("first"), item("second")]));
    const view = await mount(Review, "/review");
    await flush();
    const old = sheet(view.root);
    button(view.root, "second").click();
    await nextTick();
    old.dispatchEvent(new Event("load"));
    await nextTick();
    expect(checkbox(view.root).disabled).toBe(true);
    sheet(view.root).dispatchEvent(new Event("load"));
    await nextTick();
    expect(checkbox(view.root).disabled).toBe(false);
    old.dispatchEvent(new Event("error"));
    await nextTick();
    expect(checkbox(view.root).disabled).toBe(false);
    expect(view.root.textContent).not.toContain("联络表无法加载");
  });

  it("reloads the same version and binds readiness to its fingerprint and element", async () => {
    const reviews = [item("first")];
    vi.stubGlobal("fetch", reviewFetch(reviews));
    const view = await mount(Review, "/review");
    await flush();
    const old = sheet(view.root);
    old.dispatchEvent(new Event("load"));
    await nextTick();
    checkbox(view.root).click();
    button(view.root, "刷新队列").click();
    await flush();
    expect(sheet(view.root)).not.toBe(old);
    old.dispatchEvent(new Event("load"));
    await nextTick();
    expect(checkbox(view.root).disabled).toBe(true);
    sheet(view.root).dispatchEvent(new Event("load"));
    await nextTick();
    expect(checkbox(view.root).disabled).toBe(false);
    expect(checkbox(view.root).checked).toBe(false);
    const previousFingerprint = sheet(view.root);
    reviews[0] = item("first", "replacement-fingerprint");
    button(view.root, "刷新队列").click();
    await flush();
    previousFingerprint.dispatchEvent(new Event("load"));
    await nextTick();
    expect(checkbox(view.root).disabled).toBe(true);
    expect(view.root.textContent).toContain("replacement-fingerprint");
  });

  it("honors initial and changed task queries, with a nonblocking missing-task notice", async () => {
    vi.stubGlobal("fetch", reviewFetch([item("first"), item("second")]));
    const view = await mount(Review, "/review?task=second");
    await flush();
    expect(view.root.querySelector(".review-detail h2")?.textContent).toBe("second");
    await view.router.push("/review?task=first");
    expect(view.root.querySelector(".review-detail h2")?.textContent).toBe("first");
    await view.router.push("/review?task=absent-task");
    expect(view.root.querySelector(".review-detail")).toBeNull();
    expect(view.root.textContent).toContain("absent-task");
    button(view.root, "second").click();
    await nextTick();
    sheet(view.root).dispatchEvent(new Event("load"));
    await nextTick();
    checkbox(view.root).click();
    await nextTick();
    expect(button(view.root, "确认当前版本").disabled).toBe(false);
  });

  it("discards an earlier queue response after a newer refresh", async () => {
    const old = deferred<Response>();
    const fetch = reviewFetch([item("newest")]);
    fetch.mockImplementationOnce(() => old.promise);
    vi.stubGlobal("fetch", fetch);
    const view = await mount(Review, "/review");
    // Invoke a second refresh while the first is pending (the UI disables its button).
    const instance = view.app._instance as unknown as {
      setupState: { refresh: () => Promise<void> };
    };
    await instance.setupState.refresh();
    await flush();
    old.resolve(json({ reviews: [item("obsolete")] }));
    await flush();
    expect(view.root.querySelector(".review-detail h2")?.textContent).toBe("newest");
    expect(view.root.textContent).not.toContain("obsolete");
  });
});

describe("production refresh", () => {
  it("loads the paged list only once after claim refreshes the task store", async () => {
    const task = {
      id: "task",
      stage: "candidate",
      title: "Claim me",
      capability: "p0-fake",
      attempts: 0,
      updatedAt: new Date().toISOString(),
    };
    const fetch = vi.fn((path: string) => {
      if (path.startsWith("/api/manage/tasks"))
        return Promise.resolve(json({ items: [task], total: 1, page: 1, pageSize: 20 }));
      if (path === "/api/tasks")
        return Promise.resolve(json({ tasks: [{ ...task, stage: "claimed" }] }));
      return Promise.resolve(json({ ...task, stage: "claimed" }));
    });
    vi.stubGlobal("fetch", fetch);
    const view = await mount(Production, "/production");
    await flush();
    expect(fetch.mock.calls.filter(([path]) => path.startsWith("/api/manage/tasks"))).toHaveLength(
      1,
    );
    button(view.root, "认领").click();
    await flush();
    expect(fetch.mock.calls.filter(([path]) => path.startsWith("/api/manage/tasks"))).toHaveLength(
      2,
    );
  });
});
