// Browser acceptance on an owned HTTPS return deployment. All business writes use the UI.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { D2_ORIGIN: origin, D2_DATABASE: database, D2_EVIDENCE_DIR: evidenceDirectory } = process.env;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '') || database !== 'msf_d2_return_test'
    || process.env.D2_ACK !== `isolated-return-acceptance:${database}` || !evidenceDirectory?.startsWith('/private/tmp/')) throw Error('Owned return target required');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const tools = JSON.parse(process.env.D2_RETURN_TOOL_IDS ?? '[]');
const scopeId = process.env.D2_RETURN_SCOPE_ID;
assert.equal(tools.length, 3);assert.ok(tools.every(id => uuid.test(id)));assert.ok(uuid.test(scopeId ?? ''));
const base = '/api/v1/tooling/loan-applications';
let marker = `D2-RETURN-UI-${crypto.randomUUID()}`;
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
async function verifyAvailability(page) {
  await page.getByRole('button', { name: '新建借用申请', exact: true }).click();
  const scope = page.getByRole('combobox', { name: '保管范围', exact: true });
  await expect(scope.locator(`option[value="${scopeId}"]`)).toBeAttached();await scope.selectOption(scopeId);
  const candidates = page.getByRole('combobox', { name: '第 1 件工装', exact: true });
  await expect(candidates.locator(`option[value="${tools[0]}"]`)).toBeAttached();
  await expect(candidates.locator(`option[value="${tools[1]}"]`)).toBeAttached();
  await expect(candidates.locator(`option[value="${tools[2]}"]`)).toHaveCount(0);
  await page.screenshot({ path: `${evidenceDirectory}/damaged-tool-excluded.png`, fullPage: true });
}
async function verifySettledReadback() {
  const id = process.env.D2_VERIFY_SETTLED_LOAN_ID;assert.ok(uuid.test(id ?? ''));
  const path = process.env.D2_PRIOR_ATTEMPTS;
  if (!path?.startsWith('/private/tmp/')) throw Error('Private prior acceptance evidence required');
  const prior = JSON.parse(await readFile(path, 'utf8'));
  const borrower = await login('reader');
  const current = await borrower.evaluate(async path => {
    const response = await fetch(path);return { status: response.status, body: await response.json() };
  }, `${base}/${id}`);
  assert.equal(current.status, 200);const record = current.body.data;
  assert.equal(record.state, 'SETTLED');assert.equal(record.version, 6);assert.deepEqual(record.toolIds, tools);
  assert.equal(record.returnItems.find(item => item.toolId === tools[2]).condition, 'DAMAGED');
  assert.equal(record.returnItems.find(item => item.toolId === tools[2]).reason, '定位销断裂，待检修');
  marker = record.purpose;
  const row = borrower.getByRole('row').filter({ hasText: marker });
  await expect(row.getByRole('cell', { name: '已结清', exact: true })).toBeVisible();
  await row.getByRole('button', { name: '查看申请', exact: true }).click();
  await expect(checkboxes(borrower).getByText('尚未结清 0 件，待接收 0 件，已接收 3 件', { exact: true })).toBeVisible();
  for (const checkbox of await checkboxes(borrower).getByRole('checkbox').all()) await expect(checkbox).toBeDisabled();
  await borrower.screenshot({ path: `${evidenceDirectory}/borrower-settled-readback.png`, fullPage: true });
  await verifyAvailability(borrower);
  assert.equal(prior.length, 7);assert.deepEqual(prior.map(value => value.status), [200,200,503,200,200,200,200]);
  assert.equal(prior[0].record.id, id);assert.equal(prior[2].key, prior[3].key);assert.deepEqual(prior[2].input, prior[3].input);
  assert.equal(prior[3].record.version, 3);assert.equal(prior[4].record.state, 'ISSUED');assert.equal(prior[4].record.version, 4);
  assert.equal(prior[6].record.state, 'SETTLED');assert.equal(prior[6].record.version, 6);
  const summary = { marker, database, loan: id, tools, finalVersion: record.version, finalState: record.state,
    requestKeepsOccupancyUi: true, twoBatchesUi: true, normalReceiptUi: true, damagedReceiptUi: true,
    damageReason: record.returnItems.find(item => item.toolId === tools[2]).reason, damagedToolExcludedFromCandidatesUi: true,
    unknownResponseCommitObserved: true, sameKeyRecovery: true, recoveredVersion: 3, applicantSettledUi: true,
    evidenceBasis: 'Prior full UI command sequence plus current read-only settled and candidate verification' };
  await writeFile(`${evidenceDirectory}/summary.json`, JSON.stringify(summary, null, 2));console.log(JSON.stringify(summary));
}
try {
  if (process.env.D2_VERIFY_SETTLED_LOAN_ID) {
    await verifySettledReadback();
  } else {
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
  await expect(returns.getByText('尚未结清 3 件，待接收 0 件，已接收 0 件', { exact: true })).toBeVisible();
  await returns.getByRole('checkbox', { name: /D2-UI-RETURN-01/ }).check();
  // The upstream commit is real. Only its success response is replaced to exercise uncertain-result recovery.
  const returnPath = `${base}/${created.id}/return-requests`;
  const match = url => url.pathname === returnPath;
  let upstreamCommitted = false;
  const drop = async route => {
    if (route.request().method() !== 'POST') return route.fallback();
    const response = await route.fetch({ maxRetries: 0, maxRedirects: 0 });
    assert.equal(response.status(), 200);
    const body = await response.json();assert.equal(body.data.version, 3);upstreamCommitted = true;
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'TEST_RESPONSE_LOST', message: 'synthetic response loss after commit', traceId: 'return-browser', retryable: false }) });
  };
  await borrower.context().route(match, drop);
  const uncertain = await command(borrower, returnPath, '申请归还所选工装', 503);
  assert.ok(upstreamCommitted);await borrower.context().unroute(match, drop);
  await expect(returns.getByText('原请求结果尚未确认，核对后只能重试该请求。', { exact: true })).toBeVisible();
  await ownRow.getByRole('button', { name: '查看申请', exact: true }).click();
  const recovered = await command(borrower, returnPath, '核对后重试原请求');
  assert.equal(recovered.key, uncertain.key);assert.deepEqual(recovered.input, uncertain.input);
  assert.equal(recovered.record.version, 3);
  await expect(checkboxes(borrower).getByText('尚未结清 3 件，待接收 1 件，已接收 0 件', { exact: true })).toBeVisible();
  await borrower.screenshot({ path: `${evidenceDirectory}/return-request-pending.png`, fullPage: true });
  await keeper.getByRole('button', { name: '待接收工作', exact: true }).click();
  await keeper.getByRole('row').filter({ hasText: marker }).getByRole('button', { name: '核对接收', exact: true }).click();
  const receipt = keeper.getByRole('form', { name: '接收归还工装', exact: true });
  await receipt.getByRole('checkbox', { name: /D2-UI-RETURN-01/ }).check();
  const first = (await command(keeper, `${base}/${created.id}/receive`, '确认接收所选工装')).record;
  assert.equal(first.state, 'ISSUED');assert.equal(first.version, 4);
  await expect(receipt.getByText('尚未结清 2 件，待接收 0 件，已接收 1 件', { exact: true })).toBeVisible();
  await keeper.screenshot({ path: `${evidenceDirectory}/partial-normal-receipt.png`, fullPage: true });
  await borrower.getByRole('button', { name: '刷新归还明细', exact: true }).click();
  await expect(checkboxes(borrower).getByText('尚未结清 2 件，待接收 0 件，已接收 1 件', { exact: true })).toBeVisible();
  await checkboxes(borrower).getByRole('checkbox', { name: /D2-UI-RETURN-02/ }).check();
  await checkboxes(borrower).getByRole('checkbox', { name: /D2-UI-RETURN-03/ }).check();
  const secondReturn = (await command(borrower, returnPath, '申请归还所选工装')).record;
  assert.equal(secondReturn.version, 5);
  await expect(checkboxes(borrower).getByText('尚未结清 2 件，待接收 2 件，已接收 1 件', { exact: true })).toBeVisible();
  await keeper.getByRole('button', { name: '刷新归还明细', exact: true }).click();
  await expect(receipt.getByText('尚未结清 2 件，待接收 2 件，已接收 1 件', { exact: true })).toBeVisible();
  await receipt.getByRole('checkbox', { name: /D2-UI-RETURN-02/ }).check();
  await receipt.getByRole('checkbox', { name: /D2-UI-RETURN-03/ }).check();
  await receipt.getByRole('combobox', { name: /接收情况（.*D2-UI-RETURN-03/ }).selectOption('DAMAGED');
  await receipt.getByRole('textbox', { name: /接收说明（.*D2-UI-RETURN-03/ }).fill('定位销断裂，待检修');
  const settled = (await command(keeper, `${base}/${created.id}/receive`, '确认接收所选工装')).record;
  assert.equal(settled.state, 'SETTLED');assert.equal(settled.version, 6);
  assert.equal(settled.returnItems.find(item => item.toolId === tools[2]).condition, 'DAMAGED');
  await expect(receipt.getByText('尚未结清 0 件，待接收 0 件，已接收 3 件', { exact: true })).toBeVisible();
  await expect(receipt.getByRole('button', { name: '确认接收所选工装', exact: true })).toHaveCount(0);
  await keeper.screenshot({ path: `${evidenceDirectory}/settled-with-damage.png`, fullPage: true });
  await borrower.getByRole('button', { name: '刷新归还明细', exact: true }).click();
  await expect(checkboxes(borrower).getByText('借用单已结清 · 第 6 版', { exact: true })).toBeVisible();
  // Reopen the normal applicant entry point and check the new settled state.
  await borrower.reload();
  await expect(borrower.getByRole('row').filter({ hasText: marker }).getByRole('cell', { name: '已结清', exact: true })).toBeVisible();
  await borrower.getByRole('row').filter({ hasText: marker }).getByRole('button', { name: '查看申请', exact: true }).click();
  await expect(checkboxes(borrower).getByRole('checkbox')).toHaveCount(3);
  for (const checkbox of await checkboxes(borrower).getByRole('checkbox').all()) await expect(checkbox).toBeDisabled();
  await borrower.screenshot({ path: `${evidenceDirectory}/borrower-settled.png`, fullPage: true });
  await verifyAvailability(borrower);
  const summary = { marker, database, loan: created.id, tools, finalVersion: settled.version, finalState: settled.state,
    requestKeepsOccupancyUi: true, twoBatchesUi: true, normalReceiptUi: true, damagedReceiptUi: true,
    damageReason: '定位销断裂，待检修', damagedToolExcludedFromCandidatesUi: true,
    unknownResponseCommitObserved: upstreamCommitted, sameKeyRecovery: recovered.key === uncertain.key,
    recoveredVersion: recovered.record.version, applicantSettledUi: true };
  await writeFile(`${evidenceDirectory}/summary.json`, JSON.stringify(summary, null, 2));console.log(JSON.stringify(summary));
  }
} finally { await browser.close(); }
