import { GATES, type GateLocation, type GateName } from "@heron/contracts";

export const IMPLEMENTED_GATES: readonly GateName[] = ["dedupe"];

export function gateLocations(): GateLocation[] {
  return GATES.map((gate) => ({
    gate,
    satisfied: IMPLEMENTED_GATES.includes(gate),
    detail: IMPLEMENTED_GATES.includes(gate)
      ? "已实现，可参与拦截"
      : "尺子未从 harness-factory 搬齐，门禁位未启用 —— 发布按钮必须置灰",
  }));
}

export function publishGateOpen(): boolean {
  const blockers = gateLocations().filter((location) => !location.satisfied);
  return blockers.length === 0;
}

export function publishBlockers(): string[] {
  return gateLocations()
    .filter((location) => !location.satisfied)
    .map((location) => `${location.gate}: ${location.detail}`);
}
