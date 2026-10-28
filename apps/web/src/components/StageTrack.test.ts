import { createApp, type Component } from "vue";
import { afterEach, describe, expect, it } from "vitest";

import type { Stage } from "@heron/contracts";
import StageTrack from "./StageTrack.vue";

const roots: HTMLElement[] = [];
function mount(stage: Stage, dense = false): HTMLElement {
  const root = document.createElement("div");
  document.body.append(root);
  createApp(StageTrack as Component, { stage, dense }).mount(root);
  roots.push(root);
  return root;
}

afterEach(() => {
  while (roots.length) roots.pop()?.remove();
});

describe("生产轨道", () => {
  it("九个在轨点，当前格之前的连线都算走过", () => {
    const root = mount("factcheck");
    const dots = root.querySelectorAll("li");
    expect(dots).toHaveLength(9);
    expect([...dots].map((node) => node.classList.contains("done"))).toEqual([
      true,
      true,
      true,
      false,
      false,
      false,
      false,
      false,
      false,
    ]);
    expect(dots[3]?.classList.contains("current")).toBe(true);
    expect(dots[3]?.textContent).toContain("事实核查");
  });

  it("五道闸各落在它的入口：去重→待认领、契约→渲染、视觉→审片、人工→确认、合规→发布", () => {
    const root = mount("candidate");
    const nodes = [...root.querySelectorAll("li")];
    expect(nodes.map((node) => node.querySelector(".gate")?.textContent?.trim())).toEqual([
      "去重",
      undefined,
      undefined,
      undefined,
      "契约",
      "视觉",
      "人工",
      "合规",
      undefined,
    ]);
  });

  it("failed 是脱轨终点：不点亮任何一格，也不假装知道停在哪", () => {
    const root = mount("failed");
    expect(root.querySelectorAll(".done, .current")).toHaveLength(0);
    expect(root.querySelector(".track-label")?.textContent?.trim()).toBe("失败");
    expect(root.querySelector(".stage-track")?.getAttribute("aria-label")).toContain("失败");
  });

  it("表格窄格不逐格铺字，只给当前一格一句话", () => {
    const root = mount("rendering", true);
    expect(root.querySelector(".stage-track")?.classList.contains("is-dense")).toBe(true);
    expect(root.querySelector(".track-label")?.textContent?.trim()).toBe("渲染中");
  });
});
