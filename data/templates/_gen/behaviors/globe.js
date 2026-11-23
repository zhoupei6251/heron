// 三维地球：geo 数据（location, latitude, longitude, value）画成可旋转的地球与高度柱。
// 画面只负责空间分布；数字与结论在旁白与正文里，不在画面上生成。
// WebGL 不可用时退回 2D 投影（同一组数据、同一套令牌），画面不会空。
(() => {
  const stage = document.getElementById("chart");
  const fallback = document.getElementById("globe-fallback");
  const canvas = document.getElementById("globe-canvas");
  const legend = document.getElementById("globe-legend");
  if (!stage || !fallback) return;

  const dataset = parse(declared.dataset_json);
  const points = geoPoints(dataset);
  const geo = dataset.shape === "geo" && points.length > 0;
  const precision = number(dataset.metric && dataset.metric.precision, 0);
  const unit = (dataset.metric && dataset.metric.unit) || "";

  fillKpi(dataset, points, precision, unit);
  const ctx = fallback.getContext("2d");
  const W = fallback.width || 1080;
  const H = fallback.height || 520;

  if (!geo) {
    placeholder(ctx, W, H);
    if (legend) legend.innerHTML = "";
    return;
  }

  const sorted = points.slice().sort((a, b) => b.value - a.value);
  drawLegend(legend, sorted.slice(0, 3), precision);
  if (legend) {
    tl.from(legend.querySelectorAll("li"), {
      y: 18, opacity: 0, duration: 0.5, stagger: 0.08, ease: "power3.out", immediateRender: true,
    }, 0.5);
  }

  const state = { angle: initialAngle(sorted[0]) };
  const renderThree = initThree(points, state, canvas, stage);
  // 抓帧只认 GSAP 时间线：用 onUpdate 驱动绘制，不用墙钟计时——
  // 墙钟与时间线不同步，抽帧会闪、会漏。
  const draw = () => {
    if (renderThree) renderThree(state.angle);
    else if (ctx) drawFallback(ctx, W, H, points, state.angle);
  };
  draw();
  tl.to(state, { angle: state.angle + 0.6, duration: total, ease: "none", onUpdate: draw }, 0);

  // ---- 数据与文案 ----

  function parse(raw) {
    try {
      return JSON.parse(raw || "{}") || {};
    } catch (e) {
      return {};
    }
  }

  function number(value, fallbackValue) {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallbackValue;
  }

  function geoPoints(source) {
    const rows = Array.isArray(source.rows) ? source.rows : [];
    return rows
      .filter((row) => Array.isArray(row) && row.length >= 4)
      .map((row) => ({ label: String(row[0]), lat: Number(row[1]), lon: Number(row[2]), value: Number(row[3]) }))
      .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon) && Number.isFinite(p.value));
  }

  // 与 chart.js 的 KPI 同口径：geo 取最大值那条，没有经纬度就退回 facts 里的最后/最高值
  function fillKpi(source, list, digits, unitText) {
    const valueEl = document.getElementById("kpi-value");
    if (!valueEl) return;
    const unitEl = document.getElementById("kpi-unit");
    const changeEl = document.getElementById("kpi-change");
    const facts = source.facts || {};
    let value = null;
    let label = "";
    if (list.length) {
      const top = list.reduce((a, b) => (b.value > a.value ? b : a), list[0]);
      value = top.value;
      label = top.label;
    } else if (facts.leader && typeof facts.leader.value === "number") {
      value = facts.leader.value;
      label = facts.leader.label || facts.leader.location || "";
    } else if (facts.last && typeof facts.last.value === "number") {
      value = facts.last.value;
    }
    if (value === null) return;
    valueEl.textContent = Number(value).toFixed(digits);
    if (unitEl) unitEl.textContent = unitText;
    if (changeEl) changeEl.textContent = label;
  }

  function drawLegend(node, top, digits) {
    if (!node) return;
    node.innerHTML = top.map((p) => (
      "<li><span class=\"globe-legend-name\">" + escape(p.label) + "</span>"
      + "<span class=\"globe-legend-value\">" + p.value.toFixed(digits) + "</span></li>"
    )).join("");
  }

  function escape(text) {
    return String(text).replace(/[&<>"']/g, (ch) => (
      { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]
    ));
  }

  // ---- 2D 兜底：正交投影，只画朝向镜头的一面 ----

  function drawFallback(context, width, height, list, angle) {
    context.clearRect(0, 0, width, height);
    const R = Math.min(width * 0.28, height * 0.44);
    const cx = width / 2;
    const cy = height / 2;
    const accent = cssVar("--accent") || "#18b7c9";
    const muted = cssVar("--muted") || "#8aa0a8";
    const fg = cssVar("--fg") || "#ffffff";
    const disc = context.createRadialGradient(cx, cy - R * 0.3, R * 0.1, cx, cy, R);
    disc.addColorStop(0, rgba(accent, 0.3));
    disc.addColorStop(1, rgba(accent, 0.02));
    context.fillStyle = disc;
    context.beginPath();
    context.arc(cx, cy, R, 0, Math.PI * 2);
    context.fill();

    context.strokeStyle = rgba(muted, 0.35);
    context.lineWidth = 1.2;
    for (let lat = -60; lat <= 60; lat += 30) {
      strokeArc(context, sample((t) => project(lat, t, angle), -180, 180, 90), cx, cy, R);
    }
    for (let lon = -180; lon < 180; lon += 30) {
      strokeArc(context, sample((t) => project(t, lon, angle), -90, 90, 60), cx, cy, R);
    }
    context.strokeStyle = rgba(accent, 0.5);
    context.lineWidth = 2;
    context.beginPath();
    context.arc(cx, cy, R, 0, Math.PI * 2);
    context.stroke();

    const max = Math.max(...list.map((p) => p.value));
    const min = Math.min(...list.map((p) => p.value));
    const span = max - min || 1;
    const leader = list.reduce((a, b) => (b.value > a.value ? b : a), list[0]);
    list.forEach((p) => {
      const point = project(p.lat, p.lon, angle);
      if (point.z <= 0.04) return;
      const norm = (p.value - min) / span;
      const x = cx + point.x * R;
      const y = cy - point.y * R;
      const len = 16 + norm * 54;
      const dx = point.x / (Math.hypot(point.x, point.y) || 1);
      const dy = -point.y / (Math.hypot(point.x, point.y) || 1);
      context.strokeStyle = rgba(accent, 0.75);
      context.lineWidth = 2.4;
      context.beginPath();
      context.moveTo(x, y);
      context.lineTo(x + dx * len, y + dy * len);
      context.stroke();
      context.fillStyle = p === leader ? fg : accent;
      context.beginPath();
      context.arc(x + dx * len, y + dy * len, 4 + norm * 6, 0, Math.PI * 2);
      context.fill();
      if (p === leader) {
        context.strokeStyle = rgba(accent, 0.9);
        context.lineWidth = 2;
        context.beginPath();
        context.arc(x + dx * len, y + dy * len, 12 + norm * 6, 0, Math.PI * 2);
        context.stroke();
      }
    });
  }

  function sample(pointAt, from, to, steps) {
    const out = [];
    for (let i = 0; i <= steps; i++) out.push(pointAt(from + ((to - from) * i) / steps));
    return out;
  }

  function strokeArc(context, list, cx, cy, R) {
    let open = false;
    context.beginPath();
    list.forEach((point) => {
      if (point.z <= 0.04) {
        open = false;
        return;
      }
      const x = cx + point.x * R;
      const y = cy - point.y * R;
      if (!open) {
        context.moveTo(x, y);
        open = true;
      } else {
        context.lineTo(x, y);
      }
    });
    context.stroke();
  }

  function project(lat, lon, angle) {
    const phi = ((90 - lat) * Math.PI) / 180;
    const theta = ((lon + 180) * Math.PI) / 180 + angle;
    return {
      x: -Math.sin(phi) * Math.cos(theta),
      y: Math.cos(phi),
      z: Math.sin(phi) * Math.sin(theta),
    };
  }

  function initialAngle(leader) {
    return Math.PI / 2 - ((leader.lon + 180) * Math.PI) / 180;
  }

  function rgba(color, alpha) {
    const hex = /^#([0-9a-f]{6})$/i.exec(String(color).trim());
    if (!hex) return color;
    const value = parseInt(hex[1], 16);
    return "rgba(" + ((value >> 16) & 255) + "," + ((value >> 8) & 255) + "," + (value & 255) + "," + alpha + ")";
  }

  // 非 geo 数据（人工误选这套版式）画一句说明，不拿别的数据硬凑三维
  function placeholder(context, width, height) {
    const accent = cssVar("--accent") || "#18b7c9";
    const muted = cssVar("--muted") || "#8aa0a8";
    context.clearRect(0, 0, width, height);
    context.strokeStyle = rgba(muted, 0.5);
    context.lineWidth = 2;
    context.beginPath();
    context.arc(width / 2, height / 2, Math.min(width, height) * 0.3, 0, Math.PI * 2);
    context.stroke();
    context.fillStyle = accent;
    context.font = "600 40px 'Noto Sans SC', sans-serif";
    context.textAlign = "center";
    context.fillText("此版式用于空间数据", width / 2, height / 2 + 14);
  }

  // ---- Three.js：WebGL 可用时接管，失败就保留 2D 兜底 ----

  function initThree(list, state, target, host) {
    if (!window.THREE || !target) return null;
    let renderer;
    try {
      renderer = new THREE.WebGLRenderer({ canvas: target, antialias: true, alpha: true, preserveDrawingBuffer: true });
    } catch (e) {
      return null;
    }
    if (!renderer) return null;
    renderer.setPixelRatio(1);
    renderer.setSize(target.width, target.height, false);
    renderer.setClearColor(0x000000, 0);

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(32, target.width / target.height, 0.1, 100);
    camera.position.set(0, 0, 3.6);
    camera.lookAt(0, 0, 0);
    const group = new THREE.Group();
    scene.add(group);

    const accent = colorOf("--accent", 0x18b7c9);
    const muted = colorOf("--muted", 0x8aa0a8);
    const fg = colorOf("--fg", 0xffffff);

    group.add(new THREE.Points(dots(), new THREE.PointsMaterial({
      color: muted, size: 0.014, transparent: true, opacity: 0.7, sizeAttenuation: true,
    })));
    // 极淡的经纬线给球体一点结构，纯点阵会显得像平面散点
    group.add(new THREE.LineSegments(new THREE.WireframeGeometry(new THREE.SphereGeometry(1.001, 24, 16)),
      new THREE.LineBasicMaterial({ color: muted, transparent: true, opacity: 0.08 })));

    const max = Math.max(...list.map((p) => p.value));
    const min = Math.min(...list.map((p) => p.value));
    const span = max - min || 1;
    const leader = list.reduce((a, b) => (b.value > a.value ? b : a), list[0]);
    list.forEach((p) => {
      const dir = vector(p.lat, p.lon);
      const norm = (p.value - min) / span;
      const tip = dir.clone().multiplyScalar(1.05 + norm * 0.35);
      const line = new THREE.BufferGeometry().setFromPoints([dir.clone().multiplyScalar(1.005), tip]);
      group.add(new THREE.Line(line, new THREE.LineBasicMaterial({
        color: accent, transparent: true, opacity: 0.6,
      })));
      const dot = new THREE.Mesh(new THREE.SphereGeometry(0.018 + norm * 0.016, 12, 12),
        new THREE.MeshBasicMaterial({ color: p === leader ? fg : accent }));
      dot.position.copy(tip);
      group.add(dot);
      if (p === leader) {
        const ring = new THREE.Mesh(new THREE.RingGeometry(0.045, 0.058, 36),
          new THREE.MeshBasicMaterial({ color: accent, side: THREE.DoubleSide, transparent: true, opacity: 0.9 }));
        ring.position.copy(dir.clone().multiplyScalar(1.06));
        ring.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), dir);
        group.add(ring);
      }
    });
    group.add(new THREE.Mesh(new THREE.SphereGeometry(1.03, 32, 24), new THREE.MeshBasicMaterial({
      color: accent, transparent: true, opacity: 0.08, side: THREE.BackSide,
    })));

    group.rotation.y = state.angle;
    renderer.render(scene, camera);
    host.classList.add("is-webgl");
    return (angle) => {
      group.rotation.y = angle;
      renderer.render(scene, camera);
    };
  }

  function dots() {
    const count = 900;
    const positions = new Float32Array(count * 3);
    for (let i = 0; i < count; i++) {
      const y = 1 - (i / (count - 1)) * 2;
      const r = Math.sqrt(Math.max(0, 1 - y * y));
      const theta = i * 2.399963229728653;
      positions[i * 3] = Math.cos(theta) * r;
      positions[i * 3 + 1] = y;
      positions[i * 3 + 2] = Math.sin(theta) * r;
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
    return geometry;
  }

  function vector(lat, lon) {
    const phi = ((90 - lat) * Math.PI) / 180;
    const theta = ((lon + 180) * Math.PI) / 180;
    return new THREE.Vector3(-Math.sin(phi) * Math.cos(theta), Math.cos(phi), Math.sin(phi) * Math.sin(theta));
  }

  function colorOf(name, fallbackValue) {
    const raw = String(cssVar(name) || "").trim();
    const hex = /^#([0-9a-f]{6})$/i.exec(raw);
    if (hex) return new THREE.Color(parseInt(hex[1], 16));
    const short = /^#([0-9a-f]{3})$/i.exec(raw);
    if (short) {
      const h = short[1];
      return new THREE.Color(parseInt(h[0] + h[0] + h[1] + h[1] + h[2] + h[2], 16));
    }
    try {
      return new THREE.Color(raw);
    } catch (e) {
      return new THREE.Color(fallbackValue);
    }
  }
})();
