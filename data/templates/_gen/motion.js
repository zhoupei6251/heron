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
    const nominal = DUR.ambient * (kind === "pulse" ? 0.8 : kind === "flicker" ? 0.35 : 1);
    const cycles = Math.max(1, Math.ceil(total / nominal));
    const duration = total / cycles;
    if (kind === "drift") {
      tl.to(el, { x: 18, y: -14, duration, ease: "sine.inOut", repeat: cycles - 1, yoyo: true }, 0);
    } else if (kind === "pulse") {
      tl.to(el, { opacity: dim * 0.55, scale: 1.06, duration, ease: "sine.inOut", repeat: cycles - 1, yoyo: true }, 0);
    } else if (kind === "flicker") {
      tl.to(el, { opacity: dim * 0.7, duration, ease: "sine.inOut", repeat: cycles - 1, yoyo: true }, 0);
    }
  });
}

function swap(slot, start, outAt) {
  const parts = slot.querySelectorAll(".scene-no, .lead, .body");
  if (!parts.length) return;
  tl.from(parts, { y: 26, opacity: 0, duration: DUR.entrance, ease: EASE_OUT, stagger: 0.08, immediateRender: true }, start);
  // 短屏时退场不能排在入场之前
  tl.to(parts, { y: -14, opacity: 0, duration: DUR.swap, ease: EASE_IN, stagger: 0.04 }, Math.max(start + DUR.entrance, outAt));
}

ambient();

tl.fromTo("#progress", { scaleX: 0 }, { scaleX: 1, duration: total, ease: "none" }, 0);
enter("#kicker", { dir: "left", at: 0.1 });
enter("#title", { dir: "up", at: 0.2 });
tl.from("#rule", { scaleX: 0, duration: DUR.entrance, ease: EASE_OUT, immediateRender: true }, 0.45);
if (has("#kpi")) enter("#kpi", { dir: "up", at: 0.35 });
enter("#aigc-badge", { dir: "none", at: 0.2 });
// 数据图表常驻时保留选题标题，避免后半片只剩孤立数字；文稿线沿用原退场。
if (!has("#chart")) tl.to("#title, #rule", { opacity: 0, duration: 0.6, ease: "sine.inOut" }, 3.6);

// 版式自带的静态装饰（#plate / #rail / #tick）。它们不来自数据，
// 但各自有动画：没有动画的装饰层等于死图层。
if (has("#plate")) {
  tl.fromTo("#plate", { scaleY: 0 }, { scaleY: 1, duration: 0.8, ease: "power3.inOut" }, 0.05);
}
if (has("#rail")) {
  tl.fromTo("#rail", { scaleY: 0 }, { scaleY: 1, duration: total, ease: "none" }, 0);
}
const tick = document.getElementById("tick");
if (tick) {
  // 不能用 tl.call 逐屏写：按帧 seek 时回调会一次跑完，tick 停在最后一屏。
  // 从时间线时间反推屏次，seek 到哪一帧都对。
  const pad = (n) => String(n).padStart(2, "0");
  const starts = [...document.querySelectorAll(".scene")]
    .filter((slot) => Number(slot.dataset.sceneIndex) <= sceneCount)
    .map((slot) => parseFloat(slot.dataset.start));
  tl.to(tick, {
    duration: total,
    ease: "none",
    onUpdate: () => {
      const t = tl.time();
      const hit = starts.filter((start) => start <= t).length;
      tick.textContent = pad(Math.max(hit, 1)) + " / " + pad(sceneCount);
    },
  }, 0);
}

// 结尾口播独占片尾一段窗口（生成器已把最后一屏的窗口切短），这里只需按窗口起点进场
const closingSlot = document.getElementById("closing-slot");
const closingAt = closingSlot ? parseFloat(closingSlot.dataset.start) : NaN;
const hasClosing = Number.isFinite(closingAt) && closingAt < total - 0.1;

document.querySelectorAll(".scene").forEach((slot) => {
  if (Number(slot.dataset.sceneIndex) > sceneCount) {
    slot.hidden = true;
    slot.style.display = "none";
    return;
  }
  const start = parseFloat(slot.dataset.start);
  const end = start + parseFloat(slot.dataset.duration);
  swap(slot, start, end - DUR.swap);
});

if (hasClosing) {
  enter(closingSlot.querySelector("#closing"), { dir: "up", at: closingAt });
}

window.__timelines["main"] = tl;
