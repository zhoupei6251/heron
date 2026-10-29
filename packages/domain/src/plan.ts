import { PLAN_LIMITS, type PlanId } from "@heron/contracts";

/**
 * 一个租户当月的账。座位是当前值（成员数不按月清零），渲染与发布按自然月计。
 * 数字都由调用方从库里数出来再递进来：判据纯到不碰 IO，测试才能覆盖到边界的每一格。
 */
export interface PlanUsage {
  seats: number;
  renders: number;
  publishes: number;
}

export type PlanLimitKind = "seats" | "renders" | "publishes";

export const PLAN_LABELS: Record<PlanId, string> = {
  free: "免费",
  standard: "标准",
  enterprise: "企业",
};

export interface PlanExcess {
  kind: PlanLimitKind;
  limit: number;
  used: number;
}

const LIMIT_KIND_LABELS: Record<PlanLimitKind, string> = {
  seats: "成员数",
  renders: "本月渲染",
  publishes: "本月发布",
};

/**
 * 判据只回答一个问题：现在还能不能再加一个。
 * 所以 used 达到上限就算超（`>=`，不是 `>`）——额度说的是「最多这么多个」，
 * 写成 `>` 的后果是永久多送一个，而每一条这样的边界都会在账单上被问一遍。
 * 放行返回 null 而不是抛：调用方要知道是哪一道、多少、用了多少，才写得出「失败必须报原因」。
 */
export function exceeds(plan: PlanId, usage: PlanUsage): PlanExcess | null {
  const limits = PLAN_LIMITS[plan];
  const pairs: readonly (readonly [PlanLimitKind, number, number])[] = [
    ["seats", limits.seats, usage.seats],
    ["renders", limits.rendersPerMonth, usage.renders],
    ["publishes", limits.publishesPerMonth, usage.publishes],
  ];
  for (const [kind, limit, used] of pairs) if (used >= limit) return { kind, limit, used };
  return null;
}

/** 一句话说完哪道门、上限多少、已用多少、去哪儿改。界面与任务失败原因共用这一句。 */
export function excessMessage(plan: PlanId, excess: PlanExcess): string {
  return `${LIMIT_KIND_LABELS[excess.kind]}已达该档上限（${String(excess.used)}/${String(excess.limit)}，档位 ${PLAN_LABELS[plan]}（${plan}））；升档请由平台管理员在平台台调整`;
}
