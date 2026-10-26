import { effectScope } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { usePagedList } from "./usePagedList";

const scopes: ReturnType<typeof effectScope>[] = [];
function list(allowed = () => true) {
  const scope = effectScope();
  scopes.push(scope);
  return scope.run(() => usePagedList<{ id: string }>("/manage/members", allowed))!;
}
afterEach(() => {
  scopes.forEach((scope) => scope.stop());
  scopes.length = 0;
  vi.unstubAllGlobals();
});

describe("paged console requests", () => {
  it("encodes search and does not load a forbidden module", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ items: [], total: 0, page: 1, pageSize: 20 })),
      );
    vi.stubGlobal("fetch", fetch);
    await list(() => false).load();
    expect(fetch).not.toHaveBeenCalled();
    const state = list();
    state.query.value = "新闻 & audit";
    await state.search();
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
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ items: [{ id: "new" }], total: 1, page: 1, pageSize: 20 })),
        ),
    );
    const state = list();
    const earlier = state.load();
    await state.load();
    finish(
      new Response(JSON.stringify({ items: [{ id: "old" }], total: 1, page: 1, pageSize: 20 })),
    );
    await earlier;
    expect(state.items.value).toEqual([{ id: "new" }]);
  });
  it("clears stale rows and exposes server errors", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ items: [{ id: "old" }], total: 1, page: 1, pageSize: 20 })),
        )
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ message: "需要所有者权限" }), { status: 403 }),
        ),
    );
    const state = list();
    await state.load();
    await state.load();
    expect(state.items.value).toEqual([]);
    expect(state.error.value).toBe("需要所有者权限");
  });
});
