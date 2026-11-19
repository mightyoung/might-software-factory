import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const origin = process.env.D2_SCOPE_ORIGIN;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '')
    || process.env.D2_SCOPE_ACK !== 'isolated-read-scope:msf_d2_read_scope_test') {
  throw Error('Owned read-scope target required');
}
const phase = process.env.D2_SCOPE_GRANT_PHASE;
assert.ok(['revoke-keeper', 'grant-successor'].includes(phase), 'Unknown grant phase');
assert.ok(process.env.D2_PASSWORD_ADMIN, 'Synthetic seed admin password required');
const browser = await chromium.launch({ channel: 'chrome' });
try {
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  await page.goto(origin);
  await page.getByLabel('租户编码').fill('d1seed');
  await page.getByLabel('账号', { exact: true }).fill('d1seed-admin');
  await page.getByLabel('密码', { exact: true }).fill(process.env.D2_PASSWORD_ADMIN);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('link', { name: 'MIGHT 工作台首页' })).toBeVisible();
  const result = await page.evaluate(async ({ phase }) => {
    const rolesResponse = await fetch('/api/v1/admin/roles');
    const rolesBody = await rolesResponse.json();
    if (rolesResponse.status !== 200) return { status: rolesResponse.status, body: rolesBody };
    const code = phase === 'revoke-keeper' ? 'D1-keeper' : 'D1-nonkeeper';
    const role = rolesBody.roles.find(item => item.code === code);
    if (!role) throw Error(`Synthetic role ${code} missing`);
    const hadReceive = role.permissionCodes.includes('tool_loan.receive');
    if (hadReceive !== (phase === 'revoke-keeper')) throw Error('Unexpected baseline receive grant');
    const permissionCodes = phase === 'revoke-keeper'
      ? role.permissionCodes.filter(item => item !== 'tool_loan.receive')
      : [...role.permissionCodes, 'tool_loan.receive'];
    const csrf = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))?.slice(11);
    const response = await fetch(`/api/v1/admin/roles/${role.id}/permissions`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf ?? '') },
      body: JSON.stringify({ permissionCodes, expectedVersion: role.version }),
    });
    return { status: response.status, body: await response.json(), roleId: role.id, before: role.permissionCodes };
  }, { phase });
  assert.equal(result.status, 200, JSON.stringify(result.body));
  assert.equal(result.body.permissionCodes.includes('tool_loan.receive'), phase === 'grant-successor');
  assert.deepEqual(result.body.permissionCodes.filter(item => item !== 'tool_loan.receive').sort(),
    result.before.filter(item => item !== 'tool_loan.receive').sort());
  console.log(JSON.stringify({ phase, roleId: result.roleId, receiveGranted: phase === 'grant-successor', api: true }));
} finally {
  await browser.close();
}
