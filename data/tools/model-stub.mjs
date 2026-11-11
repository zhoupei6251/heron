// 本地 OpenAI 兼容假端点：只为验证 N2 接缝（真 HTTP 往返 + 真用量记账 + 真契约闸）。
// 稿件内容是固定文本，不代表模型写作质量；真实模型出稿要等用户填 heron.model.*。
import http from 'node:http';
import fs from 'node:fs';

// 原型线的写稿请求按 RP/1 契约回一份引擎自带的示例 spec（含动态面板 + Repeater + 事件），
// 新闻线回固定稿件。两者都只为验证接缝，不代表模型真实产出质量。
const RP1_SPEC = '/d/zhoupei/tmp/zee-rp-v0.2.1/examples/authoring/list-detail.rp1';

const SCRIPT = {
  title: '新款折叠屏手机上市',
  scenes: [
    {
      lead: '铰链寿命提到四十万次',
      body: '厂商在今日的发布会上公布新一代折叠机型，主打铰链与整机耐用度。官方资料显示，铰链开合寿命较上一代提升约一倍，屏幕折痕在侧视角度下明显变浅。',
      duration_s: 14,
    },
    {
      lead: '起售价与上一代持平',
      body: '价格方面，起步版本不做上调，存储升级需要加价。渠道反馈首销期以旧换新补贴是主要促销手段，线下门店同步开启真机体验。',
      duration_s: 14,
    },
    {
      lead: '下周开启全渠道现货',
      body: '官方宣布下周一开启全渠道现货发售，首批覆盖国内主要城市，海外上市时间表尚未公布。行业机构预计本季度折叠屏整体出货量小幅回升。',
      duration_s: 14,
    },
  ],
  closing: '我们会继续跟进真机实测与售后条款变化。',
  source_name: '公开报道整理',
};

http
  .createServer((request, response) => {
    let raw = '';
    request.on('data', (chunk) => (raw += chunk));
    request.on('end', () => {
      if (!request.url.endsWith('/chat/completions')) {
        response.writeHead(404).end('{"error":"not found"}');
        return;
      }
      const content = raw.includes('rp/1') && fs.existsSync('D:/zhoupei/tmp/zee-rp-v0.2.1/examples/authoring/list-detail.rp1')
        ? fs.readFileSync('D:/zhoupei/tmp/zee-rp-v0.2.1/examples/authoring/list-detail.rp1', 'utf8')
        : JSON.stringify(SCRIPT);
      const body = JSON.stringify({
        id: `stub-${Date.now()}`,
        object: 'chat.completion',
        choices: [{ message: { role: 'assistant', content } }],
        usage: { prompt_tokens: 1200, completion_tokens: 480 },
      });
      console.log(`stub seen ${Buffer.byteLength(raw)} bytes of prompt`);
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(body);
    });
  })
  .listen(8899, '127.0.0.1', () => console.log('model stub on 127.0.0.1:8899/v1'));
