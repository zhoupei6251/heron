import type { GateState, ReviewItem } from "@heron/contracts";
import { describe, expect, it } from "vitest";

import { reviewBlocker } from "./review";

const item: ReviewItem = {
  taskId: "102",
  title: "待审成片",
  renderVersion: 2,
  contactSheetFingerprint: "fingerprint-v2",
  contactSheetPath: "/private/sheet.png",
  mp4Path: null,
};
const gates: GateState = { open: true, blockers: [], locations: [] };

describe("人工确认的界面边界", () => {
  it("只读账号不能确认，审核员在实际预览且勾选后才能确认", () => {
    expect(reviewBlocker("viewer", gates, item, true, true)).not.toBe("");
    expect(reviewBlocker("reviewer", gates, item, true, true)).toBe("");
  });
  it("闸门未知、未开放、没有指纹、预览失败或未勾选都阻止确认", () => {
    expect(reviewBlocker("owner", null, item, true, true)).not.toBe("");
    expect(reviewBlocker("owner", { ...gates, open: false }, item, true, true)).not.toBe("");
    expect(
      reviewBlocker("owner", gates, { ...item, contactSheetFingerprint: null }, true, true),
    ).not.toBe("");
    expect(reviewBlocker("owner", gates, item, false, true)).not.toBe("");
    expect(reviewBlocker("owner", gates, item, true, false)).not.toBe("");
  });
});
