const path = require('node:path');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const { request } = require('undici');
const Fastify = require('fastify');
const fastifyStatic = require('@fastify/static');

const app = Fastify({ logger: false });
// go2rtc WebRTC offers are raw SDP. Fastify does not parse application/sdp by default.
app.addContentTypeParser('application/sdp', { parseAs: 'string' }, (_req, body, done) => done(null, body));
app.addContentTypeParser('text/plain', { parseAs: 'string' }, (_req, body, done) => done(null, body));

const FRIGATE = process.env.FRIGATE_URL || 'http://frigate-standalone:5000';
const GO2RTC = process.env.GO2RTC_URL || 'http://frigate-standalone:1984';
const DEFAULT_STREAM = process.env.PRIMARY_STREAM || 'doorbell_talk';
const PRIMARY_STREAM_OPTIONS = (process.env.PRIMARY_STREAM_OPTIONS || DEFAULT_STREAM)
  .split(',').map((value) => value.trim()).filter(Boolean);
const SECONDARY_STREAM = (process.env.SECONDARY_STREAM || '').trim();
const HA_WEBHOOK_URL = process.env.HA_WEBHOOK_URL || '';
const ATTEND_WEBHOOK_URL = process.env.ATTEND_WEBHOOK_URL || '';
const TALK_LOCK_TTL_MS = Number(process.env.TALK_LOCK_TTL_MS || 15000);
const APP_UPDATE_VERSION = process.env.APP_UPDATE_VERSION || '';
const APP_UPDATE_VERSION_CODE = Number(process.env.APP_UPDATE_VERSION_CODE || 0);
const APP_UPDATE_APK_PATH = process.env.APP_UPDATE_APK_PATH || '';
const APP_UPDATE_SHA256 = process.env.APP_UPDATE_SHA256 || '';
const GITHUB_RELEASE_REPO = (process.env.GITHUB_RELEASE_REPO || '').trim();
const GITHUB_RELEASE_ASSET_SUFFIX = process.env.GITHUB_RELEASE_ASSET_SUFFIX || '.apk';
const AUDIO_TESTS = {};
if (process.env.TALKBACK_TCP_STREAM) AUDIO_TESTS.live_tcp_pcmu = {
  label: 'Live TCP PCMU', dst: process.env.TALKBACK_TCP_STREAM,
  src: 'ffmpeg:lavfi:sine=frequency=880:sample_rate=8000#input=-f lavfi -re -i {input}#audio=pcmu/8000',
};
if (process.env.TALKBACK_UDP_STREAM) AUDIO_TESTS.live_udp_pcmu = {
  label: 'Live UDP PCMU', dst: process.env.TALKBACK_UDP_STREAM,
  src: 'ffmpeg:lavfi:sine=frequency=880:sample_rate=8000#input=-f lavfi -re -i {input}#audio=pcmu/8000',
};
let githubReleaseCache = null;
let talkLock = null;

function nowMs() { return Date.now(); }
function normalizeClientId(req) {
  const body = req.body && typeof req.body === 'object' ? req.body : {};
  const raw = String(body.clientId || req.headers['x-client-id'] || '').trim();
  return raw.slice(0, 120);
}
function normalizeClientLabel(req) {
  const body = req.body && typeof req.body === 'object' ? req.body : {};
  const raw = String(body.label || '').trim();
  return raw.slice(0, 80);
}
function pruneTalkLock() {
  if (talkLock && talkLock.expiresAt <= nowMs()) talkLock = null;
}
function talkLockStatus(clientId = '') {
  pruneTalkLock();
  const locked = Boolean(talkLock);
  return {
    locked,
    mine: locked && talkLock.clientId === clientId,
    expiresInMs: locked ? Math.max(0, talkLock.expiresAt - nowMs()) : 0,
    ownerLabel: locked ? talkLock.label : '',
  };
}

app.register(fastifyStatic, { root: path.join(__dirname, 'public') });

app.get('/healthz', async () => 'ok');
app.get('/api/config', async () => ({
  defaultStream: DEFAULT_STREAM,
  primaryStreams: PRIMARY_STREAM_OPTIONS,
  secondaryStream: SECONDARY_STREAM,
  gateEnabled: Boolean(HA_WEBHOOK_URL),
  attendEnabled: Boolean(ATTEND_WEBHOOK_URL),
  talkLockTtlMs: TALK_LOCK_TTL_MS,
}));

async function githubReleaseManifest() {
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(GITHUB_RELEASE_REPO)) return null;
  if (githubReleaseCache && githubReleaseCache.expiresAt > Date.now()) return githubReleaseCache.value;
  const response = await request(`https://api.github.com/repos/${GITHUB_RELEASE_REPO}/releases/latest`, {
    headers: { accept: 'application/vnd.github+json', 'user-agent': 'smart-doorbell-update-checker' },
  });
  const body = await response.body.json();
  if (response.statusCode !== 200) throw new Error(`GitHub releases returned ${response.statusCode}`);
  const asset = (body.assets || []).find((item) => item.name?.toLowerCase().endsWith(GITHUB_RELEASE_ASSET_SUFFIX.toLowerCase()));
  if (!asset) throw new Error(`No ${GITHUB_RELEASE_ASSET_SUFFIX} asset in latest release`);
  const digest = typeof asset.digest === 'string' && asset.digest.startsWith('sha256:') ? asset.digest.slice(7) : '';
  const value = {
    available: true,
    version: String(body.tag_name || '').replace(/^v/i, ''),
    versionCode: 0,
    apkUrl: asset.browser_download_url,
    sha256: digest,
    source: 'github-releases',
  };
  githubReleaseCache = { value, expiresAt: Date.now() + 5 * 60_000 };
  return value;
}

app.get('/app-update/manifest.json', async (_req, reply) => {
  if (GITHUB_RELEASE_REPO) {
    try {
      reply.header('cache-control', 'no-store');
      return await githubReleaseManifest();
    } catch (error) {
      app.log.warn(error);
      return reply.code(502).send({ available: false, error: 'release lookup failed' });
    }
  }
  if (!APP_UPDATE_VERSION || !APP_UPDATE_APK_PATH) {
    reply.code(503);
    return { available: false };
  }
  reply.header('cache-control', 'no-store');
  return {
    available: true,
    version: APP_UPDATE_VERSION,
    versionCode: APP_UPDATE_VERSION_CODE,
    apkUrl: '/app-update/latest.apk',
    sha256: APP_UPDATE_SHA256,
  };
});

app.get('/app-update/latest.apk', async (_req, reply) => {
  if (!APP_UPDATE_APK_PATH) {
    return reply.code(404).send({ error: 'update not configured' });
  }
  try {
    const stat = await fsp.stat(APP_UPDATE_APK_PATH);
    if (!stat.isFile()) throw new Error('not a file');
    const safeVersion = APP_UPDATE_VERSION.replace(/[^0-9A-Za-z._-]/g, '') || 'latest';
    const hashSuffix = APP_UPDATE_SHA256 ? `-${APP_UPDATE_SHA256.slice(0, 8)}` : '';
    reply.header('cache-control', 'no-store');
    reply.header('content-type', 'application/vnd.android.package-archive');
    reply.header('content-length', String(stat.size));
    reply.header('content-disposition', `attachment; filename="Campainha-${safeVersion}${hashSuffix}.apk"`);
    return reply.send(fs.createReadStream(APP_UPDATE_APK_PATH));
  } catch (error) {
    return reply.code(404).send({ error: 'update APK unavailable' });
  }
});

async function notifyAttended() {
  if (!ATTEND_WEBHOOK_URL) return;
  try {
    const res = await request(ATTEND_WEBHOOK_URL, { method: 'POST', body: '{}' });
    // Consume the response body so the connection can be reused.
    await res.body.text();
  } catch (err) {
    console.warn('attend webhook failed', err);
  }
}

app.post('/api/attend', async () => {
  await notifyAttended();
  return { ok: true };
});

app.get('/atender', async (_req, reply) => {
  await notifyAttended();
  return reply.code(302).header('location', '/').send();
});
app.get('/api/audio-tests', async () => ({
  tests: Object.entries(AUDIO_TESTS).map(([id, test]) => ({ id, label: test.label, dst: test.dst, audio: test.audio })),
}));

app.get('/api/talk-lock/status', async (req) => talkLockStatus(String(req.query.clientId || '').slice(0, 120)));

app.post('/api/talk-lock/acquire', async (req, reply) => {
  const clientId = normalizeClientId(req);
  if (!clientId) {
    reply.code(400);
    return { ok: false, error: 'clientId required' };
  }
  pruneTalkLock();
  if (talkLock && talkLock.clientId !== clientId) {
    reply.code(423);
    return { ok: false, busy: true, ...talkLockStatus(clientId) };
  }
  talkLock = {
    clientId,
    label: normalizeClientLabel(req),
    acquiredAt: nowMs(),
    expiresAt: nowMs() + TALK_LOCK_TTL_MS,
  };
  return { ok: true, ...talkLockStatus(clientId) };
});

app.post('/api/talk-lock/heartbeat', async (req, reply) => {
  const clientId = normalizeClientId(req);
  if (!clientId) {
    reply.code(400);
    return { ok: false, error: 'clientId required' };
  }
  pruneTalkLock();
  if (!talkLock) {
    reply.code(409);
    return { ok: false, lost: true, ...talkLockStatus(clientId) };
  }
  if (talkLock.clientId !== clientId) {
    reply.code(423);
    return { ok: false, busy: true, ...talkLockStatus(clientId) };
  }
  talkLock.expiresAt = nowMs() + TALK_LOCK_TTL_MS;
  return { ok: true, ...talkLockStatus(clientId) };
});

app.post('/api/talk-lock/release', async (req) => {
  const clientId = normalizeClientId(req);
  pruneTalkLock();
  if (talkLock && talkLock.clientId === clientId) talkLock = null;
  return { ok: true, ...talkLockStatus(clientId) };
});

app.get('/api/streams', async (req, reply) => {
  const res = await request(`${FRIGATE}/api/go2rtc/streams`);
  reply.header('content-type', res.headers['content-type'] || 'application/json');
  return reply.send(res.body);
});

app.post('/api/webrtc', async (req, reply) => {
  const src = encodeURIComponent((req.query.src || DEFAULT_STREAM).toString());
  const body = typeof req.body === 'string' ? req.body : (req.body?.sdp || '');
  const url = `${FRIGATE}/api/go2rtc/webrtc?src=${src}`;
  const res = await request(url, {
    method: 'POST',
    headers: { 'content-type': 'application/sdp' },
    body,
  });
  const text = await res.body.text();
  reply.code(res.statusCode);
  reply.header('content-type', res.headers['content-type'] || 'application/sdp');
  return text;
});

app.post('/api/audio-tests/:id/play', async (req, reply) => {
  const test = AUDIO_TESTS[String(req.params.id || '')];
  if (!test) {
    reply.code(404);
    return { ok: false, error: 'audio test not found' };
  }
  const src = test.src;
  const url = `${GO2RTC}/api/streams?dst=${encodeURIComponent(test.dst)}&src=${encodeURIComponent(src)}`;
  const startedAt = Date.now();
  const res = await request(url, { method: 'POST' });
  const text = await res.body.text();
  if (res.statusCode < 200 || res.statusCode >= 300) {
    reply.code(res.statusCode);
    return { ok: false, statusCode: res.statusCode, body: text.slice(0, 300) };
  }
  return { ok: true, id: req.params.id, label: test.label, dst: test.dst, audio: test.audio, startedAt };
});

app.post('/api/audio-tests/stop', async (_req, reply) => {
  const results = [];
  const dsts = [...new Set(Object.values(AUDIO_TESTS).map((test) => test.dst))];
  for (const dst of dsts) {
    const url = `${GO2RTC}/api/streams?dst=${encodeURIComponent(dst)}&src=`;
    try {
      const res = await request(url, { method: 'POST' });
      const text = await res.body.text();
      results.push({ dst, statusCode: res.statusCode, body: text.slice(0, 120) });
    } catch (err) {
      results.push({ dst, error: err.message });
    }
  }
  return { ok: true, results };
});

app.post('/api/gate/toggle', async (_req, reply) => {
  if (!HA_WEBHOOK_URL) {
    reply.code(503);
    return { ok: false, error: 'HA webhook not configured' };
  }
  const res = await request(HA_WEBHOOK_URL, { method: 'POST', body: '{}' });
  const text = await res.body.text();
  if (res.statusCode < 200 || res.statusCode >= 300) {
    reply.code(res.statusCode);
    return { ok: false, statusCode: res.statusCode, body: text.slice(0, 200) };
  }
  return { ok: true };
});

app.listen({ host: '0.0.0.0', port: Number(process.env.PORT || 8080) })
  .then((address) => console.log(`Campainha interfone listening at ${address}`))
  .catch((err) => {
    console.error(err);
    process.exit(1);
  });
