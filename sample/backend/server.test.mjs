// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { spawnSync } from 'node:child_process';
import { request } from 'node:http';
import { json } from 'node:stream/consumers';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

test('importing the helper does not listen', () => {
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', `
    import { Server } from 'node:http';
    process.env.MOBILERUN_API_KEY = 'test-only';
    Server.prototype.listen = () => { throw new Error('unexpected listen'); };
    await import('./server.mjs');
  `], { cwd: new URL('.', import.meta.url), timeout: 5_000 });
  assert.equal(result.status, 0, result.stderr.toString());
});

test('starting the helper through a symlink listens on loopback', t => {
  const directory = mkdtempSync(join(tmpdir(), 'sample-helper-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const entry = join(directory, 'helper.mjs');
  symlinkSync(fileURLToPath(new URL('./server.mjs', import.meta.url)), entry);
  const preload = `
    import { Server } from 'node:http';
    Server.prototype.listen = (port, host) => {
      process.stdout.write(JSON.stringify({ port, host }));
    };
  `;
  const result = spawnSync(process.execPath, [
    '--import', `data:text/javascript,${encodeURIComponent(preload)}`, entry,
  ], { env: { ...process.env, MOBILERUN_API_KEY: 'test-only' }, timeout: 5_000 });
  assert.equal(result.status, 0, result.stderr.toString());
  assert.deepEqual(JSON.parse(result.stdout.toString()), { port: 3001, host: '127.0.0.1' });
});

async function fixture(t, sdk) {
  const { createHelper } = await import('./server.mjs');
  const server = createHelper(sdk);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(resolve => server.close(resolve)));
  return `http://127.0.0.1:${server.address().port}/api/v1/devices/device-a`;
}

for (const host of ['foreign.test', 'localhost:0']) {
  test(`foreign Host ${host} is forbidden without calling upstream`, async t => {
    const calls = [];
    const url = await fixture(t, { devices: { get: async id => {
      calls.push(id);
      return {};
    } } });
    const response = await new Promise((resolve, reject) => {
      request(url, { headers: { Host: host } }, resolve).on('error', reject).end();
    });
    assert.equal(response.statusCode, 403);
    assert.equal(response.headers['cache-control'], 'no-store');
    assert.deepEqual(await json(response), { error: 'forbidden' });
    assert.deepEqual(calls, []);
  });
}

for (const origin of ['', 'null', 'https://foreign.test']) {
  test(`Origin present (${JSON.stringify(origin)}) is forbidden without calling upstream`, async t => {
    const calls = [];
    const url = await fixture(t, { streamTokens: { create: async (...args) => {
      calls.push(args);
      return {};
    } } });
    const response = await fetch(`${url}/stream-key`, { method: 'POST', headers: { Origin: origin } });
    assert.equal(response.status, 403);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), { error: 'forbidden' });
    assert.deepEqual(calls, []);
  });
}

// SDK 6.2.1 Device.info is DeviceInfo (DeviceRetrieveResponse); extra fields stay private.
test('discovery exposes only the narrow DTO and disables caching', async t => {
  const calls = [];
  const url = await fixture(t, { devices: { get: async id => {
    calls.push(id);
    return { info: { id: 'device-a', state: 'ready', streamUrl: 'wss://stream.test/a',
      streamToken: 'private-static' }, apiKey: 'private-parent' };
  } } });
  const response = await fetch(url);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('access-control-allow-origin'), null);
  assert.deepEqual(await response.json(), {
    id: 'device-a', state: 'ready', streamUrl: 'wss://stream.test/a',
  });
  assert.deepEqual(calls, ['device-a']);
});

test('missing discovery fields are null', async t => {
  const url = await fixture(t, { devices: { get: async () => ({}) } });
  assert.deepEqual(await (await fetch(url)).json(), { id: null, state: null, streamUrl: null });
});

test('mint uses the official operation once, no retries, and a narrow DTO', async t => {
  const calls = [];
  const url = await fixture(t, { streamTokens: { create: async (...args) => {
    calls.push(args);
    return { key: 'key-a', keyId: 'id-a', expiresAt: '2026-11-19T12:02:00Z',
      apiKey: 'private-parent', streamToken: 'private-static' };
  } } });
  const response = await fetch(`${url}/stream-key`, { method: 'POST' });
  assert.equal(response.status, 201);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await response.json(), {
    key: 'key-a', keyId: 'id-a', expiresAt: '2026-11-19T12:02:00Z',
  });
  assert.deepEqual(calls, [[{ deviceId: 'device-a', ttlSeconds: 120 }, { maxRetries: 0 }]]);
});

for (const status of [401, 403, 404, 409, 429, 503, undefined]) {
  for (const mint of [false, true]) {
    const route = mint ? 'mint' : 'discovery';
    test(`upstream ${status} on ${route}: generic, no retry`, async t => {
      let calls = 0;
      const fail = async () => {
        calls++;
        throw Object.assign(new Error('private-parent private-static private-key'), { status });
      };
      const url = await fixture(t, { streamTokens: { create: fail }, devices: { get: fail } });
      const response = await fetch(url + (mint ? '/stream-key' : ''), { method: mint ? 'POST' : 'GET' });
      assert.equal(response.status, status ?? 502);
      assert.equal(response.headers.get('cache-control'), 'no-store');
      assert.deepEqual(await response.json(), { error: 'upstream_request_failed' });
      assert.equal(calls, 1);
    });
  }
}

test('invalid paths and methods do not invoke upstream', async t => {
  const url = await fixture(t, {});
  const wrongMethod = await fetch(`${url}/stream-key`);
  assert.equal(wrongMethod.status, 405);
  assert.equal(wrongMethod.headers.get('cache-control'), 'no-store');
  const invalid = await fetch(`${url}%2Fother/stream-key`, { method: 'POST' });
  assert.equal(invalid.status, 404);
  assert.equal(invalid.headers.get('cache-control'), 'no-store');
});
