import { createPinia, disposePinia } from "pinia";
import { createApp, nextTick, type Component } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import OverviewView from "./OverviewView.vue";

const cleanups: (() => void)[] = [];
const overview = {
  candidates: 8,
  tasks: 6,
  review: 2,
  published: 1,
  failed: 1,
  members: 3,
  recentTasks: [],
};
const gates = {
  open: false,
  blockers: ["尚未启用发布"],
  locations: [
    { gate: "人工确认", satisfied: true, detail: "确认绑定成片版本" },
    { gate: "发布配置", satisfied: false, detail: "尚未连接平台" },
  ],
};
async function mount() {
  const pinia = createPinia();
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }],
  });
  await router.push("/overview");
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(OverviewView as Component)
    .use(pinia)
    .use(router);
  app.mount(root);
  cleanups.push(() => {
    app.unmount();
    disposePinia(pinia);
    root.remove();
  });
  await nextTick();
  return root;
}
beforeEach(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});
afterEach(() => {
  cleanups
    .splice(0)
    .reverse()
    .forEach((dispose) => dispose());
  vi.unstubAllGlobals();
});
describe("overview workbench", () => {
  it("keeps unchecked gates unknown rather than reporting a confirmed restriction", async () => {
    vi.stubGlobal("fetch", () => new Promise<Response>(() => {}));
    const root = await mount();
    expect(root.querySelector("[aria-label='生产门禁状态']")?.textContent).toContain("尚未读取");
    expect(root.querySelector("[aria-label='生产流程快捷入口']")).not.toBeNull();
  });
  it("shows API-backed review counts and links into the actual workflow", async () => {
    vi.stubGlobal("fetch", (path: string) =>
      Promise.resolve(new Response(JSON.stringify(path.endsWith("/gates") ? gates : overview))),
    );
    const root = await mount();
    await vi.waitFor(() => expect(root.textContent).toContain("有 2 条内容"));
    expect(root.querySelector("[aria-label='生产门禁状态']")?.textContent).toContain("1 / 2");
    const flow = root.querySelector("[aria-label='生产流程快捷入口']")!;
    expect([...flow.querySelectorAll("a")].map((link) => link.getAttribute("href"))).toEqual([
      "/candidates",
      "/production",
      "/review",
      "/publish",
    ]);
    expect(root.textContent).toContain("不等于平台真实发布");
  });
  it("preserves real zero counts instead of replacing them with placeholders", async () => {
    vi.stubGlobal("fetch", (path: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            path.endsWith("/gates")
              ? gates
              : { ...overview, review: 0, tasks: 0, published: 0, candidates: 0, failed: 0 },
          ),
        ),
      ),
    );
    const root = await mount();
    await vi.waitFor(() => expect(root.textContent).toContain("有 0 条内容"));
    expect(
      [...root.querySelectorAll(".stat-value")].map((value) => value.textContent?.trim()),
    ).toEqual(["0", "0", "0", "0"]);
  });
  it("shows a request failure and does not fabricate gate or task state", async () => {
    vi.stubGlobal("fetch", () =>
      Promise.resolve(new Response(JSON.stringify({ message: "概览暂时不可用" }), { status: 503 })),
    );
    const root = await mount();
    await vi.waitFor(() => expect(root.textContent).toContain("概览暂时不可用"));
    expect(root.querySelector("[aria-label='生产门禁状态']")?.textContent).toContain("尚未读取");
    expect(
      [...root.querySelectorAll(".stat-value")].every((value) => value.textContent?.trim() === "—"),
    ).toBe(true);
  });
});
