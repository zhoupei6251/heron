// 冲刺榜：排行是一场冲刺，不是一张表。配方卡见同目录 race.card.md——
// 意图、动作阶段、参数手感与已知坑都写在卡上，改参数先读卡。
// 取数口径与 compare.js 一致：事实来自 dataset_json.facts（items / leader）。
// 榜单 DOM 由生成器编译期写入（boardBlock）——引擎只渲带时间窗的编译期元素，
// 脚本运行时建的自由 DOM 进不了成片（实测）。脚本只做动画：事实不足就不接管，
// 榜单退回普通文字屏（#board 在 HTML 里但版式闸要求事实由脚本核对）。
// 必须排在 motion.js 之后：借它的 DUR / EASE 与同一条时间轴。
const raceDataset = JSON.parse(declared.dataset_json || "{}");
const raceFacts = raceDataset.facts || {};
const raceBoard = document.getElementById("board");

if (raceBoard) {
  const raceValues = raceBoard.querySelectorAll(".row-value");
  const raceItems = (raceFacts.items || []).filter((e) => e && Number.isFinite(Number(e.value))).slice(0, 5);
  // HTML 里的 0 占位按事实回填：生成器写的是 defaults 的静态值，
  // 真实渲染时 dataset_json 是运行时变量，两处可能不同，事实优先。
  raceValues.forEach((el, index) => {
    const target = Number(raceItems[index]?.value ?? el.dataset.value ?? 0);
    const counter = { v: 0 };
    tl.to(counter, {
      v: target, duration: 1.1, ease: "power1.out",
      onUpdate: () => { el.textContent = Number(target).toFixed(racePrecisionOf(raceDataset)); },
    }, 0.7 + index * 0.08);
  });

  // 阶段 1：弹射入位，错峰即名次（配方卡 §动作阶段 1）
  tl.from(raceBoard.querySelectorAll(".row"), { x: -120, opacity: 0, duration: DUR.entrance, ease: EASE_OUT, stagger: 0.08, immediateRender: true }, 0.55);
  // 阶段 3：戴冠（配方卡 §动作阶段 3）——第一名入位后才弹
  const raceCrown = raceBoard.querySelector(".crown");
  if (raceCrown) {
    tl.fromTo(raceCrown, { scale: 0, opacity: 0 }, { scale: 1.06, opacity: 1, duration: 0.28, ease: "back.out(2)" }, 0.97);
    tl.to(raceCrown, { scale: 1, duration: 0.12, ease: "power1.out" }, 1.25);
  }
  // 阶段 4：sparkline 生长，origin: left（配方卡 §动作阶段 4）
  tl.from(raceBoard.querySelectorAll(".row-spark"), { scaleX: 0, duration: 1.2, ease: "power2.out", stagger: 0.08 }, 0.77);
}

function racePrecisionOf(dataset) {
  const p = Number(dataset.metric && dataset.metric.precision);
  return Number.isFinite(p) ? p : 0;
}
