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
// 文件权限收紧为 0600：token 等同于网关的全部权限，不该让同机其他用户读到。
// 内容为空或过短（历史上被截断过）时直接重建，避免出现空 token 被当成合法凭证。
const TOKEN_MIN_LENGTH = 20;

function writeTokenFile(token) {
  fs.writeFileSync(TOKEN_FILE, token, { encoding: 'utf8', mode: 0o600 });
  try {
    fs.chmodSync(TOKEN_FILE, 0o600);
  } catch (e) {
    // Windows 等不支持 POSIX 权限的文件系统上忽略
  }
}

function getOrCreateToken() {
  if (fs.existsSync(TOKEN_FILE)) {
    const existing = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
    if (existing.length >= TOKEN_MIN_LENGTH) return existing;
    console.warn('[Gateway] 已有 token 文件为空或过短，将重新生成');
  }
  const newToken = 'pi_' + crypto.randomBytes(16).toString('hex');
  writeTokenFile(newToken);
  return newToken;
}

const AUTH_TOKEN = getOrCreateToken();

/** 恒定时间比较，避免通过响应耗时逐字节猜出 token。 */
function safeTokenEquals(provided, expected) {
  if (typeof provided !== 'string' || typeof expected !== 'string') return false;
  if (expected.length === 0) return false;
  const a = Buffer.from(provided, 'utf8');
  const b = Buffer.from(expected, 'utf8');
  // 长度不同直接否决（长度本身不是秘密），timingSafeEqual 要求等长
  if (a.length !== b.length) return false;
  try {
    return crypto.timingSafeEqual(a, b);
  } catch (e) {
    return false;
  }
}

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
// 允许把 token 放在查询串里的路径：只有 SSE 事件流。浏览器 EventSource 无法
// 设置请求头，是唯一确实需要它的场景。其余请求一律要求 Authorization 头，
// 以免 token 落进反向代理 / CDN / Cloudflare Tunnel 的访问日志。
const QUERY_TOKEN_PATH_PATTERN = /\/events$/;

function authMiddleware(req, res, next) {
  let token = null;

  const authHeader = req.headers['authorization'];
  if (authHeader && authHeader.startsWith('Bearer ')) {
    token = authHeader.substring(7).trim();
  }

  if (!token && req.query && typeof req.query.token === 'string') {
    const reqPath = req.path || req.url || '';
    if (QUERY_TOKEN_PATH_PATTERN.test(reqPath)) {
      token = req.query.token;
    }
  }

  if (!safeTokenEquals(token, AUTH_TOKEN)) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Invalid or missing authentication token.'
    });
  }

  next();
}

/**
 * 网关自报的版本号直接取自 Android 工程的 versionName，
 * 避免出现「安装包是 1.6.4、网关自称 1.3.2」这种对不上的情况。
 */
function readAppVersion() {
  try {
    const gradleText = fs.readFileSync(
      path.join(__dirname, '..', 'app', 'build.gradle.kts'), 'utf8');
    const matched = gradleText.match(/versionName\s*=\s*"([^"]+)"/);
    if (matched) return matched[1];
  } catch (e) {
    // 非仓库布局（例如只拷贝了 server/）时退回 package.json
  }
  try {
    return JSON.parse(fs.readFileSync(path.join(__dirname, 'package.json'), 'utf8')).version;
  } catch (e) {
    return 'unknown';
  }
}

const APP_VERSION = readAppVersion();

app.get('/api/ping', authMiddleware, (req, res) => {
  res.json({
    status: 'ok',
    device: os.hostname(),
    platform: process.platform,
    version: APP_VERSION,
    target: TARGET,
    timestamp: Date.now()
  });
});

// 3.5 伴侣网关直接增强能力：支持读取/保存 SKILL.md 源码及在 PC 上创建远程项目工作区目录
function resolveHomePath(p) {
  if (!p || typeof p !== 'string') return '';
  if (p.startsWith('~/') || p.startsWith('~\\')) {
    return path.join(os.homedir(), p.slice(2));
  }
  return p;
}

// 自动检测并无损自愈本地 ~/.pi/agent/models.json（防止因缺失 baseUrl 的单模型数组或顶层 config 字段导致 pi 无法工作）
function autoHealLocalModelsJson() {
  try {
    const modelsJsonPath = path.join(os.homedir(), '.pi', 'agent', 'models.json');
    if (!fs.existsSync(modelsJsonPath)) return;
    const raw = fs.readFileSync(modelsJsonPath, 'utf8').trim();
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return;

    let modified = false;
    if (!parsed.providers || typeof parsed.providers !== 'object') {
      parsed.providers = {};
    }
    if (parsed.config && typeof parsed.config === 'object') {
      if (parsed.config.providers && typeof parsed.config.providers === 'object') {
        for (const [k, v] of Object.entries(parsed.config.providers)) {
          if (!parsed.providers[k] && v && typeof v === 'object') {
            parsed.providers[k] = v;
          }
        }
      }
      delete parsed.config;
      modified = true;
    }

    for (const [provKey, provObj] of Object.entries(parsed.providers)) {
      if (!provObj || typeof provObj !== 'object') continue;
      const baseUrl = typeof provObj.baseUrl === 'string' ? provObj.baseUrl.trim() : '';
      if (Array.isArray(provObj.models) && !baseUrl) {
        if (!provObj.modelOverrides || typeof provObj.modelOverrides !== 'object') {
          provObj.modelOverrides = {};
        }
        for (const m of provObj.models) {
          if (m && typeof m === 'object' && typeof m.id === 'string' && m.id.trim()) {
            const mId = m.id.trim();
            const ov = provObj.modelOverrides[mId] || {};
            if (typeof m.name === 'string' && m.name.trim()) ov.name = m.name.trim();
            if (typeof m.contextWindow === 'number') ov.contextWindow = m.contextWindow;
            if (typeof m.maxTokens === 'number') ov.maxTokens = m.maxTokens;
            if (typeof m.reasoning === 'boolean') ov.reasoning = m.reasoning;
            if (Array.isArray(m.input)) ov.input = m.input;
            provObj.modelOverrides[mId] = ov;
          }
        }
        delete provObj.models;
        if (!provObj.baseUrl) {
          delete provObj.api;
        }
        modified = true;
      }
    }

    if (modified) {
      fs.writeFileSync(modelsJsonPath + '.bak', raw, 'utf8');
      fs.writeFileSync(modelsJsonPath, JSON.stringify(parsed, null, 2), 'utf8');
      console.log('[Gateway] 已自动修复 ~/.pi/agent/models.json 中的模型覆盖配置结构');
    }
  } catch (e) {
    // 文件不存在是正常情况；其余错误需要留痕，否则 models.json 没被修好时无从排查
    if (e && e.code !== 'ENOENT') {
      console.warn('[Gateway] 自动修复 models.json 失败:', e.message);
    }
  }
}

autoHealLocalModelsJson();

app.get('/api/skills/content', authMiddleware, (req, res, next) => {
  try {
    const rawPath = (req.query.filePath || req.query.path || '').toString().trim();
    const rawBaseDir = (req.query.baseDir || '').toString().trim();
    const skillName = (req.query.name || req.query.skill || req.query.skillName || '').toString().trim();
    const rawCwd = (req.query.cwd || '').toString().trim();

    const candidates = [];
    if (rawPath) candidates.push(resolveHomePath(rawPath));
    if (rawBaseDir) candidates.push(path.join(resolveHomePath(rawBaseDir), 'SKILL.md'));
    if (skillName) {
      candidates.push(path.join(os.homedir(), '.pi', 'agent', 'skills', skillName, 'SKILL.md'));
      candidates.push(path.join(os.homedir(), '.claude', 'skills', skillName, 'SKILL.md'));
      if (rawCwd) {
        const resolvedCwd = resolveHomePath(rawCwd);
        candidates.push(path.join(resolvedCwd, '.pi', 'skills', skillName, 'SKILL.md'));
        candidates.push(path.join(resolvedCwd, '.claude', 'skills', skillName, 'SKILL.md'));
      }
    }

    for (const candidate of candidates) {
      if (candidate && fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
        const content = fs.readFileSync(candidate, 'utf8');
        return res.json({ content, filePath: candidate });
      }
    }
  } catch (e) {
    // 交由下游 pi-web 继续处理
  }
  next();
});

app.put('/api/skills/content', authMiddleware, express.json({ limit: '5mb' }), (req, res) => {
  try {
    const body = req.body || {};
    const rawPath = (body.filePath || body.path || '').toString().trim();
    const skillName = (body.name || body.skill || '').toString().trim();
    const content = (body.content || '').toString();
    let resolved = resolveHomePath(rawPath);
    if (!resolved && skillName) {
      resolved = path.join(os.homedir(), '.pi', 'agent', 'skills', skillName, 'SKILL.md');
    }
    if (!resolved) {
      return res.status(400).json({ error: 'Missing filePath' });
    }
    const dir = path.dirname(resolved);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    fs.writeFileSync(resolved, content, 'utf8');
    res.json({ ok: true, filePath: resolved });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/cwd/browse', authMiddleware, express.json(), (req, res, next) => {
  try {
    const body = req.body || {};
    const targetFull = resolveHomePath(body.fullPath || body.cwd || '');
    const parentDir = resolveHomePath(body.path || body.dir || '');
    const childName = (body.name || '').trim();
    const finalDir = targetFull || (parentDir && childName ? path.join(parentDir, childName) : parentDir);
    if (finalDir) {
      fs.mkdirSync(finalDir, { recursive: true });
      return res.json({ ok: true, path: finalDir, cwd: finalDir });
    }
  } catch (e) {
    // 继续交由下游处理
  }
  next();
});

// 3.6 支持直接读取工作区中的图片、SVG、PDF 原始文件供 App 渲染缩略图与全屏预览
app.get('/api/files/raw', authMiddleware, (req, res, next) => {
  try {
    const rawPath = (req.query.path || req.query.filePath || '').toString().trim();
    const rawCwd = (req.query.cwd || '').toString().trim();
    if (!rawPath) return next();

    let resolved = resolveHomePath(rawPath);
    if (!path.isAbsolute(resolved) && rawCwd) {
      resolved = path.resolve(resolveHomePath(rawCwd), resolved);
    }
    if (resolved && fs.existsSync(resolved) && fs.statSync(resolved).isFile()) {
      const ext = path.extname(resolved).toLowerCase();
      const mimeMap = {
        '.png': 'image/png',
        '.jpg': 'image/jpeg',
        '.jpeg': 'image/jpeg',
        '.webp': 'image/webp',
        '.gif': 'image/gif',
        '.bmp': 'image/bmp',
        '.svg': 'image/svg+xml',
        '.pdf': 'application/pdf'
      };
      if (mimeMap[ext]) {
        res.setHeader('Content-Type', mimeMap[ext]);
      }
      res.setHeader('Cache-Control', 'private, max-age=300');
      return res.sendFile(path.resolve(resolved));
    }
  } catch (e) {
    // 交由下游 pi-web 继续处理
  }
  next();
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
