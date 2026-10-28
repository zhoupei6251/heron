import { createApp, defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { usePagedList } from "./usePagedList";

type State = ReturnType<typeof usePagedList<{ id: string }>>;
const mounted: (() => void)[] = [];

function page(items: { id: string }[] = [], total = items.length) {
  return new Response(JSON.stringify({ items, total, page: 1, pageSize: 20 }));
}

async function list(query: Record<string, string> = {}, allowed = () => true) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/", component: { template: "<div />" } }],
  });
  await router.push({ path: "/", query });
  let state!: State;
  const app = createApp(
    defineComponent({
      setup() {
        state = usePagedList<{ id: string }>("/manage/members", allowed);
        return () => h("div");
      },
    }),
  ).use(router);
  const root = document.createElement("div");
  document.body.append(root);
  app.mount(root);
  mounted.push(() => {
    app.unmount();
    root.remove();
  });
  await settle();
  return { state, router };
}

/** 地址栏写入 → 路由刷新 → watch 拉取，都是异步的；等它们排完。 */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

afterEach(() => {
  mounted.splice(0).forEach((cleanup) => cleanup());
  vi.unstubAllGlobals();
});

describe("paged console requests", () => {
  it("encodes search and does not load a forbidden module", async () => {
    const fetch = vi.fn().mockResolvedValue(page());
    vi.stubGlobal("fetch", fetch);
    const denied = await list({}, () => false);
    await denied.state.load();
    expect(fetch).not.toHaveBeenCalled();
    const { state } = await list();
    state.query.value = "新闻 & audit";
    await state.search();
    await settle();
    expect(fetch.mock.calls[0]![0]).toBe(
      "/api/manage/members?page=1&pageSize=20&q=%E6%96%B0%E9%97%BB+%26+audit",
    );
  });
  it("keeps newer results when an earlier request resolves late", async () => {
    let finish!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise<Response>((resolve) => {
              finish = resolve;
            }),
        )
        .mockResolvedValueOnce(page([{ id: "new" }])),
    );
    const { state } = await list();
    const earlier = state.load();
    await state.load();
    finish(page([{ id: "old" }]));
    await earlier;
    expect(state.items.value).toEqual([{ id: "new" }]);
  });
  it("clears stale rows and exposes server errors", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(page([{ id: "old" }]))
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ message: "需要所有者权限" }), { status: 403 }),
        ),
    );
    const { state } = await list();
    await state.load();
    await state.load();
    expect(state.items.value).toEqual([]);
    expect(state.error.value).toBe("需要所有者权限");
  });
});

describe("list state in the address bar", () => {
  it("resumes the page, size and search from the URL", async () => {
    const fetch = vi.fn().mockResolvedValue(page());
    vi.stubGlobal("fetch", fetch);
    const { state } = await list({ page: "2", pageSize: "10", q: "新闻" });
    expect([state.page.value, state.pageSize.value, state.query.value]).toEqual([2, 10, "新闻"]);
    await state.load();
    expect(fetch.mock.calls[0]![0]).toBe(
      "/api/manage/members?page=2&pageSize=10&q=%E6%96%B0%E9%97%BB",
    );
  });
  it("writes a search into the URL, drops defaults, and returns to the first page", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(page()));
    const { state, router } = await list({ page: "3" });
    state.query.value = "  热榜  ";
    await state.search();
    await settle();
    expect(router.currentRoute.value.query).toEqual({ q: "热榜" });
    expect(state.page.value).toBe(1);
  });
  it("follows the URL when the user goes back", async () => {
    const fetch = vi.fn().mockResolvedValue(page());
    vi.stubGlobal("fetch", fetch);
    const { state, router } = await list();
    state.page.value = 4;
    await settle();
    expect(router.currentRoute.value.query).toEqual({ page: "4" });
    await router.push({ path: "/", query: {} });
    await settle();
    expect(state.page.value).toBe(1);
    expect(fetch.mock.calls.at(-1)![0]).toBe("/api/manage/members?page=1&pageSize=20&q=");
  });
});
