const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');
const qrcode = require('qrcode-terminal');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.GATEWAY_PORT || '4100', 10);
const TARGET = process.env.PI_WEB_URL || 'http://127.0.0.1:3000';
const TOKEN_FILE = path.join(__dirname, '.token');

// 解析命令行参数
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
    version: '1.2.0',
    timestamp: Date.now()
  });
});

// 4. 反向代理到本地 pi-web
const proxy = createProxyMiddleware({
  target: TARGET,
  changeOrigin: true,
  ws: true,
  on: {
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

app.use('/', authMiddleware, proxy);

function printBannerAndQr(connectHost) {
  const localIp = getLocalIp();
  const hostName = os.hostname();
  const connectUri = `pidroid://connect?url=${encodeURIComponent(connectHost)}&token=${AUTH_TOKEN}&name=${encodeURIComponent(hostName)}`;

  console.log('\n======================================================');
  console.log('       🛡️  piDroid 安全伴侣网关已就绪 (Gateway)       ');
  console.log('======================================================');
  console.log(`[本地地址]  http://127.0.0.1:${PORT}`);
  console.log(`[局域网直连] http://${localIp}:${PORT}`);
  if (connectHost.startsWith('https://')) {
    console.log(`[公网外网直连] ${connectHost}  🌐 (全球任何网络可用)`);
  }
  console.log(`[目标 pi-web] ${TARGET}`);
  console.log(`[访问密钥 Token] ${AUTH_TOKEN}`);
  console.log('------------------------------------------------------');
  console.log('📱 请用手机 App 打开【扫码配对】，对准下方二维码扫码：\n');

  qrcode.generate(connectUri, { small: true });

  console.log('\n或在 App 中手动填入：');
  console.log(`  - 服务器地址: ${connectHost}`);
  console.log(`  - 访问 Token: ${AUTH_TOKEN}`);
  console.log('======================================================\n');
}

// 启动服务器
app.listen(PORT, '0.0.0.0', () => {
  const localIp = getLocalIp();
  const defaultHost = customUrlArg || process.env.TUNNEL_URL || `http://${localIp}:${PORT}`;

  if (autoTunnel) {
    console.log('\n⏳ 正在自动启动 Cloudflare Quick Tunnel 获取公网外网域名，请稍候...');
    // 自动拉起 cloudflared
    const tunnelProcess = spawn('cloudflared', ['tunnel', '--url', `http://127.0.0.1:${PORT}`], {
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
        printBannerAndQr(publicUrl);
      }
    }

    tunnelProcess.stdout.on('data', handleOutput);
    tunnelProcess.stderr.on('data', handleOutput);

    tunnelProcess.on('error', (err) => {
      console.warn('\n⚠️ 未找到 cloudflared 命令，自动回退到局域网直连模式。');
      console.warn('💡 如需自动公网访问，请下载 cloudflared 并放入系统 PATH 中。');
      printBannerAndQr(defaultHost);
    });

    // 10秒超时回退
    setTimeout(() => {
      if (!detectedUrl) {
        printBannerAndQr(defaultHost);
      }
    }, 10000);

  } else {
    printBannerAndQr(defaultHost);
  }
});
