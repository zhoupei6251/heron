import type { GateName, Stage } from "@heron/contracts";

export const NEXT_STAGE: Record<Stage, readonly Stage[]> = {
  candidate: ["claimed", "failed"],
  claimed: ["scripting", "failed"],
  scripting: ["factcheck", "failed"],
  factcheck: ["rendering", "scripting", "failed"],
  rendering: ["review", "failed"],
  review: ["approved", "failed"],
  approved: ["publishing", "failed"],
  publishing: ["published", "failed"],
  published: [],
  failed: [],
};

export const GATE_ON_TRANSITION: Partial<Record<`${Stage}_${Stage}`, GateName>> = {
  factcheck_rendering: "contract",
  rendering_review: "visual",
  review_approved: "human",
  approved_publishing: "compliance",
};

export function gateForTransition(from: Stage, to: Stage): GateName | null {
  return GATE_ON_TRANSITION[`${from}_${to}`] ?? null;
}

export function assertTransition(from: Stage, to: Stage): void {
  if (NEXT_STAGE[from].includes(to)) return;
  throw new Error(
    `非法状态转移 ${from} -> ${to}；${from} 的合法后继是 [${NEXT_STAGE[from].join(", ") || "无"}]`,
  );
}
