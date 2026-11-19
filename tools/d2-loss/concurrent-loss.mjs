// Real HTTPS commands. concurrency.py observes PostgreSQL waiters at both barriers.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const origin = process.env.D2_LOSS_ORIGIN;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '')
    || process.env.D2_LOSS_ACK !== 'isolated-loss-acceptance:msf_d2_loss_test') {
    throw Error('Dedicated loss acceptance target required');
}
const toolIds = JSON.parse(process.env.D2_LOSS_TOOL_IDS);
assert.equal(toolIds.length, 3);
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
    const reader = await login('reader');
    const keeper = await login('keeper');
    const candidates = success(await api(reader, `${base}/candidates?custodyScopeId=${process.env.D2_LOSS_SCOPE_ID}`));
    const input = {
        definitionVersion: '1', organizationId: candidates.departments[0].id,
        custodyScopeId: process.env.D2_LOSS_SCOPE_ID, purpose: marker, toolIds,
    };
    const loan = success(await api(reader, base, input));
    const issueKey = crypto.randomUUID();
    const issueInput = { definitionVersion: '1', expectedVersion: 1 };
    success(await api(keeper, `${base}/${loan.id}/issue`, issueInput, issueKey));
    const firstKey = crypto.randomUUID();
    const firstLoss = { definitionVersion: '1', expectedVersion: 2, toolId: toolIds[0], reason: 'Search completed; first item confirmed lost' };
    const unauthorized = await api(reader, `${base}/${loan.id}/close-lost`, firstLoss);
    assert.equal(unauthorized.status, 403, JSON.stringify(unauthorized));
    const wrongDefinition = await api(keeper, `${base}/${loan.id}/close-lost`, { ...firstLoss, definitionVersion: '2' });
    assert.equal(wrongDefinition.status, 409, JSON.stringify(wrongDefinition));
    const stale = await api(keeper, `${base}/${loan.id}/close-lost`, { ...firstLoss, expectedVersion: 99 });
    assert.equal(stale.status, 409, JSON.stringify(stale));
    const emptyReason = await api(keeper, `${base}/${loan.id}/close-lost`, { ...firstLoss, reason: ' ' });
    assert.equal(emptyReason.status, 400, JSON.stringify(emptyReason));
    const first = success(await api(keeper, `${base}/${loan.id}/close-lost`, firstLoss, firstKey));
    assert.equal(first.version, 3);
    assert.equal(first.state, 'ISSUED');
    assert.deepEqual(states(first), { [toolIds[0]]: 'LOST', [toolIds[1]]: 'OUTSTANDING', [toolIds[2]]: 'OUTSTANDING' });
    const returnKey = crypto.randomUUID();
    const returnInput = { definitionVersion: '1', expectedVersion: 3, toolIds: [toolIds[1]] };
    success(await api(reader, `${base}/${loan.id}/return-requests`, returnInput, returnKey));
    await barrier('race-ready', loan.id);
    const racedLoss = { definitionVersion: '1', expectedVersion: 4, toolId: toolIds[1], reason: 'Second item loss conclusion during receipt race' };
    const racedReceive = { definitionVersion: '1', expectedVersion: 4, items: [{ toolId: toolIds[1], condition: 'NORMAL' }] };
    const racedKeys = [crypto.randomUUID(), crypto.randomUUID()];
    const results = await Promise.all([
        api(keeper, `${base}/${loan.id}/close-lost`, racedLoss, racedKeys[0]),
        api(keeper, `${base}/${loan.id}/receive`, racedReceive, racedKeys[1]),
    ]);
    assert.deepEqual(results.map(result => result.status).sort(), [200, 409], JSON.stringify(results));
    const lossWon = results[0].status === 200;
    const winnerReplay = success(await api(keeper, `${base}/${loan.id}/${lossWon ? 'close-lost' : 'receive'}`,
        lossWon ? racedLoss : racedReceive, racedKeys[lossWon ? 0 : 1]));
    assert.equal(winnerReplay.version, 5);
    await barrier(lossWon ? 'loss-won' : 'receipt-won', loan.id);
    const lastReturn = success(await api(reader, `${base}/${loan.id}/return-requests`, {
        definitionVersion: '1', expectedVersion: 5, toolIds: [toolIds[2]],
    }));
    assert.equal(lastReturn.version, 6);
    const last = success(await api(keeper, `${base}/${loan.id}/close-lost`, {
        definitionVersion: '1', expectedVersion: 6, toolId: toolIds[2], reason: 'Last item loss conclusion',
    }));
    assert.equal(last.version, 7);
    assert.equal(last.state, 'SETTLED');
    const oldIssue = success(await api(keeper, `${base}/${loan.id}/issue`, issueInput, issueKey));
    assert.equal(oldIssue.version, 2);
    assert.ok(oldIssue.returnItems.every(item => item.state === 'OUTSTANDING'));
    const oldReturn = success(await api(reader, `${base}/${loan.id}/return-requests`, returnInput, returnKey));
    assert.equal(oldReturn.version, 4);
    assert.deepEqual(states(oldReturn), { [toolIds[0]]: 'LOST', [toolIds[1]]: 'REQUESTED', [toolIds[2]]: 'OUTSTANDING' });
    const oldLoss = success(await api(keeper, `${base}/${loan.id}/close-lost`, firstLoss, firstKey));
    assert.equal(oldLoss.version, 3);
    assert.deepEqual(states(oldLoss), { [toolIds[0]]: 'LOST', [toolIds[1]]: 'OUTSTANDING', [toolIds[2]]: 'OUTSTANDING' });
    const changed = await api(keeper, `${base}/${loan.id}/close-lost`, { ...firstLoss, reason: 'Different conclusion' }, firstKey);
    assert.equal(changed.status, 409, JSON.stringify(changed));
    const reborrow = await api(reader, base, { ...input, purpose: marker + '-lost', toolIds: [toolIds[0]] });
    assert.equal(reborrow.status, 400, JSON.stringify(reborrow));
    const directToolId = process.env.D2_LOSS_DIRECT_TOOL_ID;
    const direct = success(await api(reader, base, { ...input, purpose: marker + '-direct', toolIds: [directToolId] }));
    success(await api(keeper, `${base}/${direct.id}/issue`, { definitionVersion: '1', expectedVersion: 1 }));
    const directLoss = success(await api(keeper, `${base}/${direct.id}/close-lost`, {
        definitionVersion: '1', expectedVersion: 2, toolId: directToolId, reason: 'One-item direct loss without return request',
    }));
    assert.equal(directLoss.version, 3);
    assert.equal(directLoss.state, 'SETTLED');
    console.log(JSON.stringify({ stage: 'complete', marker, loan: loan.id, toolIds, lossWon, directLoan: direct.id, directToolId,
        statuses: results.map(result => result.status), historicalReplay: 'PASS',
        denials: { unauthorized: unauthorized.status, wrongDefinition: wrongDefinition.status, stale: stale.status,
            emptyReason: emptyReason.status, changedPayload: changed.status, lostReborrow: reborrow.status } }));
} finally {
    await browser.close();
    process.stdin.destroy();
}
