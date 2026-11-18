import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import test from 'node:test';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const gateway = require('../gateway.js');
const { resolvePairingPublicUrl } = require('../show-pairing.js');

function getUnusedPort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close((err) => (err ? reject(err) : resolve(port)));
    });
  });
}

function rawUpgradeRequest(port, pathWithQuery, headers = {}) {
  return new Promise((resolve, reject) => {
    const client = net.connect(port, '127.0.0.1');
    const chunks = [];
    client.on('error', (err) => resolve({ error: err, text: Buffer.concat(chunks).toString('utf8'), client }));
    client.on('connect', () => {
      const lines = [
        `GET ${pathWithQuery} HTTP/1.1`,
        `Host: 127.0.0.1:${port}`,
        'Upgrade: websocket',
        'Connection: Upgrade',
        'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==',
        'Sec-WebSocket-Version: 13',
      ];
      for (const [k, v] of Object.entries(headers)) {
        lines.push(`${k}: ${v}`);
      }
      client.write(lines.join('\r\n') + '\r\n\r\n');
    });
    client.on('data', (chunk) => {
      chunks.push(chunk);
      const text = Buffer.concat(chunks).toString('utf8');
      if (text.includes('\r\n\r\n')) {
        resolve({ error: null, text, client });
      }
    });
    client.on('end', () => {
      resolve({ error: null, text: Buffer.concat(chunks).toString('utf8'), client });
    });
  });
}

let upstreamHits = [];
let upstreamUpgradeCount = 0;
let slowUpstreamClosed = false;

const upstreamServer = http.createServer((req, res) => {
  upstreamHits.push(req.url);
  if (req.url === '/api/sessions') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('[]');
    return;
  }
  if (req.url.startsWith('/api/file-index')) {
    const u = new URL(req.url, 'http://127.0.0.1');
    const q = u.searchParams.get('q');
    res.writeHead(200, { 'Content-Type': 'application/json' });
    if (q !== null && q !== '') {
      res.end(JSON.stringify({ matches: [{ path: `match/${q}` }] }));
    } else {
      res.end(JSON.stringify({ files: ['full-index-a.js', 'full-index-b.js'] }));
    }
    return;
  }
  if (req.url.startsWith('/test/truncated-json')) {
    res.writeHead(200, { 'Content-Length': '100', 'Content-Type': 'application/json' });
    res.write('{"partial":');
    setTimeout(() => {
      try { req.socket.destroy(); } catch {}
    }, 15);
    return;
  }
  if (req.url.startsWith('/test/truncated-chunked')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write('{"partial":');
    setTimeout(() => {
      try { req.socket.destroy(); } catch {}
    }, 15);
    return;
  }
  if (req.url.startsWith('/test/slow')) {
    slowUpstreamClosed = false;
    req.on('close', () => {
      slowUpstreamClosed = true;
    });
    const t = setTimeout(() => {
      if (!res.writableEnded) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end('{"ok":true}');
      }
    }, 2000);
    req.on('close', () => clearTimeout(t));
    return;
  }
  res.writeHead(404);
  res.end('not found');
});

const upstreamSockets = new Set();
upstreamServer.on('upgrade', (req, socket) => {
  upstreamUpgradeCount += 1;
  upstreamSockets.add(socket);
  socket.on('close', () => upstreamSockets.delete(socket));
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n'
  );
  socket.on('data', (d) => socket.write(d));
  socket.on('error', () => {});
});

await new Promise((resolve) => upstreamServer.listen(0, '127.0.0.1', resolve));
const upstreamOrigin = `http://127.0.0.1:${upstreamServer.address().port}`;

const started = await gateway.start({
  port: 0,
  tunnelMode: 'none',
  target: upstreamOrigin,
  autoHeal: false,
  warmUp: false,
});
const gatewayServer = started.server;
const gatewayPort = gatewayServer.address().port;
const gatewayOrigin = `http://127.0.0.1:${gatewayPort}`;

test.after(() => {
  for (const s of upstreamSockets) {
    try { s.destroy(); } catch {}
  }
  gatewayServer.closeAllConnections?.();
  gatewayServer.close();
  upstreamServer.closeAllConnections?.();
  upstreamServer.close();
  gateway.proxyAgent.destroy();
  http.globalAgent.destroy();
});

test('WS upgrade still enforces auth after an HTTP request and survives client RST on 401', async () => {
  // 先发一个正常 HTTP 请求：旧版 ws:true 会在此刻把无鉴权 handleUpgrade 挂到 server 上
  const ping = await fetch(`${gatewayOrigin}/api/ping`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  assert.equal(ping.status, 200);

  const beforeUpgrades = upstreamUpgradeCount;
  const unauth = await rawUpgradeRequest(gatewayPort, '/ws/terminal');
  assert.match(unauth.text, /^HTTP\/1\.1 401 Unauthorized/i);
  assert.match(unauth.text, /invalid_token/);
  assert.equal(upstreamUpgradeCount, beforeUpgrades, 'unauthorized WS upgrade must never reach upstream');

  // 模拟客户端收到 401 后立即发 TCP RST：gateway 必须捕获 ECONNRESET 不崩溃
  try { unauth.client.resetAndDestroy?.(); } catch { unauth.client.destroy(); }
  await new Promise((r) => setTimeout(r, 30));

  // 鉴权通过的 WS 升级（Bearer 头 & ?token=）应正常直达上游
  const authed = await rawUpgradeRequest(gatewayPort, '/ws/terminal', {
    Authorization: `Bearer ${gateway.AUTH_TOKEN}`,
  });
  assert.match(authed.text, /^HTTP\/1\.1 101 Switching Protocols/i);
  assert.equal(upstreamUpgradeCount, beforeUpgrades + 1);
  authed.client.destroy();

  const queryAuthed = await rawUpgradeRequest(
    gatewayPort,
    `/ws/terminal?token=${encodeURIComponent(gateway.AUTH_TOKEN)}`,
  );
  assert.match(queryAuthed.text, /^HTTP\/1\.1 101 Switching Protocols/i);
  assert.equal(upstreamUpgradeCount, beforeUpgrades + 2);
  queryAuthed.client.destroy();
});

test('WS upgrade error destroys socket cleanly instead of throwing res.status is not a function', async () => {
  const closedPort = await getUnusedPort();
  gateway.setTarget(`http://127.0.0.1:${closedPort}`);
  try {
    const failedWs = await rawUpgradeRequest(gatewayPort, '/ws/terminal', {
      Authorization: `Bearer ${gateway.AUTH_TOKEN}`,
    });
    // 上游不可达时安全销毁 socket，网关不崩
    assert.equal(failedWs.text.startsWith('HTTP/1.1 101'), false);
    failedWs.client.destroy();
  } finally {
    gateway.setTarget(upstreamOrigin);
  }

  // 网关仍健康可用
  const ping = await fetch(`${gatewayOrigin}/api/ping`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  assert.equal(ping.status, 200);
});

test('non-SSE upstream response error and early client abort never crash or leak upstream connections', async () => {
  // 1) 非 SSE 上游写半截断流，proxyRes.on('error') 立即销毁下游连接，不挂起也不伪造完整 EOF
  const t0 = Date.now();
  const truncatedRes = await fetch(`${gatewayOrigin}/test/truncated-json`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  await assert.rejects(() => truncatedRes.text());
  const elapsed = Date.now() - t0;
  assert.ok(elapsed < 1000, `truncated response took ${elapsed}ms; must fail immediately without hanging`);

  const chunkedRes = await fetch(`${gatewayOrigin}/test/truncated-chunked`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  await assert.rejects(() => chunkedRes.text(), /terminated|aborted|reset|premature|socket|closed|ECONNRESET/i);

  // 2) 客户端早退，proxyReq 立即 destroy，上游及时感知 close（无需等 2s/5s）
  const controller = new AbortController();
  setTimeout(() => controller.abort(), 30);
  await assert.rejects(() =>
    fetch(`${gatewayOrigin}/test/slow`, {
      headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
      signal: controller.signal,
    })
  );
  await new Promise((r) => setTimeout(r, 120));
  assert.equal(slowUpstreamClosed, true, 'aborted client must destroy upstream proxyReq promptly');

  // 3) fetchUpstreamJson 遇到半截断流必须 reject，绝不缓存半截响应
  await assert.rejects(
    () => gateway.fetchUpstreamJson('/test/truncated-json', 'D:\\demo'),
    /aborted|prematurely|reset|ECONNRESET|socket hang up/i,
  );
});

test('/api/file-index caches bare ?cwd=... but bypasses cache when q= or extra query is present', async () => {
  upstreamHits = [];
  const cwd = encodeURIComponent('D:\\demo-file-index');

  const full1 = await fetch(`${gatewayOrigin}/api/file-index?cwd=${cwd}`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  assert.equal(full1.status, 200);
  assert.equal(full1.headers.get('x-gateway-cache'), 'miss');
  assert.deepEqual(await full1.json(), { files: ['full-index-a.js', 'full-index-b.js'] });

  const full2 = await fetch(`${gatewayOrigin}/api/file-index?cwd=${cwd}`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  assert.equal(full2.status, 200);
  assert.equal(full2.headers.get('x-gateway-cache'), 'fresh');

  // 带 q=... 必须绕过缓存，直接反代拿到匹配结果
  const q1 = await fetch(`${gatewayOrigin}/api/file-index?cwd=${cwd}&q=MainActivity`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  assert.equal(q1.status, 200);
  assert.equal(q1.headers.get('x-gateway-cache'), null);
  assert.deepEqual(await q1.json(), { matches: [{ path: 'match/MainActivity' }] });

  const q2 = await fetch(`${gatewayOrigin}/api/file-index?cwd=${cwd}&q=Gateway`, {
    headers: { Authorization: `Bearer ${gateway.AUTH_TOKEN}` },
  });
  assert.equal(q2.status, 200);
  assert.deepEqual(await q2.json(), { matches: [{ path: 'match/Gateway' }] });

  // 两次全量只打一次上游 + 两次 q= 各打一次上游 = 3 次
  assert.equal(upstreamHits.length, 3);
});

test('show-pairing falls back to Cloudflare stable tunnel hostname when frp has no publicUrl', () => {
  const result = resolvePairingPublicUrl(4100, {
    env: {},
    frpClient: { describeFrp: () => ({ publicUrl: '', configPath: '', localPort: 0 }) },
    loadGateway: () => ({
      resolveStableTunnel: (port) => ({
        tunnel: port === 4100 ? { name: 'pi', hostname: 'pi.example.com', source: 'config' } : null,
        notes: [],
      }),
    }),
  });
  assert.equal(result.publicUrl, 'https://pi.example.com');
});
