// Browser acceptance on an owned HTTPS loss deployment. All business writes use the UI.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { D2_ORIGIN: origin, D2_DATABASE: database, D2_EVIDENCE_DIR: evidenceDirectory } = process.env;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '') || database !== 'msf_d2_loss_test'
    || process.env.D2_ACK !== `isolated-loss-acceptance:${database}` || !evidenceDirectory?.startsWith('/private/tmp/')) throw Error('Owned loss target required');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const tools = JSON.parse(process.env.D2_LOSS_TOOL_IDS ?? '[]');
const scopeId = process.env.D2_LOSS_SCOPE_ID;
assert.equal(tools.length, 2);assert.ok(tools.every(id => uuid.test(id)));assert.ok(uuid.test(scopeId ?? ''));
const base = '/api/v1/tooling/loan-applications';
const marker = `D2-LOSS-UI-${crypto.randomUUID()}`;
const browser = await chromium.launch({ channel: 'chrome' });
await mkdir(evidenceDirectory, { recursive: true });
const attempts = [];
async function login(actor) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  await page.goto(origin);
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号', { exact: true }).fill(`d1seed-${actor}`);
  const password = process.env[`D2_PASSWORD_${actor.toUpperCase()}`];assert.ok(password);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '我的工装借用单', exact: true }).click();
  await expect(page.getByRole('heading', { name: '工装借用申请', exact: true })).toBeVisible();
  return page;
}
async function command(page, path, label, expectedStatus = 200) {
  const received = page.waitForResponse(response => new URL(response.url()).pathname === path && response.request().method() === 'POST');
  await page.getByRole('button', { name: label, exact: true }).click();
  const response = await received;
  const body = await response.json();
  const attempt = { path, status: response.status(), key: response.request().headers()['idempotency-key'], input: response.request().postDataJSON(), record: body.data, code: body.code };
  attempts.push(attempt);await writeFile(`${evidenceDirectory}/attempts.json`, JSON.stringify(attempts, null, 2));
  assert.equal(response.status(), expectedStatus, `${label} returned ${response.status()} ${body.code ?? ''}`);
  return attempt;
}
const checkboxes = page => page.getByRole('form', { name: '申请分批归还', exact: true });
try {
  const borrower = await login('reader');
  await borrower.getByRole('button', { name: '新建借用申请', exact: true }).click();
  const scope = borrower.getByRole('combobox', { name: '保管范围', exact: true });
  await expect(scope.locator(`option[value="${scopeId}"]`)).toBeAttached();await scope.selectOption(scopeId);
  const department = borrower.getByRole('combobox', { name: '申请部门', exact: true });
  await expect(department.locator('option').nth(1)).toBeAttached();await department.selectOption({ index: 1 });
  await borrower.getByLabel('借用用途', { exact: true }).fill(marker);
  for (const [index, toolId] of tools.entries()) {
    if (index) await borrower.getByRole('button', { name: '添加工装', exact: true }).click();
    const select = borrower.getByRole('combobox', { name: `第 ${index + 1} 件工装`, exact: true });
    await expect(select.locator(`option[value="${toolId}"]`)).toBeAttached();await select.selectOption(toolId);
  }
  const created = (await command(borrower, base, '提交申请')).record;
  const keeper = await login('keeper');
  await keeper.getByRole('button', { name: '待发放工作', exact: true }).click();
  await keeper.getByRole('row').filter({ hasText: marker }).getByRole('button', { name: '核对发放', exact: true }).click();
  const issued = (await command(keeper, `${base}/${created.id}/issue`, '确认整单发放')).record;
  assert.equal(issued.state, 'ISSUED');assert.equal(issued.version, 2);
  await borrower.reload();
  const ownRow = borrower.getByRole('row').filter({ hasText: marker });
  await ownRow.getByRole('button', { name: '查看申请', exact: true }).click();
  const returns = checkboxes(borrower);

  await returns.getByRole('checkbox').first().check();
  const pending = (await command(borrower, `${base}/${created.id}/return-requests`, '申请归还所选工装')).record;
  const pendingItem = pending.returnItems.find(item => item.state === 'REQUESTED');
  assert.ok(pendingItem.requestId);
  const order = [pendingItem.toolId, tools.find(id => id !== pendingItem.toolId)];
  const path = `${base}/${created.id}/close-lost`;
  await keeper.getByRole('button', { name: '遗失结案', exact: true }).click();
  await keeper.getByRole('row').filter({ hasText: marker }).getByRole('button', { name: '核对遗失工装' }).click();
  const form = keeper.getByRole('region', { name: '逐件遗失结案' });
  for (const [index, id] of order.entries()) {
    const reason = `验收遗失记录 ${index + 1}`;
    await form.getByRole('combobox').selectOption(id);
    await form.getByLabel('遗失原因').fill(reason);
    await form.getByRole('button', { name: '核对遗失结论' }).click();
    let result;
    if (index === 0) {
      const match = url => url.pathname === path;
      await keeper.route(match, async route => {
        const response = await route.fetch({ maxRetries: 0 });
        assert.equal(response.status(), 200);
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({code:'TEST_RESPONSE_LOST', message:'response lost', traceId:'loss-browser',retryable:false}) });
      });
      const uncertain = await command(keeper, path, '确认遗失结案', 503);
      await keeper.unroute(match);
      const replay = await command(keeper, path, '核对后重试原请求');
      assert.equal(replay.key, uncertain.key);
      assert.deepEqual(replay.input, uncertain.input);
      result = replay.record;
    } else {
      result = (await command(keeper, path, '确认遗失结案')).record;
    }
    const item = result.returnItems.find(value => value.toolId === id);
    assert.equal(item.state, 'LOST');assert.equal(item.reason, reason);
    if (index === 0) assert.equal(item.requestId, pendingItem.requestId);
    assert.equal(result.state, index === 0 ? 'ISSUED' : 'SETTLED');
    assert.equal(result.version, 4 + index);
  }
  await borrower.reload();
  await ownRow.getByRole('button', { name:'查看申请' }).click();
  await expect(checkboxes(borrower).getByText('尚未结清 0 件，待接收 0 件，已接收 0 件，遗失结案 2 件')).toBeVisible();
  await expect(checkboxes(borrower).getByText(/验收遗失记录 2/)).toBeVisible();
  await borrower.screenshot({ path: `${evidenceDirectory}/settled.png` });
  console.log(JSON.stringify({loan:created.id, version:5, losses:2, received:0, replay:true}));
} finally {
  await browser.close();
}
