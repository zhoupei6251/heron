// compare 屏做成真正两栏对照：左边起点、右边对照项，数字与标签都来自数据事实，不来自文案。
// 事实口径由 Java 侧 DatasetFacts 决定（wide / long / rows / geo 四种 shape），这里只取数并呈现：
//   rows、geo：首条 vs 最高
//   wide：起点 vs 最新
//   long：同一对象的起点 vs 最新；焦点指了两个对象就照焦点来
// 事实不够就不画这一栏——宁可这一屏只有文案，也不要摆一个空框。
// 这一段必须排在 motion.js 之后：要借它的 DUR / EASE 与同一条时间轴。
const compareDataset = JSON.parse(declared.dataset_json || "{}");
const compareFacts = compareDataset.facts || {};
const compareUnit = (compareDataset.metric && compareDataset.metric.unit) || "";
const comparePrecision = Number.isFinite(Number(compareDataset.metric && compareDataset.metric.precision))
  ? Number(compareDataset.metric.precision)
  : 2;
const compareNumber = (value) => Number(value).toFixed(comparePrecision);
const compareOf = (entry) => {
  if (!entry) return null;
  const value = typeof entry.value === "number" ? entry.value
    : typeof entry.last === "number" ? entry.last : NaN;
  if (!Number.isFinite(value)) return null;
  return { value, label: String(entry.label || entry.name || entry.location || entry.date || "") };
};
const comparePool = () => {
  const list = [];
  (compareFacts.series || []).forEach((entry) => list.push({ value: entry.last, label: String(entry.name || "") }));
  (compareFacts.items || []).forEach((entry) => list.push({ value: entry.value, label: String(entry.label || entry.location || "") }));
  return list;
};
const comparePair = (focus) => {
  const labels = (focus && focus.labels) || [];
  if (labels.length >= 2) {
    const pool = comparePool();
    const picked = labels.map((label) => pool.find((entry) => entry.label === label)).filter(Boolean);
    if (picked.length >= 2) {
      return { left: { ...picked[0], tag: picked[0].label }, right: { ...picked[1], tag: picked[1].label } };
    }
  }
  const items = compareFacts.items || [];
  if (items.length) {
    const first = compareOf(items[0]);
    const leader = compareOf(compareFacts.leader) || compareOf(items.reduce((best, entry) =>
      (Number(entry.value) > Number(best.value) ? entry : best), items[0]));
    if (first && leader && first.label !== leader.label) {
      return { left: { ...first, tag: "首条" }, right: { ...leader, tag: "最高" } };
    }
    // 首条就是最高时这组对照塌了，退到最高 vs 最低：对照屏总要有个对照。
    if (leader) {
      const lowest = compareOf(items.reduce((best, entry) =>
        (Number(entry.value) < Number(best.value) ? entry : best), items[0]));
      if (lowest && lowest.label !== leader.label) {
        return { left: { ...leader, tag: "最高" }, right: { ...lowest, tag: "最低" } };
      }
    }
  }
  if (compareFacts.first && compareFacts.last) {
    const left = compareOf(compareFacts.first);
    const right = compareOf(compareFacts.last);
    if (left && right && left.label !== right.label) {
      return { left: { ...left, tag: "起点" }, right: { ...right, tag: "最新" } };
    }
  }
  const series = compareFacts.series || [];
  if (series.length) {
    const leaderName = compareFacts.leader && compareFacts.leader.name;
    const entry = series.find((item) => item.name === leaderName) || series[0];
    const left = { value: entry.first, label: String(entry.name || ""), tag: "起点" };
    const right = { value: entry.last, label: String(entry.name || ""), tag: "最新" };
    if (Number.isFinite(left.value) && Number.isFinite(right.value)) return { left, right };
  }
  return null;
};
// 字号按这一栏实际能放下的宽度降档，不按字数猜：各风格栏宽与字号都不同。
const compareTextWidth = (text, px) => Array.from(String(text)).reduce((sum, ch) =>
  sum + (/[一-鿿]/.test(ch) ? px : (/[0-9]/.test(ch) ? px * 0.62 : px * 0.42)), 0);
// 单位跟在数字右侧同一行（.compare-number 是 flex），量宽度时漏掉它，
// 「4113.34」会刚好占满整栏，把 USD/oz 顶到中间列去压住差值。按单位自身字号量。
const compareNumberWidth = (text, px, unit) => {
  if (!unit) return compareTextWidth(text, px);
  const unitPx = parseFloat(cssVar("--fs-body")) || px;
  return compareTextWidth(text, px) + parseFloat(cssVar("--fs-note") || 0) + compareTextWidth(unit, unitPx);
};
const compareFitSize = (text, width) => {
  const ladder = ["--fs-title-sm", "--fs-lead", "--fs-body"];
  for (const token of ladder) {
    const px = parseFloat(cssVar(token));
    if (Number.isFinite(px) && compareNumberWidth(text, px, compareUnit) <= width) return token;
  }
  return "--fs-body";
};
const compareClip = (text, max) => {
  const list = Array.from(String(text));
  return list.length > max ? list.slice(0, max - 1).join("") + "…" : String(text);
};
const compareElement = (name, className, text) => {
  const node = document.createElement(name);
  node.className = className;
  node.textContent = text;
  return node;
};
document.querySelectorAll(".scene").forEach((slot) => {
  if (Number(slot.dataset.sceneIndex) > sceneCount) return;
  if (slot.dataset.sceneType !== "compare") return;
  const focus = JSON.parse(declared["scene" + slot.dataset.sceneIndex + "_focus"] || "{}");
  const pair = comparePair(focus);
  if (!pair) return;
  const leftText = compareNumber(pair.left.value);
  const rightText = compareNumber(pair.right.value);
  const style = getComputedStyle(slot);
  const inner = slot.clientWidth - parseFloat(style.paddingLeft || 0) - parseFloat(style.paddingRight || 0);
  const column = Math.max(120, (inner - 40 - 72) / 2);
  const size = compareFitSize(leftText.length >= rightText.length ? leftText : rightText, column);
  const pairs = compareElement("div", "compare-pairs", "");
  const left = compareElement("div", "compare-col", "");
  left.appendChild(compareElement("span", "compare-tag", pair.left.tag));
  const leftNumber = compareElement("span", "compare-number", "");
  const leftValue = compareElement("b", "compare-value", leftText);
  leftValue.style.fontSize = cssVar(size);
  leftNumber.appendChild(leftValue);
  if (compareUnit) leftNumber.appendChild(compareElement("i", "compare-unit", compareUnit));
  left.appendChild(leftNumber);
  left.appendChild(compareElement("span", "compare-label", compareClip(pair.left.label, 22)));
  const middle = compareElement("div", "compare-vs", "");
  middle.appendChild(compareElement("span", "compare-vs-mark", "vs"));
  const delta = Math.abs(pair.right.value - pair.left.value);
  if (Number.isFinite(delta)) {
    middle.appendChild(compareElement("span", "compare-delta", "差 " + compareNumber(delta)));
  }
  const right = compareElement("div", "compare-col", "");
  right.appendChild(compareElement("span", "compare-tag", pair.right.tag));
  const rightNumber = compareElement("span", "compare-number", "");
  const rightValue = compareElement("b", "compare-value", rightText);
  rightValue.style.fontSize = cssVar(size);
  rightNumber.appendChild(rightValue);
  if (compareUnit) rightNumber.appendChild(compareElement("i", "compare-unit", compareUnit));
  right.appendChild(rightNumber);
  right.appendChild(compareElement("span", "compare-label", compareClip(pair.right.label, 22)));
  pairs.append(left, middle, right);
  slot.appendChild(pairs);
  slot.classList.add("has-compare");
  const start = Number(slot.dataset.start);
  const outAt = Math.max(start + DUR.entrance, start + Number(slot.dataset.duration) - DUR.swap);
  tl.from(pairs, { y: 26, opacity: 0, duration: DUR.entrance, ease: EASE_OUT, immediateRender: true }, start);
  tl.to(pairs, { y: -14, opacity: 0, duration: DUR.swap, ease: EASE_IN }, outAt);
});
