import type { GateVerdict } from "@heron/contracts";

export function formatErrorReason(verdict: GateVerdict): string {
  if (verdict.passed) {
    return `[${verdict.gate}] 通过`;
  }
  const lines = verdict.rejections.map(
    (r) => `  - ${r.ruleId}: ${r.why}${r.snippet ? `（片段：${r.snippet}）` : ""}`,
  );
  return [`[${verdict.gate}] 拒绝，共 ${verdict.rejections.length} 条`, ...lines].join("\n");
}
