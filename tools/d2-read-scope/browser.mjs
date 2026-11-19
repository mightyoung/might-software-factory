import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const origin = process.env.D2_SCOPE_ORIGIN;
const directory = process.env.D2_SCOPE_EVIDENCE;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '') || process.env.D2_SCOPE_ACK !== 'isolated-read-scope:msf_d2_read_scope_test'
    || !directory?.startsWith('/private/tmp/')) throw Error('Owned read-scope target required');
const base = '/api/v1/tooling/loan-applications';
const browser = await chromium.launch({ channel: 'chrome' });
await mkdir(directory, { recursive: true });
async function login(actor) {
  const page = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
  await page.goto(origin);
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号', { exact: true }).fill(`d1seed-${actor}`);
  await page.getByLabel('密码', { exact: true }).fill(process.env[`D2_PASSWORD_${actor.toUpperCase()}`]);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('link', { name: 'MIGHT 工作台首页' })).toBeVisible();
  return page;
}
async function api(page, path, body) {
  return page.evaluate(async ({ path, body }) => {
    const csrf = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))?.slice(11);
    const response = await fetch(path, body ? { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf ?? ''), 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) } : {});
    return { status: response.status, body: await response.json() };
  }, { path, body });
}
function success(response) { assert.equal(response.status, 200, JSON.stringify(response.body)); return response.body.data; }
try {
  if (process.env.D2_SCOPE_PHASE === 'create') {
    const borrower = await login('reader');
    const candidates = success(await api(borrower, `${base}/candidates?custodyScopeId=${process.env.D2_SCOPE_ID}`));
    const created = success(await api(borrower, base, { definitionVersion: '1', organizationId: process.env.D2_ORG_A, custodyScopeId: process.env.D2_SCOPE_ID, purpose: `D2-READ-${crypto.randomUUID()}`, toolIds: [process.env.D2_TOOL_ID] }));
    assert.ok(candidates.tools.some(tool => tool.id === process.env.D2_TOOL_ID));
    const keeper = await login('keeper');
    const issued = success(await api(keeper, `${base}/${created.id}/issue`, { definitionVersion: '1', expectedVersion: 1 }));
    assert.equal(issued.state, 'ISSUED');
    await writeFile(`${directory}/loan.json`, JSON.stringify(issued));
    console.log(JSON.stringify({ phase: 'create', loan: issued.id }));
  } else if (process.env.D2_SCOPE_PHASE === 'permission-revoked') {
    const supervisor = await login('nonkeeper');
    assert.equal((await api(supervisor, `${base}/visibility/department`)).status, 403);
    assert.equal(await supervisor.getByRole('link', { name: '部门借用单', exact: true }).count(), 0);
    console.log(JSON.stringify({ phase: 'permission-revoked', menuAndApiDenied: true }));
  } else {
    const loan = JSON.parse(await readFile(`${directory}/loan.json`, 'utf8'));
    const borrower = await login('reader');
    const supervisor = await login('nonkeeper');
    const otherDepartment = await login('outside');
    const keeper = await login('keeper');
    assert.equal((await api(borrower, `${base}/visibility/department`)).status, 403);
    assert.equal((await api(borrower, `${base}/visibility/custody`)).status, 403);
    assert.ok(success(await api(borrower, base)).records.some(item => item.id === loan.id));
    assert.equal(success(await api(borrower, `${base}/${loan.id}`)).organizationId, process.env.D2_ORG_A);
    for (const [actor, visibility, visible] of [[supervisor, 'department', true], [otherDepartment, 'department', false], [keeper, 'custody', process.env.D2_SCOPE_PHASE !== 'revoked'], [supervisor, 'custody', false]]) {
      const list = success(await api(actor, `${base}/visibility/${visibility}`));
      assert.equal(list.records.some(item => item.id === loan.id), visible);
      const detail = await api(actor, `${base}/visibility/${visibility}/${loan.id}`);
      assert.equal(detail.status, visible ? 200 : 404);
      if (visible) assert.equal(detail.body.data.organizationId, process.env.D2_ORG_A);
      assert.ok(!list.records.some(item => item.id === process.env.D2_FOREIGN_LOAN));
      assert.equal((await api(actor, `${base}/visibility/${visibility}/${process.env.D2_FOREIGN_LOAN}`)).status, 404);
    }
    assert.equal((await api(supervisor, `${base}/${loan.id}/issue`, { definitionVersion: '1', expectedVersion: loan.version })).status, 403);
    assert.equal((await api(supervisor, `${base}/${loan.id}/return-requests`, { definitionVersion: '1', expectedVersion: loan.version, toolIds: loan.toolIds })).status, 403);
    if (process.env.D2_SCOPE_PHASE !== 'revoked') {
      await supervisor.getByRole('link', { name: '部门借用单', exact: true }).click();
      await supervisor.getByRole('row').filter({ hasText: loan.purpose }).getByRole('button', { name: '查看申请' }).click();
      await expect(supervisor.getByRole('region', { name: '借用单只读详情' })).toBeVisible();
      assert.equal(await supervisor.getByRole('button', { name: '新建借用申请' }).count(), 0);
      assert.equal(await supervisor.getByRole('button', { name: '修订申请' }).count(), 0);
      await supervisor.screenshot({ path: `${directory}/department-detail.png`, fullPage: true });
    }
    console.log(JSON.stringify({ phase: process.env.D2_SCOPE_PHASE, loan: loan.id, originalDepartment: true, independentPermissions: true, custodyRelationEnforced: true, crossTenantHidden: true, readDoesNotGrantCommands: true }));
  }
} finally { await browser.close(); }
