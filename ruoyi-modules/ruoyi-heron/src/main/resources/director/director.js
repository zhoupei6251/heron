(() => {
  const data = JSON.parse(document.getElementById("dataset").textContent);
  const tl = gsap.timeline({ paused: true });
  const svg = (name, attrs, text) => {
    const node = document.createElementNS("http://www.w3.org/2000/svg", name);
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const trim = (text, length) => {
    const chars = [...String(text ?? "")];
    return chars.length <= length ? chars.join("") : chars.slice(0, length - 1).join("") + "…";
  };
  const number = value => Number(value).toFixed(data.metric.precision);

  function chart(figure, at, active) {
    const label = document.createElement("figcaption");
    label.className = "chart-label";
    const plot = svg("svg", { viewBox: "0 0 860 420", role: "img" });
    figure.append(label, plot);
    const unit = data.metric.unit || "";
    const metric = data.metric.label || data.metric.name || data.metric.key;
    const rows = data.rows;
    if (data.shape === "geo") {
      label.textContent = trim(metric + " · " + rows.length + " 个记录点", 32);
      plot.setAttribute("aria-label", label.textContent);
      plot.append(svg("circle", { cx: 430, cy: 210, r: 184, fill: "none", class: "chart-grid" }));
      for (const ratio of [0.35, 0.68]) {
        plot.append(svg("ellipse", { cx: 430, cy: 210, rx: 184 * ratio, ry: 184, fill: "none", class: "chart-grid" }));
        plot.append(svg("ellipse", { cx: 430, cy: 210, rx: 184, ry: 184 * ratio, fill: "none", class: "chart-grid" }));
      }
      const peak = rows.reduce((a, b) => b[3] > a[3] ? b : a);
      const points = rows.map(row => {
        const dot = svg("circle", { r: row === peak ? 10 : 5, class: "chart-point" });
        dot.append(svg("title", {}, row[0] + " " + number(row[3]) + unit));
        plot.append(dot);
        return [dot, row[1] * Math.PI / 180, row[2] * Math.PI / 180];
      });
      const globe = { angle: peak[2] * Math.PI / 180 - .5 };
      const draw = () => {
        for (const [dot, lat, lon] of points) {
          const depth = Math.cos(lat) * Math.cos(lon - globe.angle);
          dot.setAttribute("cx", 430 + 184 * Math.cos(lat) * Math.sin(lon - globe.angle));
          dot.setAttribute("cy", 210 - 184 * Math.sin(lat));
          dot.setAttribute("opacity", depth < 0 ? 0 : .5 + depth * .5);
        }
      };
      draw();
      tl.to(globe, { angle: globe.angle + .8, duration: active, ease: "sine.inOut", onUpdate: draw }, at);
      return;
    }
    if (data.shape === "wide" || data.shape === "long") {
      const firstSeries = data.shape === "long" ? rows.at(-1)[1] : "";
      const series = firstSeries ? rows.filter(row => row[1] === firstSeries).map(row => [row[0], row[2]]) : rows;
      label.textContent = trim([metric, unit, firstSeries].filter(Boolean).join(" · "), 32);
      plot.setAttribute("aria-label", label.textContent);
      const vals = series.map(row => row[1]);
      const low = Math.min(...vals);
      const high = Math.max(...vals);
      const points = series.map((row, index) => [60 + 740 * index / Math.max(series.length - 1, 1),
        low === high ? 190 : 320 - 220 * (row[1] - low) / (high - low)]);
      plot.append(svg("path", { d: "M60 342H800", class: "chart-grid" }));
      if (points.length > 1) {
        const line = svg("polyline", { points: points.map(p => p.join(",")).join(" "), class: "chart-line", pathLength: 1 });
        plot.append(line);
        tl.fromTo(line, { attr: { "stroke-dasharray": "1 1", "stroke-dashoffset": 1 } },
          { attr: { "stroke-dashoffset": 0 }, duration: Math.min(active * .7, 2.1), ease: "power2.out" }, at);
      }
      const [x, y] = points.at(-1);
      const point = svg("circle", { cx: x, cy: y, r: 9, class: "chart-point" });
      plot.append(point, svg("text", { x, y: y - 21, "text-anchor": x > 430 ? "end" : "start" }, number(vals.at(-1))),
        svg("text", { x: 60, y: 391 }, series[0][0]));
      if (series.length > 1) plot.append(svg("text", { x: 800, y: 391, "text-anchor": "end" }, series.at(-1)[0]));
      return;
    }
    const ranked = rows.slice().sort((a, b) => b[1] - a[1]).slice(0, 5);
    label.textContent = trim([metric, unit, "前 " + ranked.length + " 项"].filter(Boolean).join(" · "), 32);
    plot.setAttribute("aria-label", label.textContent);
    const low = Math.min(0, ...ranked.map(row => row[1]));
    const high = Math.max(0, ...ranked.map(row => row[1]));
    const scale = value => 320 + 360 * (value - low) / (high - low || 1);
    const zero = scale(0);
    plot.append(svg("path", { d: `M${zero} 10V404`, class: "chart-grid" }));
    ranked.forEach(([name, value], i) => {
      const y = 35 + 75 * i;
      const bar = svg("rect", { x: zero, y: y - 20, width: 0, height: 33, rx: 4, class: "chart-bar" });
      plot.append(svg("text", { x: 0, y: y + 5 }, trim(name, 10)), bar,
        svg("text", { x: 858, y: y + 5, "text-anchor": "end" }, number(value)));
      tl.to(bar, { attr: { x: Math.min(zero, scale(value)), width: Math.abs(scale(value) - zero) },
        duration: Math.min(active * .6, 1.2), ease: "power2.out" }, at + i * Math.min(active * .06, .1));
    });
  }

  for (const shot of document.querySelectorAll(".shot")) {
    const start = Number(shot.dataset.start);
    const duration = Number(shot.dataset.duration);
    const active = Math.max(duration * .45, duration - 1);
    const early = Math.min(.16, active * .16);
    const headline = shot.querySelector(".headline");
    const field = shot.querySelector(".field");
    const rule = shot.querySelector(".rule");
    const photo = shot.querySelector(".photo");
    const figure = shot.querySelector(".chart-body");
    const isHook = shot.classList.contains("hook");
    const detail = shot.classList.contains("detail");
    tl.fromTo(headline, isHook ? { scale: .9, y: 24, opacity: .68 }
      : { x: detail ? 30 : -26, opacity: .42 }, { x: 0, y: 0, scale: 1, opacity: 1,
      ease: isHook ? "power4.out" : "power2.out", duration: Math.min(isHook ? .34 : .55, active * .72), immediateRender: false }, start);
    tl.fromTo(rule, { scaleX: .08 }, { scaleX: 1, duration: Math.min(.65, active * .78), ease: "power3.out", immediateRender: false }, start + early);
    tl.fromTo(field, { x: detail ? 48 : -38, y: 26, scale: .99 }, { x: 0, y: -10, scale: 1.07,
      ease: "sine.inOut", duration: active, immediateRender: false }, start);
    if (photo) tl.fromTo(photo, { scale: 1 }, { scale: 1.08, ease: "sine.inOut", duration: active, immediateRender: false }, start);
    if (figure) chart(figure, start + early, active - early);
  }
  window.__timelines = window.__timelines || {};
  window.__timelines["heron-film"] = tl;
})();
