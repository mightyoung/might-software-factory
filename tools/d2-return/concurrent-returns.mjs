// Real HTTPS commands. concurrency.py observes PostgreSQL waiters at both barriers.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const origin = process.env.D2_RETURN_ORIGIN;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '')
    || process.env.D2_RETURN_ACK !== 'isolated-return-acceptance:msf_d2_return_test') {
    throw Error('Dedicated return acceptance target required');
}
const toolIds = JSON.parse(process.env.D2_RETURN_TOOL_IDS);
assert.equal(toolIds.length, 3);
const marker = `RETURN-PG-${crypto.randomUUID()}`;
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
    const candidates = success(await api(reader, `${base}/candidates?custodyScopeId=${process.env.D2_RETURN_SCOPE_ID}`));
    const loan = success(await api(reader, base, {
        definitionVersion: '1', organizationId: candidates.departments[0].id,
        custodyScopeId: process.env.D2_RETURN_SCOPE_ID, purpose: marker, toolIds,
    }));
    const issueKey = crypto.randomUUID();
    const issueInput = { definitionVersion: '1', expectedVersion: 1 };
    const issued = success(await api(keeper, `${base}/${loan.id}/issue`, issueInput, issueKey));
    assert.equal(issued.version, 2);
    const rejectedKeeperReturn = await api(keeper, `${base}/${loan.id}/return-requests`, {
        definitionVersion: '1', expectedVersion: 2, toolIds: [toolIds[0]],
    });
    assert.ok([403, 404].includes(rejectedKeeperReturn.status), JSON.stringify(rejectedKeeperReturn));
    const rejectedReaderReceive = await api(reader, `${base}/${loan.id}/receive`, {
        definitionVersion: '1', expectedVersion: 2, items: [{ toolId: toolIds[0], condition: 'NORMAL' }],
    });
    assert.equal(rejectedReaderReceive.status, 403, JSON.stringify(rejectedReaderReceive));
    const staleReturn = await api(reader, `${base}/${loan.id}/return-requests`, {
        definitionVersion: '1', expectedVersion: 99, toolIds: [toolIds[0]],
    });
    assert.equal(staleReturn.status, 409, JSON.stringify(staleReturn));
    const wrongDefinition = await api(reader, `${base}/${loan.id}/return-requests`, {
        definitionVersion: '2', expectedVersion: 2, toolIds: [toolIds[0]],
    });
    assert.equal(wrongDefinition.status, 409, JSON.stringify(wrongDefinition));
    const returnKeys = [crypto.randomUUID(), crypto.randomUUID()];
    const firstReturn = { definitionVersion: '1', expectedVersion: 2, toolIds: [toolIds[0]] };
    await barrier('return-ready', loan.id);
    const requests = await Promise.all(returnKeys.map(key => api(reader, `${base}/${loan.id}/return-requests`, firstReturn, key)));
    assert.deepEqual(requests.map(result => result.status).sort(), [200, 409], JSON.stringify(requests));
    const returnKey = returnKeys[requests.findIndex(result => result.status === 200)];
    success(await api(reader, `${base}/${loan.id}/return-requests`, {
        definitionVersion: '1', expectedVersion: 3, toolIds: [toolIds[1]],
    }));
    const receiveKeys = [crypto.randomUUID(), crypto.randomUUID()];
    const firstReceive = { definitionVersion: '1', expectedVersion: 4, items: [{ toolId: toolIds[0], condition: 'NORMAL' }] };
    await barrier('receive-ready', loan.id);
    const receives = await Promise.all(receiveKeys.map(key => api(keeper, `${base}/${loan.id}/receive`, firstReceive, key)));
    assert.deepEqual(receives.map(result => result.status).sort(), [200, 409], JSON.stringify(receives));
    const receiveKey = receiveKeys[receives.findIndex(result => result.status === 200)];
    const historical = success(await api(reader, `${base}/${loan.id}/return-requests`, firstReturn, returnKey));
    assert.equal(historical.version, 3);
    assert.deepEqual(states(historical), { [toolIds[0]]: 'REQUESTED', [toolIds[1]]: 'OUTSTANDING', [toolIds[2]]: 'OUTSTANDING' });
    const next = success(await api(reader, `${base}/${loan.id}/return-requests`, {
        definitionVersion: '1', expectedVersion: 5, toolIds: [toolIds[2]],
    }));
    assert.equal(next.version, 6);
    const finalKey = crypto.randomUUID();
    const finalInput = {
        definitionVersion: '1', expectedVersion: 6,
        items: [{ toolId: toolIds[1], condition: 'NORMAL' }, { toolId: toolIds[2], condition: 'DAMAGED', reason: 'Inspection confirmed a crack' }],
    };
    const settled = success(await api(keeper, `${base}/${loan.id}/receive`, finalInput, finalKey));
    assert.equal(settled.version, 7);
    assert.equal(settled.state, 'SETTLED');
    assert.ok(settled.returnItems.every(item => item.state === 'RECEIVED'));
    const replay = success(await api(keeper, `${base}/${loan.id}/receive`, finalInput, finalKey));
    assert.equal(replay.version, 7);
    const originalIssue = success(await api(keeper, `${base}/${loan.id}/issue`, issueInput, issueKey));
    assert.equal(originalIssue.version, 2);
    assert.ok(originalIssue.returnItems.every(item => item.state === 'OUTSTANDING'));
    const originalReceive = success(await api(keeper, `${base}/${loan.id}/receive`, firstReceive, receiveKey));
    assert.equal(originalReceive.version, 5);
    assert.deepEqual(states(originalReceive), { [toolIds[0]]: 'RECEIVED', [toolIds[1]]: 'REQUESTED', [toolIds[2]]: 'OUTSTANDING' });
    const damaged = await api(reader, base, {
        definitionVersion: '1', organizationId: loan.organizationId,
        custodyScopeId: loan.custodyScopeId, purpose: marker + '-damaged', toolIds: [toolIds[2]],
    });
    assert.equal(damaged.status, 400, JSON.stringify(damaged));
    console.log(JSON.stringify({ stage: 'complete', marker, loan: loan.id, toolIds,
        denials: { keeperReturn: rejectedKeeperReturn.status, readerReceive: rejectedReaderReceive.status, staleReturn: staleReturn.status, wrongDefinition: wrongDefinition.status, damagedReborrow: damaged.status }, requestStatuses: requests.map(result => result.status), receiveStatuses: receives.map(result => result.status), finalVersion: settled.version, historicalReplay: 'PASS' }));
} finally {
    await browser.close();
    process.stdin.destroy();
}
