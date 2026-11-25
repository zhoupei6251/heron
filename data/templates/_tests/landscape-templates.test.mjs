import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '../../..');
const templates = path.join(root, 'data/templates');
const build = (args) => spawnSync(process.execPath, [path.join(root, 'data/tools/build-templates.mjs'), ...args], {
  cwd: root,
  encoding: 'utf8',
});
const landscape = ['news-16x9', 'news-data-16x9'];
// 竖版主干：加横版、清重复模板都不该把它们变成横版（按比例判，不钉产物哈希——模板本身会合理演进）
const vertical = ['news-9x16', 'news-data-9x16', 'news-minimal-9x16', 'news-trend-9x16', 'news-data-3d-9x16'];

const sha256 = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const filesUnder = (dir) => {
  const files = [];
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      if (entry.name.startsWith('.') || entry.name === 'LOCK.json' || entry.name === 'assets-manifest.json') continue;
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else files.push(path.relative(dir, full).split(path.sep).join('/'));
    }
  };
  walk(dir);
  return files.sort();
};
// 与 build-templates.mjs 的 ruleBlock 同口径：先剥注释，选择器前必须是上一条的 `}` 或开头。
// 不带锚点会把 `.scene.has-compare .body { display: none }` 这类覆盖规则也算进来，取到最后一条时读不到字号。
const lastRule = (css, selector) => {
  const escaped = selector.replace(/[.*#]/g, '\\$&');
  const source = css.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...source.matchAll(new RegExp(`(^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`, 'g'))].at(-1)?.[2] ?? '';
};
const pixels = (block, property) => Number(block.match(new RegExp(`${property}:\\s*(\\d+)px`))?.[1]);

for (const key of landscape) {
  const dir = path.join(templates, key);
  const run = build(['--check', '--template', key]);
  assert.equal(run.status, 0, `${run.stdout}\n${run.stderr}`);
  assert.ok(fs.existsSync(dir), `缺少横版模板 ${key}`);
  assert.ok(run.stdout.includes(`✓ ${key}`), `--check 没有覆盖 ${key}`);

  const cfg = JSON.parse(fs.readFileSync(path.join(dir, 'template.config.json'), 'utf8'));
  assert.equal(cfg.key, key);
  assert.equal(cfg.width, 1920);
  assert.equal(cfg.height, 1080);
  assert.deepEqual(cfg.layouts, ['16x9']);

  const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
  assert.match(html, /data-width="1920"/);
  assert.match(html, /data-height="1080"/);
  const shift = Number([...html.matchAll(/--slide-shift:\s*(\d+)px/g)].at(-1)?.[1]);
  assert.ok(shift >= 180, `${key} 横版转场位移不足`);
  assert.ok(pixels(lastRule(html, '#bottom-bar'), 'bottom') <= 72, `${key} 底栏侵入字幕安全区`);
  assert.ok(pixels(lastRule(html, '.scene'), 'bottom') >= 150, `${key} 场景侵入底栏`);
  assert.ok(pixels(lastRule(html, '.body'), 'font-size') >= 32, `${key} 横版正文小于 32px`);

  const meta = JSON.parse(fs.readFileSync(path.join(dir, 'template.json'), 'utf8'));
  assert.equal(meta.aspectRatio, '16:9');
  if (key === 'news-data-16x9') {
    const motion = JSON.parse(fs.readFileSync(path.join(dir, 'index.motion.json'), 'utf8'));
    const title = motion.assertions.find((entry) => entry.selector === '#title');
    assert.ok(title.bySec >= 5, '数据横版标题闸必须允许首屏 headline 接管结束');
  }
  const lock = JSON.parse(fs.readFileSync(path.join(dir, 'LOCK.json'), 'utf8'));
  assert.equal(lock.templateKey, key);
  assert.equal(lock.assetManifestSha256, sha256(path.join(dir, 'assets-manifest.json')));
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'assets-manifest.json'), 'utf8'));
  assert.deepEqual(manifest.files.map((entry) => entry.path).sort(), filesUnder(dir), `${key} 资产清单文件集不一致`);
  for (const entry of manifest.files) {
    const file = path.join(dir, entry.path);
    assert.equal(sha256(file), entry.sha256, `${key}/${entry.path} 资产哈希不一致`);
    assert.equal(fs.statSync(file).size, entry.bytes, `${key}/${entry.path} 资产字节数不一致`);
  }
}

for (const key of vertical) {
  const dir = path.join(templates, key);
  const meta = JSON.parse(fs.readFileSync(path.join(dir, 'template.json'), 'utf8'));
  assert.equal(meta.aspectRatio, '9:16', `${key} 竖版模板的比例变了`);
  assert.match(fs.readFileSync(path.join(dir, 'index.html'), 'utf8'), /data-height="1920"/, `${key} 竖版模板被改成了横版画布`);
}

const probeDir = path.join(templates, 'news-16x9');
const backupDir = path.join(root, 'data/.stage', `.news-16x9-test-${process.pid}`);
fs.mkdirSync(path.dirname(backupDir), { recursive: true });
assert.equal(fs.existsSync(backupDir), false);
fs.renameSync(probeDir, backupDir);
try {
  const missing = build(['--check']);
  assert.notEqual(missing.status, 0, '缺少横版目录时 --check 必须失败');
  assert.equal(fs.existsSync(probeDir), false, '--check 不得创建缺失的横版目录');
} finally {
  if (fs.existsSync(probeDir)) fs.rmSync(probeDir, { recursive: true, force: true });
  fs.renameSync(backupDir, probeDir);
}

const stageOut = path.join(root, 'data/.stage', `check-stage-${process.pid}`);
assert.equal(fs.existsSync(stageOut), false);
try {
  const combined = build(['--check', '--stage', 'news-9x16', '--out', stageOut]);
  assert.notEqual(combined.status, 0, '--check 与 --stage 不得组合');
  assert.equal(fs.existsSync(stageOut), false, '--check --stage 不得写盘');
} finally {
  fs.rmSync(stageOut, { recursive: true, force: true });
}

const extraAsset = path.join(templates, 'news-16x9', 'unexpected-test-asset.txt');
assert.equal(fs.existsSync(extraAsset), false);
fs.writeFileSync(extraAsset, 'fault injection\n');
try {
  const drifted = build(['--check', '--template', 'news-16x9']);
  assert.notEqual(drifted.status, 0, '出现未登记资产时 --check 必须失败');
} finally {
  fs.rmSync(extraAsset, { force: true });
}

// ---- 构建期闸的回归 ----
// 这两道闸都是「不报错即通过」。不确认它们真的会报错，一道写坏的闸（曾有过：相似度算出 NaN，
// 任何一对都判为不重复）会一直静默放行。所以每个闸都要有一个「必须被拒」的样本。

/** 复制一套模板做故障注入：只带配置，版式按 mutate 的结果另存一份，不碰原模板。 */
const withCopy = (source, key, mutate) => {
  const cfg = JSON.parse(fs.readFileSync(path.join(templates, source, 'template.config.json'), 'utf8'));
  const dir = path.join(templates, key);
  const layout = path.join(templates, 'layouts', cfg.style, `${key}.css`);
  assert.equal(fs.existsSync(dir) || fs.existsSync(layout), false, `残留的测试副本 ${key}`);
  const sourceCss = fs.readFileSync(path.join(templates, 'layouts', cfg.style, `${cfg.layout}.css`), 'utf8');
  cfg.key = key;
  cfg.layout = key;
  // 源模板若声明了多种比例，副本只保留当前这一种，免得多出一个横版变体目录
  cfg.layouts = [cfg.width > cfg.height ? '16x9' : '9x16'];
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(layout, mutate(cfg, sourceCss));
  fs.writeFileSync(path.join(dir, 'template.config.json'), `${JSON.stringify(cfg, null, 2)}\n`);
  return { dir, layout };
};
const dropCopy = (copy) => {
  fs.rmSync(copy.dir, { recursive: true, force: true });
  fs.rmSync(copy.layout, { force: true });
};

// 1) 模板身份闸：只换色换数值的副本必须被拒（它和原件是同一套模板）
{
  const copy = withCopy('news-9x16', 'zz-dup-9x16', (cfg, css) => css
    .replace(/#[0-9a-fA-F]{3,8}\b/g, '#654321')
    .replace(/(\d+(?:\.\d+)?)px\b/g, '9px')
    .replace(/rgba?\([^)]*\)/g, 'rgba(1,2,3,0.5)'));
  try {
    const run = build(['--check']);
    assert.notEqual(run.status, 0, '重复模板必须让构建失败');
    assert.match(run.stdout + run.stderr, /相似度/, `身份闸没有报出相似度：\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout + run.stderr, /zz-dup-9x16/, '身份闸的报错要点名是哪两套');
  } finally {
    dropCopy(copy);
  }
}

// 2) 场景类型闸：声明了渲染端没有的类型必须被拒
{
  const copy = withCopy('news-minimal-9x16', 'zz-badtype-9x16', (cfg, css) => {
    cfg.supportedSceneTypes = ['headline', 'text', 'timeline'];
    return css;
  });
  try {
    const run = build(['--check', '--template', 'zz-badtype-9x16']);
    assert.notEqual(run.status, 0, '声明未实现的场景类型必须让构建失败');
    assert.match(run.stdout + run.stderr, /timeline/, `场景类型闸没有点名类型：\n${run.stdout}\n${run.stderr}`);
  } finally {
    dropCopy(copy);
  }
}

// 3) 已知的 type 但没有实现也必须被拒：旧闸扫全量文本，fill.js 让 image 空声明也过。
//    现在证据只认模板自己的版式图位（且要 capabilities: image）。
{
  const copy = withCopy('news-minimal-9x16', 'zz-noimage-9x16', (cfg, css) => {
    cfg.supportedSceneTypes = ['headline', 'text', 'image'];
    return css;
  });
  try {
    const run = build(['--check', '--template', 'zz-noimage-9x16']);
    assert.notEqual(run.status, 0, '没有 image 能力却声明 image 必须让构建失败');
    assert.match(run.stdout + run.stderr, /image/, `场景类型闸没有点名类型：\n${run.stdout}\n${run.stderr}`);
  } finally {
    dropCopy(copy);
  }
}

// 4) 现有模板本身必须过闸：整次构建零漂移、零重复
{
  const run = build(['--check']);
  assert.equal(run.status, 0, `现有模板没过构建检查：\n${run.stdout}\n${run.stderr}`);
  assert.equal((run.stdout.match(/^✓ /gm) ?? []).length, fs.readdirSync(templates, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !entry.name.startsWith('_') && entry.name !== 'layouts').length, '--check 必须逐套覆盖');
}

console.log('横版模板产物、竖版回归与构建期闸检查通过');
