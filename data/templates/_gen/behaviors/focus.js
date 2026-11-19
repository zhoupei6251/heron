// 每段的画面重点来自Java解析的事实引用，时间来自真实口播。所有帧都从时间轴绝对时间重算。
const focusWindows = [...document.querySelectorAll(".scene")]
  .filter((slot) => Number(slot.dataset.sceneIndex) <= sceneCount)
  .map((slot) => ({
    start: Number(slot.dataset.start),
    end: Number(slot.dataset.start) + Number(slot.dataset.duration),
    focus: JSON.parse(declared["scene" + slot.dataset.sceneIndex + "_focus"] || "{}"),
  }));
const focusCaption = document.getElementById("focus-caption");
const focusLayer = make("g", { "aria-hidden": "true" });
svg.appendChild(focusLayer);
const factAt = (path) => path.split(".").reduce((node, key) =>
  node != null && Object.hasOwn(node, key) ? node[key] : undefined, facts);
const signed = (value) => (value > 0 ? "+" : "") + fmt(value);
const captionText = (value) => {
  const text = Array.from(String(value));
  const max = /[一-鿿]/.test(value) ? 28 : 50;
  return text.length > max ? text.slice(0, max - 1).join("") + "…" : text.join("");
};
const periodText = () => {
  const start = facts.first && facts.first.date;
  const end = facts.last && facts.last.date;
  if (!start || !end) return "同一快照";
  return start.slice(0, 4) === end.slice(0, 4)
    ? start.slice(5) + " → " + end.slice(5) : start.slice(0, 4) + " → " + end.slice(0, 4);
};
function focusValue(focus) {
  const refs = focus.refs || [];
  const labels = focus.labels || [];
  const base = facts.last || facts.leader || {};
  const result = { value: base.value, unit, detail: "", caption: dataset.metric.label || "数据全貌", signed: false };
  if (typeof facts.change === "number") {
    result.detail = (facts.change >= 0 ? "▲ " : "▼ ") + fmt(Math.abs(facts.change));
    if (typeof facts.change_pct === "number") result.detail += "（" + Math.abs(facts.change_pct).toFixed(2) + "%）";
  } else if (facts.leader) {
    result.detail = facts.leader.label || facts.leader.name || facts.leader.location || "";
  }
  if (focus.mode === "change") {
    const pct = !refs.includes("change") && refs.includes("change_pct");
    result.value = pct ? facts.change_pct : facts.change;
    result.unit = pct ? "%" : unit;
    result.detail = pct ? signed(facts.change) + " " + unit
      : typeof facts.change_pct === "number" ? signed(facts.change_pct) + "%" : "";
    result.caption = (pct ? "区间涨跌幅 · " : "区间首末变化 · ") + periodText();
    result.signed = true;
  } else if (focus.mode === "range") {
    const key = (refs.find((ref) => ref.startsWith("min.") || ref.startsWith("max.")) || "max.value").split(".")[0];
    const point = facts[key];
    if (point) {
      result.value = point.value;
      result.caption = (key === "min" ? "区间最低 · " : "区间最高 · ") + (point.date || "同一快照");
      result.detail = "";
    }
  } else if (focus.mode === "comparison") {
    result.caption = typeof facts.spread === "number" ? "首尾差距 · 同一快照" : "区间首末对比 · " + periodText();
    result.value = typeof facts.spread === "number" ? facts.spread : base.value;
    result.detail = facts.first ? "起点 " + fmt(facts.first.value) + " " + unit : "";
  } else if (focus.mode === "item" || focus.mode === "leader") {
    const entities = [...(facts.items || []), ...(facts.series || []), ...(facts.leader ? [facts.leader] : [])];
    const item = entities.find((entry) => labels.includes(entry.label || entry.name || entry.location));
    if (item) {
      const ref = refs.find((ref) => /^(items\.\d+|series\.\d+|leader)\.(value|first|last|min|max|change|change_pct)$/.test(ref)
        && typeof factAt(ref) === "number");
      const field = ref && ref.split(".").at(-1);
      result.value = ref ? factAt(ref) : typeof item.value === "number" ? item.value : item.last;
      result.unit = field === "change_pct" ? "%" : unit;
      result.signed = field === "change" || field === "change_pct";
      result.detail = "";
      const meaning = { first: "起点", last: "最新值", min: "最低", max: "最高", change: "区间变化", change_pct: "区间涨跌幅" };
      result.caption = (item.label || item.name || item.location) + " · " + (meaning[field] || "数值");
    }
  } else if (focus.mode === "latest") {
    const key = refs.some((ref) => ref.startsWith("last.")) ? "last" : "first";
    if (facts[key]) {
      result.value = facts[key].value;
      result.caption = (key === "last" ? "最新数值 · " : "区间起点 · ") + (facts[key].date || "同一快照");
    }
  }
  return result;
}
let paintedFocus = -2;
function paintEvidence(focus, index) {
  if (paintedFocus === index) return;
  paintedFocus = index;
  focusLayer.replaceChildren();
  const refs = focus.refs || [];
  let keys = refs.map((ref) => ref.split(".")[0]).filter((key) => chartEvidence.points.has(key));
  if (focus.mode === "change" || focus.mode === "comparison") keys = ["first", "last"];
  const points = [...new Set(keys)].map((key) => chartEvidence.points.get(key)).filter(Boolean);
  if (points.length === 2) {
    focusLayer.appendChild(make("path", { d: "M" + points[0].x + "," + points[0].y
      + " L" + points[1].x + "," + points[1].y, class: "focus-link" }));
  }
  points.forEach((point) => {
    focusLayer.appendChild(make("circle", { cx: point.x, cy: point.y, r: 23, class: "focus-halo" }));
    focusLayer.appendChild(make("circle", { cx: point.x, cy: point.y, r: 11, class: "focus-ring" }));
  });
  const selected = new Set(focus.labels || []);
  if (focus.mode === "comparison" && chartEvidence.rows.size) {
    const names = [...chartEvidence.rows.keys()];
    selected.add(names[0]);
    selected.add(names[names.length - 1]);
  }
  chartEvidence.rows.forEach((node, name) => {
    const active = selected.size === 0 || selected.has(name);
    node.bar.style.opacity = active ? "1" : "0.55";
    node.label.style.fontWeight = selected.has(name) ? "800" : "600";
    node.value.style.fill = active ? "var(--fg)" : "var(--muted-2)";
  });
  chartEvidence.series.forEach((node, name) => {
    const active = selected.size === 0 || selected.has(name);
    node.shape.style.filter = active ? "" : "brightness(0.55)";
    node.legend.style.fontWeight = selected.has(name) ? "800" : "600";
  });
}
function paintFocus() {
  const t = tl.time();
  const index = focusWindows.findIndex((slot) => slot.start <= t && t < slot.end);
  const focus = index >= 0 ? focusWindows[index].focus : {};
  paintEvidence(focus, index);
  let display = focusValue(focus);
  if (chartEvidence.race && chartEvidence.race.frames.length) {
    const race = chartEvidence.race;
    const frameIndex = Math.max(0, Math.min(race.frames.length - 1, Math.floor((t - race.start) / race.step)));
    const frame = race.frames[frameIndex];
    const values = new Map(frame.ranked.map((item) => [item.name, item.value]));
    chartEvidence.rows.forEach((node, name) => {
      node.value.textContent = values.has(name) ? fmt(values.get(name)) + " " + unit : "";
    });
    const leader = frame.ranked[0];
    display = { value: leader && leader.value, unit, detail: leader ? leader.name : "", signed: false,
      caption: frame.date + " · 当期榜首" };
  }
  if (Number.isFinite(display.value)) {
    // 只在片头滚动数字；之后展示本段实际事实值，避免过渡中的数值被误当作新事实。
    const progress = !chartEvidence.race ? Math.max(0, Math.min(1, (t - 0.35) / 1.2)) : 1;
    const value = t < 1.55 && !chartEvidence.race ? display.value * (1 - Math.pow(1 - progress, 3)) : display.value;
    const kpi = document.getElementById("kpi-value");
    kpi.textContent = display.signed ? signed(value) : fmt(value);
    kpi.style.color = display.signed && display.value < 0 ? "var(--down)" : "var(--accent)";
    document.getElementById("kpi-unit").textContent = display.unit;
    document.getElementById("kpi-change").textContent = display.detail;
    document.getElementById("kpi-change").style.color = "var(--muted-2)";
  }
  if (focusCaption) focusCaption.textContent = captionText(display.caption);
}
paintFocus();
const focusClock = { time: 0 };
tl.to(focusClock, { time: total, duration: total, ease: "none", onUpdate: paintFocus }, 0);
