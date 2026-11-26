// 情绪短片的氛围动效。配方卡见同目录 mood.card.md。
// 场景 DOM 由生成器编译期写入（moodBlock），脚本只做氛围：星星、天灯、光晕呼吸、歌词逐句。
// 必须排在 motion.js 之后：借它的 DUR / EASE 与同一条时间轴。
const moodActs = [...document.querySelectorAll(".mood-act")];
const moodStars = document.querySelectorAll(".mood-stars");

// 星空：每幕随机撒 60 颗，闪烁交给 ambient 的 flicker（motion.js 已统一驱动）
moodStars.forEach((group) => {
  for (let i = 0; i < 60; i++) {
    const star = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    star.setAttribute("cx", String(Math.round(Math.random() * 1080)));
    star.setAttribute("cy", String(Math.round(Math.random() * 1100)));
    star.setAttribute("r", String((Math.random() * 2.2 + 0.8).toFixed(1)));
    star.setAttribute("opacity", (Math.random() * 0.5 + 0.25).toFixed(2));
    group.appendChild(star);
  }
});

// 天灯幕：从举灯人手里放出一串天灯，缓慢上浮——整幕的一次性事件，不循环（重复即廉价）
const lanternField = document.querySelector(".mood-sky-lanterns");
if (lanternField) {
  for (let i = 0; i < 9; i++) {
    const kind = Math.random() > 0.5 ? "circle" : "ellipse";
    const lamp = document.createElementNS("http://www.w3.org/2000/svg", kind);
    const x = 120 + Math.random() * 840;
    lamp.setAttribute("cx", String(Math.round(x)));
    lamp.setAttribute("cy", String(Math.round(1650 + Math.random() * 120)));
    if (kind === "circle") {
      lamp.setAttribute("r", String((Math.random() * 10 + 9).toFixed(1)));
    } else {
      lamp.setAttribute("rx", String((Math.random() * 6 + 7).toFixed(1)));
      lamp.setAttribute("ry", String((Math.random() * 9 + 10).toFixed(1)));
    }
    lamp.setAttribute("opacity", "0");
    lanternField.appendChild(lamp);
    // 错峰起升：每盏延迟 0.6–5s，用 gsap 对 SVG 属性做 y 位移 + 淡入
    const drift = 26 + Math.random() * 30;
    tl.fromTo(lamp,
      { y: 0, opacity: 0 },
      { y: -drift * 12, opacity: 0.85, duration: 12, ease: "sine.inOut", immediateRender: false },
      27 + i * 0.55 + Math.random() * 0.5);
  }
  // 举灯人手里的那盏在放灯瞬间熄灭：光交给了天上的灯
  const raising = document.querySelector(".mood-raising .mood-lantern-halo");
  if (raising) tl.to(raising, { opacity: 0, duration: 2.5, ease: "sine.inOut" }, 27);
}

// 歌词逐句：每幕歌词在自己的窗口里淡入淡出（同一轨道的 clip 切换是硬切，谁淡入谁就在下一幕）
moodActs.forEach((act) => {
  const lyric = act.querySelector(".mood-lyric");
  if (!lyric) return;
  const start = parseFloat(act.dataset.start);
  const dur = parseFloat(act.dataset.duration);
  const inAt = start + 0.8;
  const outAt = start + dur - 1.2;
  tl.fromTo(lyric, { opacity: 0, y: 18 }, { opacity: 1, y: 0, duration: 1.4, ease: EASE_OUT, immediateRender: true }, inAt);
  if (!act.classList.contains("mood-lantern")) {
    tl.to(lyric, { opacity: 0, y: -14, duration: 0.9, ease: EASE_IN }, outAt);
  }
});

// 光晕呼吸：每幕的光斑缓慢起伏（幕是 clip，动画对子元素做，不动 section）
moodActs.forEach((act) => {
  const glow = act.querySelector(".mood-glow");
  const halo = act.querySelector(".mood-moonhalo");
  if (glow) tl.to(glow, { opacity: "+=0.18", duration: 4, ease: "sine.inOut", yoyo: true, repeat: 4 }, parseFloat(act.dataset.start));
  if (halo) tl.to(halo, { opacity: "+=0.12", duration: 5, ease: "sine.inOut", yoyo: true, repeat: 3 }, parseFloat(act.dataset.start));
});
