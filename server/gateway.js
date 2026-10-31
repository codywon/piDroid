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

// 固定域名隧道（Cloudflare Named Tunnel）：
// 同时设置 PI_TUNNEL_NAME + PI_TUNNEL_HOSTNAME 后，网关不再启动临时 trycloudflare
// 隧道，而是直接拉起已绑定自有域名的命名隧道。公网地址每次重启都相同，
// 手机只需扫码/手动填写一次即可长期复用。
//
// 未设环境变量时，再尝试从 `~/.cloudflared/config.yml` 自动识别：只要做过
// `cloudflared tunnel create/route dns`，配置里就有 tunnel 名与 hostname。
// 这很重要——Quick Tunnel 每次重启都换随机域名，手机里存的旧地址会永久失效，
// 表现就是「一直自动重连中」而且App 自己无法自愈（它无从得知新域名）。
function detectStableTunnelFromConfig(expectedPort) {
  const home = process.env.USERPROFILE || process.env.HOME || '';
  if (!home) return null;
  for (const fileName of ['config.yml', 'config.yaml']) {
    const file = path.join(home, '.cloudflared', fileName);
    let text;
    try {
      text = fs.readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    const tunnelName = (text.match(/^\s*tunnel:\s*["']?([^\s"'#]+)/m) || [])[1];
    if (!tunnelName) continue;
    // ingress 是「- hostname: X」紧跟「service: http://127.0.0.1:PORT」的列表，
    // 按行配对，取第一个指向本网关端口的条目。
    let pendingHost = null;
    let hostname = null;
    let targetPort = null;
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      const hostMatch = line.match(/^-?\s*hostname:\s*["']?([^\s"'#]+)/);
      if (hostMatch) {
        pendingHost = hostMatch[1];
        continue;
      }
      const portMatch = line.match(/^service:\s*\S*?:(\d{2,5})\s*$/);
      if (portMatch && pendingHost) {
        if (Number(portMatch[1]) === expectedPort) {
          hostname = pendingHost;
          targetPort = Number(portMatch[1]);
          break;
        }
        pendingHost = null;
      }
    }
    if (!hostname) continue;
    return { tunnelName, hostname, targetPort, file: fileName };
  }
  return null;
}

// 网关端口先算出来：自动识别命名隧道时要用它做匹配，避免把指向别的服务的域名配上去。
const EXPECTED_GATEWAY_PORT = (() => {
  const fromEnv = Number(process.env.GATEWAY_PORT);
  if (Number.isFinite(fromEnv) && fromEnv > 0) return fromEnv;
  const idx = process.argv.indexOf('--port');
  const fromArg = idx !== -1 ? Number(process.argv[idx + 1]) : NaN;
  return Number.isFinite(fromArg) && fromArg > 0 ? fromArg : 4100;
})();

const detectedTunnel = detectStableTunnelFromConfig(EXPECTED_GATEWAY_PORT);
const STABLE_TUNNEL_NAME = (process.env.PI_TUNNEL_NAME || detectedTunnel?.tunnelName || '').trim();
const STABLE_TUNNEL_HOSTNAME = (process.env.PI_TUNNEL_HOSTNAME || detectedTunnel?.hostname || '')
  .trim()
  .replace(/^https?:\/\//i, '')
  .replace(/\/+$/, '');
const hasStableTunnel = Boolean(STABLE_TUNNEL_NAME && STABLE_TUNNEL_HOSTNAME);

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

// 401 诊断日志：只记录长度和 UA，不打印 token 本身。
// 节流只作用于「压根没带 token」的请求 —— 公网扫描器一天能刷出几十万条，不节流会把日志写爆；
// 而**带了 token 却不匹配**只可能来自自己的客户端（App / 试连脚本），那种记录一条都不能丢：
// 全局节流会让「扫描器刷屏 + 轮换 Token 后 App 疯狂 401」同时发生时，恰好把最要紧的那几条挤掉。
let lastAuthFailureLogAt = 0;
const MISSING_TOKEN_LOG_INTERVAL_MS = 3000;
const LOG_DIR = path.join(__dirname, 'logs');
const AUTH_FAIL_LOG = path.join(LOG_DIR, 'auth-fail.log');

function tokenFingerprint(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex').slice(0, 8);
}

const AUTH_TOKEN_FP = tokenFingerprint(AUTH_TOKEN);
function logAuthFailure(req, token) {
  const provided = typeof token === 'string' && token.length > 0
    ? `present(len=${token.length})`
    : 'missing';
  if (provided === 'missing') {
    const now = Date.now();
    if (now - lastAuthFailureLogAt < MISSING_TOKEN_LOG_INTERVAL_MS) return;
    lastAuthFailureLogAt = now;
  }
  const ua = (req.headers['user-agent'] || 'n/a').toString().slice(0, 48);
  const pathOnly = (req.path || '/').split('?')[0];
  console.warn(`[auth] 401 ${req.method} ${pathOnly} token=${provided} ua=${ua}`);

  // 同时落盘，便于事后比对“客户端到底发了哪个 Token”（只存不可逆指纹，不存原值）
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    const line = [
      new Date().toISOString(),
      req.method,
      pathOnly,
      provided,
      `fp=${typeof token === 'string' && token.length ? tokenFingerprint(token) : 'none'}`,
      `expected=${AUTH_TOKEN_FP}`,
      `ip=${req.ip || (req.socket && req.socket.remoteAddress) || '?'}`,
      `ua=${ua}`
    ].join(' | ');
    if (fs.existsSync(AUTH_FAIL_LOG) && fs.statSync(AUTH_FAIL_LOG).size > 512 * 1024) {
      fs.renameSync(AUTH_FAIL_LOG, AUTH_FAIL_LOG + '.1');
    }
    fs.appendFileSync(AUTH_FAIL_LOG, line + '\n');
  } catch (e) {
    // 日志写失败不影响鉴权结果
  }
}

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
    // 排查用：只记录「有没有带 token、长度多少」，绝不打印 token 内容。
    // 长度 35 且不匹配通常意味着客户端手里还是上一轮轮换掉的旧 token。
    logAuthFailure(req, token);
    // `WWW-Authenticate: ... invalid_token` 是给客户端做**机器判定**用的：
    // App 只有看到这个标记才认定「本地 Token 失效」（停止轮询 + 提示重新扫码）。
    // 不能见到 401 就判 —— pi-web 自己会在模型 API Key 填错时返回 401 `Invalid API key`，
    // 那是另一回事，误判会把用户往「重新扫码」的死路上推。
    // 响应体里的 `code` 是同一件事的文本版，方便 curl / 日志肉眼比对。
    return res
      .status(401)
      .set('WWW-Authenticate', 'Bearer realm="pi-gateway", error="invalid_token"')
      .json({
        code: 'TOKEN_INVALID',
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

// ── 提示词增强（伴侣网关自有能力，不代理给 pi-web）─────────────────────────
// 懒加载 ESM 模块：只有客户端真的点击增强时才加载 SDK，避免拖慢每次 /api/ping。
const { readEnhancementConfig } = require('./prompt-enhancement/config.js');
const PROMPT_ENHANCE_CONFIG = readEnhancementConfig();
let promptEnhancementModule = null;

function loadPromptEnhancement() {
  if (!promptEnhancementModule) {
    promptEnhancementModule = (async () => {
      const [{ createEnhancementService }, { createSdkModelClient }, { createPromptEnhancementHandler }] =
        await Promise.all([
          import('./prompt-enhancement/service.mjs'),
          import('./prompt-enhancement/model-client.mjs'),
          import('./prompt-enhancement/routes.mjs')
        ]);
      const service = createEnhancementService({
        modelClient: createSdkModelClient(),
        config: PROMPT_ENHANCE_CONFIG
      });
      return { service, handler: createPromptEnhancementHandler({ service }) };
    })().catch((error) => {
      // 加载失败不能把整个网关拖死：清空缓存，下次请求重试
      promptEnhancementModule = null;
      throw error;
    });
  }
  return promptEnhancementModule;
}

app.get('/api/ping', authMiddleware, (req, res) => {
  res.json({
    status: 'ok',
    device: os.hostname(),
    platform: process.platform,
    version: APP_VERSION,
    target: TARGET,
    timestamp: Date.now(),
    // 能力探测：旧客户端忽略；新客户端据此决定是否显示增强入口。
    // 这里只报「模块与配置是否就绪」，不加载 SDK、不调用模型。
    capabilities: {
      promptEnhancement: {
        version: 1,
        available: PROMPT_ENHANCE_CONFIG.enabled,
        maxDraftChars: PROMPT_ENHANCE_CONFIG.maxDraftChars,
        maxContextChars: PROMPT_ENHANCE_CONFIG.maxContextChars
      }
    }
  });
});

// POST /api/mobile/prompt-enhance：必须是网关自有路由，且必须挂在反向代理之前
// （pi-web 没有这个路径，落到代理会得到 404）。鉴权沿用网关 Bearer Token。
app.post(
  '/api/mobile/prompt-enhance',
  authMiddleware,
  express.json({ limit: '256kb' }),
  async (req, res) => {
    if (!PROMPT_ENHANCE_CONFIG.enabled) {
      res.status(503).json({ requestId: null, code: 'ENHANCEMENT_UNAVAILABLE', message: '当前网关未启用提示词增强' });
      return;
    }
    try {
      const { handler } = await loadPromptEnhancement();
      await handler(req, res);
    } catch (error) {
      if (!res.headersSent) {
        res.status(503).json({
          requestId: null,
          code: 'ENHANCEMENT_UNAVAILABLE',
          message: '提示词增强模块未就绪，请确认 server 目录已执行 npm install'
        });
      }
    }
  }
);

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
    // 安全防护：该接口仅用于保存技能 Markdown 源码，严禁覆写非 .md 文件
    if (!resolved.toLowerCase().endsWith('.md')) {
      return res.status(400).json({ error: 'Invalid filePath: only markdown files (.md) can be modified' });
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
      if (!mimeMap[ext]) return next();
      res.setHeader('Content-Type', mimeMap[ext]);
      res.setHeader('Cache-Control', 'private, max-age=300');
      return res.sendFile(path.resolve(resolved));
    }
  } catch (e) {
    // 交由下游 pi-web 继续处理
  }
  next();
});

// 4. 反向代理到 pi-web（启用 HTTP Keep-Alive 连接池，避免高频轮询时反复创建销毁本地 socket）
const proxyAgent = new http.Agent({
  keepAlive: true,
  maxSockets: 100,
  keepAliveMsecs: 30000,
  timeout: 30000
});

const proxy = createProxyMiddleware({
  target: TARGET,
  changeOrigin: true,
  ws: true,
  agent: proxyAgent,
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
  console.log('       🛡️  piDroid 安全网关   ');
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

      if (isTunnelEnabled && hasStableTunnel) {
        // 固定域名隧道：地址由 Cloudflare 配置决定，无需再监听输出解析临时域名
        const publicUrl = `https://${STABLE_TUNNEL_HOSTNAME}`;
        console.log(`\n🌐 正在启动固定域名隧道 "${STABLE_TUNNEL_NAME}" -> ${publicUrl} ...`);
        const tunnelProcess = spawn(
          'cloudflared',
          ['tunnel', 'run', STABLE_TUNNEL_NAME],
          { shell: true }
        );

        // cloudflared 把运行日志写在 stderr，这里按行转发便于排查 404/1033。
        // 两处处理：
        // 1) 客户主动断开 SSE 时 cloudflared 会以 ERR 级别打印 "canceled by remote
        //    with error code 0"，这是正常现象（手机切页面/息屏/重连），不展示；
        // 2) 日志里会带上 EventSource 的 ?token=...，必须脱敏，不能落到控制台/日志。
        const pipeTunnelLogs = (readable, out) => {
          let buffer = '';
          readable.on('data', (chunk) => {
            buffer += chunk.toString();
            const lines = buffer.split(/\r?\n/);
            buffer = lines.pop() || '';
            for (const raw of lines) {
              const line = raw.replace(/\s+$/, '');
              if (!line.trim()) continue;
              if (/canceled by remote with error code 0/.test(line)) continue;
              const safe = line.replace(/([?&]token=)[^&\s]+/gi, '$1***');
              out.write(`    [tunnel] ${safe}\n`);
            }
          });
        };
        pipeTunnelLogs(tunnelProcess.stdout, process.stdout);
        pipeTunnelLogs(tunnelProcess.stderr, process.stderr);

        tunnelProcess.on('error', (err) => {
          console.error(`\n[tunnel] 启动 ${STABLE_TUNNEL_NAME} 失败: ${err.message}`);
          console.error('[tunnel] 请确认 cloudflared 已安装且已完成 cloudflared tunnel login / create / route dns');
          printBannerAndQr(publicUrl, isAlive);
        });

        printBannerAndQr(publicUrl, isAlive);
        resolve({ server, tunnelProcess, publicUrl, token: AUTH_TOKEN, target: TARGET });
      } else if (isTunnelEnabled) {
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

    // WebSocket 升级请求不经过 Express 中间件，ws:true 的代理会绕过 authMiddleware。
    // 这里在 server 层单独校验一次 token，未授权直接断开；授权通过后转交 proxy.upgrade。
    server.on('upgrade', (req, socket, head) => {
      const authHeader = req.headers['authorization'] || '';
      let token = authHeader.startsWith('Bearer ') ? authHeader.substring(7).trim() : null;
      if (!token && typeof req.url === 'string') {
        const queryIndex = req.url.indexOf('?');
        if (queryIndex !== -1) {
          token = new URLSearchParams(req.url.slice(queryIndex + 1)).get('token');
        }
      }
      if (!safeTokenEquals(token, AUTH_TOKEN)) {
        socket.end(
          'HTTP/1.1 401 Unauthorized\r\n' +
          'WWW-Authenticate: Bearer realm="pi-gateway", error="invalid_token"\r\n' +
          'Connection: close\r\n\r\n'
        );
        return;
      }
      if (typeof proxy.upgrade === 'function') {
        proxy.upgrade(req, socket, head);
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
