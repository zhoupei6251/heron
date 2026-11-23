#!/usr/bin/env node
// 模板生成器：把 template.config.json 展开成可渲染的单文件 index.html。
// 产物仍是自包含 HTML，渲染链路与哈希闸不变。
//
//   node data/tools/build-templates.mjs                 生成全部模板
//   node data/tools/build-templates.mjs --template <key> 只生成一个
//   node data/tools/build-templates.mjs --check         只比对，不写盘
//   node data/tools/build-templates.mjs --verify        生成后跑 hyperframes check

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const TEMPLATES = path.resolve(import.meta.dirname, '../templates');
const GEN = path.join(TEMPLATES, '_gen');
const SHARED = path.join(TEMPLATES, '_shared');
const LAYOUTS = path.join(TEMPLATES, 'layouts');
// 字体只存一份，模板目录不再各带一份：渲染前由 NewsProductionPipeline.copySharedFont 注入，
// 手动渲染用 `--stage` 生成同样结构的暂存目录。见 docs/video-visual-upgrade-report.md
const SHARED_FONT = path.resolve(TEMPLATES, '..', 'fonts', 'NotoSansSC-Regular.otf');
const FONT_NAME = 'NotoSansSC-Regular.otf';

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const value = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};

const CHECK_ONLY = flag('--check');
const VERIFY = flag('--verify');
const ONLY = value('--template');
const STAGE = value('--stage');
const STAGE_OUT = value('--out');

const CAPABILITIES = new Set(['chart', 'image', 'globe']);
// globe 模板自带的渲染库：与 gsap 一样随模板一起提交，渲染时不需要联网。
const THREE_SOURCE = path.resolve(import.meta.dirname, 'node_modules', 'three', 'build', 'three.min.js');
// 版式自带的静态装饰节点。迁移前它们写在各模板的 HTML 里，迁移后由配置声明，
// 否则 layouts/article 里的 #plate / #rail / #tick 规则会指向不存在的元素。
const DECOR = {
  plate: '<div id="plate"></div>',
  rail: '<div id="rail"></div>',
  tick: '<div id="tick"></div>',
};

class BuildError extends Error {}

function fail(message) {
  throw new BuildError(message);
}

const read = (file) => fs.readFileSync(file, 'utf8');
const sha256 = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

const escapeHtml = (text) =>
  String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

// 单引号属性里放 JSON：只需处理 & 和 '，HTML 解析后会还原成原字符
const escapeAttr = (json) => json.replace(/&/g, '&amp;').replace(/'/g, '&#39;');

const num = (n) => (Number.isInteger(n) ? String(n) : String(Number(n.toFixed(3))));

function loadConfig(dir) {
  const file = path.join(dir, 'template.config.json');
  if (!fs.existsSync(file)) fail(`${path.basename(dir)}: 缺少 template.config.json`);
  let cfg;
  try {
    cfg = JSON.parse(read(file));
  } catch (e) {
    fail(`${path.basename(dir)}: template.config.json 解析失败 — ${e.message}`);
  }
  const key = path.basename(dir);
  const required = ['key', 'name', 'line', 'style', 'layout', 'width', 'height', 'duration', 'scenes', 'capabilities'];
  for (const field of required) {
    if (cfg[field] === undefined) fail(`${key}: 配置缺少字段 ${field}`);
  }
  if (cfg.key !== key) fail(`${key}: 配置里的 key「${cfg.key}」与目录名不一致`);
  for (const cap of cfg.capabilities) {
    if (!CAPABILITIES.has(cap)) fail(`${key}: 未知能力「${cap}」，合法值：${[...CAPABILITIES].join('、')}`);
  }
  if (cfg.visualMode !== undefined && !['2d', '3d'].includes(cfg.visualMode)) {
    fail(`${key}: visualMode 只能是 2d 或 3d，收到：${cfg.visualMode}`);
  }
  if (cfg.visualMode === '3d' && !cfg.capabilities.includes('globe')) {
    fail(`${key}: 3D 模板必须声明 globe 能力——没有渲染库的三维模板渲不出来`);
  }
  cfg.activeScenes ??= cfg.scenes;
  cfg.defaults ??= {};
  // 声明了组件能力的模板才进分镜：场景类型、叙事节点与上屏文字一起进出，
  // 一半有、一半没有的模板会在 --strict-variables 上被卡住。
  const storyboard = (cfg.supportedSceneTypes ?? []).length > 0;
  if (cfg.activeScenes > cfg.scenes) fail(`${key}: activeScenes(${cfg.activeScenes}) 大于 scenes(${cfg.scenes})`);
  return cfg;
}

function variables(cfg) {
  const d = cfg.defaults;
  const scenes = d.scenes ?? [];
  const out = [
    { id: 'title', type: 'string', label: '标题', maxLength: 15, default: d.title ?? '' },
    { id: 'kicker', type: 'string', label: '栏目标签', maxLength: 12, default: d.kicker ?? '' },
  ];
  for (let i = 1; i <= cfg.scenes; i++) {
    const [lead = '', body = ''] = scenes[i - 1] ?? [];
    out.push({ id: `scene${i}_lead`, type: 'string', label: `第${i}屏领句`, maxLength: 40, default: lead });
    out.push({ id: `scene${i}_body`, type: 'string', label: `第${i}屏正文`, maxLength: 600, default: body });
  }
  out.push({ id: 'closing', type: 'string', label: '结尾口播', maxLength: 200, default: d.closing ?? '' });
  out.push({ id: 'source_name', type: 'string', label: '来源名称', maxLength: 80, default: d.sourceName ?? '' });
  out.push({ id: 'accent', type: 'color', label: '品牌色', default: d.accent ?? '#18b7c9' });
  out.push({
    id: 'scene_count', type: 'number', label: '场景数',
    min: 3, max: cfg.scenes, default: Math.min(cfg.activeScenes, cfg.scenes),
  });
  if (cfg.capabilities.includes('chart') || cfg.capabilities.includes('globe')) {
    const globe = cfg.capabilities.includes('globe');
    out.push({
      id: 'chart', type: 'enum', label: globe ? '三维画面' : '图表模块',
      default: d.chart ?? (globe ? 'globe' : 'line'),
      options: globe
        ? [{ value: 'globe', label: '三维地球' }]
        : [
          { value: 'line', label: '折线' },
          { value: 'bars', label: '排行' },
          { value: 'race', label: '赛跑' },
          { value: 'stacked', label: '堆叠' },
          { value: 'multiline', label: '多线' },
          { value: 'radar', label: '雷达' },
        ],
    });
    out.push({
      id: 'dataset_json', type: 'string', label: '数据快照',
      default: JSON.stringify(d.dataset ?? {}),
    });
    for (let i = 1; i <= cfg.scenes; i++) {
      out.push({ id: `scene${i}_focus`, type: 'string', label: `第${i}屏画面依据`, default: '{}' });
    }
  }
  for (let i = 1; i <= cfg.scenes; i++) {
    out.push({ id: `scene${i}_type`, type: 'string', label: '场景类型', default: '' });
  }
  if (cfg.storyboard) {
    // 分镜另两件：叙事节点给版式一条辅线，上屏文字与旁白解耦。
    // 与 sceneN_focus 一样不绑到元素上，由 fill.js 从 declared 里读。
    for (let i = 1; i <= cfg.scenes; i++) {
      out.push({ id: `scene${i}_beat`, type: 'string', label: '叙事节点', default: '' });
      out.push({ id: `scene${i}_text`, type: 'string', label: '上屏文字', default: '' });
    }
  }
  if (cfg.capabilities.includes('image')) {
    for (let i = 1; i <= cfg.scenes; i++) {
      out.push({ id: `scene${i}_image`, type: 'string', label: '第' + i + '屏配图', default: '' });
    }
  }
  return (cfg.supportedSceneTypes ?? []).length ? out : out.filter((v) => !v.id.endsWith('_type'));
}

/** 片尾留给结尾口播的时间窗。和最后一屏共用窗口会让两块文字互相遮挡。 */
const closingTail = (slot) => Math.min(6, Math.max(3, Math.round(slot * 0.5)));

function sceneBlocks(cfg) {
  const slot = cfg.duration / cfg.activeScenes;
  const tail = closingTail(slot);
  const defaults = cfg.defaults.scenes ?? [];
  const blocks = [];
  for (let i = 1; i <= cfg.scenes; i++) {
    const active = i <= cfg.activeScenes;
    const start = active ? (i - 1) * slot : cfg.duration;
    // 最后一屏让出尾段给口播，两段窗口不重叠
    const dur = active ? (i === cfg.activeScenes ? slot - tail : slot) : 0.01;
    const [lead = '', body = ''] = defaults[i - 1] ?? [];
    const figure = cfg.capabilities.includes('image')
      ? `\n        <figure class="scene-figure" data-image-slot="scene${i}_image"></figure>`
      : '';
    blocks.push(
      `      <section id="scene-${i}" class="clip scene" data-start="${num(start)}" data-duration="${num(dur)}" data-track-index="2" data-scene-index="${i}">
        <span class="scene-no">${String(i).padStart(2, '0')}</span>${figure}
        <p class="lead" data-var-text="scene${i}_lead">${escapeHtml(lead)}</p>
        <p class="body" data-var-text="scene${i}_body">${escapeHtml(body)}</p>
      </section>`
    );
  }
  return blocks.join('\n');
}

/** 环境层可以按模板关掉：文稿线 12 套版式原本没有这层，硬加等于改画面。 */
function ambientBlock(cfg) {
  if (cfg.ambient === false) return '';
  return `
      <div class="ambient" aria-hidden="true">
        <div class="ambient-grid" data-ambient="drift"></div>
        <div class="ambient-glow" data-ambient="pulse"></div>
        <div class="ambient-grain" data-ambient="flicker"></div>
      </div>
`;
}

/** 版式自带的装饰元素，按 template.config.json 的 decor 声明顺序展开。 */
function decorBlock(cfg) {
  const list = cfg.decor ?? [];
  if (!list.length) return '';
  const nodes = list.map((name) => {
    if (!(name in DECOR)) fail(`${cfg.key}: 未知装饰「${name}」，合法值：${Object.keys(DECOR).join('、')}`);
    return `      ${DECOR[name]}`;
  });
  return '\n' + nodes.join('\n') + '\n';
}

function chartSlot(cfg) {
  if (cfg.capabilities.includes('globe')) {
    // 两层画布：底层是 2D 兜底（WebGL 不可用时仍有画面），上层 WebGL 就绪后接管。
    // 同一个 canvas 先取 2D 上下文就再也拿不到 WebGL，所以必须分开。
    return `
      <section id="chart-slot" class="clip" data-start="0" data-duration="${num(cfg.duration)}" data-track-index="1">
        <div id="chart" class="globe-stage" role="img" aria-label="三维地球数据图">
          <canvas id="globe-fallback" width="1080" height="520" aria-hidden="true"></canvas>
          <canvas id="globe-canvas" width="1080" height="520" aria-hidden="true"></canvas>
        </div>
        <ul id="globe-legend"></ul>
      </section>
`;
  }
  if (!cfg.capabilities.includes('chart')) return '';
  return `
      <section id="chart-slot" class="clip" data-start="0" data-duration="${num(cfg.duration)}" data-track-index="1">
        <svg id="chart" viewBox="0 0 912 760" role="img" aria-label="数据图表"></svg>
      </section>
`;
}

function kpiBlock(cfg) {
  if (!cfg.capabilities.includes('chart') && !cfg.capabilities.includes('globe')) return '';
  return `        <div id="kpi">
          <span id="kpi-value"></span>
          <span id="kpi-unit"></span>
           <span id="kpi-change"></span>
         </div>
         <p id="focus-caption"></p>`;
}

function styles(cfg) {
  const files = [
    path.join(SHARED, 'base.css'),
    path.join(SHARED, 'tokens', `${cfg.style}.css`),
  ];
  if (cfg.capabilities.includes('chart')) files.push(path.join(GEN, 'behaviors', 'chart.css'));
  if (cfg.capabilities.includes('globe')) files.push(path.join(GEN, 'behaviors', 'globe.css'));
  // 分镜版式：只给声明了分镜的模板，三套数据风格共用一份规则
  if (cfg.storyboard) files.push(path.join(SHARED, 'storyboard.css'));
  files.push(path.join(LAYOUTS, cfg.style, `${cfg.layout}.css`));
  for (const file of files) {
    if (!fs.existsSync(file)) fail(`${cfg.key}: 缺少样式文件 ${path.relative(TEMPLATES, file)}`);
  }
  const css = files.map((file) => read(file).trimEnd()).join('\n');
  // 内联进 <style> 的内容里出现标签字面量会把后面的标记吞掉，且 check 之前看不出来
  if (/<\/?style[\s>]/i.test(css)) {
    fail(`${cfg.key}: 内联 CSS 里出现了 style 标签字面量，会把后续标记吞掉`);
  }
  // 硬规则：低于 24px 的字在 1080×1920 上读不出来。令牌与版式都算在内。
  const sizes = [...css.matchAll(/(?:font-size|--fs-[\w-]+):\s*(\d+)px/g)].map((m) => Number(m[1]));
  const tiny = sizes.filter((size) => size < 24);
  if (tiny.length) {
    fail(`${cfg.key}: 有 ${tiny.length} 处字号低于 24px（最小 ${Math.min(...tiny)}px）`);
  }
  return css;
}

function script(cfg) {
  const files = [path.join(GEN, 'behaviors', 'fill.js')];
  if (cfg.capabilities.includes('chart')) {
    files.push(path.join(GEN, 'behaviors', 'chart.js'), path.join(GEN, 'behaviors', 'focus.js'));
  }
  if (cfg.capabilities.includes('globe')) {
    files.push(path.join(GEN, 'behaviors', 'globe.js'));
  }
  files.push(path.join(GEN, 'motion.js'));
  for (const file of files) {
    if (!fs.existsSync(file)) fail(`${cfg.key}: 缺少脚本片段 ${path.relative(TEMPLATES, file)}`);
  }
  return files.map((file) => read(file).trimEnd()).join('\n\n');
}

/**
 * 产物统一用 LF。
 *
 * <p>仓库没给 data/** 定 eol 规则，Windows 上 git checkout 会把 LF 换成 CRLF；
 * 构建脚本原样读进骨架与样式，产物就跟着变成 CRLF，提交回去又被换回 LF——
 * 于是「在本地重建一次模板」等于全仓 diff，跟本次改动毫无关系。
 *
 * <p>写盘前统一成 LF：产物与仓库规范一致，两端构建结果相同，
 * --check 才不会在 Windows 上误报。
 */
const normalizeEol = (text) => text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');

function render(cfg) {
  const skeleton = read(path.join(GEN, 'skeleton.html'));
  // 结尾口播与最后一屏共用时间窗：正文在这一屏里退场、口播接上。
  // 之前把它钉在 DURATION 上，等于永远落在成片之外，15 套模板都从没播出过这句。
  const slot = cfg.duration / cfg.activeScenes;
  const closingStart = cfg.duration - closingTail(slot);
  const slots = {
    CLOSING_START: num(closingStart),
    CLOSING_DURATION: num(cfg.duration - closingStart),
    VARIABLES_JSON: escapeAttr(JSON.stringify(variables(cfg))),
    WIDTH: cfg.width,
    HEIGHT: cfg.height,
    DURATION: num(cfg.duration),
    STYLE: cfg.style,
    AMBIENT_BLOCK: ambientBlock(cfg),
    DECOR_BLOCK: decorBlock(cfg),
    PAGE_TITLE: escapeHtml(cfg.pageTitle ?? cfg.name),
    STYLES: styles(cfg),
    KPI_BLOCK: kpiBlock(cfg),
    CHART_SLOT: chartSlot(cfg),
    SCENES_BLOCK: sceneBlocks(cfg),
    DEFAULT_TITLE: escapeHtml(cfg.defaults.title),
    DEFAULT_KICKER: escapeHtml(cfg.defaults.kicker ?? ''),
    DEFAULT_CLOSING: escapeHtml(cfg.defaults.closing),
    DEFAULT_SOURCE: escapeHtml(cfg.defaults.sourceName),
    EXTRA_LIB: cfg.capabilities.includes('globe') ? '<script src="three.min.js"></script>' : '',
    SCRIPT: script(cfg),
  };
  return normalizeEol(skeleton.replace(/\{\{([A-Z_]+)\}\}/g, (match, name) => {
    if (!(name in slots)) fail(`${cfg.key}: 骨架里有未定义的占位符 ${match}`);
    return slots[name];
  }));
}

function aspectRatio(cfg) {
  const gcd = (a, b) => (b === 0 ? a : gcd(b, a % b));
  const d = gcd(cfg.width, cfg.height) || 1;
  return `${cfg.width / d}:${cfg.height / d}`;
}

/**
 * 风格的对外身份：名字与色板。前端拿它做风格预览，前后端因此不必各写一份色值。
 * 色值从令牌文件里现读——手抄一份就会和画面漂移。
 */
function styleMeta(cfg) {
  const tokens = read(path.join(SHARED, 'tokens', `${cfg.style}.css`));
  const declared = (name) => (tokens.match(new RegExp(`--${name}:\\s*([^;]+);`)) || [])[1]?.trim();
  const catalog = JSON.parse(read(path.join(SHARED, 'styles.json')));
  const meta = catalog[cfg.style];
  if (!meta) fail(`${cfg.key}: 风格 ${cfg.style} 没有在 _shared/styles.json 登记`);
  // 叙事类型登记在风格上，不登记在模板上：
  // 一套画面语法能承载多种叙事，模板只是这套语法的实现。
  // 它是选风格的硬依据——先有故事，才知道该用哪套画面。
  return {
    key: cfg.style,
    name: meta.name,
    label: meta.label,
    role: meta.role,
    narratives: meta.narratives ?? [],
    bg: declared('bg'),
    fg: declared('fg'),
    accent: cfg.defaults.accent ?? null,
  };
}

function templateJson(cfg) {
  return JSON.stringify({
    name: cfg.name,
    description: cfg.description ?? '',
    style: styleMeta(cfg),
    supportedLines: [cfg.line],
    supportedSceneTypes: cfg.supportedSceneTypes ?? ['headline', 'text'],
    // 只有 3D 才写：2D 是缺省值，写出来只会让历史模板全部产生无意义 diff
    ...(cfg.visualMode === '3d' ? { visualMode: '3d' } : {}),
    aspectRatio: aspectRatio(cfg),
    recommendedDuration: cfg.recommendedDuration ?? { min: cfg.duration, max: cfg.duration },
    requiredAssets: [],
    requiredFields: ['title', 'scenes'],
    version: cfg.version ?? '1.0.0',
  }, null, 2) + '\n';
}

/**
 * 断言随模板一起生成：时长来自配置，装饰层的出现时限写死在这条线里。
 * 手写这份文件时，改 duration 忘了改这里会让 check 按错的时长抽样。
 */
function motionJson(cfg) {
  return JSON.stringify({
    duration: cfg.duration,
    assertions: [
      // 关掉环境层的模板不能断言它出现：断言必须描述这套模板真的有的东西
      ...(cfg.ambient === false ? [] : [{ kind: 'appearsBy', selector: '.ambient', bySec: 2 }]),
      { kind: 'appearsBy', selector: '#aigc-badge', bySec: 0.5 },
      { kind: 'appearsBy', selector: '#title', bySec: 1.5 },
      { kind: 'staysInFrame', selector: '#aigc-badge' },
      { kind: 'keepsMoving', withinSelector: '#root', maxStaticSec: 4 },
    ],
  }, null, 2) + '\n';
}

function writeManifest(dir) {
  const entries = [];
  const walk = (current) => {
    for (const name of fs.readdirSync(current, { withFileTypes: true })) {
      // 点目录是工具产物（如 .hyperframes），不是模板资产，不进清单
      if (name.isDirectory() && name.name.startsWith('.')) continue;
      const full = path.join(current, name.name);
      if (name.isDirectory()) { walk(full); continue; }
      const rel = path.relative(dir, full).split(path.sep).join('/');
      if (rel === 'LOCK.json' || rel === 'assets-manifest.json') continue;
      entries.push({ path: rel, sha256: sha256(full), bytes: fs.statSync(full).size });
    }
  };
  walk(dir);
  entries.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  const manifest = { templateKey: path.basename(dir), templateVersion: '1', files: entries };
  const manifestPath = path.join(dir, 'assets-manifest.json');
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');

  const lockPath = path.join(dir, 'LOCK.json');
  const previous = fs.existsSync(lockPath) ? JSON.parse(read(lockPath)) : {};
  if (!fs.existsSync(SHARED_FONT)) fail(`${path.basename(dir)}: 找不到共用字体 ${SHARED_FONT}`);
  const lock = {
    templateKey: path.basename(dir),
    templateVersion: '1',
    // 字体不进模板目录：渲染前注入的是同一份共用字体，哈希因此与所有模板一致
    fontSha256: sha256(SHARED_FONT),
    voiceKey: previous.voiceKey ?? 'zh-CN-XiaoxiaoNeural',
    voiceVersion: previous.voiceVersion ?? '1',
    assetManifestSha256: sha256(manifestPath),
  };
  fs.writeFileSync(lockPath, JSON.stringify(lock, null, 2) + '\n');
}

/**
 * 时间窗不变量。这类错误在画面上表现为"某段内容根本没出现"，截图靠抽样很难发现：
 * 结尾口播就曾长期落在成片之外，15 套模板一次都没播过。生成时就断言。
 */
function checkWindows(cfg, html) {
  const windows = [...html.matchAll(/data-start="([\d.]+)" data-duration="([\d.]+)"/g)]
    .map((m) => ({ start: Number(m[1]), dur: Number(m[2]) }))
    .filter((w) => w.dur > 0.01);
  for (const w of windows) {
    if (w.start + w.dur > cfg.duration + 0.001) {
      fail(`${cfg.key}: 有片段 ${w.start}s + ${w.dur}s 超出成片时长 ${cfg.duration}s`);
    }
  }
  const closing = html.match(/id="closing-slot"[^>]*data-start="([\d.]+)" data-duration="([\d.]+)"/);
  if (!closing) fail(`${cfg.key}: 找不到结尾口播的时间窗`);
  const closingStart = Number(closing[1]);
  const closingEnd = closingStart + Number(closing[2]);
  if (closingEnd > cfg.duration + 0.001) fail(`${cfg.key}: 结尾口播落在成片之外（${closingStart}s 起）`);
  const lastScene = [...html.matchAll(/class="clip scene"[^>]*data-start="([\d.]+)"[^>]*data-duration="([\d.]+)"/g)]
    .map((m) => Number(m[1]) + Number(m[2]))
    .filter((end) => end < cfg.duration - 0.01);
  const lastSceneEnd = Math.max(...lastScene, 0);
  // 口播与最后一屏共用版面，时间上必须错开，否则闸门会判文字被遮挡
  if (closingStart + 0.001 < lastSceneEnd) {
    fail(`${cfg.key}: 结尾口播(${closingStart}s) 早于最后一屏结束(${lastSceneEnd}s)，两段会互相遮挡`);
  }
}

// 三套风格的骨架不变量：写进版式 CSS 的注释里，也在构建期断言一次。
// 为什么要在构建期查：三套风格最容易的退化方式不是换配色，而是「悄悄变成同一套骨架」——
// 改一处 #chart-slot 高度、把 .scene 从通栏改成缩进，画面看起来仍然「正常」，风格却没了。
// 断言只查结构性声明，不查具体数值，避免把版式微调也锁死。
const SKELETON_INVARIANTS = {
  'news-broadcast': [
    ['../layouts/news-broadcast/news-data-broadcast-9x16.css', '.scene', 'border-left'],
    ['../layouts/news-broadcast/news-data-broadcast-9x16.css', '.scene', 'background'],
    ['../layouts/news-broadcast/news-data-broadcast-9x16.css', '.scene', 'grid-template-columns'],
  ],
  'data-investigation': [
    ['../layouts/data-investigation/news-data-investigate-9x16.css', '.scene', 'border-left'],
    ['../layouts/data-investigation/news-data-investigate-9x16.css', '.body', 'font-weight'],
    ['../layouts/data-investigation/news-data-investigate-9x16.css', '#closing', 'border-top'],
  ],
  'data-board': [
    ['../layouts/data-board/news-data-9x16.css', '.scene', 'left'],
    ['../layouts/data-board/news-data-9x16.css', '.scene', 'right'],
    ['../layouts/data-board/news-data-9x16.css', '#closing', 'border-left'],
  ],
};

// 图表语义令牌：三套各自必须定义，缺一个就会回落到 chart.css 的默认值，
// 三套的图表手法因此又变成同一套（这正是本轮要修的问题）。
const CHART_TOKENS = [
  '--chart-line-width', '--chart-area-alpha', '--chart-max-annot', '--chart-min-annot',
  '--chart-min-size', '--focus-dim', '--focus-ring-width', '--focus-ring-dash',
];

/**
 * 叙事类型与风格的对应必须唯一。
 *
 * <p>收窄候选的前提是「一种叙事只对应一套画面」——若两个风格都声明同一个叙事，
 * 收窄就收不窄，选风格又退回模型随机挑，正是这套设计要消灭的东西。
 *
 * <p>叙事类型的全集由后端契约定（Java 侧 DatasetStoryContract.NARRATIVES），
 * 这里不重复列一遍：清单写两处迟早会漂。这里只管「不重」这一层，
 * 「不漏」由后端在运行时再查一次。
 */
function checkNarrativeContract(catalog) {
  const owner = new Map();
  for (const [style, meta] of Object.entries(catalog)) {
    const narratives = meta.narratives ?? [];
    if (!Array.isArray(narratives)) {
      fail(`风格 ${style} 的 narratives 必须是数组`);
    }
    for (const narrative of narratives) {
      if (typeof narrative !== 'string' || !narrative) {
        fail(`风格 ${style} 的 narratives 里有空项`);
      }
      if (owner.has(narrative)) {
        fail(`叙事类型 ${narrative} 同时登记在 ${owner.get(narrative)} 与 ${style} 上——一种叙事只能对应一套画面`);
      }
      owner.set(narrative, style);
    }
  }
}

/**
 * 从一份 CSS 文本里取某个选择器块的内容；找不到返回 null。
 *
 * <p>选择器必须精确匹配到 `{` 为止：`.scene` 不能匹配到 `.scene-no` / `.scene-figure`，
 * `#closing` 不能匹配到 `#closing-slot`。否则断言会查到隔壁规则上去，白查一场。
 */
function ruleBlock(css, selector) {
  const escaped = selector.replace(/[\\.#*]/g, (ch) => '\\' + ch);
  const pattern = new RegExp('(^|\\})\\s*' + escaped + '\\s*\\{([^}]*)\\}', 'g');
  let last = null;
  for (const match of css.matchAll(pattern)) last = match[2];
  return last;
}

function checkStyleContract(cfg) {
  // 骨架不变量只对「数据看板三套」这只角：这三套是同一套生成器产出的三条风格，
  // 最容易的退化就是彼此长成同一套。article 系列是文稿线，骨架本来就不同，不在此列。
  if (!cfg.capabilities.includes('chart')) return;
  // 1) 骨架不变量
  const invariants = SKELETON_INVARIANTS[cfg.style];
  if (!invariants) {
    fail(`${cfg.key}: 风格 ${cfg.style} 没有登记骨架不变量，新风格必须先在 SKELETON_INVARIANTS 里写清它凭什么与别的不一样`);
  }
  for (const [file, selector, property] of invariants) {
    const css = read(path.resolve(GEN, file));
    const block = ruleBlock(css, selector);
    if (block == null) {
      fail(`${cfg.key}: 版式里找不到 ${selector}，骨架不变量「${selector} 必须有 ${property}」无从检查`);
    }
    if (!new RegExp(`(^|;|\\s)${property.replace(/[-]/g, '\\-')}\\s*:`).test(block)) {
      fail(`${cfg.key}: ${selector} 少了 ${property}——这是 ${cfg.style} 的骨架签名，丢掉它三套风格就长成同一套`);
    }
  }
  // 2) 图表语义令牌齐全
  const tokens = read(path.join(SHARED, 'tokens', `${cfg.style}.css`));
  const missing = CHART_TOKENS.filter((name) => !new RegExp(`${name}\\s*:`).test(tokens));
  if (missing.length) {
    fail(`${cfg.key}: 令牌缺少 ${missing.join('、')}，图表会回落到共享默认值，三套的图表手法会退化成同一套`);
  }
  // 3) 图表内字号下限：令牌不得低于 24px（1080×1920 上低于这个值读不出来）
  const minSize = tokens.match(/--chart-min-size\s*:\s*(\d+)px/);
  if (!minSize || Number(minSize[1]) < 24) {
    fail(`${cfg.key}: --chart-min-size 必须 ≥ 24px（当前 ${minSize ? minSize[1] + 'px' : '未定义'}）`);
  }
  // 4) 字号下限必须由令牌驱动，不许在脚本里写死数字。
  // 过去的写法是 Math.max(16, …)：图表内字号由 JS 反推、构建期扫不到，
  // 三套风格一起掉到 16px 也没人发现。改成读令牌之后，这里守住「别再写回去」。
  const script = [path.join(GEN, 'behaviors', 'chart.js'), path.join(GEN, 'behaviors', 'focus.js')]
    .map((file) => read(file)).join('\n');
  if (/\bMath\.max\(\s*1[0-9]\s*,/.test(script)) {
    fail(`${cfg.key}: 脚本里又出现了写死的字号下限（Math.max(1x, …)），图表字号必须读 --chart-min-size`);
  }
  if (!/minSize\(\)/.test(script)) {
    fail(`${cfg.key}: chart.js 没有使用 minSize()，图表字号下限又脱离了令牌`);
  }
}

/**
 * globe 模板自带 three.min.js：渲染不联网，产物必须自包含。
 *
 * <p>源文件来自 data/tools 的依赖；--check 时比对哈希，防止模板里的副本悄悄漂移。
 */
function vendorThree(dir) {
  const key = path.basename(dir);
  if (!fs.existsSync(THREE_SOURCE)) {
    fail(`${key}: 找不到 Three.js 构建产物 ${THREE_SOURCE}（先在 data/tools 安装依赖）`);
  }
  const target = path.join(dir, 'three.min.js');
  const same = fs.existsSync(target) && sha256(target) === sha256(THREE_SOURCE);
  if (same) return true;
  if (CHECK_ONLY) {
    console.log(`✗ ${key}: three.min.js 与依赖里的版本不一致（运行不带 --check 以重新拷贝）`);
    return false;
  }
  fs.copyFileSync(THREE_SOURCE, target);
  return true;
}

function build(dir) {
  const cfg = loadConfig(dir);
  checkStyleContract(cfg);
  if (cfg.capabilities.includes('globe') && !vendorThree(dir)) {
    return false;
  }
  // 三个产物都从配置派生，--check 就三个都要比：
  // 只比 index.html 的话，手改 index.motion.json 能悄悄放宽断言而检查不出来。
  const generated = {
    'index.html': render(cfg),
    'template.json': templateJson(cfg),
    'index.motion.json': motionJson(cfg),
  };
  checkWindows(cfg, generated['index.html']);
  const indexPath = path.join(dir, 'index.html');

  if (CHECK_ONLY) {
    const drifted = Object.entries(generated)
      .filter(([name, expected]) => {
        const file = path.join(dir, name);
        return !fs.existsSync(file) || normalizeEol(read(file)) !== expected;
      })
      .map(([name]) => name);
    if (drifted.length) {
      console.log(`✗ ${cfg.key}: ${drifted.join('、')} 与配置不一致（运行不带 --check 以重新生成）`);
      return false;
    }
    console.log(`✓ ${cfg.key}`);
    return true;
  }

  for (const [name, content] of Object.entries(generated)) {
    fs.writeFileSync(path.join(dir, name), content);
  }
  writeManifest(dir);
  console.log(`✓ ${cfg.key}  ${(Buffer.byteLength(generated['index.html']) / 1024).toFixed(1)} KB`);
  return true;
}

/**
 * 模板目录不含字体。要单独渲染一个模板，先暂存成 composition 结构——
 * 与 NewsProductionPipeline 复制模板后注入共用字体的工作目录完全一致。
 */
function stage(dir, out) {
  const cfg = loadConfig(dir);
  if (!fs.existsSync(SHARED_FONT)) fail(`${cfg.key}: 找不到共用字体 ${SHARED_FONT}`);
  fs.rmSync(out, { recursive: true, force: true });
  fs.cpSync(dir, out, {
    recursive: true,
    filter: (source) => !path.basename(source).startsWith('.'),
  });
  fs.mkdirSync(path.join(out, 'fonts'), { recursive: true });
  fs.copyFileSync(SHARED_FONT, path.join(out, 'fonts', FONT_NAME));
  console.log(`✓ ${cfg.key} → ${out}`);
}

function verify(dir) {
  const cfg = loadConfig(dir);
  // 版式闸要在「注入字体之后」的目录上跑：模板目录本身不含字体，缺字体会让排版测量失真
  const staged = path.resolve(TEMPLATES, '..', '.stage', `verify-${cfg.key}`);
  stage(dir, staged);
  try {
    // 用仓库里装好的 CLI，不走 npx：离线环境里 npx --yes 会尝试联网安装
    const cli = path.resolve(import.meta.dirname, 'node_modules', 'hyperframes', 'bin', 'hyperframes.mjs');
    const out = execFileSync(process.execPath, [cli, 'check', staged, '--json'], {
      // 长模板要抽 300+ 帧，5 分钟会超时——超时会被误报成 check 失败
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 900000,
    });
    const parsed = JSON.parse(out);
    const errors = parsed.errors ?? parsed.errorCount ?? 0;
    if (errors > 0) {
      console.error(`✗ ${cfg.key}: check 报 ${errors} 个 error`);
      console.error(out.slice(0, 4000));
      return false;
    }
    console.log(`✓ ${cfg.key}: check 通过`);
    return true;
  } catch (e) {
    console.error(`✗ ${cfg.key}: hyperframes check 执行失败 — ${e.message}`);
    if (e.stdout) console.error(String(e.stdout).slice(0, 4000));
    return false;
  }
}

function main() {
  // 叙事类型与风格的对应是全局不变量：每构建一个模板都查一遍太吵，
  // 但一次都不查就等于没有——风格登记写错时没有任何地方会响。
  checkNarrativeContract(JSON.parse(read(path.join(SHARED, 'styles.json'))));

  if (STAGE) {
    const dir = path.join(TEMPLATES, STAGE);
    if (!fs.existsSync(path.join(dir, 'template.config.json'))) {
      fail(`找不到模板 ${STAGE}（或其缺少 template.config.json）`);
    }
    stage(dir, STAGE_OUT ? path.resolve(STAGE_OUT) : path.resolve(TEMPLATES, '..', '.stage', STAGE));
    return;
  }

  const dirs = fs.readdirSync(TEMPLATES, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !entry.name.startsWith('_') && entry.name !== 'layouts')
    .map((entry) => path.join(TEMPLATES, entry.name))
    .filter((dir) => fs.existsSync(path.join(dir, 'template.config.json')));

  const targets = ONLY ? dirs.filter((dir) => path.basename(dir) === ONLY) : dirs;
  if (ONLY && targets.length === 0) fail(`找不到模板 ${ONLY}（或其缺少 template.config.json）`);

  let ok = true;
  for (const dir of targets) {
    try {
      ok = build(dir) && ok;
      if (VERIFY && !CHECK_ONLY) ok = verify(dir) && ok;
    } catch (e) {
      if (e instanceof BuildError) {
        console.error(`✗ ${e.message}`);
        ok = false;
      } else {
        throw e;
      }
    }
  }
  if (!ok) process.exit(1);
  console.log(`\n${targets.length} 个模板处理完成`);
}

main();
