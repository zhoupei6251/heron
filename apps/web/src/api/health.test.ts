import { afterEach, expect, it, vi } from "vitest";
import { loadHealth } from "./health";

const degraded = {
  ok: false,
  at: "2026-10-23T00:00:00.000Z",
  node: "v24.22.1",
  db: { ok: true, detail: "connected" },
  webDist: { ok: false, detail: "missing build" },
  publish: { open: false, blockers: ["review required"] },
};
afterEach(() => vi.unstubAllGlobals());
it("preserves the failed probes returned with HTTP 503", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(JSON.stringify(degraded), { status: 503 })),
  );
  expect(await loadHealth()).toEqual(degraded);
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
