import { describe, expect, it } from "vitest";

import { PROTO_VISUAL_GATES, protoContractVerdict, protoVisualVerdict } from "./index";

const PASSING_AUDIT = {
  gates: {
    color_ok: true,
    font_ok: true,
    semantic_naming_ok: true,
    group_coverage_ok: true,
  },
  coverage: {
    font: { passed: 5, applicable: 5 },
    semanticNaming: { passed: 12, applicable: 12 },
  },
};

describe("契约闸（check 输出）", () => {
  it("structural_ok=true 且无 error 诊断才放行", () => {
    expect(protoContractVerdict({ structural_ok: true, diagnostics: [] }).ok).toBe(true);
    const warningOnly = protoContractVerdict({
      structural_ok: true,
      diagnostics: [{ severity: "warning", code: "W_X", pointer: "/pages/0", message: "提示" }],
    });
    expect(warningOnly.ok).toBe(true);
  });

  it("诊断里带 JSON Pointer 与原因，拒绝时能指出哪一条不过", () => {
    const verdict = protoContractVerdict({
      structural_ok: false,
      diagnostics: [
        {
          severity: "error",
          code: "E_PROPERTY",
          pointer: "/pages/0/root/type",
          message: "未知组件类型 nonsense-widget",
        },
      ],
    });
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toContain("/pages/0/root/type");
    expect(verdict.reason).toContain("nonsense-widget");
  });

  it("引擎没输出 JSON 时拒绝，而不是当成通过", () => {
    const verdict = protoContractVerdict("python: command not found");
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toContain("没有输出 JSON");
  });
});

describe("视觉闸（audit-report.json）", () => {
  it("四项全 pass 才放行，并带上 coverage 的比例", () => {
    const verdict = protoVisualVerdict(PASSING_AUDIT);
    expect(verdict.ok).toBe(true);
    expect(verdict.checks).toHaveLength(PROTO_VISUAL_GATES.length);
    expect(verdict.checks.find((c) => c.key === "semantic_naming_ok")?.detail).toContain("12/12");
  });

  it("任一项 false 就拒绝，原因点名是哪一项", () => {
    const verdict = protoVisualVerdict({
      ...PASSING_AUDIT,
      gates: { ...PASSING_AUDIT.gates, font_ok: false },
      coverage: { ...PASSING_AUDIT.coverage, font: { passed: 3, applicable: 5 } },
    });
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toContain("字体合规");
    expect(verdict.reason).toContain("3/5");
  });

  it("缺字段等于没过（fail-closed），引擎换报告形状也不会静默放行", () => {
    const verdict = protoVisualVerdict({
      ...PASSING_AUDIT,
      gates: { color_ok: true, font_ok: true, semantic_naming_ok: true },
    });
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toContain("分组覆盖");
    expect(verdict.reason).toContain("缺失");
  });
});
