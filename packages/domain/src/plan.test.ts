import { PLAN_LIMITS } from "@heron/contracts";
import { describe, expect, it } from "vitest";

import { exceeds, excessMessage } from "./plan";

/** 额度判据的边界只有一处需要小心：used === limit 就已经不能再加一个。 */
describe("套餐额度", () => {
  const zero = { seats: 0, renders: 0, publishes: 0 };

  it("三档各自的上限就是 PLAN_LIMITS 里的数字，没超时一律放行", () => {
    for (const plan of ["free", "standard", "enterprise"] as const) {
      const limits = PLAN_LIMITS[plan];
      expect(
        exceeds(plan, {
          seats: limits.seats - 1,
          renders: limits.rendersPerMonth - 1,
          publishes: limits.publishesPerMonth - 1,
        }),
      ).toBeNull();
      expect(exceeds(plan, zero)).toBeNull();
    }
  });

  it("用到上限就等于用尽：再要一个必须被拒，并说清哪道门、多少、用了多少", () => {
    const free = PLAN_LIMITS.free;
    expect(exceeds("free", { seats: free.seats, renders: 0, publishes: 0 })).toEqual({
      kind: "seats",
      limit: free.seats,
      used: free.seats,
    });
    expect(exceeds("free", { seats: 0, renders: free.rendersPerMonth, publishes: 0 })).toEqual({
      kind: "renders",
      limit: free.rendersPerMonth,
      used: free.rendersPerMonth,
    });
    expect(
      exceeds("standard", {
        seats: 0,
        renders: 0,
        publishes: PLAN_LIMITS.standard.publishesPerMonth,
      }),
    ).toEqual({
      kind: "publishes",
      limit: PLAN_LIMITS.standard.publishesPerMonth,
      used: PLAN_LIMITS.standard.publishesPerMonth,
    });
  });

  it("升档即刻放宽：同一个用量在上一档被拒、在这一档放行", () => {
    const usage = { seats: 3, renders: 500, publishes: 100 };
    expect(exceeds("free", usage)?.kind).toBe("seats");
    expect(exceeds("standard", usage)).toBeNull();
  });

  it("拒绝理由是一句话，不含内部标识、带当前档位与去处", () => {
    const message = excessMessage("free", { kind: "publishes", limit: 30, used: 30 });
    expect(message).toContain("30/30");
    expect(message).toContain("free");
    expect(message).toContain("平台台");
    expect(message).not.toContain("publishesPerMonth");
  });
});
