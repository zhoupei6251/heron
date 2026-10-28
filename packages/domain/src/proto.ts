/**
 * 原型工坊的两道自动闸。判据来自引擎自己的离线输出，不在这里重新发明：
 * `check` 给结构结论（契约闸），`compile` 的 audit-report.json 给审美四项（视觉闸）。
 *
 * 两份输出都是外部进程的 JSON，形状可能随引擎升级而变——所以这里 fail-closed：
 * 字段不是显式的 true 就算没过，缺字段也要说「缺的是哪一项」，而不是默默放行。
 */

export interface ProtoGateCheck {
  key: string;
  label: string;
  passed: boolean;
  detail: string;
}

export interface ProtoVerdict {
  ok: boolean;
  checks: ProtoGateCheck[];
  /** 一次能读完的拒绝原因；放行时为 null。 */
  reason: string | null;
}

/**
 * 视觉闸的固定四项。键名照抄 engine 的 audit-report.json（aesthetic_gates / structural_gates），
 * 移植时抄错一个键，这四个检查会全部 fail-closed——那是能看见的红，不是静默放行。
 */
export const PROTO_VISUAL_GATES = [
  { key: "color_ok", label: "色彩收敛", coverageKey: null },
  { key: "font_ok", label: "字体合规", coverageKey: "font" },
  { key: "semantic_naming_ok", label: "语义命名", coverageKey: "semanticNaming" },
  { key: "group_coverage_ok", label: "分组覆盖", coverageKey: "groupCoverage" },
] as const;

export const PROTO_GATE_LABELS: Record<string, string> = {
  contract: "契约闸",
  visual: "视觉闸",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown): string {
  return typeof value === "string" ? value : JSON.stringify(value);
}

/**
 * 契约闸读 `check` 的结论。退出码非零时调用方也会把 stdout 原样递进来——
 * 那时 ok 为 false，diagnostics 里带着 JSON Pointer，正好是「哪条不过」的原文。
 */
export function protoContractVerdict(output: unknown): ProtoVerdict {
  if (!isRecord(output)) {
    return {
      ok: false,
      checks: [
        {
          key: "structural_ok",
          label: "结构校验",
          passed: false,
          detail: `引擎没有输出 JSON 诊断：${text(output).slice(0, 300)}`,
        },
      ],
      reason: `契约闸：引擎没有输出 JSON 诊断，无法判断结构是否合法`,
    };
  }

  const diagnostics = Array.isArray(output.diagnostics) ? output.diagnostics : [];
  const errors = diagnostics.filter(
    (item) => !isRecord(item) || item.severity === undefined || item.severity === "error",
  );
  const passed = output.structural_ok === true && errors.length === 0;
  const detail =
    errors.length === 0
      ? "structural_ok=true，无 error 级诊断"
      : errors
          .slice(0, 5)
          .map((item) => {
            const record = isRecord(item) ? item : {};
            const pointer = typeof record.pointer === "string" ? record.pointer : "?";
            const code = typeof record.code === "string" ? record.code : "E_UNKNOWN";
            const message = typeof record.message === "string" ? record.message : text(item);
            return `${pointer} ${code}: ${message}`;
          })
          .join("；");
  return {
    ok: passed,
    checks: [{ key: "structural_ok", label: "结构校验", passed, detail }],
    reason: passed ? null : `契约闸：RP/1 未通过结构校验。${detail}`,
  };
}

/**
 * 视觉闸：四项全 pass 才放行。`coverage` 里如果有分母就带上比例——
 * 「语义命名 31/39」比「没过」更能让人知道下一步改哪里。
 */
export function protoVisualVerdict(audit: unknown): ProtoVerdict {
  if (!isRecord(audit)) {
    return {
      ok: false,
      checks: PROTO_VISUAL_GATES.map((gate) => ({
        key: gate.key,
        label: gate.label,
        passed: false,
        detail: "审计报告不是 JSON 对象",
      })),
      reason: "视觉闸：引擎没有产出可读的审计报告",
    };
  }

  const gates = isRecord(audit.gates) ? audit.gates : {};
  const coverage = isRecord(audit.coverage) ? audit.coverage : {};
  const checks: ProtoGateCheck[] = PROTO_VISUAL_GATES.map((gate) => {
    const passed = gates[gate.key] === true;
    const cover = gate.coverageKey ? coverage[gate.coverageKey] : undefined;
    const ratio =
      isRecord(cover) && typeof cover.passed === "number" && typeof cover.applicable === "number"
        ? `${String(cover.passed)}/${String(cover.applicable)}`
        : null;
    return {
      key: gate.key,
      label: gate.label,
      passed,
      detail: passed
        ? `${gate.key}=pass${ratio ? `（${ratio}）` : ""}`
        : `${gate.key}=${gates[gate.key] === false ? "fail" : "缺失"}${ratio ? `（${ratio}）` : ""}`,
    };
  });

  const failed = checks.filter((check) => !check.passed);
  return {
    ok: failed.length === 0,
    checks,
    reason:
      failed.length === 0
        ? null
        : `视觉闸：${failed.map((check) => `${check.label}未过（${check.detail}）`).join("；")}`,
  };
}
