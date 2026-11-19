// Real HTTPS application commands. A separate database observer coordinates the lock barrier.
import { createRequire } from 'node:module';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../frontend/package.json',import.meta.url));
const { chromium } = require('@playwright/test');
const origin=process.env.D2_ISSUE_ORIGIN;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin??'') || process.env.D2_ISSUE_DATABASE!=='msf_d2_issue_test'
    || process.env.D2_ISSUE_ACK!=='isolated-issuance-acceptance:msf_d2_issue_test') throw Error('Dedicated issuance acceptance target required');
const toolIds=JSON.parse(process.env.D2_ISSUE_TOOL_IDS);
assert.equal(toolIds.length,3);
const marker=`ISSUE-PG-${crypto.randomUUID()}`;
const base='/api/v1/tooling/loan-applications';
const browser=await chromium.launch({channel:'chrome'});
async function login(actor) {
  const context=await browser.newContext({ignoreHTTPSErrors:true});
  const page=await context.newPage();
  await page.goto(origin);
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号',{exact:true}).fill(`d1seed-${actor}`);
  await page.getByLabel('密码',{exact:true}).fill(process.env[`D2_PASSWORD_${actor.toUpperCase()}`]);
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByText('我的工装借用单',{exact:true}).first().waitFor();
  return page;
}
async function api(page,path,input,key=crypto.randomUUID()) {
 return page.evaluate(async ({path,input,key})=>{
   const token=document.cookie.split('; ').find(cookie=>cookie.startsWith('XSRF-TOKEN='));
   const response=await fetch(path,input?{method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':decodeURIComponent(token.slice(11)),'Idempotency-Key':key},body:JSON.stringify(input)}:{});
   return {status:response.status,body:await response.json()};
 },{path,input,key});
}
try {
 const reader=await login('reader'),keeper=await login('keeper');
 const options=await api(reader,`${base}/candidates?custodyScopeId=${process.env.D2_ISSUE_SCOPE_ID}`);
 assert.equal(options.status,200);
 const input={definitionVersion:'1',organizationId:options.body.data.departments[0].id,custodyScopeId:process.env.D2_ISSUE_SCOPE_ID,purpose:marker,toolIds:[toolIds[0]]};
 const created=await api(reader,base,input);assert.equal(created.status,200,JSON.stringify(created));
 const loan=created.body.data;const key=crypto.randomUUID();
 const lines=createInterface({input:process.stdin});const waiting=[];lines.on('line',()=>waiting.shift()?.());
 const gate=()=>new Promise(resolve=>waiting.push(resolve));
 console.log(JSON.stringify({stage:'ready',loan:loan.id,tool:toolIds[0]}));await gate();
 const failed=await api(keeper,`${base}/${loan.id}/issue`,{definitionVersion:'1',expectedVersion:1},key);
 assert.equal(failed.status,503,JSON.stringify(failed));console.log(JSON.stringify({stage:'failed',loan:loan.id,status:failed.status}));await gate();
 const success=await api(keeper,`${base}/${loan.id}/issue`,{definitionVersion:'1',expectedVersion:1},key);
 assert.equal(success.status,200,JSON.stringify(success));assert.equal(success.body.data.state,'ISSUED');assert.equal(success.body.data.version,2);
 console.log(JSON.stringify({stage:'complete',loan:loan.id,failedStatus:failed.status,retryStatus:success.status}));lines.close();
} finally {await browser.close();}
