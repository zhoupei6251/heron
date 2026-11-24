// 全局时间线与场景进出。所有模板共用，必须在 chart 之后执行。
// 只有六个动作：enter / enterChars / ambient / camera / wipe / swap。参数全部来自令牌，
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
  const list = queryList(targets);
  if (!list) return;
  tl.from(list, { ...FROM[dir], opacity: 0, duration: DUR.entrance, ease: EASE_OUT, stagger, immediateRender: true }, at);
}

function queryList(targets) {
  if (!targets) return null;
  const list = typeof targets === "string" ? document.querySelectorAll(targets) : targets;
  return list && (list.nodeType || list.length) ? list : null;
}

// 逐字入场：只给「标题型」文字用——主标题、屏头编号、引句、片尾口播。
// 正文不拆：一段话逐字进场会拖长入场时间，也把该一眼扫过的句子打散成一串字。
// 拆字之后每个字符是绝对定位的 span，autoSplit 让它在宽度变化时重新分行，
// 而不是按拆字那一刻的排版钉死。
function enterChars(targets, { at = 0, stagger = 0.025, duration = DUR.entrance } = {}) {
  const list = queryList(targets);
  if (!list) return;
  // 插件没加载时退回整段淡入：少一个文件不该让模板白屏
  if (typeof SplitText === "undefined") {
    enter(targets, { at });
    return;
  }
  const split = SplitText.create(list, { type: "chars,lines", mask: "lines", autoSplit: true });
  tl.from(split.chars, { yPercent: 55, opacity: 0, duration, ease: EASE_OUT, stagger }, at);
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

// 镜头：全片一条补间——钩子段快推、中段几乎匀速、片尾回拉半格。
// 三条独立补间会在同一个 scale 上互相抢值，所以用 keyframes 串成一条。
// 推近的裁切交给 #root 的 overflow：推出去的部分本就该看不见。
function camera() {
  const el = document.getElementById("camera");
  const push = numberVar("--cam-push", 1.05);
  if (!el || push <= 1) return;
  el.style.transformOrigin = cssVar("--cam-origin") || "50% 50%";
  const hook = Math.min(2.2, total * 0.2);
  const tail = Math.min(2.4, total * 0.16);
  const mid = Math.max(0.1, total - hook - tail);
  tl.to(el, {
    keyframes: [
      { scale: 1 + (push - 1) * 0.6, duration: hook, ease: "power2.out" },
      { scale: push, duration: mid, ease: "none" },
      { scale: 1 + (push - 1) * 0.4, duration: tail, ease: "power2.inOut" },
    ],
  }, 0);
}

// 屏与屏之间是硬切：同轨的 clip 时间窗不重叠，没有可以交叉淡入的两帧。
// 于是用一块整帧面板盖住切点：进场 power2.in 收拢、切点正落在面板盖满的那一帧、
// 再 power2.out 让开，露出一屏。观感是擦除，不是闪一下。
function wipe(at, index) {
  const el = document.getElementById("wipe");
  const dur = numberVar("--wipe-dur", 0.3);
  if (!el || dur <= 0) return;
  // 逐屏换向：同一个方向连擦五次就变成噪音
  const dir = index % 2 ? 1 : -1;
  tl.fromTo(el, { xPercent: -100 * dir }, { xPercent: 0, duration: dur / 2, ease: "power2.in" }, Math.max(0, at - dur / 2));
  tl.to(el, { xPercent: 100 * dir, duration: dur / 2, ease: "power2.out" }, at);
}

function swap(slot, start, outAt) {
  const parts = slot.querySelectorAll(".scene-no, .lead, .body");
  if (!parts.length) return;
  // 屏头编号与引句逐字，正文整段：正文是给人读的，拆成字只会读得更慢
  enterChars(slot.querySelectorAll(".scene-no, .lead"), { at: start });
  const body = slot.querySelectorAll(".body");
  if (body.length) {
    tl.from(body, { y: 26, opacity: 0, duration: DUR.entrance, ease: EASE_OUT, stagger: 0.08, immediateRender: true }, start + 0.12);
  }
  // 短屏时退场不能排在入场之前
  tl.to(parts, { y: -14, opacity: 0, duration: DUR.swap, ease: EASE_IN, stagger: 0.04 }, Math.max(start + DUR.entrance, outAt));
}

ambient();
camera();

tl.fromTo("#progress", { scaleX: 0 }, { scaleX: 1, duration: total, ease: "none" }, 0);
enter("#kicker", { dir: "left", at: 0.1 });
enterChars("#title", { at: 0.2 });
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
  // 第一屏之前是标题卡，靠淡入衔接，不需要擦除
  if (Number(slot.dataset.sceneIndex) > 1) wipe(start, Number(slot.dataset.sceneIndex));
});

if (hasClosing) {
  enterChars(closingSlot.querySelector("#closing"), { at: closingAt });
}

window.__timelines["main"] = tl;
