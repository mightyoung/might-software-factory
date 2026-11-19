import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
if (process.env.PGDATABASE !== 'msf_d2_transfer_test' || process.env.D2_TRANSFER_ACK !== 'isolated:msf_d2_transfer_test') {
  throw Error('Dedicated transfer fixture required');
}
const origin = process.env.D2_TRANSFER_UI_ORIGIN ?? 'https://127.0.0.1:15494';
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin)) throw Error('Loopback HTTPS origin required');
const directory = '/private/tmp/msf-transfer/editor';
const base = '/api/v1/apps/tooling/entities/tool';
const browser = await chromium.launch({ channel: 'chrome' });
try {
  const page = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin);
  await mkdir(directory, { recursive: true });
  await page.screenshot({ path: `${directory}/initial.png`, fullPage: true });
  await expect(page.getByLabel("租户编码")).toBeVisible({ timeout: 10000 });
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号', { exact: true }).fill('d1seed-keeper');
  await page.getByLabel('密码', { exact: true }).fill(process.env.D2_PASSWORD_KEEPER);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('link', { name: 'MIGHT 工作台首页' })).toBeVisible();
  async function api(path, body) {
    return page.evaluate(async ({ path, body }) => {
      const csrf = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))?.slice(11);
      const response = await fetch(path, body ? { method: 'POST', headers: {
        'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf ?? ''), 'Idempotency-Key': crypto.randomUUID(),
      }, body: JSON.stringify(body) } : {});
      return { status: response.status, body: await response.json() };
    }, { path, body });
  }
  function success(response) {
    assert.equal(response.status, 200, JSON.stringify(response.body));
    return response.body.data;
  }
  const definitionData = success(await api(`${base}/definition?use=CREATE`));
  const definition = definitionData.definition ?? definitionData;
  const referenceKey = definition.inputFields.custodyScopeId.referenceKey;
  const reference = (use, search) => api(`${base}/references/${referenceKey}/query`, {
    definitionVersion: definition.definitionVersion, use, search, context: {}, size: 20,
  });
  const source = success(await reference('CREATE', 'D1-SCOPE-A')).options[0];
  const target = success(await reference('EDIT', '跨组织调拨验收')).options[0];
  assert.ok(source && target && source.id !== target.id, 'Authorized transfer scopes missing; run acceptance.py first');
  const code = `AU06-UI-${crypto.randomUUID()}`;
  const created = success(await api(`${base}/records`, { definitionVersion: definition.definitionVersion,
    values: { name: '编辑页调拨验收', code, custodyScopeId: source.id } }));
  const original = success(await api(`${base}/records/${created.id}`));
  const tenantPrefix = new URL(page.url()).pathname.match(/^\/t\/[^/]+/)?.[0];
  assert.ok(tenantPrefix, 'Authenticated tenant route missing');
  await page.goto(`${origin}${tenantPrefix}/apps/tooling/entities/tool/records/${created.id}/edit`);
  const search = page.getByRole('searchbox', { name: '检索保管范围' });
  await expect(search).toBeVisible();
  await search.fill('跨组织调拨验收');
  const selector = page.getByRole('combobox', { name: '保管范围', exact: true });
  await expect(selector.locator(`option[value="${target.id}"]`)).toBeAttached();
  await expect(selector).toBeEnabled();
  await selector.selectOption(target.id);
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('heading', { name: '保存成功' })).toBeVisible();
  const updated = success(await api(`${base}/records/${created.id}`));
  assert.equal(updated.version, original.version + 1);
  assert.equal(updated.values.custodyScopeId, target.id);
  assert.notEqual(updated.values.organizationId, original.values.organizationId);
  assert.deepEqual(errors, []);
  await mkdir(directory, { recursive: true });
  await page.screenshot({ path: `${directory}/saved.png`, fullPage: true });
  const evidence = { tool: created.id, source: source.id, destination: target.id,
    originalOrganization: original.values.organizationId, organization: updated.values.organizationId,
    version: updated.version, browserEditSaved: true };
  await writeFile(`${directory}/summary.json`, JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence));
} finally {
  await browser.close();
}
