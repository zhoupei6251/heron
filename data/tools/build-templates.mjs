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

const CAPABILITIES = new Set(['chart', 'image']);

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
  cfg.sceneTypes ??= [];
  cfg.activeScenes ??= cfg.scenes;
  cfg.defaults ??= {};
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
  if (cfg.capabilities.includes('chart')) {
    out.push({
      id: 'chart', type: 'enum', label: '图表模块', default: d.chart ?? 'line',
      options: [
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
  }
  for (let i = 1; i <= cfg.scenes; i++) {
    out.push({ id: `scene${i}_type`, type: 'string', label: '场景类型', default: '' });
  }
  if (cfg.capabilities.includes('image')) {
    for (let i = 1; i <= cfg.scenes; i++) {
      out.push({ id: `scene${i}_image`, type: 'string', label: '第' + i + '屏配图', default: '' });
    }
  }
  return cfg.sceneTypes.length ? out : out.filter((v) => !v.id.endsWith('_type'));
}

function sceneBlocks(cfg) {
  const slot = cfg.duration / cfg.activeScenes;
  const defaults = cfg.defaults.scenes ?? [];
  const blocks = [];
  for (let i = 1; i <= cfg.scenes; i++) {
    const active = i <= cfg.activeScenes;
    const start = active ? (i - 1) * slot : cfg.duration;
    const dur = active ? slot : 0.01;
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

function chartSlot(cfg) {  if (!cfg.capabilities.includes('chart')) return '';
  return `
      <section id="chart-slot" class="clip" data-start="0" data-duration="${num(cfg.duration)}" data-track-index="1">
        <svg id="chart" viewBox="0 0 912 760" role="img" aria-label="数据图表"></svg>
      </section>
`;
}

function kpiBlock(cfg) {
  if (!cfg.capabilities.includes('chart')) return '';
  return `        <div id="kpi">
          <span id="kpi-value"></span>
          <span id="kpi-unit"></span>
          <span id="kpi-change"></span>
        </div>`;
}

function styles(cfg) {
  const files = [
    path.join(SHARED, 'base.css'),
    path.join(SHARED, 'tokens', `${cfg.style}.css`),
  ];
  if (cfg.capabilities.includes('chart')) files.push(path.join(GEN, 'behaviors', 'chart.css'));
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
  if (cfg.capabilities.includes('chart')) files.push(path.join(GEN, 'behaviors', 'chart.js'));
  files.push(path.join(GEN, 'motion.js'));
  for (const file of files) {
    if (!fs.existsSync(file)) fail(`${cfg.key}: 缺少脚本片段 ${path.relative(TEMPLATES, file)}`);
  }
  return files.map((file) => read(file).trimEnd()).join('\n\n');
}

function render(cfg) {
  const skeleton = read(path.join(GEN, 'skeleton.html'));
  const slots = {
    VARIABLES_JSON: escapeAttr(JSON.stringify(variables(cfg))),
    WIDTH: cfg.width,
    HEIGHT: cfg.height,
    DURATION: num(cfg.duration),
    STYLE: cfg.style,
    AMBIENT_BLOCK: ambientBlock(cfg),
    PAGE_TITLE: escapeHtml(cfg.pageTitle ?? cfg.name),
    STYLES: styles(cfg),
    KPI_BLOCK: kpiBlock(cfg),
    CHART_SLOT: chartSlot(cfg),
    SCENES_BLOCK: sceneBlocks(cfg),
    DEFAULT_TITLE: escapeHtml(cfg.defaults.title),
    DEFAULT_KICKER: escapeHtml(cfg.defaults.kicker ?? ''),
    DEFAULT_CLOSING: escapeHtml(cfg.defaults.closing),
    DEFAULT_SOURCE: escapeHtml(cfg.defaults.sourceName),
    SCRIPT: script(cfg),
  };
  return skeleton.replace(/\{\{([A-Z_]+)\}\}/g, (match, name) => {
    if (!(name in slots)) fail(`${cfg.key}: 骨架里有未定义的占位符 ${match}`);
    return slots[name];
  });
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
  return {
    key: cfg.style,
    name: meta.name,
    label: meta.label,
    role: meta.role,
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

function build(dir) {
  const cfg = loadConfig(dir);
  // 三个产物都从配置派生，--check 就三个都要比：
  // 只比 index.html 的话，手改 index.motion.json 能悄悄放宽断言而检查不出来。
  const generated = {
    'index.html': render(cfg),
    'template.json': templateJson(cfg),
    'index.motion.json': motionJson(cfg),
  };
  const indexPath = path.join(dir, 'index.html');

  if (CHECK_ONLY) {
    const drifted = Object.entries(generated)
      .filter(([name, expected]) => {
        const file = path.join(dir, name);
        return !fs.existsSync(file) || read(file) !== expected;
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
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000,
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
