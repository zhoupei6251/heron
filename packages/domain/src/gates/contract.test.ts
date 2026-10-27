import { describe, expect, it } from "vitest";

import { scriptVerdict } from "./contract";

const good = {
  title: "台风巴威今晚登陆",
  scenes: [
    { body: "中央气象台今晚发布台风红色预警。", onscreen: "红色预警｜今晚登陆" },
    { body: "沿海地区已转移十二万人。" },
  ],
};

describe("契约闸", () => {
  it("没有稿子就不许进渲染", () => {
    const verdict = scriptVerdict(null);
    expect(verdict.passed).toBe(false);
    expect(verdict.rejections[0]?.ruleId).toBe("contract.script_missing");
  });

  it("少一个字段时点名是哪一镜的哪个字段", () => {
    const verdict = scriptVerdict({
      title: "缺正文",
      scenes: [{ body: "有正文" }, { onscreen: "只有屏句" }],
    });
    expect(verdict.passed).toBe(false);
    expect(verdict.rejections[0]).toMatchObject({
      ruleId: "contract.script_shape",
      snippet: "scenes.1.body",
    });
  });

  it("title 与屏句去掉标点后同句 → 拒，理由说清楚版式会打两遍", () => {
    const verdict = scriptVerdict({
      title: "两次提示，一句没事",
      scenes: [{ body: "正文" }, { body: "正文二", onscreen: "两次提示｜一句没事" }],
    });
    expect(verdict.passed).toBe(false);
    expect(verdict.rejections[0]?.ruleId).toBe("contract.title_onscreen_same");
  });

  it("分镜数量越界也拒（1 镜或 9 镜都不是一条短视频）", () => {
    expect(scriptVerdict({ title: "只有一个镜", scenes: [{ body: "正文" }] }).passed).toBe(false);
    expect(
      scriptVerdict({
        title: "九镜",
        scenes: Array.from({ length: 9 }, () => ({ body: "正文" })),
      }).passed,
    ).toBe(false);
  });

  it("形状正确且屏句换了角度 → 放行", () => {
    expect(scriptVerdict(good)).toEqual({ gate: "contract", passed: true, rejections: [] });
  });
});
