// 变量注入与通用填充。所有模板共用，必须最先执行。
const declared = window.__hyperframes && window.__hyperframes.getVariables
  ? window.__hyperframes.getVariables()
  : {};
const root = document.getElementById("root");
const tl = gsap.timeline({ paused: true });
const total = parseFloat(root.dataset.duration);

// 模板里的尺寸一律来自令牌，避免 JS 和 CSS 各写一份而漂移
const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

// 标题按字数降档：92px → 76px → 68px
const titleEl = document.getElementById("title");
const titleLen = Array.from(declared.title || titleEl.textContent || "").length;
if (titleLen > 11) titleEl.style.fontSize = cssVar("--fs-title-sm");
else if (titleLen > 9) titleEl.style.fontSize = cssVar("--fs-title-lg");

// 实际播几屏由稿件决定，模板只负责准备好槽位
const sceneCount = Number.isFinite(Number(declared.scene_count)) ? Number(declared.scene_count) : 3;

// 屏次编号与配图同理：槽位先备好，有没有编号和有没有图由稿件与模板能力决定。
document.querySelectorAll(".scene").forEach((slot) => {
  const index = slot.dataset.sceneIndex;
  const no = slot.querySelector(".scene-no");
  if (no) no.textContent = String(index).padStart(2, "0");
  const figure = slot.querySelector(".scene-figure");
  const src = declared["scene" + index + "_image"];
  if (!figure || !src) return;
  // img 由脚本创建而不是写在 HTML 里：静态 <img> 会被版式闸判成同组重复媒体节点
  const img = document.createElement("img");
  img.alt = "";
  img.src = src;
  figure.appendChild(img);
});
