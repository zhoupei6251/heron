import "@fontsource-variable/jetbrains-mono";
import "@fontsource-variable/space-grotesk";
import { createApp } from "vue";

import NewsVertical from "./NewsVertical.vue";

export interface MountOptions {
  vars?: Record<string, unknown>;
}

export function mount(target: HTMLElement, compId: string, options: MountOptions = {}): void {
  createApp(NewsVertical, { compId, vars: options.vars ?? {} }).mount(target);
}

/** 预览用的真实形状示例，不是占位文案：一眼能看出标题、领句、正文与声明的位置。 */
const PREVIEW: Record<string, unknown> = {
  kicker: "热榜 · 科技",
  rank: "03",
  count: "01 / 04",
  title: "国产大模型开源之后，第一批受益的是谁",
  onscreen: "开源不等于免费",
  body: "一家公司把模型权重放到网上，真正被改变的往往不是实验室，而是那些原本买不起推理预算的小团队。",
  source: "来源：公开热榜 · 2026-10-27",
};

/** 1080×1920 塞不进浏览器窗口：等比缩到看得全，不改版式本身的尺寸。 */
function fit(pack: HTMLElement): void {
  const scale = Math.min(1, (innerWidth - 48) / 1080, (innerHeight - 48) / 1920);
  pack.style.transform = `scale(${scale})`;
  pack.style.transformOrigin = "top center";
  (pack.parentElement as HTMLElement).style.height = `${1920 * scale}px`;
}

const stage = document.getElementById("stage");
if (stage?.dataset.preview === "1") {
  mount(stage, "news-vertical", { vars: PREVIEW });
  fit(stage.firstElementChild as HTMLElement);
  addEventListener("resize", () => fit(stage.firstElementChild as HTMLElement));
}
