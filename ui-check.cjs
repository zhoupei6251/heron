// 一次性验收脚本：登录后打开决策页，断言成本概览与「提升」入口存在。
// 只读，不点任何会写库的按钮。
const { chromium } = require('playwright');

const BASE = 'http://localhost:5176';
const OUT = process.argv[2] || '.';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));

  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('heron!2026');
  await page.click('.submit-button');
  await page.waitForFunction(() => !location.pathname.includes('/login'), null, { timeout: 30000 });
  console.log('登录后 URL:', page.url());

  await page.goto(BASE + '/heron/decision', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);
  await page.screenshot({ path: OUT + '/ui-decision.png', fullPage: true });

  const text = await page.locator('body').innerText();
  const has = (s) => text.includes(s);
  console.log('当前 URL:', page.url());
  console.log('成本卡片「近 30 天研究」:', has('近 30 天研究'));
  console.log('「采纳率」字段:', has('采纳率'));
  console.log('分档均值提示语:', has('分档均值分开算'));
  const rows = await page.locator('.el-table__body tbody tr').count();
  console.log('表格行数:', rows);
  console.log('行内「提升」按钮数:', await page.getByRole('button', { name: '提升', exact: true }).count());

  if (rows > 0) {
    await page.getByRole('button', { name: '详情', exact: true }).first().click();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: OUT + '/ui-decision-detail.png', fullPage: true });
    const detail = await page.locator('body').innerText();
    console.log('详情弹窗「采纳并提升」:', detail.includes('采纳并提升'));
  }

  console.log('控制台错误:', errors.length ? errors.slice(0, 5) : '无');
  await browser.close();
})().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
