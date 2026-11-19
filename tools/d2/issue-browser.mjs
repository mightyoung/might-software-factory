// Real Chrome acceptance through applicant and keeper UI on an owned HTTPS D2 deployment.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { D2_ORIGIN: origin, D2_DATABASE: database } = process.env;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '') || database !== 'msf_d2_issue_test'
    || process.env.D2_ACK !== `isolated-issue-acceptance:${database}`) throw Error('Isolated D2 issue target required');
const tools = JSON.parse(process.env.D2_ISSUE_TOOL_IDS ?? '[]');
const scopeId = process.env.D2_ISSUE_SCOPE_ID;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
assert.equal(tools.length, 3);assert.ok(tools.every(id => uuid.test(id)));assert.ok(uuid.test(scopeId ?? ''));
const evidenceDirectory = process.env.D2_EVIDENCE_DIR;
if (evidenceDirectory && !evidenceDirectory.startsWith('/private/tmp/')) throw Error('Private evidence directory required');
const base = '/api/v1/tooling/loan-applications';
let marker = `D2-ISSUE-UI-${crypto.randomUUID()}`;
const browser = await chromium.launch({ channel: 'chrome' });
async function login(actor) {
  const password = process.env[`D2_PASSWORD_${actor.toUpperCase()}`];
  assert.ok(password, 'Synthetic identity password required');
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  await page.goto(origin);
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号', { exact: true }).fill(`d1seed-${actor}`);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '我的工装借用单', exact: true }).click();
  await expect(page.getByRole('heading', { name: '工装借用申请', exact: true })).toBeVisible();
  return page;
}
async function command(page, path, label) {
  const responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === path && response.request().method() === 'POST');
  await page.getByRole('button', { name: label, exact: true }).click();
  const response = await responsePromise;
  const body = await response.json();
  const submitted = response.request().postDataJSON();
  const key = response.request().headers()['idempotency-key'];
  if (evidenceDirectory) {
    await mkdir(evidenceDirectory, { recursive: true });
    await writeFile(`${evidenceDirectory}/${path === base ? 'create' : 'issue'}-attempt.json`, JSON.stringify({ marker, path, status: response.status(), key, input: submitted, result: body.data, errorCode: body.code }, null, 2));
  }
  assert.equal(response.status(), 200, `Expected successful ${label}, received ${response.status()} ${body.code ?? ''}`);
  return { record: body.data, input: submitted, key };
}
async function missingOtherCustodian() {
  marker = `D2-Q13-UI-${crypto.randomUUID()}`;
  const keeper = await login('keeper');
  const ownTab = keeper.getByRole('button', { name: '本人申请', exact: true });
  if (await ownTab.count()) await ownTab.click();
  await keeper.getByRole('button', { name: '新建借用申请', exact: true }).click();
  const scope = keeper.getByRole('combobox', { name: '保管范围', exact: true });
  await expect(scope.locator(`option[value="${scopeId}"]`)).toBeAttached();
  await scope.selectOption(scopeId);
  const department = keeper.getByRole('combobox', { name: '申请部门', exact: true });
  await expect(department.locator('option').nth(1)).toBeAttached();
  await department.selectOption({ index: 1 });
  await keeper.getByLabel('借用用途', { exact: true }).fill(marker);
  const item = keeper.getByRole('combobox', { name: '第 1 件工装', exact: true });
  await expect(item.locator(`option[value="${tools[0]}"]`)).toBeAttached();
  await item.selectOption(tools[0]);
  const created = await command(keeper, base, '提交申请');
  assert.equal(created.record.state, 'PENDING_ISSUE');
  // Reload and open the actual own-detail endpoint so the hint survives the normal entry point.
  await keeper.reload();
  const own = keeper.getByRole('button', { name: '本人申请', exact: true });
  if (await own.count()) await own.click();
  const row = keeper.getByRole('row').filter({ hasText: marker });
  await row.getByRole('button', { name: '查看申请', exact: true }).click();
  const detail = keeper.getByRole('region', { name: '借用申请详情' });
  await expect(detail.getByText('对应保管范围目前无人可办（需另一名有效保管人发放）', { exact: true })).toBeVisible();
  await expect(detail.getByRole('listitem')).toHaveCount(1);
  const refused = await keeper.evaluate(async ({ path, version }) => {
    const cookie = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='));
    if (!cookie) throw Error('Authenticated CSRF cookie required');
    const response = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json',
      'X-XSRF-TOKEN': decodeURIComponent(cookie.slice(11)), 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ definitionVersion: '1', expectedVersion: version }) });
    const body = await response.json();
    return { status: response.status, code: body.code };
  }, { path: `${base}/${created.record.id}/issue`, version: created.record.version });
  assert.equal(refused.status, 403);
  await keeper.getByRole('button', { name: '待发放工作', exact: true }).click();
  await expect(keeper.getByRole('row').filter({ hasText: marker })).toHaveCount(0);
  const summary = { marker, database, loan: created.record.id, applicationVersion: created.record.version,
    tool: tools[0], missingOtherCustodianUi: true, selfIssueStatus: refused.status, selfIssueCode: refused.code,
    selfExcludedFromQueue: true };
  if (evidenceDirectory) {
    await mkdir(evidenceDirectory, { recursive: true });
    await own.click();
    await keeper.getByRole('row').filter({ hasText: marker }).getByRole('button', { name: '查看申请', exact: true }).click();
    await keeper.screenshot({ path: `${evidenceDirectory}/missing-other-custodian.png`, fullPage: true });
    await writeFile(`${evidenceDirectory}/q13-summary.json`, JSON.stringify(summary, null, 2));
  }
  console.log(JSON.stringify(summary));
}
try {
  if (process.env.D2_BROWSER_SCENARIO === 'q13') {
    await missingOtherCustodian();
  } else {
  const applicant = await login('reader');
  let created;
  if (process.env.D2_EXISTING_LOAN_ID) {
    const loanId = process.env.D2_EXISTING_LOAN_ID;
    assert.ok(uuid.test(loanId));
    const existing = await applicant.evaluate(async path => {
      const response = await fetch(path);
      return { status: response.status, body: await response.json() };
    }, `${base}/${loanId}`);
    assert.equal(existing.status, 200);
    created = { record: existing.body.data };
    marker = created.record.purpose;
    assert.equal(created.record.state, 'PENDING_ISSUE');
    assert.deepEqual(created.record.toolIds, tools);
    const ownRow = applicant.getByRole('row').filter({ hasText: marker });
    await ownRow.getByRole('button', { name: '查看申请', exact: true }).click();
    await expect(applicant.getByRole('region', { name: '借用申请详情' }).getByRole('listitem')).toHaveCount(3);
  } else {
  await applicant.getByRole('button', { name: '新建借用申请', exact: true }).click();
  const scope = applicant.getByRole('combobox', { name: '保管范围', exact: true });
  await expect(scope.locator(`option[value="${scopeId}"]`)).toBeAttached();
  await scope.selectOption(scopeId);
  const department = applicant.getByRole('combobox', { name: '申请部门', exact: true });
  await expect(department.locator('option').nth(1)).toBeAttached();
  await department.selectOption({ index: 1 });
  await applicant.getByLabel('借用用途', { exact: true }).fill(marker);
  for (const [index, tool] of tools.entries()) {
    if (index) await applicant.getByRole('button', { name: '添加工装', exact: true }).click();
    const select = applicant.getByRole('combobox', { name: `第 ${index + 1} 件工装`, exact: true });
    await expect(select.locator(`option[value="${tool}"]`)).toBeAttached();
    await select.selectOption(tool);
  }
  created = await command(applicant, base, '提交申请');
  assert.equal(created.record.state, 'PENDING_ISSUE');assert.deepEqual(created.record.toolIds, tools);
  }
  const keeper = await login('keeper');
  await keeper.getByRole('button', { name: '待发放工作', exact: true }).click();
  const work = keeper.getByRole('row').filter({ hasText: marker });
  await work.getByRole('button', { name: '核对发放', exact: true }).click();
  const detail = keeper.getByRole('region', { name: '发放核对' });
  await expect(detail.getByRole('listitem')).toHaveCount(3);
  for (const code of ['D2-UI-ISSUE-01', 'D2-UI-ISSUE-02', 'D2-UI-ISSUE-03']) await expect(detail.getByText(new RegExp(code))).toBeVisible();
  const issued = await command(keeper, `${base}/${created.record.id}/issue`, '确认整单发放');
  assert.equal(issued.record.state, 'ISSUED');assert.equal(issued.record.version, created.record.version + 1);
  assert.deepEqual(issued.input, { definitionVersion: created.record.definitionVersion, expectedVersion: created.record.version });
  assert.ok(uuid.test(issued.key));await expect(keeper.getByText('整单已发放。', { exact: true })).toBeVisible();
  await expect(keeper.getByRole('button', { name: '确认整单发放', exact: true })).toHaveCount(0);
  await applicant.reload();
  const own = applicant.getByRole('row').filter({ hasText: marker });
  await expect(own.getByRole('cell', { name: '已发放', exact: true })).toBeVisible();
  await own.getByRole('button', { name: '查看申请', exact: true }).click();
  const ownDetail = applicant.getByRole('region', { name: '借用申请详情' });
  await expect(ownDetail.getByRole('listitem')).toHaveCount(3);
  await expect(ownDetail.getByRole('button', { name: '修订申请' })).toHaveCount(0);
  await expect(ownDetail.getByRole('button', { name: '撤销申请' })).toHaveCount(0);
  const summary = { marker, database, loan: created.record.id, tools, applicationVersion: created.record.version,
    issuedVersion: issued.record.version, issuedState: issued.record.state, applicantUi: true, keeperUi: true, wholeOrder: true };
  if (evidenceDirectory) {
    await mkdir(evidenceDirectory, { recursive: true });
    await keeper.screenshot({ path: `${evidenceDirectory}/keeper-issued.png`, fullPage: true });
    await applicant.screenshot({ path: `${evidenceDirectory}/applicant-issued.png`, fullPage: true });
    await writeFile(`${evidenceDirectory}/summary.json`, JSON.stringify(summary, null, 2));
  }
  console.log(JSON.stringify(summary));
  }
} finally { await browser.close(); }
