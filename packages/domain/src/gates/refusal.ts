import type { GateName, GateVerdict, Task } from "@heron/contracts";

/**
 * 闸的判定入口：capability 侧（写稿、视觉、合规那一头）提供纯函数，仓储只负责把判定结果落库。
 * 签名放在 domain 而不是 db：SQLite 层与 pg 层是同一条状态机的两种存储，评估器不该跟着存储层长两份。
 */
export type GateEvaluator = (input: { gate: GateName; task: Task }) => GateVerdict;

/**
 * 闸拒绝。`message` 是给界面展开全文的那句拼好文案（formatErrorReason），
 * 结构化理由另存在 gate_results.rejections：光有这句话，审计与回归都对不上账。
 */
export class GateRefusalError extends Error {
  readonly gate: GateName;
  readonly reason: string;

  constructor(gate: GateName, reason: string) {
    super(reason);
    this.name = "GateRefusalError";
    this.gate = gate;
    this.reason = reason;
  }
}
