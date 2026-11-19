import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { join } from 'node:path';
const { chromium, expect } = createRequire(new URL('../../frontend/package.json', import.meta.url))('@playwright/test');
const base = '/api/v1/tooling/loan-applications';
const origin = process.env.D2_VERSION_UI_ORIGIN ?? 'https://127.0.0.1:15504';
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin) || process.env.PGDATABASE !== 'msf_d2_version_test' ||
    process.env.D2_VERSION_ACK !== 'isolated:msf_d2_version_test') throw Error('Dedicated loopback fixture required');
const stateFile = join(process.env.D2_VERSION_EVIDENCE, 'scenario.json');
const s = JSON.parse(await readFile(stateFile, 'utf8'));
const browser = await chromium.launch({ channel: 'chrome' });
try {
  const page = await browser.newPage({ ignoreHTTPSErrors: true });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  async function submit(path) {
    const [response] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST'),
      page.getByRole('button', { name: '提交申请', exact: true }).click(),
    ]);
    assert.equal(response.status(), 200, await response.text());
    return (await response.json()).data;
  }
  await page.goto(origin);
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号', {exact:true}).fill('d1seed-reader');
  await page.getByLabel('密码', {exact:true}).fill(process.env.D2_PASSWORD_READER);
  await page.getByRole('button', {name:'登录',exact:true}).click();
  await page.getByRole('link', {name:'我的工装借用单',exact:true}).click();
  const old = page.getByRole('row').filter({hasText:s.body.purpose});
  await old.getByRole('button', {name:'查看申请'}).click();
  await page.getByRole('button',{name:'修订申请'}).click();
  await expect(page.getByLabel('使用地点', {exact:false})).toHaveCount(0);
  await page.getByLabel('第 1 件工装',{exact:true}).selectOption(s.tools[1]);
  const revised = await submit(base+'/'+s.id+'/revise');
  assert.equal(revised.definitionVersion,'1');
  assert.deepEqual(revised.toolIds,s.tools.slice(1));
  await page.reload();
  await page.getByRole('button',{name:'新建借用申请',exact:true}).click();
  await page.getByRole('combobox',{name:'申请部门',exact:true}).selectOption(s.body.organizationId);
  await page.getByRole('combobox',{name:'保管范围',exact:true}).selectOption(s.body.custodyScopeId);
  await page.getByLabel('借用用途',{exact:true}).fill(s.body.purpose+'-V2');
  await page.getByLabel('第 1 件工装',{exact:true}).selectOption(s.tools[0]);
  const field=page.getByLabel(/使用地点/);
  await expect(field).toBeVisible();
  const location='装配一线点检台';
  await field.fill(location);
  const created=await submit(base);
  assert.equal(created.definitionVersion,'2');
  assert.equal(created.usageLocation,location);
  assert.deepEqual(errors,[]);
  await writeFile(stateFile,JSON.stringify({...s,v2Id:created.id,location}));
  console.log('Chrome revised historical V1 items and submitted a V2 loan with usage location');
} finally {
  await browser.close();
}
