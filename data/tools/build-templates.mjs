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
// 模板能声明的编辑线，取值与 EditorialSelection 的线常量一致。
const LINES = new Set(['NEWS_EXPLAINER', 'DATA_BOARD', 'TREND_INSIGHT', 'KNOWLEDGE_EXPLAINER', 'MUSIC_VIDEO']);
const RATIOS = {
  '9x16': { width: 1080, height: 1920 },
  '16x9': { width: 1920, height: 1080 },
};
// globe 模板自带的渲染库：与 gsap 一样随模板一起提交，渲染时不需要联网。
const THREE_SOURCE = path.resolve(import.meta.dirname, 'node_modules', 'three', 'build', 'three.min.js');
// 只装 SplitText 一个插件。图表的描边生长 chart.js 已经用 strokeDashoffset 自己做掉了，
// 再引 DrawSVG 等于同一件事的第二份实现；MorphSVG / MotionPath 要模板里有对应图形才有意义。
// 缺的是文字：现在每块文字整体淡入，看不出「在讲」。
const SPLIT_TEXT_FILE = 'SplitText.min.js';
const SPLIT_TEXT_SOURCE = path.resolve(import.meta.dirname, 'node_modules', 'gsap', 'dist', SPLIT_TEXT_FILE);
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
  const currentRatio = ratioOf(cfg);
  const supportedLines = cfg.lines ?? [cfg.line];
  if (!Array.isArray(supportedLines) || supportedLines.length === 0 || new Set(supportedLines).size !== supportedLines.length) {
    fail(`${key}: lines 必须是非空且不重复的线数组`);
  }
  for (const line of supportedLines) {
    if (!LINES.has(line)) fail(`${key}: 未知线「${line}」，合法值：${[...LINES].join('、')}`);
  }
  if (!supportedLines.includes(cfg.line)) fail(`${key}: lines 必须包含 line（${cfg.line}）`);
  if (cfg.layouts !== undefined) {
    if (!Array.isArray(cfg.layouts) || cfg.layouts.length === 0 || new Set(cfg.layouts).size !== cfg.layouts.length) {
      fail(`${key}: layouts 必须是非空且不重复的比例数组`);
    }
    for (const layout of cfg.layouts) {
      if (!(layout in RATIOS)) fail(`${key}: 未知比例「${layout}」，合法值：${Object.keys(RATIOS).join('、')}`);
    }
    if (!currentRatio || !cfg.layouts.includes(currentRatio)) {
      fail(`${key}: layouts 必须包含当前画布比例 ${currentRatio ?? `${cfg.width}x${cfg.height}`}`);
    }
  }
  cfg.layouts ??= currentRatio ? [currentRatio] : [];
  cfg.activeScenes ??= cfg.scenes;
  cfg.defaults ??= {};
  // 分镜由模板配置显式开启（现只有五个数据模板开了）：场景类型、叙事节点与上屏文字
  // 一起进出，一半有、一半没有的模板会在 --strict-variables 上被卡住。
  // 别改成「有 supportedSceneTypes 就开」——文稿与知识模板的稿子不产 beat 与上屏文字，
  // 开了会让 headline 屏藏正文、藏标题卡，是一轮单独的渲染验证。
  if (cfg.activeScenes > cfg.scenes) fail(`${key}: activeScenes(${cfg.activeScenes}) 大于 scenes(${cfg.scenes})`);
  return cfg;
}

function ratioOf(cfg) {
  return Object.keys(RATIOS).find((key) => RATIOS[key].width === cfg.width && RATIOS[key].height === cfg.height);
}

function variantConfig(cfg, ratio) {
  const baseKey = cfg.key.replace(/-(?:9x16|16x9)$/, '');
  const description = (cfg.description ?? '').replace('1080×1920', '1920×1080');
  return {
    ...cfg,
    key: `${baseKey}-${ratio}`,
    name: `${cfg.name}（横版）`,
    pageTitle: (cfg.pageTitle ?? cfg.name).replace('竖版', '横版'),
    layout: cfg.layout.replace(/(?:9x16|16x9)$/, ratio),
    width: RATIOS[ratio].width,
    height: RATIOS[ratio].height,
    layouts: [ratio],
    description: description.includes('16:9') ? description : `${description}；横版 16:9`,
    generatedFrom: cfg.key,
    // 横版只承接源模板的主线。lines 里其余的线（如知识线）按竖版设计，
    // 稿子形态未必在横版上成立；要给横版挂第二条线，在源模板上单独声明。
    lines: [cfg.line],
  };
}

function buildJobs() {
  const dirs = fs.readdirSync(TEMPLATES, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !entry.name.startsWith('_') && entry.name !== 'layouts')
    .map((entry) => path.join(TEMPLATES, entry.name))
    .filter((dir) => fs.existsSync(path.join(dir, 'template.config.json')))
    .map((dir) => ({ dir, cfg: loadConfig(dir), sourceDir: dir, generated: false }))
    .filter((job) => !job.cfg.generatedFrom);
  const jobs = [];
  for (const job of dirs) {
    jobs.push(job);
    for (const ratio of job.cfg.layouts) {
      if (ratio === ratioOf(job.cfg)) continue;
      const cfg = variantConfig(job.cfg, ratio);
      jobs.push({ dir: path.join(TEMPLATES, cfg.key), cfg, sourceDir: job.dir, generated: true });
    }
  }
  return jobs;
}

function variables(cfg) {
  const d = cfg.defaults;
  // 情绪短片：三句歌词就是全部内容变量，没有场景文字层
  if (cfg.mood) {
    return [
      { id: 'line1', type: 'string', label: '第一幕歌词', maxLength: 24, default: d.line1 ?? '' },
      { id: 'line2', type: 'string', label: '第二幕歌词', maxLength: 24, default: d.line2 ?? '' },
      { id: 'line3', type: 'string', label: '第三幕歌词', maxLength: 24, default: d.line3 ?? '' },
      { id: 'source_name', type: 'string', label: '来源名称', maxLength: 80, default: d.sourceName ?? '' },
      { id: 'accent', type: 'color', label: '光源色', default: d.accent ?? '#ffd98c' },
    ];
  }
  const scenes = d.scenes ?? [];
  // fixture：每一屏的画面依据与场景类型。生产期这两项由 Java 注入
  // （NewsProductionPipeline.dataTemplateVariables），模板里的默认值是给本地
  // 渲染用的样例。默认值过去一律写死成 '{}' 与 ''，本地渲出来的永远是
  // 「固定图表 + 三行字」的最差形态，和线上不是同一个画面，看片等于看错片。
  // 模板在 defaults.sceneMeta 里声明样例；没声明就退回原来的空值，行为不变。
  const meta = d.sceneMeta ?? [];
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
      const focus = meta[i - 1]?.focus;
      out.push({
        id: `scene${i}_focus`, type: 'string', label: `第${i}屏画面依据`,
        default: focus ? JSON.stringify(focus) : '{}',
      });
    }
  }
  for (let i = 1; i <= cfg.scenes; i++) {
    out.push({ id: `scene${i}_type`, type: 'string', label: '场景类型', default: meta[i - 1]?.type ?? '' });
  }
  if (cfg.storyboard) {
    // 分镜另两件：叙事节点给版式一条辅线，上屏文字与旁白解耦。
    // 与 sceneN_focus 一样不绑到元素上，由 fill.js 从 declared 里读。
    for (let i = 1; i <= cfg.scenes; i++) {
      out.push({ id: `scene${i}_beat`, type: 'string', label: '叙事节点', default: meta[i - 1]?.beat ?? '' });
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

/** 冲刺榜的编译期榜单：行 DOM 必须在 HTML 里，引擎只渲带时间窗的编译期元素，
 * 脚本运行时建的自由 DOM 进不了成片（实测）。数据取 config.defaults.dataset.facts，
 * 与 race.js 读取的是同一份——脚本只做动画，不做 DOM。 */
function boardBlock(cfg) {
  if (!cfg.race) return '';
  const items = ((((cfg.defaults || {}).dataset || {}).facts || {}).items || [])
    .filter((entry) => entry && Number.isFinite(Number(entry.value))).slice(0, 5);
  if (items.length < 2) {
    fail(`${cfg.key}: race 模板的 dataset.facts.items 至少要 2 条事实，榜单没有可画的内容`);
  }
  const firstScene = (cfg.defaults.scenes ?? [])[0] ?? [];
  const firstStart = 0;
  const sceneSpan = num(cfg.duration / cfg.activeScenes);
  const unit = ((((cfg.defaults || {}).dataset || {}).metric || {}).unit) || '';
  const rows = items.map((entry, index) => {
    const rank = String(index + 1).padStart(2, '0');
    return `        <div class="row${index === 0 ? ' row-1' : ''}" data-rank="${index + 1}">
          <span class="rank-no">${rank}</span>
          <span class="row-name">${escapeHtml(String(entry.label ?? entry.name ?? entry.location ?? ''))}</span>
          <span class="row-value" data-value="${num(Number(entry.value))}">0</span>
          <svg class="row-spark" viewBox="0 0 120 44" preserveAspectRatio="none" aria-hidden="true">
            <polyline points="${index === 0 ? '0,40 40,32 80,20 120,8' : '0,30 120,18'}" fill="none"
              stroke="${index === 0 ? 'var(--accent)' : 'var(--muted-2)'}" stroke-width="6"/>
          </svg>
        </div>`;
  }).join('\n');
  const crown = `        <div class="crown" aria-hidden="true"><span>领</span></div>\n`;
  return `
      <section id="board" class="clip" data-start="${firstStart}" data-duration="${num(cfg.duration / cfg.activeScenes)}" data-track-index="2" aria-label="排行榜">
${crown}${rows}
      </section>
      <span id="board-unit" class="clip" data-start="${firstStart}" data-duration="${sceneSpan}" data-track-index="2">${escapeHtml(unit)}</span>
      <!-- 场景 1 的文字窗口让给榜单：首屏领句压在榜单上方当钩子，正文藏掉 -->
`;
}

/** 情绪短片的三幕场景：剪影地形 + 天空渐变 + 光源粒子，全部编译期写入。
 * 每幕一个 clip（同一轨道先后接管），幕与幕的色彩由各幕容器自己的 --mood-sky 渐变决定；
 * mood.js 只做氛围动效（视差、光晕呼吸、天灯上浮、歌词逐句），不改结构。 */
function moodBlock(cfg) {
  if (!cfg.mood) return '';
  const acts = cfg.moodActs ?? [
    { id: 'night', sky: ['#0b1626', '#16283e', '#1d2f4a'], ground: '#0a0f18', moon: 0.86 },
    { id: 'dusk', sky: ['#3a2b48', '#5a3d5c', '#7a4b62'], ground: '#1a1420', moon: 0.18 },
    { id: 'lantern', sky: ['#241a10', '#4a3218', '#7a4d20'], ground: '#160f08', moon: 0 },
  ];
  const span = cfg.duration / (cfg.moodActs ? cfg.moodActs.length : 3);
  const scenes = acts.map((act, index) => {
    const start = index * span;
    const last = index === acts.length - 1;
    const dur = last ? cfg.duration - start : span;
    return `      <section id="mood-${act.id}" class="clip mood-act" data-start="${num(start)}" data-duration="${num(dur)}" data-track-index="1" data-act="${act.id}"
        style="--mood-sky-top:${act.sky[0]};--mood-sky-mid:${act.sky[1]};--mood-sky-end:${act.sky[2]};--mood-ground:${act.ground};">
        <svg class="mood-scene" viewBox="0 0 1080 1920" preserveAspectRatio="xMidYMax slice" aria-hidden="true">
          <defs>
            <linearGradient id="sky-${act.id}" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="var(--mood-sky-top)"/>
              <stop offset="55%" stop-color="var(--mood-sky-mid)"/>
              <stop offset="100%" stop-color="var(--mood-sky-end)"/>
            </linearGradient>
            <radialGradient id="halo-${act.id}">
              <stop offset="0%" stop-color="var(--mood-light)" stop-opacity="0.9"/>
              <stop offset="45%" stop-color="var(--mood-light)" stop-opacity="0.28"/>
              <stop offset="100%" stop-color="var(--mood-light)" stop-opacity="0"/>
            </radialGradient>
          </defs>
          <rect class="mood-sky" width="1080" height="1920" fill="url(#sky-${act.id})"/>
          ${act.moon > 0 ? `<circle class="mood-moon" cx="${820}" cy="${330}" r="${Math.round(120 * act.moon / 0.86)}" fill="#e8e4d8" opacity="${act.moon}"/>
          <circle class="mood-moonhalo" cx="${820}" cy="${330}" r="${Math.round(220 * act.moon / 0.86)}" fill="url(#halo-${act.id})" opacity="${0.5 * act.moon}"/>` : ''}
          <g class="mood-stars" data-ambient="flicker"></g>
          <ellipse class="mood-glow" cx="${index === 2 ? 420 : 380}" cy="${index === 2 ? 1560 : 1520}" rx="300" ry="200" fill="url(#halo-${act.id})" opacity="${index === 2 ? 0.8 : 0.55}"/>
          <g class="mood-terrain">
            <path class="mood-hill mood-hill-far" d="M0,1560 Q270,1470 540,1540 T1080,1500 L1080,1920 L0,1920 Z" fill="var(--mood-ground)" opacity="0.55"/>
            <path class="mood-hill mood-hill-near" d="M0,1680 Q330,1600 640,1670 T1080,1650 L1080,1920 L0,1920 Z" fill="var(--mood-ground)"/>
          </g>
          ${index === 0 ? `<g class="mood-figure">
            <circle class="mood-head" cx="380" cy="1482" r="26" fill="var(--mood-figure)"/>
            <path class="mood-body" d="M354,1512 Q380,1494 406,1512 L400,1616 L360,1616 Z" fill="var(--mood-figure)"/>
            <g class="mood-lantern"><line x1="352" y1="1516" x2="330" y2="1560" stroke="var(--mood-figure)" stroke-width="6"/>
              <rect class="mood-lantern-body" x="308" y="1560" width="44" height="56" rx="10" fill="var(--mood-light)"/>
              <circle class="mood-lantern-halo" cx="330" cy="1588" r="90" fill="url(#halo-${act.id})"/></g>
          </g>` : ''}
          ${index === 1 ? `<g class="mood-boat">
            <path class="mood-boat-hull" d="M700,1642 Q780,1672 860,1642 L820,1680 Q780,1694 740,1680 Z" fill="var(--mood-figure)"/>
            <g class="mood-figure mood-sitter"><circle class="mood-head" cx="780" cy="1612" r="22" fill="var(--mood-figure)"/>
              <path class="mood-body" d="M760,1636 Q780,1622 800,1636 L796,1646 L764,1646 Z" fill="var(--mood-figure)"/>
              <g class="mood-lantern"><circle class="mood-lantern-body" cx="818" cy="1630" r="16" fill="var(--mood-light)"/>
                <circle class="mood-lantern-halo" cx="818" cy="1630" r="70" fill="url(#halo-${act.id})"/></g>
            </g>
          </g>` : ''}
          ${index === 2 ? `<g class="mood-sky-lanterns"></g>
          <g class="mood-figure">
            <circle class="mood-head" cx="420" cy="1500" r="26" fill="var(--mood-figure)"/>
            <path class="mood-body" d="M394,1530 Q420,1512 446,1530 L440,1634 L400,1634 Z" fill="var(--mood-figure)"/>
            <g class="mood-lantern mood-raising"><circle class="mood-lantern-body" cx="470" cy="1560" r="18" fill="var(--mood-light)"/>
              <circle class="mood-lantern-halo" cx="470" cy="1560" r="80" fill="url(#halo-${act.id})"/></g>
          </g>` : ''}
        </svg>
        <p class="mood-lyric" data-var-text="line${index + 1}"></p>
      </section>`;
  });
  return '\n' + scenes.join('\n') + '\n';
}

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

function styleFiles(cfg) {
  const files = [path.join(SHARED, 'base.css'), path.join(SHARED, 'tokens', `${cfg.style}.css`)];
  if (cfg.capabilities.includes('chart')) files.push(path.join(GEN, 'behaviors', 'chart.css'));
  if (cfg.capabilities.includes('globe')) files.push(path.join(GEN, 'behaviors', 'globe.css'));
  if (cfg.storyboard) files.push(path.join(SHARED, 'storyboard.css'));
  files.push(path.join(LAYOUTS, cfg.style, `${cfg.layout}.css`));
  if (cfg.skeletons) files.push(path.join(SHARED, 'skeletons.css'));
  return files;
}

function behaviorFiles(cfg) {
  const files = [path.join(GEN, 'behaviors', 'fill.js')];
  if (cfg.storyboard) files.push(path.join(GEN, 'behaviors', 'headline.js'));
  if (cfg.capabilities.includes('chart')) {
    files.push(path.join(GEN, 'behaviors', 'chart.js'), path.join(GEN, 'behaviors', 'focus.js'));
  }
  if (cfg.capabilities.includes('globe')) files.push(path.join(GEN, 'behaviors', 'globe.js'));
  files.push(path.join(GEN, 'motion.js'));
  const afterMotion = [];
  if ((cfg.supportedSceneTypes || []).includes('compare')) afterMotion.push(path.join(GEN, 'behaviors', 'compare.js'));
  if (cfg.skeletons) afterMotion.push(path.join(GEN, 'behaviors', 'skeleton.js'));
  if (cfg.race) afterMotion.push(path.join(GEN, 'behaviors', 'race.js'));
  if (cfg.mood) afterMotion.push(path.join(GEN, 'behaviors', 'mood.js'));
  files.push(...afterMotion);
  return files;
}

function styles(cfg) {
  const files = styleFiles(cfg);
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
  if (ratioOf(cfg) === '16x9') checkLandscapeContract(cfg, css);
  return css;
}

function checkLandscapeContract(cfg, css) {
  const value = (block, property) => Number(block.match(new RegExp(`(?:^|;)\\s*${property}\\s*:\\s*(\\d+)px`))?.[1]);
  const bodySize = value(ruleBlock(css, '.body'), 'font-size');
  if (!Number.isFinite(bodySize) || bodySize < 32) {
    fail(`${cfg.key}: 横版正文必须显式 ≥ 32px（当前 ${Number.isFinite(bodySize) ? bodySize + 'px' : '未定义'}）`);
  }
  const shift = Number([...css.matchAll(/--slide-shift:\s*(\d+)px/g)].at(-1)?.[1]);
  if (!Number.isFinite(shift) || shift < 180) {
    fail(`${cfg.key}: 横版转场位移必须 ≥ 180px（当前 ${Number.isFinite(shift) ? shift + 'px' : '未定义'}）`);
  }
  const bottomBar = value(ruleBlock(css, '#bottom-bar'), 'bottom');
  if (!Number.isFinite(bottomBar) || bottomBar < 0 || bottomBar > 72) {
    fail(`${cfg.key}: 横版底栏必须距底 0–72px，给字幕留出安全区`);
  }
  for (const selector of ['.scene', '#closing-slot']) {
    const bottom = value(ruleBlock(css, selector), 'bottom');
    if (!Number.isFinite(bottom) || bottom < 150) {
      fail(`${cfg.key}: ${selector} 必须距底 ≥ 150px，不能压住横版底栏与字幕区`);
    }
  }
}

function script(cfg) {
  const files = behaviorFiles(cfg);
  // 场景骨架排在 compare.js 之后，这样 skeleton-compare 类加上时两栏对照块已经建好，
  // 不会先闪一版单栏；compare.js 要借 motion.js 的 DUR / EASE，所以两者都在 motion.js 之后。
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
    BOARD_BLOCK: boardBlock(cfg),
    MOOD_BLOCK: moodBlock(cfg),
    SCENES_BLOCK: sceneBlocks(cfg),
    DEFAULT_TITLE: escapeHtml(cfg.defaults.title),
    DEFAULT_KICKER: escapeHtml(cfg.defaults.kicker ?? ''),
    DEFAULT_CLOSING: escapeHtml(cfg.defaults.closing),
    DEFAULT_SOURCE: escapeHtml(cfg.defaults.sourceName),
    EXTRA_LIB: extraLib(cfg),
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
    supportedLines: cfg.lines ?? [cfg.line],
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
      { kind: 'appearsBy', selector: '#title', bySec: ratioOf(cfg) === '16x9' && cfg.storyboard ? 5.5 : 1.5 },
      { kind: 'staysInFrame', selector: '#aigc-badge' },
      { kind: 'keepsMoving', withinSelector: '#root', maxStaticSec: 4 },
    ],
  }, null, 2) + '\n';
}

function manifestJson(dir) {
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
  return JSON.stringify({ templateKey: path.basename(dir), templateVersion: '1', files: entries }, null, 2) + '\n';
}

function lockJson(dir, manifest) {
  const lockPath = path.join(dir, 'LOCK.json');
  const previous = fs.existsSync(lockPath) ? JSON.parse(read(lockPath)) : {};
  if (!fs.existsSync(SHARED_FONT)) fail(`${path.basename(dir)}: 找不到共用字体 ${SHARED_FONT}`);
  return JSON.stringify({
    templateKey: path.basename(dir),
    templateVersion: '1',
    // 字体不进模板目录：渲染前注入的是同一份共用字体，哈希因此与所有模板一致
    fontSha256: sha256(SHARED_FONT),
    voiceKey: previous.voiceKey ?? 'zh-CN-XiaoxiaoNeural',
    voiceVersion: previous.voiceVersion ?? '1',
    assetManifestSha256: crypto.createHash('sha256').update(manifest).digest('hex'),
  }, null, 2) + '\n';
}

function writeManifest(dir) {
  const manifest = manifestJson(dir);
  fs.writeFileSync(path.join(dir, 'assets-manifest.json'), manifest);
  fs.writeFileSync(path.join(dir, 'LOCK.json'), lockJson(dir, manifest));
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

// 两套数据风格的骨架不变量：写进版式 CSS 的注释里，也在构建期断言一次。
// 为什么要在构建期查：风格最容易的退化方式不是换配色，而是「悄悄变成同一套骨架」——
// 改一处 #chart-slot 高度、把 .scene 从通栏改成缩进，画面看起来仍然「正常」，风格却没了。
// 断言只查结构性声明，不查具体数值，避免把版式微调也锁死。
// 播报风（news-broadcast）已并入 data-board：它与调查风的结构相似度 97%，是同一套骨架换个底色。
const SKELETON_INVARIANTS = {
  // 调查风现在只有 news-trend 一套在承载，骨架断言落在它自己的版式上
  'data-investigation': [
    ['../layouts/data-investigation/news-trend-9x16.css', '.scene', 'border-left'],
    ['../layouts/data-investigation/news-trend-9x16.css', '.body', 'font-weight'],
    ['../layouts/data-investigation/news-trend-9x16.css', '#closing', 'border-top'],
  ],
  'data-board': [
    ['../layouts/data-board/news-data-9x16.css', '.scene', 'left'],
    ['../layouts/data-board/news-data-9x16.css', '.scene', 'right'],
    ['../layouts/data-board/news-data-9x16.css', '#closing', 'border-left'],
  ],
  // 冲刺榜：座次即画面——行式全宽榜单（无左右分栏），领跑者行有冠标定位。
  // 「.row 全宽」+「.crown 绝对定位」是这套骨架的身份签名。
  'sprint-board': [
    ['../layouts/sprint-board/news-rank-9x16.css', '.row', 'left'],
    ['../layouts/sprint-board/news-rank-9x16.css', '.row', 'right'],
    ['../layouts/sprint-board/news-rank-9x16.css', '.crown', 'position'],
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
  // 先剥注释：规则之间夹着一段注释时，上一条的 `}` 与本条选择器就被隔开，锚点落不到这里
  const pattern = new RegExp('(^|\\})\\s*' + escaped + '\\s*\\{([^}]*)\\}', 'g');
  let last = null;
  for (const match of css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(pattern)) last = match[2];
  return last;
}

/**
 * 场景类型闸：声明了就必须有实现。
 *
 * <p>supportedSceneTypes 是对模型的承诺——它照着这份清单选每一屏的画面。
 * 清单里躺着一个渲染端没实现的类型（曾有过 timeline / list / qa），模型照着写了，
 * 画面上却什么都不会发生，直到成片才看得出来。
 *
 * <p>证据必须是这套模板独有的装配，不能用「每套都挂」的文件凑数：
 * fill.js 会读 .scene-figure，但它只负责填槽位，图位样式在版式里；
 * storyboard.css 每条分镜模板都会挂，里面的 number / compare 规则不是某套模板的实现证据。
 * 所以判据写成「按类型检查哪一份装配 + 在那里找什么痕迹」，不扫全量文本。
 *
 * <p>这里的全集就是渲染端实现了的类型。quote / rank 的唯一实现（news-quote / news-rank 两套版式）
 * 已于 2026-11-25 连同模板删除，它们在这里与 Java 契约里同时除名。
 */
const SCENE_TYPE_EVIDENCE = {
  headline: { files: () => [path.join(GEN, 'behaviors', 'headline.js')], pattern: /headlineSlots|takeover/ },
  chart: { files: () => [path.join(GEN, 'behaviors', 'chart.css')], pattern: /#chart-slot/ },
  number: { files: (cfg) => cfg.skeletons
    ? [{ file: path.join(GEN, 'behaviors', 'skeleton.js'), pattern: /number\s*:/ }]
    : cfg.race ? [{ file: path.join(GEN, 'behaviors', 'race.js'), pattern: /row-value|toFixed/ }] : [] },
  compare: { files: () => [path.join(GEN, 'behaviors', 'compare.js')], pattern: /compareFacts|compareDataset/ },
  // 图位靠版式自己声明：fill.js 只是往槽位里塞图，所有模板都挂它
  image: { files: (cfg) => [path.join(LAYOUTS, cfg.style, `${cfg.layout}.css`)], pattern: /\.scene-figure\b|\.has-image\b/,
    require: (cfg) => cfg.capabilities.includes('image') },
};

function checkSceneTypes(cfg) {
  for (const type of cfg.supportedSceneTypes ?? ['headline', 'text']) {
    // text 是缺省形态：没有专属规则才是对的，不算「声明了没实现」
    if (type === 'text') continue;
    const evidence = SCENE_TYPE_EVIDENCE[type];
    // 表里没有的类型直接拒：新类型先在这里登记它的实现痕迹，闸才认
    if (!evidence) fail(`${cfg.key}: 未知场景类型「${type}」，先在 build-templates.mjs 的 SCENE_TYPE_EVIDENCE 登记`);
    // files 可以返回 {file, pattern} 列表（同一类型在不同装配里有不同痕迹），
    // 也可以是纯文件列表配表上的公共 pattern——两种都认。
    const entries = evidence.files(cfg).map((item) =>
      typeof item === 'string' ? { file: item, pattern: evidence.pattern } : item);
    const hasEvidence = entries.length > 0 && (!evidence.require || evidence.require(cfg))
      && entries.every((entry) => entry.pattern.test(read(entry.file)));
    if (!hasEvidence) {
      fail(`${cfg.key}: 声明了场景类型「${type}」，但这份模板没有装配它的实现`);
    }
  }
}

/**
 * 模板身份闸：两套模板不能是同一套。
 *
 * <p>身份 = 结构 + 动效。结构是选择器集合与属性名集合（数值、颜色剥掉），
 * 动效是挂载的行为脚本组合。只换色换数值不算另一套模板——那叫配色。
 *
 * <p>口径（2026-11-25 拍板）：综合相似度 = (结构 + 动效) / 2 ≥ 90% 即判为重复。
 * 阈值按改造前的 23 套实测标定：只换色换数值的版式两两 92%–100%，各有独立构图的 ≤ 88%。
 */
const IDENTITY_THRESHOLD = 0.9;

function identityOf(cfg) {
  const sources = styleFiles(cfg).map((file) => read(file).replace(/\/\*[\s\S]*?\*\//g, ''));
  const selectors = new Set();
  const properties = new Set();
  for (const css of sources) {
    for (const match of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      for (const selector of match[1].split(',')) {
        const normalized = selector.trim().replace(/\s+/g, '');
        if (normalized && !normalized.startsWith('@')) selectors.add(normalized);
      }
      for (const declaration of match[2].split(';')) {
        const property = declaration.split(':')[0]?.trim();
        // 自定义属性按「名字」算结构：--glow-x 与 --rank 是两种版式手段，但它的取值不算
        if (property) properties.add(property);
      }
    }
  }
  return { selectors, properties, behaviors: new Set(behaviorFiles(cfg).map((file) => path.basename(file))) };
}

function jaccard(left, right) {
  const union = new Set([...left, ...right]).size;
  const intersection = [...left].filter((item) => right.has(item)).length;
  return union === 0 ? 1 : intersection / union;
}

/** 只在整次构建里比：--template 只生成一套时无从比较。 */
function checkTemplateIdentity(jobs) {
  // 横版是竖版的派生变体（generatedFrom），本来就该像，不参与比较
  const groups = new Map();
  for (const job of jobs.filter((item) => !item.generated)) {
    const ratio = ratioOf(job.cfg);
    if (!groups.has(ratio)) groups.set(ratio, []);
    // 身份签名在分组时算一次，别在两两比较里反复读同一份 CSS
    groups.get(ratio).push({ cfg: job.cfg, identity: identityOf(job.cfg) });
  }
  for (const group of groups.values()) {
    for (let i = 0; i < group.length; i++) {
      for (let j = i + 1; j < group.length; j++) {
        const score = similarity(group[i].identity, group[j].identity);
        if (score >= IDENTITY_THRESHOLD) {
          fail(`${group[i].cfg.key} 与 ${group[j].cfg.key} 综合相似度 ${(score * 100).toFixed(0)}%，超过 ${IDENTITY_THRESHOLD * 100}%：`
            + '只换色换数值不算另一套模板——删掉一套，或给它独立的构图与动效');
        }
      }
    }
  }
}

function similarity(left, right) {
  const structure = (jaccard(left.selectors, right.selectors) + jaccard(left.properties, right.properties)) / 2;
  return (structure + jaccard(left.behaviors, right.behaviors)) / 2;
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

/**
 * SplitText 与 three 同一套规矩：渲染不联网，所以随模板提交；--check 比哈希，
 * 否则依赖升级了而模板里的副本还是旧的，谁都不会发现。
 */
function vendorSplitText(dir) {
  const key = path.basename(dir);
  if (!fs.existsSync(SPLIT_TEXT_SOURCE)) {
    fail(`${key}: 找不到 SplitText 构建产物 ${SPLIT_TEXT_SOURCE}（先在 data/tools 安装依赖）`);
  }
  const target = path.join(dir, SPLIT_TEXT_FILE);
  const same = fs.existsSync(target) && sha256(target) === sha256(SPLIT_TEXT_SOURCE);
  if (same) return true;
  if (CHECK_ONLY) {
    console.log(`✗ ${key}: ${SPLIT_TEXT_FILE} 与依赖里的版本不一致（运行不带 --check 以重新拷贝）`);
    return false;
  }
  fs.copyFileSync(SPLIT_TEXT_SOURCE, target);
  return true;
}

/**
 * 附加库都排在骨架里的 gsap.min.js 之后——插件必须在 gsap 之后加载。
 */
function extraLib(cfg) {
  const libs = [`<script src="${SPLIT_TEXT_FILE}"></script>`];
  if (cfg.capabilities.includes('globe')) libs.push('<script src="three.min.js"></script>');
  return libs.join('');
}

const VARIANT_GENERATED = new Set([
  'template.config.json', 'index.html', 'template.json', 'index.motion.json', 'assets-manifest.json', 'LOCK.json',
]);

function syncVariantAssets(job) {
  const copy = (sourceDir, targetDir) => {
    if (!fs.existsSync(targetDir)) {
      if (CHECK_ONLY) fail(`${job.cfg.key}: 横版模板目录缺失（运行不带 --check 以生成）`);
      fs.mkdirSync(targetDir, { recursive: true });
    }
    for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
      if (entry.name.startsWith('.') || VARIANT_GENERATED.has(entry.name)) continue;
      const source = path.join(sourceDir, entry.name);
      const target = path.join(targetDir, entry.name);
      if (entry.isDirectory()) {
        copy(source, target);
        continue;
      }
      if (fs.existsSync(target) && sha256(target) === sha256(source)) continue;
      if (CHECK_ONLY) fail(`${job.cfg.key}: 横版静态资产 ${path.relative(job.dir, target)} 与源模板不一致`);
      fs.copyFileSync(source, target);
    }
  };
  copy(job.sourceDir, job.dir);
}

function build(job) {
  const cfg = job.cfg;
  if (job.generated) syncVariantAssets(job);
  checkStyleContract(cfg);
  checkSceneTypes(cfg);
  if (!vendorSplitText(job.dir)) return false;
  if (cfg.capabilities.includes('globe') && !vendorThree(job.dir)) {
    return false;
  }
  // 生成产物都从配置派生，--check 就都要比：
  // 只比 index.html 的话，手改 index.motion.json 能悄悄放宽断言而检查不出来。
  const generated = {
    'index.html': render(cfg),
    'template.json': templateJson(cfg),
    'index.motion.json': motionJson(cfg),
    ...(job.generated ? { 'template.config.json': JSON.stringify(cfg, null, 2) + '\n' } : {}),
  };
  checkWindows(cfg, generated['index.html']);
  const indexPath = path.join(job.dir, 'index.html');

  if (CHECK_ONLY) {
    const manifest = manifestJson(job.dir);
    const expected = {
      ...generated,
      'assets-manifest.json': manifest,
      'LOCK.json': lockJson(job.dir, manifest),
    };
    const drifted = Object.entries(expected)
      .filter(([name, content]) => {
        const file = path.join(job.dir, name);
        return !fs.existsSync(file) || normalizeEol(read(file)) !== content;
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
    fs.writeFileSync(path.join(job.dir, name), content);
  }
  writeManifest(job.dir);
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

  if (CHECK_ONLY && STAGE) fail('--check 不能与 --stage 同用：前者只比对，后者会写暂存目录');
  if (STAGE) {
    const dir = path.join(TEMPLATES, STAGE);
    if (!fs.existsSync(path.join(dir, 'template.config.json'))) {
      fail(`找不到模板 ${STAGE}（或其缺少 template.config.json）`);
    }
    stage(dir, STAGE_OUT ? path.resolve(STAGE_OUT) : path.resolve(TEMPLATES, '..', '.stage', STAGE));
    return;
  }

  const jobs = buildJobs();
  const targets = ONLY ? jobs.filter((job) => job.cfg.key === ONLY) : jobs;
  if (ONLY && targets.length === 0) fail(`找不到模板 ${ONLY}（或其缺少 template.config.json）`);

  // 跨模板的不变量只在整次构建时查；出错直接抛出，否则第一套重复模板就会先被写盘
  if (!ONLY) {
    try {
      checkTemplateIdentity(jobs);
    } catch (e) {
      if (!(e instanceof BuildError)) throw e;
      console.error(`✗ ${e.message}`);
      process.exit(1);
    }
  }

  let ok = true;
  for (const job of targets) {
    try {
      ok = build(job) && ok;
      if (VERIFY && !CHECK_ONLY) ok = verify(job.dir) && ok;
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
