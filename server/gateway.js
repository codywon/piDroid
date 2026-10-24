const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');
const qrcode = require('qrcode-terminal');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = parseInt(process.env.GATEWAY_PORT || '4100', 10);
const TARGET = process.env.PI_WEB_URL || 'http://127.0.0.1:3000';
const TOKEN_FILE = path.join(__dirname, '.token');

// 1. 获取或生成安全 Auth Token
function getOrCreateToken() {
  if (fs.existsSync(TOKEN_FILE)) {
    return fs.readFileSync(TOKEN_FILE, 'utf8').trim();
  }
  const newToken = 'pi_' + crypto.randomBytes(16).toString('hex');
  fs.writeFileSync(TOKEN_FILE, newToken, 'utf8');
  return newToken;
}

const AUTH_TOKEN = getOrCreateToken();

// 2. 获取本机局域网 IP
function getLocalIp() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      // 排除内部回环和非 IPv4
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

const app = express();

// 3. 安全鉴权中间件
function authMiddleware(req, res, next) {
  let token = null;

  // 支持 Authorization: Bearer <token>
  const authHeader = req.headers['authorization'];
  if (authHeader && authHeader.startsWith('Bearer ')) {
    token = authHeader.substring(7).trim();
  }

  // 同时也支持 URL Query 参数 ?token=... (专门适配 SSE / EventSource 与某些移动端流连接)
  if (!token && req.query && req.query.token) {
    token = req.query.token;
  }

  if (!token || token !== AUTH_TOKEN) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Invalid or missing authentication token.'
    });
  }

  next();
}

// 4. Ping 探测接口 (App 扫码后首先通过此接口验证连通性与 Token)
app.get('/api/ping', authMiddleware, (req, res) => {
  res.json({
    status: 'ok',
    device: os.hostname(),
    platform: process.platform,
    version: '1.0.0',
    timestamp: Date.now()
  });
});

// 5. 反向代理到本地 pi-web
const proxy = createProxyMiddleware({
  target: TARGET,
  changeOrigin: true,
  ws: true, // 支持 WebSocket (如果 pi-web 或扩展用到)
  on: {
    proxyReq: (proxyReq, req, res) => {
      // 代理时可清理已通过鉴权的内部 query token，保持发往 pi-web 的干净
    },
    error: (err, req, res) => {
      console.error('[Gateway Error] Proxy failed:', err.message);
      if (!res.headersSent) {
        res.status(502).json({
          error: 'Bad Gateway',
          message: `Cannot connect to pi-web at ${TARGET}. Is pi-web running?`
        });
      }
    }
  }
});

// 对所有请求应用鉴权后进行代理
app.use('/', authMiddleware, proxy);

// 启动服务器
app.listen(PORT, '0.0.0.0', () => {
  const localIp = getLocalIp();
  const tunnelUrl = process.env.TUNNEL_URL;
  const connectHost = tunnelUrl || `http://${localIp}:${PORT}`;
  const hostName = os.hostname();

  // 构建标准 App 扫码协议 URI
  const connectUri = `pidroid://connect?url=${encodeURIComponent(connectHost)}&token=${AUTH_TOKEN}&name=${encodeURIComponent(hostName)}`;

  console.log('\n======================================================');
  console.log('       🛡️  Pi Mobile Companion Gateway is Running       ');
  console.log('======================================================');
  console.log(`[本地监听]  http://127.0.0.1:${PORT}`);
  console.log(`[局域网直连] http://${localIp}:${PORT}`);
  if (tunnelUrl) {
    console.log(`[公网隧道]  ${tunnelUrl}`);
  }
  console.log(`[目标服务]  ${TARGET} (pi-web)`);
  console.log(`[访问密钥]  ${AUTH_TOKEN}`);
  console.log('------------------------------------------------------');
  console.log('📱 请在手机 App 打开【扫一扫】，扫描下方二维码一键配对：\n');

  qrcode.generate(connectUri, { small: true });

  console.log('\n或在 App 中手动填入：');
  console.log(`  - 服务器地址: ${connectHost}`);
  console.log(`  - 访问 Token: ${AUTH_TOKEN}`);
  console.log('======================================================\n');
});
