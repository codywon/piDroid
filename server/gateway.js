const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');
const qrcode = require('qrcode-terminal');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.GATEWAY_PORT || '4100', 10);
// pi-web 官方默认端口为 30141 (取自 π ≈ 3.141)
const DEFAULT_TARGET = process.env.PI_WEB_URL || 'http://127.0.0.1:30141';
let TARGET = DEFAULT_TARGET;
const TOKEN_FILE = path.join(__dirname, '.token');

// 命令行参数解析
const args = process.argv.slice(2);
const autoTunnel = args.includes('--tunnel');
let customUrlArg = null;
const urlIndex = args.indexOf('--url');
if (urlIndex !== -1 && args[urlIndex + 1]) {
  customUrlArg = args[urlIndex + 1];
}

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
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

// 探测本地服务是否存活
function checkServerAlive(targetUrl, timeout = 1000) {
  return new Promise((resolve) => {
    try {
      const url = new URL(targetUrl);
      const req = http.request({
        hostname: url.hostname,
        port: url.port,
        path: '/api/sessions',
        method: 'GET',
        timeout: timeout
      }, (res) => {
        resolve(res.statusCode < 500);
      });
      req.on('error', () => resolve(false));
      req.on('timeout', () => { req.destroy(); resolve(false); });
      req.end();
    } catch (e) {
      resolve(false);
    }
  });
}

const app = express();

// 3. 安全鉴权中间件
function authMiddleware(req, res, next) {
  let token = null;

  const authHeader = req.headers['authorization'];
  if (authHeader && authHeader.startsWith('Bearer ')) {
    token = authHeader.substring(7).trim();
  }

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

app.get('/api/ping', authMiddleware, (req, res) => {
  res.json({
    status: 'ok',
    device: os.hostname(),
    platform: process.platform,
    version: '1.3.1',
    target: TARGET,
    timestamp: Date.now()
  });
});

// 4. 反向代理到 pi-web
const proxy = createProxyMiddleware({
  target: TARGET,
  changeOrigin: true,
  ws: true,
  router: () => TARGET, // 动态支持更新后的目标端口
  on: {
    error: (err, req, res) => {
      console.error(`[Gateway Error] 无法连接到本地 pi-web (${TARGET}):`, err.message);
      if (!res.headersSent) {
        res.status(502).json({
          error: 'Bad Gateway',
          message: `无法连接到本地 pi-web (${TARGET})。请确认 pi-web 是否已在后台启动 (运行 pi-web 或 npx @agegr/pi-web)`
        });
      }
    }
  }
});

app.use('/', authMiddleware, proxy);

function printBannerAndQr(connectHost, isPiWebRunning) {
  const localIp = getLocalIp();
  const hostName = os.hostname();
  const connectUri = `pidroid://connect?url=${encodeURIComponent(connectHost)}&token=${AUTH_TOKEN}&name=${encodeURIComponent(hostName)}`;

  console.log('\n======================================================');
  console.log('       🛡️  piDroid 安全伴侣网关 (Termius 纯暗色体系)   ');
  console.log('======================================================');
  console.log(`[本地监听]  http://127.0.0.1:${PORT}`);
  console.log(`[局域网直连] http://${localIp}:${PORT}`);
  if (connectHost.startsWith('https://')) {
    console.log(`[公网外网直连] ${connectHost}  🌐 (全球任何网络可用)`);
  }
  if (isPiWebRunning) {
    console.log(`[目标 pi-web] ${TARGET}  ✓ (连接正常)`);
  } else {
    console.log(`[目标 pi-web] ${TARGET}  ⚠️ (未检测到运行，请确认已运行 pi-web)`);
  }
  console.log(`[访问密钥 Token] ${AUTH_TOKEN}`);
  console.log('------------------------------------------------------');
  console.log('📱 请用手机 App 打开【扫码配对】，对准下方二维码扫码：\n');

  qrcode.generate(connectUri, { small: true });

  console.log('\n或在 App 中手动填入：');
  console.log(`  - 服务器地址: ${connectHost}`);
  console.log(`  - 访问 Token: ${AUTH_TOKEN}`);
  console.log('======================================================\n');
}

// 自动探测并启动网关
async function start(options = {}) {
  const listenPort = options.port || PORT;
  const targetOverride = options.target || customUrlArg || null;
  const isTunnelEnabled = options.autoTunnel !== undefined ? options.autoTunnel : autoTunnel;

  // 先检查指定目标或 30141，再检查 3000
  let isAlive = false;
  if (targetOverride) {
    TARGET = targetOverride;
    isAlive = await checkServerAlive(TARGET);
  } else {
    isAlive = await checkServerAlive('http://127.0.0.1:30141');
    if (isAlive) {
      TARGET = 'http://127.0.0.1:30141';
    } else {
      const is3000Alive = await checkServerAlive('http://127.0.0.1:3000');
      if (is3000Alive) {
        TARGET = 'http://127.0.0.1:3000';
        isAlive = true;
      }
    }
  }

  return new Promise((resolve, reject) => {
    const server = app.listen(listenPort, '0.0.0.0', () => {
      const localIp = getLocalIp();
      const defaultHost = customUrlArg || process.env.TUNNEL_URL || `http://${localIp}:${listenPort}`;

      if (isTunnelEnabled) {
        console.log('\n⏳ 正在自动启动 Cloudflare Quick Tunnel 获取公网外网域名，请稍候...');
        const tunnelProcess = spawn('cloudflared', ['tunnel', '--url', `http://127.0.0.1:${listenPort}`], {
          shell: true
        });

        let detectedUrl = false;
        const regex = /https:\/\/[a-zA-Z0-9-]+\.trycloudflare\.com/i;

        function handleOutput(data) {
          if (detectedUrl) return;
          const text = data.toString();
          const match = text.match(regex);
          if (match) {
            detectedUrl = true;
            const publicUrl = match[0];
            printBannerAndQr(publicUrl, isAlive);
            resolve({ server, tunnelProcess, publicUrl, token: AUTH_TOKEN, target: TARGET });
          }
        }

        tunnelProcess.stdout.on('data', handleOutput);
        tunnelProcess.stderr.on('data', handleOutput);

        tunnelProcess.on('error', () => {
          printBannerAndQr(defaultHost, isAlive);
          resolve({ server, tunnelProcess: null, publicUrl: defaultHost, token: AUTH_TOKEN, target: TARGET });
        });

        setTimeout(() => {
          if (!detectedUrl) {
            printBannerAndQr(defaultHost, isAlive);
            resolve({ server, tunnelProcess, publicUrl: defaultHost, token: AUTH_TOKEN, target: TARGET });
          }
        }, 10000);

      } else {
        printBannerAndQr(defaultHost, isAlive);
        resolve({ server, tunnelProcess: null, publicUrl: defaultHost, token: AUTH_TOKEN, target: TARGET });
      }
    });

    server.on('error', (err) => {
      reject(err);
    });
  });
}

if (require.main === module) {
  start().catch((err) => {
    console.error('[Gateway Fatal Error]', err.message);
    process.exit(1);
  });
}

module.exports = {
  start,
  app,
  getOrCreateToken,
  getLocalIp,
  checkServerAlive,
  printBannerAndQr,
  AUTH_TOKEN
};
