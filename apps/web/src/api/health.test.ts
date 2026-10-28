import { afterEach, expect, it, vi } from "vitest";
import { createApp, nextTick, type Component } from "vue";
import PlatformHealthView from "../pages/platform/PlatformHealthView.vue";
import { loadHealth } from "./health";

const cleanups: (() => void)[] = [];
const degraded = {
  ok: false,
  at: "2026-10-23T00:00:00.000Z",
  node: "v24.22.1",
  db: { ok: true, detail: "connected" },
  webDist: { ok: false, detail: "missing build" },
  redis: { configured: false, ok: true, detail: "REDIS_URL 未配置" },
  publish: { open: false, blockers: ["review required"] },
  proto: {
    engine: { ok: false, detail: "ZEE_RP_HOME 未配置", version: null },
    template: { ok: false, detail: "AXURE_BLANK_TEMPLATE_RP9 未配置" },
  },
};
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => cleanup());
  vi.unstubAllGlobals();
});
it("preserves the failed probes returned with HTTP 503", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(JSON.stringify(degraded), { status: 503 })),
  );
  expect(await loadHealth()).toEqual(degraded);
});
it.each([200, 503])("preserves proto versions on HTTP %i", async (status) => {
  const report = {
    ...degraded,
    ok: status === 200,
    proto: {
      engine: { ok: true, detail: "zee-rp 已就位", version: "p9-test" },
      template: { ok: true, detail: "RP9 模板已就位" },
    },
  };
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(report), { status }));
  vi.stubGlobal("fetch", fetch);
  expect(await loadHealth()).toEqual(report);
  expect(fetch).toHaveBeenCalledWith(
    "/api/platform/health",
    expect.objectContaining({ credentials: "same-origin" }),
  );
});
it.each([
  ["missing proto", undefined],
  ["null proto", null],
  ["missing engine", { template: degraded.proto.template }],
  ["missing template", { engine: degraded.proto.engine }],
  [
    "invalid engine readiness",
    { ...degraded.proto, engine: { ...degraded.proto.engine, ok: "no" } },
  ],
  ["missing engine detail", { ...degraded.proto, engine: { ok: false, version: null } }],
  ["missing engine version", { ...degraded.proto, engine: { ok: false, detail: "missing" } }],
  [
    "invalid engine version",
    { ...degraded.proto, engine: { ...degraded.proto.engine, version: 9 } },
  ],
  ["invalid template readiness", { ...degraded.proto, template: { ok: "no", detail: "missing" } }],
  ["missing template detail", { ...degraded.proto, template: { ok: false } }],
])("rejects a 503 report with %s", async (_name, proto) => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ ...degraded, proto }), { status: 503 })),
  );
  await expect(loadHealth()).rejects.toMatchObject({ status: 503 });
});
it.each([401, 502, 503])(
  "does not treat an HTTP %i error page as a health report",
  async (status) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response(JSON.stringify({ message: "Unavailable" }), { status })),
    );
    await expect(loadHealth()).rejects.toMatchObject({ status });
  },
);

it.each([
  [false, true, 200, null],
  [true, false, 503, "p9-test"],
  [false, false, 200, null],
  [true, true, 200, "p9-test"],
  [true, true, 503, null],
] as const)(
  "platform health view shows proto readiness and reasons (engine=%s, template=%s, HTTP=%i, version=%s)",
  async (engineOk, templateOk, status, version) => {
    const report = {
      ...degraded,
      ok: status === 200,
      webDist: { ok: status === 200, detail: status === 200 ? "built" : "missing build" },
      proto: {
        engine: { ok: engineOk, detail: engineOk ? "引擎可用" : "引擎文件缺失", version },
        template: { ok: templateOk, detail: templateOk ? "RP9 模板可用" : "模板不是 RP9 工程" },
      },
    };
    vi.stubGlobal("fetch", () => Promise.resolve(new Response(JSON.stringify(report), { status })));
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    const root = document.createElement("div");
    document.body.append(root);
    const app = createApp(PlatformHealthView as Component);
    app.mount(root);
    cleanups.push(() => {
      app.unmount();
      root.remove();
    });
    await vi.waitFor(() => expect(root.textContent).toContain(report.proto.engine.detail));
    await nextTick();
    const panel = [...root.querySelectorAll(".panel")].find((item) =>
      item.querySelector("h2")?.textContent?.includes("原型工坊"),
    )!;
    expect(panel.textContent).toContain("zee-rp");
    expect(panel.textContent).toContain(report.proto.template.detail);
    expect(panel.textContent).toContain(version ?? "未知");
    expect(panel.textContent).toContain("不影响新闻流水线");
    expect([...panel.querySelectorAll(".el-tag")].map((tag) => tag.textContent?.trim())).toEqual([
      engineOk ? "已就绪" : "未就绪",
      templateOk ? "已就绪" : "未就绪",
    ]);
    expect(root.querySelector(".panel-header .el-tag")?.textContent?.trim()).toBe(
      report.ok ? "运行正常" : "需要检查",
    );
  },
);
