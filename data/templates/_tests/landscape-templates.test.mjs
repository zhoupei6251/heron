import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '../../..');
const templates = path.join(root, 'data/templates');
const landscape = ['news-16x9', 'news-data-16x9', 'news-magazine-16x9', 'news-rank-16x9'];
const verticalHashes = {
  'news-9x16': 'b28b1c22caed3b743d2c97ebe30342c438ee05091eeb713c5fa5ff2cbc0e5c60',
  'news-magazine-9x16': '7a4ef67175747a554ed1733fe271ca0aaac92b86ad8e10408f027ff7b5dea69f',
  'news-rank-9x16': 'ca5a2ed34ee758c4df7e85a0cdab8cbafab32b845b5ded9f234edd9a81b2120d',
};
const verticalMetadataHashes = {
  'news-data-9x16/template.json': '3ec27dcc801e55a1b54ddb041c76aa62cdecd4ef40c7abf65b27d8490d5c0d6e',
  'news-data-9x16/index.motion.json': 'b02cc58c005cc756b37f8eb1c40f22343baeae2845a0968577a437df7bbee3e5',
};

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
const lastRule = (css, selector) => {
  const escaped = selector.replace(/[.*#]/g, '\\$&');
  return [...css.matchAll(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, 'g'))].at(-1)?.[1] ?? '';
};
const pixels = (block, property) => Number(block.match(new RegExp(`${property}:\\s*(\\d+)px`))?.[1]);

for (const key of landscape) {
  const dir = path.join(templates, key);
  const run = spawnSync(process.execPath, [path.join(root, 'data/tools/build-templates.mjs'), '--check', '--template', key], {
    cwd: root,
    encoding: 'utf8',
  });
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

for (const [key, expected] of Object.entries(verticalHashes)) {
  assert.equal(sha256(path.join(templates, key, 'index.html')), expected, `${key} 竖版 HTML 发生变化`);
}
for (const [file, expected] of Object.entries(verticalMetadataHashes)) {
  assert.equal(sha256(path.join(templates, file)), expected, `${file} 竖版元信息发生变化`);
}

const probeDir = path.join(templates, 'news-16x9');
const backupDir = path.join(root, 'data/.stage', `.news-16x9-test-${process.pid}`);
fs.mkdirSync(path.dirname(backupDir), { recursive: true });
assert.equal(fs.existsSync(backupDir), false);
fs.renameSync(probeDir, backupDir);
try {
  const missing = spawnSync(process.execPath, [path.join(root, 'data/tools/build-templates.mjs'), '--check'], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.notEqual(missing.status, 0, '缺少横版目录时 --check 必须失败');
  assert.equal(fs.existsSync(probeDir), false, '--check 不得创建缺失的横版目录');
} finally {
  if (fs.existsSync(probeDir)) fs.rmSync(probeDir, { recursive: true, force: true });
  fs.renameSync(backupDir, probeDir);
}

const stageOut = path.join(root, 'data/.stage', `check-stage-${process.pid}`);
assert.equal(fs.existsSync(stageOut), false);
try {
  const combined = spawnSync(process.execPath, [
    path.join(root, 'data/tools/build-templates.mjs'), '--check', '--stage', 'news-9x16', '--out', stageOut,
  ], { cwd: root, encoding: 'utf8' });
  assert.notEqual(combined.status, 0, '--check 与 --stage 不得组合');
  assert.equal(fs.existsSync(stageOut), false, '--check --stage 不得写盘');
} finally {
  fs.rmSync(stageOut, { recursive: true, force: true });
}

const extraAsset = path.join(templates, 'news-16x9', 'unexpected-test-asset.txt');
assert.equal(fs.existsSync(extraAsset), false);
fs.writeFileSync(extraAsset, 'fault injection\n');
try {
  const drifted = spawnSync(process.execPath, [
    path.join(root, 'data/tools/build-templates.mjs'), '--check', '--template', 'news-16x9',
  ], { cwd: root, encoding: 'utf8' });
  assert.notEqual(drifted.status, 0, '出现未登记资产时 --check 必须失败');
} finally {
  fs.rmSync(extraAsset, { force: true });
}

console.log('横版模板产物与竖版回归检查通过');
