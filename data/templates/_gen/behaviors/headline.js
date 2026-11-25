// 冲击屏：headline 屏接管整幅画面——图表与常驻标题让位，这一屏只有一句话。
// 口径：docs/2026-11-20-storyboard-and-visual-signal-report.md §六.2「冲击屏」。
// 屏次窗口取渲染期改写过的 data-start/data-duration（与真实口播对齐），不另建时间轴：
// 这套模板只有一条时间轴，多一条就会和口播对不齐。
const sceneSlots = Array.from(document.querySelectorAll(".scene"));
const headlineSlots = sceneSlots.filter((slot) => {
  // 超出实际播出的屏次没有窗口，motion.js 会把它们整块藏掉
  if (slot.dataset.sceneType !== "headline") return false;
  return Number(slot.dataset.sceneIndex) <= sceneCount;
});

const headlineWindows = headlineSlots
  .map((slot) => {
    const start = Number(slot.dataset.start);
    const duration = Number(slot.dataset.duration);
    if (!Number.isFinite(start) || !Number.isFinite(duration) || duration <= 0) return null;
    return { slot, start, end: start + duration };
  })
  .filter(Boolean);

if (headlineWindows.length) {
  // 冲击屏最多接管这么久。窗口来自真实口播，一屏常到 10 秒以上；
  // 整段都让常驻层退场，等于用十几秒白板换一句钩子——冲击只在开头成立，
  // 之后观众需要数据接住。超出部分把常驻层放回来，这一屏退回普通版式。
  const MAX_TAKEOVER = 4.5;
  // 相邻的 headline 屏之间不把常驻层放回来：放回来再藏一次等于抖一下
  const merged = [];
  headlineWindows.forEach((win) => {
    const last = merged[merged.length - 1];
    const end = Math.min(win.end, win.start + MAX_TAKEOVER);
    if (last && win.start <= last.end + 0.8) last.end = Math.max(last.end, end);
    else merged.push({ start: win.start, end });
  });

  const layers = ["#title-card", "#chart-slot"]
    .map((selector) => document.querySelector(selector))
    .filter(Boolean);
  const FADE = 0.4;
  merged.forEach((win) => {
    const fadeOutAt = Math.max(0, win.start - 0.35);
    // 冲击屏就是片头时，常驻层一开始就不该露脸。
    // set 之外再压一次 inline：渲染是逐帧 seek，零时长补间在第 0 帧偶尔不落值。
    if (fadeOutAt <= 0) {
      tl.set(layers, { opacity: 0 }, 0);
      layers.forEach((el) => { el.style.opacity = "0"; });
    } else {
      tl.to(layers, { opacity: 0, duration: FADE, ease: "sine.inOut" }, fadeOutAt);
    }
    // 收尾不越片长：越界的补间会把时间轴拖长，渲染帧数跟着变
    const fadeInDur = Math.min(FADE, Math.max(0, total - win.end));
    if (fadeInDur > 0.05) tl.to(layers, { opacity: 1, duration: fadeInDur, ease: "sine.inOut" }, win.end);
  });

  // 上屏文字按「排不排得下一行」定字号，不按字数猜：各风格的 --fs-title 与可用宽度都不同
  // （调查线 100px + 窄边距，播报线 86px + 通栏），猜出来的档位迟早在某个风格上挤成孤字。
  headlineWindows.forEach((win) => {
    // 上屏文字才看得见（.lead.screen-text），原领句被 CSS 藏了——按看得见的那句定字号
    const lead = win.slot.querySelector(".lead.screen-text") || win.slot.querySelector(".lead");
    if (!lead) return;
    const style = getComputedStyle(win.slot);
    const avail = win.slot.clientWidth
      - parseFloat(style.paddingLeft || 0)
      - parseFloat(style.paddingRight || 0);
    const len = Array.from((lead.textContent || "").trim()).length;
    if (!Number.isFinite(avail) || avail <= 0 || !len) return;
    let picked = null;
    for (const token of ["--fs-title", "--fs-title-lg", "--fs-title-sm"]) {
      const px = parseFloat(cssVar(token));
      if (!Number.isFinite(px) || px <= 0) continue;
      picked = token;
      if (len * px <= avail) break;
    }
    if (picked) lead.style.fontSize = cssVar(picked);
  });

  // 接管期间这一屏占满整幅画面。类名按时间轴绝对时间算：seek 到哪一帧都对。
  // 窗口用 merged（已按时长上限截断），与常驻层的进出保持同一份时间窗；
  // 用原始窗口会让文字继续满屏居中，而标题卡已经放回来了。
  const takeoverClock = { time: 0 };
  tl.to(takeoverClock, {
    time: total,
    duration: total,
    ease: "none",
    onUpdate: () => {
      const t = tl.time();
      headlineWindows.forEach((win) => {
        const capped = Math.min(win.end, win.start + MAX_TAKEOVER);
        win.slot.classList.toggle("takeover", t >= win.start && t < capped);
      });
    },
  }, 0);
}
