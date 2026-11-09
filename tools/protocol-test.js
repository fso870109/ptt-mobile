#!/usr/bin/env node
'use strict';
/**
 * 驗證與 ptt-prototype 伺服器的協定是否互通：
 *  - 雲端 /api/health 與登入格式（必須帶 org／orgCode，舊的 company 欄位會 need_org）
 *  - 可選：用本機管理員憑證檔在 DEMO 建立 t_ 開頭暫用帳號，測完刪除（絕不印出密碼）
 *  - 本機啟動一份伺服器（DATA_DIR 在 /tmp）：登入、hello、join、發話鎖、搶話、
 *    PCM 封包、文字訊息、即時字幕、調度台呼叫
 *
 * 不修改 /workspace/ptt-prototype 的檔案。
 */
const http = require('http');
const https = require('https');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

function loadWs() {
  const candidates = [
    path.join(__dirname, 'node_modules', 'ws'),
    path.join(__dirname, '..', '..', 'ptt-prototype', 'node_modules', 'ws'),
    'ws',
  ];
  for (const c of candidates) {
    try { return require(c); } catch { /* next */ }
  }
  throw new Error('找不到 ws 套件，請在 tools/ 執行 npm install');
}
const WebSocket = loadWs();

const CLOUD = process.env.PTT_CLOUD || 'https://ptt-prototype.onrender.com';
function pickPort() {
  if (process.env.PTT_LOCAL_PORT) return +process.env.PTT_LOCAL_PORT;
  const net = require('net');
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
    srv.on('error', reject);
  });
}
let LOCAL_PORT = 0;
const PROTO_DIR = process.env.PTT_PROTOTYPE || '/workspace/ptt-prototype';
const ADMIN_USER = 'admin';
const ADMIN_PASS = 'test-admin-pass-1234';
const DEFAULT_ORG = 'DEMO';

let failed = 0;
function ok(name) { console.log('  ✓', name); }
function bad(name, extra) { failed++; console.log('  ✗', name, extra ? '— ' + extra : ''); }
function assert(cond, name, extra) { if (cond) ok(name); else bad(name, extra); }

function request(urlStr, { method = 'GET', body, token, timeout = 25000 } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const lib = u.protocol === 'https:' ? https : http;
    const data = body == null ? null : Buffer.from(JSON.stringify(body));
    const req = lib.request({
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(data ? { 'Content-Length': data.length } : {}),
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString();
        let json = null;
        try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
        resolve({ status: res.statusCode, json, text, buffer: Buffer.concat(chunks) });
      });
    });
    req.on('error', reject);
    req.setTimeout(timeout, () => { req.destroy(new Error('timeout')); });
    if (data) req.write(data);
    req.end();
  });
}

function wait(ms) { return new Promise((r) => setTimeout(r, ms)); }

function sinePcm(samples = 1600, freq = 440) {
  const buf = Buffer.alloc(samples * 2);
  for (let i = 0; i < samples; i++) {
    const v = Math.round(Math.sin((2 * Math.PI * freq * i) / 16000) * 16000);
    buf.writeInt16LE(v, i * 2);
  }
  return buf;
}

// 最小 JPEG（僅檔頭可被伺服器 sniff 成 image/jpeg）
const TINY_JPEG = Buffer.from([
  0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
  0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xff, 0xd9,
]);

function loadCloudAdmin() {
  if (process.env.PTT_ADMIN_USER && process.env.PTT_ADMIN_PASS) {
    return { user: process.env.PTT_ADMIN_USER, pass: process.env.PTT_ADMIN_PASS };
  }
  const candidates = [
    process.env.PTT_ADMIN_CREDS,
    '/workspace/ptt-prototype-admin-credentials.txt',
  ].filter(Boolean);
  for (const f of candidates) {
    try {
      if (!fs.existsSync(f)) continue;
      const text = fs.readFileSync(f, 'utf8');
      const user = (text.match(/^username:\s*(.+)$/m) || [])[1];
      const pass = (text.match(/^password:\s*(.+)$/m) || [])[1];
      if (user && pass) return { user: user.trim(), pass: pass.trim() };
    } catch { /* next */ }
  }
  return null;
}

class Client {
  constructor(url) {
    this.url = url;
    this.json = [];
    this.binaries = [];
    this.ws = null;
  }
  connect() {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      const t = setTimeout(() => reject(new Error('ws connect timeout')), 10000);
      ws.on('open', () => { clearTimeout(t); resolve(); });
      ws.on('error', (e) => { clearTimeout(t); reject(e); });
      ws.on('message', (data, isBinary) => {
        if (isBinary) {
          this.binaries.push(Buffer.from(data));
          return;
        }
        try { this.json.push(JSON.parse(data.toString())); } catch { /* ignore */ }
      });
    });
  }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  sendBin(buf) { this.ws.send(buf); }
  async waitType(t, ms = 5000) {
    const start = Date.now();
    while (Date.now() - start < ms) {
      const hit = this.json.find((m) => m.t === t);
      if (hit) return hit;
      await wait(30);
    }
    throw new Error('timeout waiting for ' + t + ' (got ' + this.json.map((m) => m.t).join(',') + ')');
  }
  drain(t) { this.json = this.json.filter((m) => m.t !== t); }
  close() { try { this.ws && this.ws.close(); } catch { /* */ } }
}

async function testCloud() {
  console.log('\n== 雲端', CLOUD, '==');
  try {
    const h = await request(CLOUD + '/api/health');
    assert(h.status === 200 && h.json.ok === true, 'GET /api/health → ok');
    assert(h.json.storesAudio === false, 'storesAudio === false');
    assert(typeof h.json.online === 'number', 'health 含 online 欄位');
  } catch (e) {
    bad('雲端 /api/health 連線', e.message);
    return;
  }

  try {
    const r = await request(CLOUD + '/api/login', {
      method: 'POST',
      body: { username: 'no-such-user-ptt-mobile-ci', password: 'wrong-password-xxxx' },
    });
    assert(r.status === 400 && r.json.error === 'need_org',
      'POST /api/login 缺少 org → 400 need_org',
      `status=${r.status} error=${r.json && r.json.error}`);
  } catch (e) {
    bad('雲端 /api/login（缺少 org）', e.message);
  }

  try {
    const r = await request(CLOUD + '/api/login', {
      method: 'POST',
      body: { username: 'no-such-user-ptt-mobile-ci', password: 'wrong-password-xxxx', company: 'DEMO' },
    });
    assert(r.status === 400 && r.json.error === 'need_org',
      '舊 APK 只送 company、未送 org → 400 need_org',
      `status=${r.status} error=${r.json && r.json.error}`);
  } catch (e) {
    bad('雲端 /api/login（舊 company 欄位）', e.message);
  }

  try {
    const r = await request(CLOUD + '/api/login', {
      method: 'POST',
      body: { username: 'no-such-user-ptt-mobile-ci', password: 'wrong-password-xxxx', org: DEFAULT_ORG },
    });
    assert(r.status === 401 && r.json.error === 'invalid_credentials',
      'POST /api/login 帶 org=DEMO + 假帳號 → 401 invalid_credentials',
      `status=${r.status} error=${r.json && r.json.error}`);
  } catch (e) {
    bad('雲端 /api/login（org + 假帳號）', e.message);
  }

  try {
    const r = await request(CLOUD + '/api/login', {
      method: 'POST',
      body: { username: 'no-such-user-ptt-mobile-ci', password: 'wrong-password-xxxx', orgCode: DEFAULT_ORG },
    });
    assert(r.status === 401 && r.json.error === 'invalid_credentials',
      'POST /api/login 接受 orgCode 別名（假帳號仍 401）',
      `status=${r.status} error=${r.json && r.json.error}`);
  } catch (e) {
    bad('雲端 /api/login（orgCode 別名）', e.message);
  }

  const creds = loadCloudAdmin();
  if (!creds) {
    console.log('  · 找不到管理員憑證檔，略過雲端成功登入與暫用帳號流程');
    return;
  }

  let adminTok = null;
  let createdId = null;
  try {
    const login = await request(CLOUD + '/api/login', {
      method: 'POST',
      body: { username: creds.user, password: creds.pass, org: DEFAULT_ORG },
    });
    assert(login.status === 200 && login.json.token,
      '管理員以 org=DEMO 登入成功',
      `status=${login.status} error=${login.json && login.json.error}`);
    if (!(login.status === 200 && login.json.token)) return;
    adminTok = login.json.token;
    const orgCode = login.json.org && login.json.org.code;
    assert(orgCode === DEFAULT_ORG, `登入回應 org.code === ${DEFAULT_ORG}`, `got=${orgCode}`);

    const uname = 't_' + Date.now().toString(36) + crypto.randomBytes(2).toString('hex');
    const tmpPass = 'Tmp' + crypto.randomBytes(8).toString('hex');
    const created = await request(CLOUD + '/api/admin/users', {
      method: 'POST',
      token: adminTok,
      body: { username: uname, displayName: '暫測帳號', password: tmpPass, role: 'user' },
    });
    assert(created.status === 201 && created.json.user && created.json.user.id,
      `在 DEMO 建立暫用帳號 ${uname}`,
      `status=${created.status} error=${created.json && created.json.error}`);
    if (!(created.status === 201 && created.json.user)) return;
    createdId = created.json.user.id;

    const ul = await request(CLOUD + '/api/login', {
      method: 'POST',
      body: { username: uname, password: tmpPass, org: DEFAULT_ORG },
    });
    assert(ul.status === 200 && ul.json.token && ul.json.user && ul.json.user.username === uname,
      `暫用帳號 ${uname} 以 org=DEMO 登入成功`,
      `status=${ul.status} error=${ul.json && ul.json.error}`);
  } catch (e) {
    bad('雲端管理員／暫用帳號流程', e.message);
  } finally {
    if (adminTok && createdId) {
      try {
        const del = await request(CLOUD + '/api/admin/users/' + createdId, {
          method: 'DELETE',
          token: adminTok,
        });
        assert(del.status === 200 || del.status === 204,
          '已刪除雲端暫用帳號',
          `status=${del.status} error=${del.json && del.json.error}`);
      } catch (e) {
        bad('刪除雲端暫用帳號', e.message);
      }
    }
  }
}

function startLocalServer() {
  if (!fs.existsSync(path.join(PROTO_DIR, 'server.js'))) {
    throw new Error('找不到原型伺服器：' + PROTO_DIR);
  }
  const dataDir = '/tmp/ptt-mobile-testdata-' + Date.now();
  fs.mkdirSync(dataDir, { recursive: true });
  const env = {
    ...process.env,
    PORT: String(LOCAL_PORT),
    DATA_DIR: dataDir,
    ADMIN_USERNAME: ADMIN_USER,
    ADMIN_PASSWORD: ADMIN_PASS,
    ADMIN_DISPLAY_NAME: '管理員',
  };
  delete env.DATABASE_URL;
  const child = spawn(process.execPath, ['server.js'], {
    cwd: PROTO_DIR,
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', (d) => { child._out = (child._out || '') + d; });
  child.stderr.on('data', (d) => { child._err = (child._err || '') + d; });
  child.dataDir = dataDir;
  return child;
}

async function waitHealth(base, tries = 40) {
  for (let i = 0; i < tries; i++) {
    try {
      const h = await request(base + '/api/health', { timeout: 2000 });
      if (h.status === 200 && h.json.ok) return h;
    } catch { /* retry */ }
    await wait(150);
  }
  throw new Error('本機伺服器沒有起來');
}

async function testLocal() {
  LOCAL_PORT = await pickPort();
  const base = 'http://127.0.0.1:' + LOCAL_PORT;
  console.log('\n== 本機', base, '==');
  let child;
  try {
    child = startLocalServer();
    await waitHealth(base);
    ok('本機伺服器啟動（DATA_DIR 在 /tmp）');
  } catch (e) {
    bad('本機伺服器啟動', e.message + (child && child._err ? ' ' + child._err : ''));
    if (child) child.kill('SIGTERM');
    return;
  }

  try {
    const missing = await request(base + '/api/login', {
      method: 'POST',
      body: { username: ADMIN_USER, password: ADMIN_PASS },
    });
    assert(missing.status === 400 && missing.json.error === 'need_org',
      '本機登入缺少 org → 400 need_org',
      `status=${missing.status} error=${missing.json && missing.json.error}`);

    const oldField = await request(base + '/api/login', {
      method: 'POST',
      body: { username: ADMIN_USER, password: ADMIN_PASS, company: DEFAULT_ORG },
    });
    assert(oldField.status === 400 && oldField.json.error === 'need_org',
      '本機登入只送 company → 400 need_org',
      `status=${oldField.status} error=${oldField.json && oldField.json.error}`);

    const loginAdmin = await request(base + '/api/login', {
      method: 'POST',
      body: { username: ADMIN_USER, password: ADMIN_PASS, org: DEFAULT_ORG },
    });
    assert(loginAdmin.status === 200 && loginAdmin.json.token, '管理員以 org=DEMO 登入成功');
    const adminTok = loginAdmin.json.token;

    async function mkUser(username, displayName) {
      const r = await request(base + '/api/admin/users', {
        method: 'POST',
        token: adminTok,
        body: { username, displayName, password: 'password123', role: 'user' },
      });
      assert(r.status === 201 && r.json.user && r.json.user.id, `建立帳號 ${username}`);
      return r.json.user;
    }
    const aliceU = await mkUser('alice_m', '小明');
    const bobU = await mkUser('bob_m', '小華');

    const aliceLogin = await request(base + '/api/login', {
      method: 'POST', body: { username: 'alice_m', password: 'password123', org: DEFAULT_ORG },
    });
    const bobLogin = await request(base + '/api/login', {
      method: 'POST', body: { username: 'bob_m', password: 'password123', org: DEFAULT_ORG },
    });
    assert(aliceLogin.status === 200 && bobLogin.status === 200, '兩個測試帳號以 org=DEMO 登入');

    const wsUrl = 'ws://127.0.0.1:' + LOCAL_PORT + '/ws';
    const alice = new Client(wsUrl);
    const bob = new Client(wsUrl);
    const admin = new Client(wsUrl);
    await alice.connect();
    await bob.connect();
    await admin.connect();
    ok('三條 WebSocket 連上 /ws');

    alice.send({ t: 'hello', token: aliceLogin.json.token });
    bob.send({ t: 'hello', token: bobLogin.json.token });
    admin.send({ t: 'hello', token: adminTok });
    const aw = await alice.waitType('welcome');
    const bw = await bob.waitType('welcome');
    const cw = await admin.waitType('welcome');
    assert(aw.user && aw.user.id === aliceU.id, 'alice 收到 welcome');
    assert(bw.user && bw.channels.some((c) => c.id === 'lobby'), 'bob welcome 含大廳');
    assert(cw.user && cw.user.role === 'admin', 'admin welcome');

    alice.send({ t: 'join', channel: 'lobby' });
    bob.send({ t: 'join', channel: 'lobby' });
    admin.send({ t: 'join', channel: 'lobby' });
    await alice.waitType('joined');
    await bob.waitType('joined');
    await admin.waitType('joined');
    ok('三人都加入大廳');

    alice.drain('talk_start');
    bob.drain('talk_start');
    alice.send({ t: 'ptt_start', conv: 'lobby' });
    const granted = await alice.waitType('floor_granted');
    assert(granted.conv === 'lobby' && granted.streamId > 0 && granted.maxMs === 60000,
      `alice floor_granted streamId=${granted.streamId}`);

    const talkStart = await bob.waitType('talk_start');
    assert(talkStart.from === aliceU.id && talkStart.streamId === granted.streamId,
      'bob 收到 talk_start，streamId 一致');

    bob.send({ t: 'ptt_start', conv: 'lobby' });
    const denied = await bob.waitType('floor_denied');
    assert(denied.reason === 'busy' && denied.by === aliceU.id, 'bob 發話被拒（busy）');

    const pcm = sinePcm(1600, 440);
    alice.sendBin(pcm);
    const start = Date.now();
    let pkt = null;
    while (Date.now() - start < 4000) {
      pkt = bob.binaries.find((b) => b.length >= 4);
      if (pkt) break;
      await wait(20);
    }
    if (!pkt) {
      bad('bob 收到二元音訊封包', 'timeout');
    } else {
      const sid = pkt.readUInt32BE(0);
      const payload = pkt.subarray(4);
      assert(sid === granted.streamId, `接收端 streamId（BE uint32）= ${sid}`);
      assert(payload.equals(pcm), `PCM 淨荷與送出的 ${pcm.length} bytes 完全相同`);
    }

    bob.drain('caption');
    alice.send({ t: 'caption', conv: 'lobby', text: '請到三號門', interim: false });
    const cap = await bob.waitType('caption');
    assert(cap.text === '請到三號門' && cap.conv === 'lobby' && cap.interim === false,
      'bob 收到即時字幕 caption');

    alice.send({ t: 'ptt_stop' });
    const end = await bob.waitType('talk_end');
    assert(end.reason === 'released' && end.streamId === granted.streamId, 'bob 收到 talk_end released');

    alice.drain('chat');
    bob.drain('chat');
    alice.send({ t: 'chat', conv: 'lobby', text: '你好測試' });
    const chatA = await alice.waitType('chat');
    const chatB = await bob.waitType('chat');
    assert(chatA.message && chatA.message.text === '你好測試' && chatA.message.kind === 'text',
      'alice 收到自己送出的文字訊息');
    assert(chatB.message && chatB.message.text === '你好測試' && chatB.message.from === aliceU.id,
      'bob 收到文字訊息');

    const hist = await request(base + '/api/messages?conv=lobby&limit=100', {
      token: aliceLogin.json.token,
    });
    assert(hist.status === 200 && (hist.json.messages || []).some((m) => m.text === '你好測試'),
      'GET /api/messages 可讀回剛才的文字');

    const photo = await request(base + '/api/photos', {
      method: 'POST',
      token: aliceLogin.json.token,
      body: { conv: 'lobby', data: TINY_JPEG.toString('base64') },
    });
    assert(photo.status === 201 && photo.json.message && photo.json.message.kind === 'photo' && photo.json.message.photoId,
      'POST /api/photos 上傳成功',
      `status=${photo.status} error=${photo.json && photo.json.error}`);
    if (photo.json.message && photo.json.message.photoId) {
      const got = await request(base + '/api/photos/' + photo.json.message.photoId, {
        token: bobLogin.json.token,
      });
      const raw = got.buffer || Buffer.from(got.text || '');
      assert(got.status === 200 && raw.equals(TINY_JPEG), 'GET /api/photos/:id 以 Bearer 取回原圖');
    }

    alice.drain('queue_self');
    alice.drain('dispatch_done');
    alice.send({ t: 'dispatch_call' });
    const qs = await alice.waitType('queue_self');
    assert(qs.item && qs.item.id, 'alice dispatch_call 收到 queue_self');
    alice.send({ t: 'dispatch_done', id: qs.item.id });
    const done = await alice.waitType('dispatch_done');
    assert(done.id === qs.item.id, 'alice 結束呼叫後收到 dispatch_done');

    // 搶話：一般成員先講，管理員（大廳預設主管）可中斷
    bob.drain('floor_granted');
    bob.drain('floor_revoked');
    admin.drain('floor_granted');
    alice.drain('talk_start');
    bob.send({ t: 'ptt_start', conv: 'lobby' });
    const g2 = await bob.waitType('floor_granted');
    assert(g2.streamId > 0, 'bob 取得發言權（搶話前）');
    admin.send({ t: 'ptt_start', conv: 'lobby' });
    const revoked = await bob.waitType('floor_revoked');
    const g3 = await admin.waitType('floor_granted');
    assert(revoked.reason === 'preempted', 'bob 被主管搶話（floor_revoked preempted）');
    assert(g3.streamId > 0, 'admin 取得發言權');
    admin.send({ t: 'ptt_stop' });

    alice.close();
    bob.close();
    admin.close();
  } catch (e) {
    bad('本機協定流程', e.stack || e.message);
  } finally {
    if (child) {
      child.kill('SIGTERM');
      await wait(300);
      try { child.kill('SIGKILL'); } catch { /* */ }
    }
  }
}

(async () => {
  console.log('PTT 原生客戶端 · 協定測試');
  await testCloud();
  await testLocal();
  console.log(failed ? `\n失敗 ${failed} 項` : '\n全部通過');
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
