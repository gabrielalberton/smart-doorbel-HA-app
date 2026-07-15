const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const http = require('node:http');
const crypto = require('node:crypto');

const port = 18881;
let child;

function scryptHash(password) {
  const salt = Buffer.from('00112233445566778899aabbccddeeff', 'hex');
  const derived = crypto.scryptSync(password, salt, 32);
  return `scrypt:${salt.toString('hex')}:${derived.toString('hex')}`;
}

function requestPair(password) {
  const payload = JSON.stringify({ password });
  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: '127.0.0.1', port, path: '/api/pair', method: 'POST',
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) },
    }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(body) }));
    });
    req.on('error', reject);
    req.end(payload);
  });
}

async function waitForServer() {
  for (let i = 0; i < 50; i += 1) {
    try {
      await new Promise((resolve, reject) => {
        const req = http.get(`http://127.0.0.1:${port}/healthz`, (res) => { res.resume(); resolve(); });
        req.on('error', reject);
      });
      return;
    } catch { await new Promise((resolve) => setTimeout(resolve, 50)); }
  }
  throw new Error('server did not start');
}

test.before(async () => {
  child = spawn(process.execPath, ['server.js'], {
    cwd: require('node:path').resolve(__dirname, '..'),
    env: {
      ...process.env,
      PORT: String(port),
      PAIRING_PASSWORD_HASH: scryptHash('synthetic-test-password'),
      PUBLIC_BASE_URL: 'https://doorbell.example.test',
      LOCAL_BASE_URL: 'https://doorbell.local.example.test',
      AUTH_HOST: 'auth.example.test',
      GITHUB_RELEASE_REPO: 'example/smart-doorbell',
      HOME_ASSISTANT_PACKAGE_PREFIX: 'io.homeassistant.companion.',
      DOORBELL_TRIGGER_TAG: 'synthetic_trigger',
      DOORBELL_TRIGGER_CHANNEL: 'synthetic_trigger',
      DOORBELL_NOTIFICATION_TITLE: 'Synthetic Doorbell',
    },
    stdio: 'ignore',
  });
  await waitForServer();
});

test.after(() => { child?.kill('SIGTERM'); });

test('rejects an invalid pairing password', async () => {
  const result = await requestPair('wrong-password');
  assert.equal(result.status, 401);
  assert.equal(result.body.ok, false);
});

test('returns non-secret runtime configuration for the fixed password', async () => {
  const result = await requestPair('synthetic-test-password');
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    ok: true,
    publicBaseUrl: 'https://doorbell.example.test',
    localBaseUrl: 'https://doorbell.local.example.test',
    authHost: 'auth.example.test',
    githubReleaseRepo: 'example/smart-doorbell',
    homeAssistantPackagePrefix: 'io.homeassistant.companion.',
    triggerTag: 'synthetic_trigger',
    triggerChannel: 'synthetic_trigger',
    doorbellTitle: 'Synthetic Doorbell',
  });
  assert.equal(JSON.stringify(result.body).includes('synthetic-test-password'), false);
});

test('rate limits repeated invalid pairing attempts', async () => {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const result = await requestPair(`wrong-${attempt}`);
    assert.equal(result.status, 401);
  }
  const blocked = await requestPair('wrong-again');
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.ok, false);
});
