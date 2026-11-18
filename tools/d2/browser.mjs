// Run against the isolated, HTTPS D2 acceptance deployment with D1 synthetic identities.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { D2_ORIGIN: origin, D2_DATABASE: database } = process.env;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '') || database !== 'msf_d2_loans_test' || process.env.D2_ACK !== `isolated-loan-acceptance:${database}`) throw Error('Isolated D2 target required');
const base = '/api/v1/tooling/loan-applications';
const marker = `D2-${crypto.randomUUID()}`;
const browser = await chromium.launch({channel:'chrome'});
async function login(actor) {
  const context = await browser.newContext({ignoreHTTPSErrors:true});
  const page = await context.newPage();
  await page.goto(origin);
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号',{exact:true}).fill(`d1seed-${actor}`);
  await page.getByLabel('密码',{exact:true}).fill(process.env[`D2_PASSWORD_${actor.toUpperCase()}`]);
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByText('我的工装借用单',{exact:true}).first().click();
  await expect(page.getByRole('heading',{name:'工装借用申请'})).toBeVisible();
  return page;
}
async function api(page, path, input, key = crypto.randomUUID()) {
  return page.evaluate(async ({path,input,key}) => {
    const cookie = document.cookie.split('; ').find(c=>c.startsWith('XSRF-TOKEN='));
    const response = input ? await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':decodeURIComponent(cookie.slice(11)), 'Idempotency-Key':key},body:JSON.stringify(input)}) : await fetch(path);
    return {status:response.status, body:await response.json()};
  },{path,input,key});
}
async function submit(page,path,label) {
  const [response] = await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname===path && r.request().method()==='POST'),page.getByRole('button',{name:label,exact:true}).click()]);
  assert.equal(response.status(),200,JSON.stringify(await response.json()));
  return (await response.json()).data;
}
try {
  const page = await login('reader');
  await page.getByRole('button',{name:'新建借用申请'}).click();
  const scope = page.getByRole('combobox',{name:'保管范围',exact:true});
  await expect(scope.locator('option').nth(1)).toBeAttached();
  await scope.selectOption({index:1});
  const department=page.getByRole('combobox',{name:'申请部门',exact:true});
  if (await department.count()) await department.selectOption({index:1});
  await page.getByLabel('借用用途').fill(marker);
  for(let i=1;i<=3;i++) {
    if(i>1) await page.getByRole('button',{name:'添加工装'}).click();
    const row=page.getByLabel(`第 ${i} 件工装`,{exact:true});
    await expect(row.locator('option').nth(i)).toBeAttached();
    await row.selectOption({index:i});
  }
  const record=await submit(page,base,'提交申请');
  assert.equal(record.state,'PENDING_ISSUE'); assert.equal(record.toolIds.length,3);
  const input={definitionVersion:record.definitionVersion,organizationId:record.organizationId,custodyScopeId:record.custodyScopeId,purpose:marker,toolIds:record.toolIds};
  const second=await login('keeper');
  const key=crypto.randomUUID();
  const other=await api(second,base,input,key); assert.equal(other.status,200); assert.notEqual(other.body.data.id,record.id);
  const replay=await api(second,base,input,key); assert.equal(replay.status,200); assert.equal(replay.body.data.id,other.body.data.id);
  const alien=await api(second,`${base}/${record.id}`);assert.ok([403,404].includes(alien.status));
  const bad={...input,purpose:marker+'-invalid',toolIds:[record.toolIds[0],process.env.D2_OUTSIDE_TOOL_ID]};
  assert.ok(bad.toolIds[1]); const invalid=await api(page,base,bad);assert.ok([400,403,404,409,422].includes(invalid.status));
  await page.reload();
  const row=page.getByRole('row').filter({hasText:marker});await row.getByRole('button',{name:'查看申请'}).click();
  await page.getByRole('button',{name:'修订申请'}).click();
  await page.getByLabel('借用用途').fill(marker+'-revised');
  await page.getByRole('button',{name:'移除第 3 件'}).click();
  const revised=await submit(page,`${base}/${record.id}/revise`,'提交申请');assert.equal(revised.version,2);assert.equal(revised.toolIds.length,2);
  const stale=await api(page,`${base}/${record.id}/cancel`,{definitionVersion:'1',expectedVersion:1});assert.equal(stale.status,409);
  await page.getByRole('button',{name:'撤销申请',exact:true}).click();
  const withdrawn=await submit(page,`${base}/${record.id}/cancel`,'确认撤销');assert.equal(withdrawn.version,3);assert.equal(withdrawn.state,'WITHDRAWN');
  console.log(JSON.stringify({marker,loan:record.id,second:other.body.data.id,invalidStatus:invalid.status,staleStatus:stale.status}));
} finally {await browser.close();}
