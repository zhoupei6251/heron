// 全局时间线与场景进出。所有模板共用，必须在 chart 之后执行。
// 只做三个动作：enter / ambient / swap。参数全部来自令牌，
// 风格差异靠改令牌而不是另写一套动画——否则三套风格很快就会长成三份真相。
// 数字滚动不在这里：KPI 的小数位与单位随数据集变化，由 chart.js 按 precision 驱动。
const has = (selector) => document.querySelector(selector) !== null;
const numberVar = (name, fallback) => {
  const value = parseFloat(cssVar(name));
  return Number.isFinite(value) ? value : fallback;
};
const EASE_OUT = cssVar("--ease-out") || "power3.out";
const EASE_IN = cssVar("--ease-in") || "power2.in";
const DUR = {
  entrance: numberVar("--dur-entrance", 0.5),
  swap: numberVar("--dur-swap", 0.3),
  ambient: numberVar("--dur-ambient", 7),
};
const FROM = { up: { y: 30 }, down: { y: -30 }, left: { x: 46 }, right: { x: -46 }, none: {} };

// immediateRender：动画排在中段时 GSAP 默认不提前写起始值，元素会先裸着露出来
function enter(targets, { dir = "up", at = 0, stagger = 0 } = {}) {
  if (!targets) return;
  const list = typeof targets === "string" ? document.querySelectorAll(targets) : targets;
  if (!list || (!list.nodeType && !list.length)) return;
  tl.from(list, { ...FROM[dir], opacity: 0, duration: DUR.entrance, ease: EASE_OUT, stagger, immediateRender: true }, at);
}

function ambient(scope = document) {
  scope.querySelectorAll("[data-ambient]").forEach((el) => {
    const base = parseFloat(getComputedStyle(el).opacity);
    const dim = Number.isFinite(base) ? base : 1;
    const kind = el.dataset.ambient;
    if (kind === "drift") {
      tl.to(el, { x: 18, y: -14, duration: DUR.ambient, ease: "sine.inOut", repeat: -1, yoyo: true }, 0);
    } else if (kind === "pulse") {
      tl.to(el, { opacity: dim * 0.55, scale: 1.06, duration: DUR.ambient * 0.8, ease: "sine.inOut", repeat: -1, yoyo: true }, 0);
    } else if (kind === "flicker") {
      tl.to(el, { opacity: dim * 0.7, duration: DUR.ambient * 0.35, ease: "sine.inOut", repeat: -1, yoyo: true }, 0);
    }
  });
}

function swap(slot, start, end) {
  const parts = slot.querySelectorAll(".scene-no, .lead, .body");
  if (!parts.length) return;
  tl.from(parts, { y: 26, opacity: 0, duration: DUR.entrance, ease: EASE_OUT, stagger: 0.08, immediateRender: true }, start);
  const outAt = Math.max(start + DUR.entrance, end - DUR.swap);
  tl.to(parts, { y: -14, opacity: 0, duration: DUR.swap, ease: EASE_IN, stagger: 0.04 }, outAt);
}

ambient();

tl.fromTo("#progress", { scaleX: 0 }, { scaleX: 1, duration: total, ease: "none" }, 0);
enter("#kicker", { dir: "left", at: 0.1 });
enter("#title", { dir: "up", at: 0.2 });
tl.from("#rule", { scaleX: 0, duration: DUR.entrance, ease: EASE_OUT, immediateRender: true }, 0.45);
if (has("#kpi")) enter("#kpi", { dir: "up", at: 0.35 });
enter("#aigc-badge", { dir: "none", at: 0.2 });
// 只淡出标题区，栏目标签留作画面锚点
tl.to("#title, #rule", { opacity: 0, duration: 0.6, ease: "sine.inOut" }, 3.6);

document.querySelectorAll(".scene").forEach((slot) => {
  if (Number(slot.dataset.sceneIndex) > sceneCount) {
    slot.hidden = true;
    slot.style.display = "none";
    return;
  }
  const start = parseFloat(slot.dataset.start);
  const end = start + parseFloat(slot.dataset.duration);
  swap(slot, start, end);
});

// 结尾口播占最后一屏让出的时间窗；模板默认值下它就落在片尾，不另外排动画
const closingSlot = document.getElementById("closing-slot");
if (closingSlot) {
  const at = parseFloat(closingSlot.dataset.start);
  if (Number.isFinite(at) && at < total - 0.1) {
    enter(closingSlot.querySelector("#closing"), { dir: "up", at });
  }
}

window.__timelines["main"] = tl;
