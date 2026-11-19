// Real HTTPS commands. concurrency.py observes PostgreSQL waiters at both barriers.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const origin = process.env.D2_LOSS_ORIGIN;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '')
    || process.env.D2_LOSS_ACK !== 'isolated-loss-acceptance:msf_d2_loss_test') {
    throw Error('Dedicated return acceptance target required');
}
const marker = `LOSS-PG-${crypto.randomUUID()}`;
const base = '/api/v1/tooling/loan-applications';
const browser = await chromium.launch({ channel: 'chrome' });
let releaseBarrier;
process.stdin.setEncoding('utf8');
process.stdin.on('data', () => releaseBarrier?.());
async function barrier(stage, loan) {
    const released = new Promise(resolve => { releaseBarrier = resolve; });
    console.log(JSON.stringify({ stage, loan, marker }));
    await released;
    releaseBarrier = undefined;
}
async function login(actor) {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    await page.goto(origin);
    await page.getByLabel('租户编码').fill('d1seed');
    await page.getByLabel('账号', { exact: true }).fill(`d1seed-${actor}`);
    await page.getByLabel('密码', { exact: true }).fill(process.env[`D2_PASSWORD_${actor.toUpperCase()}`]);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByText('我的工装借用单', { exact: true }).first().waitFor();
    return page;
}
async function api(page, path, input, key = crypto.randomUUID()) {
    return page.evaluate(async ({ path, input, key }) => {
        const token = document.cookie.split('; ').find(cookie => cookie.startsWith('XSRF-TOKEN='));
        const response = await fetch(path, input ? {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': decodeURIComponent(token.slice(11)),
                'Idempotency-Key': key,
            },
            body: JSON.stringify(input),
        } : {});
        return { status: response.status, body: await response.json() };
    }, { path, input, key });
}
function success(result) {
    assert.equal(result.status, 200, JSON.stringify(result));
    return result.body.data;
}
function states(record) {
    return Object.fromEntries(record.returnItems.map(item => [item.toolId, item.state]));
}
try {
    const reader=await login('reader'),keeper=await login('keeper');
    const candidates=success(await api(reader,`${base}/candidates`));
    const scope=candidates.scopes[0].id;
    const tool=success(await api(keeper,'/api/v1/apps/tooling/entities/tool/records',{
        definitionVersion:'1',values:{code:marker,name:'Late loss rollback',custodyScopeId:scope}
    }));
    const loan=success(await api(reader,base,{definitionVersion:'1',organizationId:candidates.departments[0].id,
        custodyScopeId:scope,purpose:marker,toolIds:[tool.id]}));
    success(await api(keeper,`${base}/${loan.id}/issue`,{definitionVersion:'1',expectedVersion:1}));
    const pending=process.env.D2_LOSS_WITH_REQUEST==='true';
    if(pending) success(await api(reader,`${base}/${loan.id}/return-requests`,{definitionVersion:'1',expectedVersion:2,toolIds:[tool.id]}));
    const version=pending?3:2;
    const key=crypto.randomUUID();
    const released=new Promise(resolve=>{releaseBarrier=resolve;});
    console.log(JSON.stringify({stage:'ready',loan:loan.id,version,taskKey:pending?'receive':'returnRequest'}));
    await released;releaseBarrier=undefined;
    const input={definitionVersion:'1',expectedVersion:version,toolId:tool.id,reason:'Synthetic late loss rollback'};
    const failed=await api(keeper,`${base}/${loan.id}/close-lost`,input,key);
    assert.equal(failed.status,503,JSON.stringify(failed));
    await barrier('failed',loan.id);
    const closed=success(await api(keeper,`${base}/${loan.id}/close-lost`,input,key));
    assert.equal(closed.state,'SETTLED');assert.equal(closed.version,version+1);
    assert.equal(closed.returnItems[0].state,'LOST');
    console.log(JSON.stringify({stage:'complete',loan:loan.id,tool:tool.id,originalTask:pending?'receive':'returnRequest',failedStatus:failed.status,retryStatus:200}));
} finally {await browser.close();process.stdin.pause();}
