// 场景骨架：每一屏按自己的 scene_type 换构图，而不只是换三行字。
//
// 为什么需要这一层：模板原先只有一个骨架——上半屏固定图表、下半屏编号+领句+正文。
// 三个场景讲三件不同的事，画面上却只有文字在换，构图 45 秒不动。
// 观众读到的是「一张图配三段话」，不是三段有层次的叙事。
//
// 借鉴 HyperFrames registry 的两个设计（只借理念，不复制实现）：
//   chart-story 的 emphasize —— 「强调哪一个数据点」是内容给的，不是模板写死的；
//   decline-chart 的 ambient lockstep —— 背景随数据走向一起变，画面与内容同感。
// 落到这里：chart 屏把整幅让给图表并锁定焦点；number 屏把数字放大到读得清；
// compare 屏走两栏对照（compare.js 已有，这里只清场）。
//
// 三种骨架都只在**屏内前段**接管，之后把常驻层放回来——冲击与强调是开场动作，
// 不是整屏形态。时长上限与 headline 同一条规则，不另立一套。
const skeletonSlots = Array.from(document.querySelectorAll(".scene"))
  .filter((slot) => Number(slot.dataset.sceneIndex) <= sceneCount);

// 每种场景类型接管时，常驻层里哪些要退场。留空的类型不接管（text/timeline 保持原样）。
const SKELETONS = {
  // 图表屏：图表升为主角，场景文字压缩成一句压底标注
  chart: { hide: ["#title-card"], maxSec: 0 },
  // 数字屏：数字与单位占主视区，图表退场
  number: { hide: ["#chart-slot"], maxSec: 0 },
  // 对照屏：两栏对照由 compare.js 建，常驻图表退场
  compare: { hide: ["#chart-slot"], maxSec: 0 },
};

const windows = [];
skeletonSlots.forEach((slot) => {
  const type = slot.dataset.sceneType;
  const spec = SKELETONS[type];
  if (!spec) return;
  const start = Number(slot.dataset.start);
  const duration = Number(slot.dataset.duration);
  if (!Number.isFinite(start) || !Number.isFinite(duration) || duration <= 0) return;
  // maxSec 为 0 表示整屏接管：这三种类型本身就是「这一屏就是这件事」，
  // 不像 headline 只是钩子。仍然保留整屏而不是半屏，因为点掉图表之后
  // 画面必须立刻给出替代内容（大数字或两栏对照），不存在白板风险。
  windows.push({ slot, type, start, end: start + duration, hide: spec.hide });
});

if (windows.length) {
  const FADE = 0.4;
  const byLayer = new Map();
  windows.forEach((win) => {
    win.hide.forEach((selector) => {
      const el = document.querySelector(selector);
      if (!el) return;
      if (!byLayer.has(el)) byLayer.set(el, []);
      byLayer.get(el).push(win);
    });
  });

  byLayer.forEach((list, el) => {
    // 逐屏排进/排出的补间。相邻窗口重叠时不重复藏，避免抖一下。
    list.sort((a, b) => a.start - b.start).forEach((win, index) => {
      const prev = list[index - 1];
      const fadeOutAt = Math.max(0, win.start - 0.35);
      if (fadeOutAt <= 0) {
        tl.set(el, { opacity: 0 }, 0);
        el.style.opacity = "0";
      } else if (!prev || fadeOutAt >= prev.end) {
        // 与上一屏窗口重叠时不再藏一次：它本来就该一直是暗的
        tl.to(el, { opacity: 0, duration: FADE, ease: "sine.inOut" }, fadeOutAt);
      }
      const next = list[index + 1];
      // 下一屏紧接着也要藏，就不在这里放回来——放回来再藏等于闪一下
      const overlapsNext = next && next.start <= win.end + 0.8;
      const fadeInDur = Math.min(FADE, Math.max(0, total - win.end));
      if (!overlapsNext && fadeInDur > 0.05) {
        tl.to(el, { opacity: 1, duration: fadeInDur, ease: "sine.inOut" }, win.end);
      }
    });
  });

  // 骨架类名按时间轴时间算，seek 到哪一帧都对
  const clock = { time: 0 };
  tl.to(clock, {
    time: total,
    duration: total,
    ease: "none",
    onUpdate: () => {
      const t = tl.time();
      windows.forEach((win) => {
        const on = t >= win.start && t < win.end;
        win.slot.classList.toggle("skeleton-" + win.type, on);
      });
    },
  }, 0);
}
