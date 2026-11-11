#!/usr/bin/env node
// 生成新闻竖版模板的版式变体：契约冻结（同样的 15 个变量与槽位），只在构图、字体层级、动效节奏上分叉。
// 所有套共用同一份几何骨架（安全区、栏位、槽位），差异只写在各自的 css / motion / fx 里。
// 字体不进模板目录：<模板根目录>/../fonts/NotoSansSC-Regular.otf 只存一份（可变字体 wght 100–900），
// 由 NewsProductionPipeline 在复制模板时注入 composition；单独预览模板要先把这份字体拷进模板的 fonts/。
// 用法: node script/tools/news-templates.mjs <模板根目录> [--force]
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const args = process.argv.slice(2);
const root = args.find(a => !a.startsWith('--'));
if (!root) {
  console.error('用法: node script/tools/news-templates.mjs <模板根目录> [--force]');
  process.exit(2);
}
const force = args.includes('--force');
const rootDir = resolve(root);
const base = join(rootDir, 'news-9x16');
if (!existsSync(base)) {
  console.error(`缺少基线模板，先准备 ${base}`);
  process.exit(1);
}

const VARS = `[
  {"id":"title","type":"string","label":"标题","maxLength":15,"default":"新一代旗舰手机发布"},
  {"id":"scene1_lead","type":"string","label":"第一屏领句","maxLength":40,"default":"电池容量提升两成"},
  {"id":"scene1_body","type":"string","label":"第一屏正文","maxLength":600,"default":"厂商在今日上午的线上发布会上公布新一代旗舰机型，主打续航与影像。官方资料显示，新机电池容量较上一代提升约两成，整机重量基本持平。"},
  {"id":"scene2_lead","type":"string","label":"第二屏领句","maxLength":40,"default":"影像算法全面重写"},
  {"id":"scene2_body","type":"string","label":"第二屏正文","maxLength":600,"default":"影像部分引入新的降噪与色彩管线，官方称弱光成片速度提升明显。发布会现场展示的样张来自工程机，量产版本仍可能调整。"},
  {"id":"scene3_lead","type":"string","label":"第三屏领句","maxLength":40,"default":"起售价保持不变"},
  {"id":"scene3_body","type":"string","label":"第三屏正文","maxLength":600,"default":"价格方面，起步版本与上一代首发价一致，存储升级需要加价。渠道商反馈，首销期赠品与以旧换新补贴是主要促销手段。"},
  {"id":"scene4_lead","type":"string","label":"第四屏领句","maxLength":40,"default":"现货发售时间已定"},
  {"id":"scene4_body","type":"string","label":"第四屏正文","maxLength":600,"default":"官方宣布在下周开启全渠道现货发售，首批覆盖国内主要城市。海外上市时间表尚未公布。"},
  {"id":"scene5_lead","type":"string","label":"第五屏领句","maxLength":40,"default":"竞品同期密集上新"},
  {"id":"scene5_body","type":"string","label":"第五屏正文","maxLength":600,"default":"过去一个月内，多家厂商集中发布新机，旗舰档位竞争加剧。分析机构预计本季度整体出货量小幅回升。"},
  {"id":"scene6_lead","type":"string","label":"第六屏领句","maxLength":40,"default":"售后政策同步调整"},
  {"id":"scene6_body","type":"string","label":"第六屏正文","maxLength":600,"default":"厂商将整机保修期延长，并新增电池健康度检测服务。具体条款以官网公告为准。"},
  {"id":"closing","type":"string","label":"结尾口播","maxLength":200,"default":"更多机型对比与实测，我们会在后续持续跟进。"},
  {"id":"source_name","type":"string","label":"来源名称","maxLength":80,"default":"公开报道整理"},
  {"id":"accent","type":"color","label":"品牌色","default":"#d97706"},
  {"id":"scene_count","type":"number","label":"场景数","min":3,"max":6,"default":6}
]`;

const DEFAULT_SCENES = [
  ['电池容量提升两成', '厂商在今日上午的线上发布会上公布新一代旗舰机型，主打续航与影像。官方资料显示，新机电池容量较上一代提升约两成，整机重量基本持平。'],
  ['影像算法全面重写', '影像部分引入新的降噪与色彩管线，官方称弱光成片速度提升明显。发布会现场展示的样张来自工程机，量产版本仍可能调整。'],
  ['起售价保持不变', '价格方面，起步版本与上一代首发价一致，存储升级需要加价。渠道商反馈，首销期赠品与以旧换新补贴是主要促销手段。'],
  ['现货发售时间已定', '官方宣布在下周开启全渠道现货发售，首批覆盖国内主要城市。海外上市时间表尚未公布。'],
  ['竞品同期密集上新', '过去一个月内，多家厂商集中发布新机，旗舰档位竞争加剧。分析机构预计本季度整体出货量小幅回升。'],
  ['售后政策同步调整', '厂商将整机保修期延长，并新增电池健康度检测服务。具体条款以官网公告为准。']
];

const FONT_FACE = `      @font-face {
        font-family: "Noto Sans SC";
        src: url("fonts/NotoSansSC-Regular.otf") format("truetype");
        font-weight: 100 900;
        font-display: block;
      }
      * { box-sizing: border-box; }
      .lead, .body { clip-path: inset(0); }
      /* 正文数字高亮：让数据自己跳出来，而不是混在句子里的普通文字 */
      .num {
        color: var(--accent);
        font-weight: 700;
        font-size: 1.1em;
        font-variant-numeric: tabular-nums;
      }`;

function scenesMarkup() {
  const spans = [8, 8, 8, 7, 7, 7];
  let cursor = 0;
  return DEFAULT_SCENES.map(([lead, body], index) => {
    const start = cursor;
    cursor += spans[index];
    return `      <section id="scene-${index + 1}" class="clip scene" data-start="${start}" data-duration="${spans[index]}" data-track-index="1" data-scene-index="${index + 1}">
        <p class="lead" data-var-text="scene${index + 1}_lead">${lead}</p>
        <p class="body" data-var-text="scene${index + 1}_body">${body}</p>
      </section>`;
  }).join('\n');
}

// 八套共用的几何骨架：正文与结尾口播共用同一条安全带（顶 320 底 420），带内垂直居中，
// 页脚整体上抬到底部 300px，避开竖屏平台压在画面最底部的文案与按钮区。
function layout(tpl) {
  const scenePad = tpl.scenePad || `0 ${tpl.pad}`;
  return `      .clip { position: absolute; inset: 0; }
      .scene {
        top: ${tpl.sceneTop};
        bottom: 420px;
        left: 0;
        right: 0;
        padding: ${scenePad};
        display: flex;
        flex-direction: column;
        justify-content: ${tpl.sceneJustify || 'center'};
      }
      #closing-slot {
        top: ${tpl.sceneTop};
        bottom: 420px;
        padding: ${tpl.closingPad || scenePad};
        display: flex;
        align-items: center;
      }
      #bottom-bar { position: absolute; left: 0; right: 0; bottom: 300px; padding: 0 ${tpl.pad}; }
      #progress {
        display: block;
        height: 6px;
        border-radius: 3px;
        background: var(--accent);
        transform-origin: left center;
        margin-bottom: 28px;
      }
      #meta-row { display: flex; align-items: center; justify-content: space-between; }
      #aigc-badge {
        font-size: 28px;
        padding: 12px 24px;
        border-radius: 999px;
        white-space: nowrap;
        color: var(--badge-fg, #0b1418);
        background: var(--badge-bg, var(--accent));
      }
      #source-line { font-size: 28px; max-width: 560px; text-align: right; }`;
}

function timeline(tpl) {
  const m = tpl.motion;
  return `      // 数字高亮：变量注入可能发生在脚本之后，所以再补一次；已有 .num 就不重复包裹
      const highlightNumbers = () => {
        document.querySelectorAll(".body").forEach((el) => {
          if (el.querySelector(".num")) return;
          el.innerHTML = el.innerHTML.replace(/(\\d+(?:\\.\\d+)?%?)/g, '<span class="num">$1</span>');
        });
      };
      highlightNumbers();
      setTimeout(highlightNumbers, 120);

      const root = document.getElementById("root");
      const total = parseFloat(root.dataset.duration);
      const slots = [...document.querySelectorAll(".scene")];
      const declared = window.__hyperframes ? window.__hyperframes.getVariables() : {};
      const sceneCount = Number.isFinite(declared.scene_count) ? declared.scene_count : slots.length;
      slots.forEach((slot) => {
        if (Number(slot.dataset.sceneIndex) > sceneCount) {
          slot.hidden = true;
          slot.style.display = "none";
        }
      });

      const tl = gsap.timeline({ paused: true });
      tl.fromTo("#progress", { scaleX: 0 }, { scaleX: 1, duration: total, ease: "none" }, 0);
      tl.from("#title", { ${m.title} }, ${m.titleAt});
      tl.from("#rule", { scaleX: 0, opacity: 0, duration: ${m.ruleDur}, ease: "power2.out" }, ${m.ruleAt});
      tl.from("#aigc-badge", { opacity: 0, duration: ${m.badgeDur}, ease: "none" }, ${m.badgeAt});
      // 只淡出大标题与分隔线，保留顶部小标签作为画面锚点
      tl.to("#title, #rule", { opacity: 0, duration: 0.6, ease: "sine.inOut" }, ${m.titleOut || 2.6});
      const live = slots.filter((slot) => !slot.hidden);
      live.forEach((slot, index) => {
        const start = parseFloat(slot.dataset.start);
        const span = parseFloat(slot.dataset.duration);
        const isLast = index === live.length - 1;
        // immediateRender：动画排在时间轴中段时 GSAP 默认不提前写起始值，元素会先裸着露出来
        tl.from(slot.querySelector(".lead"), { ${m.lead}, immediateRender: true }, start + ${m.leadAt});
        tl.from(slot.querySelector(".body"), { ${m.body}, immediateRender: true }, start + ${m.bodyAt});
        if (isLast) {
          // 最后一屏让位给结尾口播：正文先退场，口播接在同一段时间窗里，避免两块文字叠在一起
          const outAt = start + Math.max(0.8, span - 3.9);
          tl.to(slot, { opacity: 0, duration: 0.45, ease: "power1.in" }, outAt);
          tl.from("#closing", { ${m.closing}, immediateRender: true }, outAt + 0.35);
        } else {
          tl.to(slot, { opacity: 0, duration: ${m.outDur}, ease: "power1.in" }, start + span - ${m.outDur});
        }
      });
${tpl.fx ? tpl.fx : ''}
      window.__timelines["main"] = tl;`;
}

function page(tpl) {
  return `<!doctype html>
<html lang="zh-CN" data-composition-variables='${VARS}'>
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1080, height=1920" />
    <title>${tpl.htmlTitle}</title>
    <style>
${FONT_FACE}
${tpl.css}
${layout(tpl)}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-width="1080" data-height="1920" data-duration="45">
      <audio id="narration" class="clip" src="narration.mp3" data-start="0" data-duration="45" data-track-index="9"></audio>
${tpl.decor || ''}
      <header id="title-card" class="clip" data-start="0" data-duration="45" data-track-index="0">
        <p id="kicker">${tpl.kicker}</p>
        <h1 id="title" data-var-text="title">新一代旗舰手机发布</h1>
        <div id="rule"></div>
      </header>

${scenesMarkup()}

      <aside id="closing-slot" class="clip" data-start="38" data-duration="7" data-track-index="2">
        <p id="closing" data-var-text="closing">更多机型对比与实测，我们会在后续持续跟进。</p>
      </aside>

      <footer id="bottom-bar">
        <span id="progress"></span>
        <div id="meta-row">
          <span id="aigc-badge">${tpl.badgeText || '内容由 AI 生成'}</span>
          <span id="source-line">来源：<span id="source" data-var-text="source_name">公开报道整理</span></span>
        </div>
      </footer>
    </div>

    <script src="gsap.min.js"></script>
    <script>
${timeline(tpl)}
    </script>
  </body>
</html>
`;
}

const MOTION_JSON = `${JSON.stringify({
  duration: 45,
  assertions: [
    { kind: 'appearsBy', selector: '#aigc-badge', bySec: 0.5 },
    { kind: 'appearsBy', selector: '#title', bySec: 1.2 },
    { kind: 'staysInFrame', selector: '#aigc-badge' },
    { kind: 'keepsMoving', withinSelector: '#root', maxStaticSec: 4 }
  ]
}, null, 2)}\n`;

const TEMPLATES = [
  {
    key: 'news-9x16',
    pad: '84px',
    sceneTop: '336px',
    name: '快报',
    description: '深色底加暖色雾，标题压顶、正文自上而下擦出，节奏均衡，适合日常资讯',
    htmlTitle: 'Heron 新闻竖版快报',
    kicker: 'HERON · 快报',
    motion: {
      title: 'y: 46, opacity: 0, duration: 0.55, ease: "power3.out"', titleAt: 0.15,
      ruleDur: 0.45, ruleAt: 0.35, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'y: 32, opacity: 0, duration: 0.45, ease: "power2.out"', leadAt: 0.1,
      body: 'clipPath: "inset(0% 0% 100% 0%)", duration: 0.62, ease: "power2.out"', bodyAt: 0.5,
      closing: 'y: 26, opacity: 0, duration: 0.5, ease: "power2.out"',
      outDur: 0.35
    },
    css: `      body { margin: 0; background: #131a2a; color: #f2f6f9; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #131a2a; }
      #glow { position: absolute; left: -220px; top: -260px; width: 820px; height: 820px; border-radius: 50%;
              background: radial-gradient(circle, var(--accent) 0%, rgba(19,26,42,0) 66%); opacity: 0.34; }
      #title-card { padding: 132px 84px 0 84px; }
      #kicker { margin: 0 0 22px; font-size: 28px; font-weight: 500; letter-spacing: 7px; color: #8fb6c6; }
      #title { margin: 0; font-size: 104px; font-weight: 700; line-height: 1.18; letter-spacing: -0.01em; color: #ffffff; }
      #rule { margin-top: 30px; width: 180px; height: 10px; border-radius: 5px; background: var(--accent); }
      .lead { margin: 0 0 26px; font-size: 76px; font-weight: 700; line-height: 1.4; letter-spacing: -0.01em; color: #ffffff;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 0; font-size: 50px; font-weight: 400; line-height: 1.72; color: #c2d1dd;
              max-height: 620px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 7; }
      #closing { margin: 0; padding: 32px 38px; font-size: 42px; font-weight: 500; line-height: 1.55; color: #f2f6f9;
                 background: rgba(217,119,6,0.14); border-left: 12px solid var(--accent); border-radius: 10px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #source-line { color: #9fb2bf; }`
  },
  {
    key: 'news-poster-9x16',
    pad: '78px',
    sceneTop: '640px',
    name: '大字报',
    description: '一屏一句超大字加厚重字重，遮罩自下而上擦出，节奏最快，适合突发与硬消息',
    htmlTitle: 'Heron 大字报竖版',
    kicker: 'HERON · 快报',
    motion: {
      title: 'y: 120, opacity: 0, duration: 0.62, ease: "power4.out"', titleAt: 0.1,
      ruleDur: 0.5, ruleAt: 0.4, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'clipPath: "inset(100% 0% 0% 0%)", duration: 0.42, ease: "power3.out"', leadAt: 0.06,
      body: 'clipPath: "inset(100% 0% 0% 0%)", duration: 0.55, ease: "power2.out"', bodyAt: 0.42,
      closing: 'clipPath: "inset(100% 0% 0% 0%)", duration: 0.4, ease: "power3.out"',
      titleOut: 1.1,
      outDur: 0.12
    },
    css: `      body { margin: 0; background: #0b1418; color: #f7fbfc; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #0b1418; }
      #title-card { padding: 200px 78px 0 78px; }
      #kicker { margin: 0 0 32px; font-size: 28px; font-weight: 500; letter-spacing: 11px; color: #6fbccc; }
      #title { margin: 0; font-size: 138px; font-weight: 900; line-height: 1.02; letter-spacing: -0.03em; color: #ffffff; }
      #rule { margin-top: 38px; width: 300px; height: 16px; background: var(--accent); }
      .lead { margin: 0 0 20px; padding-bottom: 24px; font-size: 118px; font-weight: 900; line-height: 1.08; letter-spacing: -0.03em; color: #ffffff;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
      .body { margin: 0; font-size: 48px; font-weight: 400; line-height: 1.66; color: #b0c2c8;
              max-height: 460px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 5; }
      #closing { margin: 0; font-size: 52px; font-weight: 700; line-height: 1.4; letter-spacing: -0.02em; color: #f7fbfc;
                 border-left: 16px solid var(--accent); padding-left: 34px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      #kicker { color: #6fbccc; }
      #root { --badge-fg: #0b1418; --badge-bg: #f7fbfc; }
      #source-line { color: #8ba0a6; }`
  },
  {
    key: 'news-magazine-9x16',
    pad: '92px',
    sceneTop: '400px',
    name: '杂志',
    description: '浅色纸感、引文竖线与横向揭开，行距最宽，适合评测与深度内容',
    htmlTitle: 'Heron 杂志竖版',
    kicker: 'HERON WEEKLY',
    motion: {
      title: 'y: 40, opacity: 0, duration: 0.7, ease: "power2.out"', titleAt: 0.2,
      ruleDur: 0.6, ruleAt: 0.55, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'x: -40, clipPath: "inset(0% 100% 0% 0%)", duration: 0.62, ease: "power2.out"', leadAt: 0.12,
      body: 'x: -32, opacity: 0, duration: 0.85, ease: "power2.out"', bodyAt: 0.5,
      closing: 'y: 24, opacity: 0, duration: 0.65, ease: "sine.out"',
      outDur: 0.4
    },
    css: `      body { margin: 0; background: #f6f4ef; color: #1c2226; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #f6f4ef; }
      #title-card { padding: 150px 92px 0 92px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 8px; color: #6b7c80; }
      #title { margin: 0; font-size: 92px; font-weight: 600; line-height: 1.2; letter-spacing: 0.01em; color: #131a1d;
               border-bottom: 3px solid #1c2226; padding-bottom: 34px; }
      #rule { margin-top: 26px; width: 110px; height: 6px; background: var(--accent); }
      .lead { margin: 0 0 26px; font-size: 76px; font-weight: 600; line-height: 1.4; color: #131a1d;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .lead::before { content: "— "; color: var(--accent); }
      .body { margin: 0; padding-left: 26px; border-left: 2px solid #c9cfc9; font-size: 48px; font-weight: 400;
              line-height: 1.8; color: #3f4a4e; max-height: 660px; overflow: hidden;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 7; }
      #closing { margin: 0; font-size: 42px; font-weight: 500; line-height: 1.6; color: #3f4a4e;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #root { --badge-fg: #131a1d; --badge-bg: transparent; }
      #aigc-badge { border: 2px solid #131a1d; }
      #source-line { color: #6b7c80; border-top: 1px solid #c9cfc9; padding-top: 22px; }`
  },
  {
    key: 'news-board-9x16',
    pad: '80px',
    sceneTop: '352px',
    name: '信息板',
    description: '色块标题横向扩张、正文装进面板，信息分区最清楚，适合多要点并列',
    htmlTitle: 'Heron 信息板竖版',
    kicker: 'HERON BOARD',
    motion: {
      title: 'y: 60, opacity: 0, duration: 0.5, ease: "power3.out"', titleAt: 0.15,
      ruleDur: 0.4, ruleAt: 0.45, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'scaleX: 0, transformOrigin: "left center", duration: 0.42, ease: "power3.out"', leadAt: 0.08,
      body: 'y: 28, opacity: 0, duration: 0.45, ease: "power2.out"', bodyAt: 0.32,
      closing: 'y: 24, opacity: 0, duration: 0.45, ease: "power2.out"',
      outDur: 0.25
    },
    css: `      body { margin: 0; background: #101a1e; color: #e8f1f3; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #101a1e; }
      #title-card { padding: 128px 80px 0 80px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 8px; color: #6fbccc; }
      #title { margin: 0; font-size: 92px; font-weight: 700; line-height: 1.22; letter-spacing: -0.01em; color: #ffffff; }
      #rule { margin-top: 28px; width: 100%; height: 2px; background: rgba(140,170,176,0.35); }
      .lead { margin: 0 0 22px; padding: 16px 24px; transform-origin: left center;
              font-size: 64px; font-weight: 700; line-height: 1.24; color: #0b1418; background: var(--accent);
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 6px 0 0; padding: 34px 32px; font-size: 48px; font-weight: 400; line-height: 1.68; color: #cddbdd;
              background: rgba(255,255,255,0.045); border: 1px solid rgba(140,170,176,0.22); border-radius: 6px;
              max-height: 600px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 7; }
      #closing { margin: 0; padding: 28px 32px; font-size: 42px; font-weight: 500; line-height: 1.55; color: #e8f1f3;
                 border: 1px solid var(--accent); border-radius: 6px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #source-line { color: #8ba0a6; }`
  },
  {
    key: 'news-rail-9x16',
    pad: '84px',
    scenePad: '0 84px 0 210px',
    sceneTop: '352px',
    sceneJustify: 'flex-start',
    name: '时间轴',
    description: '左侧轨道随进度生长、锚点逐屏点亮，突出先后顺序，适合事件进展与流程',
    htmlTitle: 'Heron 时间轴竖版',
    kicker: 'HERON TIMELINE',
    decor: `      <div id="rail"></div>`,
    motion: {
      title: 'x: -40, opacity: 0, duration: 0.55, ease: "power3.out"', titleAt: 0.12,
      ruleDur: 0.4, ruleAt: 0.42, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'x: 36, opacity: 0, duration: 0.45, ease: "power2.out"', leadAt: 0.1,
      body: 'x: 20, opacity: 0, duration: 0.5, ease: "power1.out"', bodyAt: 0.38,
      closing: 'opacity: 0, duration: 0.5, ease: "power1.out"',
      outDur: 0.28
    },
    fx: `      // 轨道连续生长：既是这套的动效签名，也保证画面任意 4 秒内都有位移
      tl.fromTo("#rail", { scaleY: 0 }, { scaleY: 1, duration: total, ease: "none" }, 0);
      live.forEach((slot) => {
        const start = parseFloat(slot.dataset.start);
        tl.fromTo(slot, { "--dot": 0 }, { "--dot": 1, duration: 0.4, ease: "back.out(2.4)" }, start + 0.05);
      });`,
    css: `      body { margin: 0; background: #0d1518; color: #eaf2f4; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #0d1518; }
      #rail { position: absolute; left: 126px; top: 350px; height: 900px; width: 3px;
              background: linear-gradient(to bottom, var(--accent), rgba(140,170,176,0.18));
              transform-origin: top center; }
      #title-card { padding: 130px 84px 0 84px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 8px; color: #6fbccc; }
      #title { margin: 0; font-size: 96px; font-weight: 700; line-height: 1.2; letter-spacing: -0.01em; color: #ffffff; }
      #rule { margin-top: 28px; width: 180px; height: 8px; background: var(--accent); }
      .scene { --dot: 1; }
      .scene::before { content: attr(data-scene-index); position: absolute; left: 96px; top: 12px; width: 60px; height: 60px;
                       display: grid; place-items: center; font-size: 28px; font-weight: 700; color: #0d1518;
                       background: var(--accent); border-radius: 50%; font-variant-numeric: tabular-nums;
                       transform: scale(var(--dot)); }
      .lead { margin: 0 0 26px; font-size: 76px; font-weight: 700; line-height: 1.4; letter-spacing: -0.01em; color: #ffffff;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 0; font-size: 48px; font-weight: 400; line-height: 1.74; color: #b9c9cd;
              max-height: 640px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 7; }
      #closing { margin: 0; padding-left: 26px; border-left: 8px solid var(--accent); font-size: 42px; font-weight: 500;
                 line-height: 1.55; color: #eaf2f4;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #source-line { color: #8ba0a6; }`
  },
  {
    key: 'news-rank-9x16',
    pad: '84px',
    scenePad: '230px 84px 0',
    closingPad: '0 84px',
    sceneTop: '380px',
    sceneJustify: 'flex-start',
    name: '榜单',
    description: '大号名次数字配横向标尺，逐条排列，适合 TOP N、排名与名次',
    htmlTitle: 'Heron 榜单竖版',
    kicker: 'HERON RANKING',
    motion: {
      title: 'y: 40, opacity: 0, duration: 0.55, ease: "power3.out"', titleAt: 0.15,
      ruleDur: 0.4, ruleAt: 0.4, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'y: 26, opacity: 0, duration: 0.42, ease: "power2.out"', leadAt: 0.42,
      body: 'opacity: 0, duration: 0.5, ease: "power1.out"', bodyAt: 0.68,
      closing: 'y: 22, opacity: 0, duration: 0.45, ease: "power2.out"',
      titleOut: 1.6,
      outDur: 0.28
    },
    fx: `      // 名次数字弹出、标尺从左展开，是这套的签名
      live.forEach((slot) => {
        const start = parseFloat(slot.dataset.start);
        tl.fromTo(slot, { "--rank": 0 }, { "--rank": 1, duration: 0.45, ease: "back.out(2.2)" }, start);
        tl.fromTo(slot, { "--rule": 0 }, { "--rule": 1, duration: 0.55, ease: "power3.out" }, start + 0.16);
      });`,
    css: `      body { margin: 0; background: #fafaf8; color: #101a1d; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #fafaf8; }
      #title-card { padding: 132px 84px 0 84px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 9px; color: #7a8a8f; }
      #title { margin: 0; font-size: 96px; font-weight: 800; line-height: 1.18; letter-spacing: -0.02em; color: #101a1d; }
      #rule { margin-top: 28px; width: 100%; height: 3px; background: #101a1d; }
      .scene { --rank: 1; --rule: 1; }
      .scene::before { content: attr(data-scene-index); position: absolute; left: 78px; top: 40px;
                       font-size: 168px; font-weight: 900; line-height: 1; letter-spacing: -0.05em;
                       color: var(--accent); font-variant-numeric: tabular-nums;
                       transform: scale(var(--rank)); transform-origin: left bottom; }
      .scene::after { content: ""; position: absolute; left: 84px; right: 84px; top: 214px; height: 2px;
                      background: linear-gradient(to right, var(--accent), rgba(16,26,29,0.12));
                      transform: scaleX(var(--rule)); transform-origin: left center; }
      .lead { margin: 0 0 24px; font-size: 66px; font-weight: 700; line-height: 1.4; color: #101a1d;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 0; font-size: 46px; font-weight: 400; line-height: 1.7; color: #4a5a5f;
              max-height: 540px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 7; }
      #closing { margin: 0; padding: 26px 30px; font-size: 40px; font-weight: 500; line-height: 1.55; color: #101a1d;
                 background: #f0efe9; border-left: 12px solid var(--accent); border-radius: 8px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #root { --badge-fg: #ffffff; --badge-bg: #101a1d; }
      #source-line { color: #7a8a8f; }`
  },
  {
    key: 'news-quote-9x16',
    pad: '92px',
    scenePad: '230px 92px 0',
    closingPad: '0 92px',
    sceneTop: '380px',
    sceneJustify: 'flex-start',
    name: '引语',
    description: '巨型引号压顶，领句当被引用的话来讲，适合发言人、声明与原文引用',
    htmlTitle: 'Heron 引语竖版',
    kicker: 'HERON QUOTE',
    motion: {
      title: 'y: 40, opacity: 0, duration: 0.55, ease: "power3.out"', titleAt: 0.15,
      ruleDur: 0.4, ruleAt: 0.4, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'scale: 1.04, opacity: 0, duration: 0.72, ease: "sine.out"', leadAt: 0.1,
      body: 'opacity: 0, duration: 0.7, ease: "sine.out"', bodyAt: 0.55,
      closing: 'y: 24, opacity: 0, duration: 0.5, ease: "power2.out"',
      titleOut: 1.8,
      outDur: 0.4
    },
    fx: `      // 引号先落、引文再定，是这套的签名：引号从略大收敛到实大
      live.forEach((slot) => {
        const start = parseFloat(slot.dataset.start);
        tl.fromTo(slot, { "--glyph": 1.28 }, { "--glyph": 1, duration: 0.72, ease: "power2.out" }, start);
      });`,
    css: `      body { margin: 0; background: #1a1310; color: #f5efe9; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #1a1310; }
      #title-card { padding: 132px 92px 0 92px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 9px; color: #a08a76; }
      #title { margin: 0; font-size: 96px; font-weight: 800; line-height: 1.2; letter-spacing: -0.02em; color: #f5efe9; }
      #rule { margin-top: 28px; width: 150px; height: 8px; border-radius: 4px; background: var(--accent); }
      .scene { --glyph: 1; }
      .scene::before { content: "「"; position: absolute; left: 62px; top: 14px;
                       font-size: 190px; font-weight: 700; line-height: 1; color: var(--accent);
                       opacity: 0.5; transform: scale(var(--glyph)); transform-origin: left bottom; }
      .lead { margin: 0 0 30px; font-size: 74px; font-weight: 700; line-height: 1.42; letter-spacing: -0.01em; color: #fbf7f2;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 0; padding-left: 28px; border-left: 6px solid rgba(160,138,118,0.45);
              font-size: 46px; font-weight: 400; line-height: 1.75; color: #b7a99c;
              max-height: 470px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 6; }
      #closing { margin: 0; padding: 26px 30px; font-size: 40px; font-weight: 500; line-height: 1.55; color: #f5efe9;
                 background: rgba(217,119,6,0.16); border-left: 12px solid var(--accent); border-radius: 8px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #source-line { color: #a08a76; }`
  },
  {
    key: 'news-qa-9x16',
    pad: '88px',
    sceneTop: '340px',
    name: '问答',
    description: '问与答分区排版，领句当问题、正文当答案，适合释疑与来龙去脉',
    htmlTitle: 'Heron 问答竖版',
    kicker: 'HERON Q&A',
    motion: {
      title: 'y: 40, opacity: 0, duration: 0.55, ease: "power3.out"', titleAt: 0.15,
      ruleDur: 0.4, ruleAt: 0.4, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'x: -34, opacity: 0, duration: 0.45, ease: "power2.out"', leadAt: 0.1,
      body: 'x: 40, opacity: 0, duration: 0.5, ease: "power2.out"', bodyAt: 0.42,
      closing: 'y: 24, opacity: 0, duration: 0.45, ease: "power2.out"',
      titleOut: 1.9,
      outDur: 0.32
    },
    css: `      body { margin: 0; background: #eef2f4; color: #16232a; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #eef2f4; }
      #title-card { padding: 136px 88px 0 88px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 9px; color: #6b7f88; }
      #title { margin: 0; font-size: 96px; font-weight: 800; line-height: 1.2; letter-spacing: -0.02em; color: #16232a; }
      #rule { margin-top: 28px; width: 100%; height: 3px; background: rgba(22,35,42,0.25); }
      .lead { margin: 0 0 30px; padding-bottom: 30px; border-bottom: 2px dashed rgba(22,35,42,0.22);
              font-size: 70px; font-weight: 700; line-height: 1.36; letter-spacing: -0.01em; color: #16232a;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .lead::before { content: "问 "; color: var(--accent); }
      .body { margin: 0; font-size: 46px; font-weight: 400; line-height: 1.78; color: #40525a;
              max-height: 520px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 6; }
      .body::before { content: "答 "; color: var(--accent); font-weight: 700; }
      #closing { margin: 0; padding: 26px 30px; font-size: 40px; font-weight: 500; line-height: 1.55; color: #16232a;
                 background: #ffffff; border-left: 12px solid var(--accent); border-radius: 8px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #root { --badge-fg: #ffffff; --badge-bg: #16232a; }
      #source-line { color: #6b7f88; }`
  },
  {
    key: 'news-compare-9x16',
    pad: '76px',
    sceneTop: '320px',
    name: '对比',
    description: '上下两张卡片分置两边，领句与正文各占一栏，适合涨跌、前后与 AB 方案',
    htmlTitle: 'Heron 对比竖版',
    kicker: 'HERON COMPARE',
    motion: {
      title: 'y: 40, opacity: 0, duration: 0.55, ease: "power3.out"', titleAt: 0.15,
      ruleDur: 0.4, ruleAt: 0.4, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'x: -52, opacity: 0, duration: 0.5, ease: "power3.out"', leadAt: 0.08,
      body: 'x: 52, opacity: 0, duration: 0.5, ease: "power3.out"', bodyAt: 0.3,
      closing: 'y: 24, opacity: 0, duration: 0.45, ease: "power2.out"',
      titleOut: 1.9,
      outDur: 0.3
    },
    css: `      body { margin: 0; background: #0f1b16; color: #e9f2ed; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #0f1b16; }
      #title-card { padding: 132px 76px 0 76px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 9px; color: #6fa08a; }
      #title { margin: 0; font-size: 96px; font-weight: 800; line-height: 1.2; letter-spacing: -0.02em; color: #ffffff; }
      #rule { margin-top: 28px; width: 170px; height: 8px; border-radius: 4px; background: var(--accent); }
      .lead { margin: 0 0 26px; padding: 30px 32px; border-radius: 14px;
              background: rgba(217,119,6,0.14); border: 2px solid var(--accent);
              font-size: 64px; font-weight: 700; line-height: 1.36; letter-spacing: -0.01em; color: #ffffff;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 0; padding: 30px 32px; border-radius: 14px;
              background: rgba(255,255,255,0.045); border: 1px solid rgba(140,170,160,0.22);
              font-size: 46px; font-weight: 400; line-height: 1.72; color: #c6d6ce;
              max-height: 520px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 6; }
      #closing { margin: 0; padding: 26px 30px; font-size: 40px; font-weight: 500; line-height: 1.55; color: #e9f2ed;
                 background: rgba(255,255,255,0.05); border-left: 12px solid var(--accent); border-radius: 10px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #source-line { color: #7f9f8f; }`
  },
  {
    key: 'news-contrast-9x16',
    pad: '84px',
    sceneTop: '380px',
    name: '撞色',
    description: '上下双色板从中间推开，标题压在暗底上，动静对比最强，适合强对比短标题',
    htmlTitle: 'Heron 撞色竖版',
    kicker: 'HERON FLASH',
    decor: `      <div id="plate"></div>`,
    motion: {
      title: 'y: 80, opacity: 0, duration: 0.6, ease: "power4.out"', titleAt: 0.28,
      ruleDur: 0.5, ruleAt: 0.52, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'y: -24, opacity: 0, duration: 0.42, ease: "back.out(1.6)"', leadAt: 0.08,
      body: 'y: 26, opacity: 0, duration: 0.5, ease: "power2.out"', bodyAt: 0.36,
      closing: 'y: 30, opacity: 0, duration: 0.45, ease: "power2.out"',
      outDur: 0.26
    },
    fx: `      // 双色板从中间的缝往上下推开，撑出这套的撞色骨架
      tl.fromTo("#plate", { scaleY: 0 }, { scaleY: 1, duration: 0.8, ease: "power3.inOut" }, 0.05);`,
    css: `      body { margin: 0; background: #f2f5f5; color: #12232a; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #f2f5f5; }
      #plate { position: absolute; left: 0; right: 0; top: 0; height: 900px; background: #12232a;
               transform-origin: center center; z-index: 0; }
      #title-card, .scene, #closing-slot, #bottom-bar { z-index: 1; }
      #title-card { padding: 168px 84px 0 84px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 9px; color: #6fbccc; }
      #title { margin: 0; font-size: 100px; font-weight: 800; line-height: 1.16; letter-spacing: -0.02em; color: #ffffff; }
      #rule { margin-top: 30px; width: 240px; height: 12px; background: var(--accent); }
      .lead { margin: 0 0 26px; padding: 14px 24px;
              font-size: 66px; font-weight: 700; line-height: 1.26; color: #ffffff; background: #12232a;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 22px 0 0; font-size: 48px; font-weight: 400; line-height: 1.72; color: #34474d;
              max-height: 600px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 7; }
      #closing { margin: 0; padding: 26px 30px; font-size: 42px; font-weight: 500; line-height: 1.55; color: #12232a;
                 background: rgba(18,35,42,0.08); border-left: 12px solid var(--accent); border-radius: 8px;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #root { --badge-fg: #ffffff; --badge-bg: #12232a; }
      #source-line { color: #5b6b70; }`
  },
  {
    key: 'news-mono-9x16',
    pad: '84px',
    sceneTop: '336px',
    decor: `      <div id="tick">01 / 06</div>`,
    name: '数据',
    description: '数字从零滚到目标值，等宽数字配细网格，适合参数、报价与数值对比',
    htmlTitle: 'Heron 数据竖版',
    kicker: 'HERON DATA',
    motion: {
      title: 'y: 40, opacity: 0, duration: 0.5, ease: "power2.out"', titleAt: 0.12,
      ruleDur: 0.4, ruleAt: 0.4, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'y: 26, opacity: 0, duration: 0.42, ease: "power2.out"', leadAt: 0.08,
      body: 'opacity: 0, duration: 0.5, ease: "none"', bodyAt: 0.55,
      closing: 'opacity: 0, duration: 0.45, ease: "power1.out"',
      outDur: 0.3
    },
    fx: `      // 数字滚动：把高亮出来的 .num 从 0 补到目标值，保留小数位与百分号后缀
      const counters = [];
      live.forEach((slot) => {
        const start = parseFloat(slot.dataset.start);
        slot.querySelectorAll(".num").forEach((node, order) => {
          const hit = node.textContent.match(/^(\\d+(?:\\.\\d+)?)(.*)$/);
          if (!hit) return;
          const target = parseFloat(hit[1]);
          if (!Number.isFinite(target) || target === 0) return;
          counters.push({ node, target, digits: (hit[1].split(".")[1] || "").length, suffix: hit[2],
                          at: start + 0.75 + order * 0.12 });
        });
      });
      counters.forEach((item) => {
        const proxy = { v: 0 };
        tl.to(proxy, {
          v: item.target,
          duration: 0.9,
          ease: "power2.out",
          onUpdate: () => { item.node.textContent = proxy.v.toFixed(item.digits) + item.suffix; }
        }, item.at);
      });
      // 右上角读数随屏切换，补上「没有阿拉伯数字可滚」时的数据气质
      const tick = document.getElementById("tick");
      const pad = (n) => String(n).padStart(2, "0");
      live.forEach((slot, index) => {
        const start = parseFloat(slot.dataset.start);
        const label = pad(index + 1) + " / " + pad(live.length);
        tl.call(() => { tick.textContent = label; }, null, start);
        tl.fromTo(tick, { opacity: 0, y: 8 }, { opacity: 1, y: 0, duration: 0.32, ease: "power2.out" }, start);
      });`,
    css: `      body { margin: 0; background: #0a1013; color: #e6eef0; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #0a1013; }
      #root::after { content: ""; position: absolute; inset: 0; pointer-events: none;
                     background-image: repeating-linear-gradient(to right, rgba(140,170,176,0.07) 0 1px, transparent 1px 108px);
                     z-index: 0; }
      #tick { position: absolute; right: 84px; top: 146px; z-index: 1; font-size: 28px; font-weight: 500;
              letter-spacing: 4px; color: var(--accent); font-variant-numeric: tabular-nums; }
      #title-card, .scene, #closing-slot, #bottom-bar { z-index: 1; }
      #title-card { padding: 140px 84px 0 84px; }
      #kicker { margin: 0 0 24px; font-size: 28px; font-weight: 500; letter-spacing: 10px; color: #6fbccc;
                font-variant-numeric: tabular-nums; }
      #title { margin: 0; font-size: 94px; font-weight: 700; line-height: 1.2; letter-spacing: -0.01em; color: #ffffff; }
      #rule { margin-top: 26px; width: 100%; height: 1px; background: rgba(140,170,176,0.4); }
      .lead { margin: 0 0 24px; font-size: 70px; font-weight: 700; line-height: 1.4; color: #ffffff;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 0; font-size: 48px; font-weight: 400; line-height: 1.72; color: #a8bcc2;
              font-variant-numeric: tabular-nums;
              max-height: 620px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 7; }
      .num { font-size: 1.18em; letter-spacing: -0.01em; }
      #closing { margin: 0; padding-left: 24px; border-left: 6px solid var(--accent);
                 font-size: 40px; font-weight: 400; line-height: 1.6; color: #cfe0e4;
                 font-variant-numeric: tabular-nums;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #source-line { color: #7d8d92; }`
  },
  {
    key: 'news-minimal-9x16',
    pad: '96px',
    sceneTop: '440px',
    name: '极简',
    description: '大量留白、极轻字重与宽行距，节奏最慢，适合长文与观点',
    htmlTitle: 'Heron 极简竖版',
    kicker: 'HERON',
    motion: {
      title: 'y: 18, opacity: 0, duration: 0.9, ease: "sine.out"', titleAt: 0.3,
      ruleDur: 0.8, ruleAt: 0.75, badgeDur: 0.2, badgeAt: 0.05,
      lead: 'y: 14, opacity: 0, duration: 1, ease: "sine.out"', leadAt: 0.18,
      body: 'opacity: 0, duration: 1, ease: "sine.out"', bodyAt: 0.6,
      closing: 'opacity: 0, duration: 0.8, ease: "sine.out"',
      outDur: 0.45
    },
    css: `      body { margin: 0; background: #fbfcfc; color: #1a2529; font-family: "Noto Sans SC", sans-serif; }
      #root { position: relative; width: 100%; height: 100%; overflow: hidden; background: #fbfcfc; }
      #title-card { padding: 250px 96px 0 96px; }
      #kicker { margin: 0 0 38px; font-size: 28px; font-weight: 400; letter-spacing: 13px; color: #7d8d92; }
      #title { margin: 0; font-size: 82px; font-weight: 300; line-height: 1.34; letter-spacing: 0.01em; color: #131a1d; }
      #rule { margin-top: 44px; width: 56px; height: 3px; background: var(--accent); }
      .lead { margin: 0 0 38px; font-size: 68px; font-weight: 300; line-height: 1.42; color: #131a1d;
              display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
      .body { margin: 0; font-size: 48px; font-weight: 300; line-height: 1.95; color: #5b6b70;
              max-height: 620px; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 6; }
      #closing { margin: 0; font-size: 42px; font-weight: 300; line-height: 1.7; color: #5b6b70;
                 display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow: hidden; }
      #root { --badge-fg: #7d8d92; --badge-bg: transparent; }
      #aigc-badge { border: 1px solid #cfd8da; }
      #source-line { color: #7d8d92; }`
  }
];

const sha256 = file => createHash('sha256').update(readFileSync(file)).digest('hex');
const ASSETS = ['gsap.min.js', 'narration.mp3'];
// 字体十二套共用一份，不复制进模板目录；清单仍按 composition 里的落下路径登记
const SHARED_FONT = join(rootDir, '..', 'fonts', 'NotoSansSC-Regular.otf');
if (!existsSync(SHARED_FONT)) {
  console.error(`缺少共享字体，先放好 ${SHARED_FONT}`);
  process.exit(1);
}

for (const tpl of TEMPLATES) {
  const dir = join(rootDir, tpl.key);
  if (existsSync(join(dir, 'index.html')) && !force && tpl.key !== 'news-9x16') {
    console.log(`跳过已存在: ${tpl.key}（--force 覆盖）`);
    continue;
  }
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'template.json'),
    `${JSON.stringify({ name: tpl.name, description: tpl.description }, null, 2)}\n`, 'utf8');
  writeFileSync(join(dir, 'index.html'), page(tpl), 'utf8');
  writeFileSync(join(dir, 'index.motion.json'), MOTION_JSON, 'utf8');
  for (const asset of ASSETS) {
    const from = join(base, asset);
    const to = join(dir, asset);
    if (resolve(from) !== resolve(to)) copyFileSync(from, to);
  }

  // 清单里只记相对路径、哈希与字节数；改模板后重跑本脚本再跑 template-lock.mjs 即可。
  const files = [
    ['fonts/NotoSansSC-Regular.otf', SHARED_FONT],
    ['gsap.min.js', join(dir, 'gsap.min.js')],
    ['index.html', join(dir, 'index.html')],
    ['index.motion.json', join(dir, 'index.motion.json')],
    ['narration.mp3', join(dir, 'narration.mp3')],
    ['template.json', join(dir, 'template.json')]
  ].map(([path, file]) => ({ path, sha256: sha256(file), bytes: readFileSync(file).length }));
  writeFileSync(join(dir, 'assets-manifest.json'),
    `${JSON.stringify({ templateKey: tpl.key, templateVersion: '1', files }, null, 2)}\n`, 'utf8');
  console.log(`已生成 ${tpl.key}`);
}
