#!/usr/bin/env node
// 为新闻模板目录生成 LOCK.json：谁改模板谁跑一次，assets 闸据此判定资产漂移。
// 字体是十二套共用的，不放在模板目录里，默认取模板根目录同级的 fonts/。
// 用法: node script/tools/template-lock.mjs <模板目录> [--font=共享字体路径] [--voice-key=xxx] [--voice-version=1] [--template-version=1]
import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';

const args = process.argv.slice(2);
const dir = args.find(a => !a.startsWith('--'));
if (!dir) {
  console.error('用法: node script/tools/template-lock.mjs <模板目录> [--font=共享字体路径] [--voice-key=xxx] [--voice-version=1] [--template-version=1]');
  process.exit(2);
}
const opt = (name, fallback) => {
  const hit = args.find(a => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};

const root = resolve(dir);
const manifest = join(root, 'assets-manifest.json');
const font = opt('font', join(root, '..', '..', 'fonts', 'NotoSansSC-Regular.otf'));
for (const file of [manifest, font]) {
  if (!statSync(file, { throwIfNoEntry: false })?.isFile()) {
    console.error(`缺少文件: ${file}`);
    process.exit(1);
  }
}

const sha256 = file => createHash('sha256').update(readFileSync(file)).digest('hex');
const lock = {
  templateKey: basename(root),
  templateVersion: opt('template-version', '1'),
  fontSha256: sha256(font),
  voiceKey: opt('voice-key', 'zh-CN-XiaoxiaoNeural'),
  voiceVersion: opt('voice-version', '1'),
  assetManifestSha256: sha256(manifest)
};

const out = join(root, 'LOCK.json');
writeFileSync(out, `${JSON.stringify(lock, null, 2)}\n`, 'utf8');
console.log(`已写入 ${out}`);
for (const [key, value] of Object.entries(lock)) {
  console.log(`  ${key}: ${value}`);
}
