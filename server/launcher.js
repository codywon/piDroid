#!/usr/bin/env node
/**
 * 🚀 piDroid 统一统合启动器 (Unified Companion Launcher)
 * 
 * 架构特性：
 * 1. 零侵入 (Zero-Intrusion)：完全不侵入、不修改 @agegr/pi-web 官方源码，无缝享受官方持续更新；
 * 2. 智能探测 (Smart Health Check)：自动感知 30141 端口，已运行服务不重复启动；
 * 3. 伴侣中继 (Companion Gateway)：自动挂载 Token 鉴权、扫码配对与可选 Cloudflare 公网隧道；
 * 4. 联合守护 (Lifecycle Management)：主进程 Ctrl+C 时统一安全回收所有子进程，防止端口占用。
 */

const { spawn, execSync } = require('child_process');
const http = require('http');
const https = require('https');
const path = require('path');
const gateway = require('./gateway');

// 命令行参数解析
const args = process.argv.slice(2);
const autoTunnel = args.includes('--tunnel');
const skipPiWeb = args.includes('--no-pi-web');

let gatewayPort = parseInt(process.env.GATEWAY_PORT || '4100', 10);
const portIndex = args.indexOf('--port');
if (portIndex !== -1 && args[portIndex + 1]) {
  gatewayPort = parseInt(args[portIndex + 1], 10);
}

let piWebUrl = process.env.PI_WEB_URL || 'http://127.0.0.1:30141';
const piUrlIndex = args.indexOf('--pi-url');
if (piUrlIndex !== -1 && args[piUrlIndex + 1]) {
  piWebUrl = args[piUrlIndex + 1];
}

if (args.includes('--help') || args.includes('-h')) {
  console.log(`
🚀 piDroid 统合启动器使用说明:
  node launcher.js [options]

选项:
  --tunnel          一键自动启用 Cloudflare 临时公网隧道 (免公网 IP，外网手机直连)
  --no-pi-web       仅启动伴侣安全网关，不自动启动/托管本地 pi-web
  --port <port>     自定义伴侣网关监听端口 (默认: 4100)
  --pi-url <url>    自定义目标 pi-web 服务地址 (默认: http://127.0.0.1:30141)
  -h, --help        显示此帮助信息
`);
  process.exit(0);
}

let piWebProcess = null;
let tunnelProcess = null;
let gatewayServer = null;

// 1. 探测 HTTP 服务是否存活
function checkUrlAlive(targetUrl, timeoutMs = 800) {
  return new Promise((resolve) => {
    try {
      const parsed = new URL(targetUrl);
      const req = http.request({
        hostname: parsed.hostname,
        port: parsed.port,
        path: '/',
        method: 'GET',
        timeout: timeoutMs
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

// 探测是否已有正在运行的 companion gateway
function checkGatewayRunning(port, timeoutMs = 800) {
  return new Promise((resolve) => {
    try {
      const req = http.request({
        hostname: '127.0.0.1',
        port: port,
        path: '/api/ping',
        method: 'GET',
        timeout: timeoutMs
      }, (res) => {
        // 401(未授权) 或 200(已授权) 均证明是 companion gateway 在运行
        resolve(res.statusCode === 401 || res.statusCode === 200);
      });
      req.on('error', () => resolve(false));
      req.on('timeout', () => { req.destroy(); resolve(false); });
      req.end();
    } catch (e) {
      resolve(false);
    }
  });
}

// 公网地址可达性探测：只看“有没有人应答”，不关心 401/200。
// 重试几次：Cloudflare 边缘刚重连时偶尔瞬断，一次失败就报警只会制造假警报。
function probePublicUrl(hostname, attempts = 3, timeoutMs = 2500) {
  const once = () =>
    new Promise((resolve) => {
      try {
        const req = https.request(
          { hostname, path: '/api/ping', method: 'GET', timeout: timeoutMs },
          (res) => {
            res.resume();
            resolve(Boolean(res.statusCode) && res.statusCode < 500);
          }
        );
        req.on('error', () => resolve(false));
        req.on('timeout', () => { req.destroy(); resolve(false); });
        req.end();
      } catch {
        resolve(false);
      }
    });

  return (async () => {
    for (let i = 0; i < attempts; i += 1) {
      if (await once()) return true;
      if (i < attempts - 1) await new Promise((r) => setTimeout(r, 800));
    }
    return false;
  })();
}

// 2. 递归清理子进程树 (防止 Windows 或 POSIX 残留后台孤儿进程)
function killProcessTree(pid) {
  if (!pid) return;
  if (process.platform === 'win32') {
    try {
      execSync(`taskkill /pid ${pid} /T /F >nul 2>&1`);
    } catch (e) {}
  } else {
    try {
      process.kill(-pid, 'SIGTERM');
    } catch (e) {
      try { process.kill(pid, 'SIGTERM'); } catch (err) {}
    }
  }
}

// 优雅退出管理
let isShuttingDown = false;
function shutdown(exitCode = 0) {
  if (isShuttingDown) return;
  isShuttingDown = true;

  const hasChildren = Boolean(piWebProcess || tunnelProcess || gatewayServer);
  if (hasChildren) {
    console.log('\n🛑 正在停止 piDroid 服务并清理后台进程...');
  }

  if (piWebProcess && piWebProcess.pid) {
    killProcessTree(piWebProcess.pid);
    piWebProcess = null;
  }

  if (tunnelProcess && tunnelProcess.pid) {
    killProcessTree(tunnelProcess.pid);
    tunnelProcess = null;
  }
  // 命名隧道会自己重连（换新 PID），所以要显式让它停手，否则会留孤儿进程。
  try { gateway.stopTunnel(); } catch (e) {}

  if (gatewayServer) {
    try { gatewayServer.close(); } catch (e) {}
    gatewayServer = null;
  }

  setTimeout(() => {
    process.exit(exitCode);
  }, 200);
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));
process.on('exit', () => shutdown(0));

// 3. 启动本地官方 pi-web 子进程
async function launchPiWeb() {
  const isWindows = process.platform === 'win32';
  
  // 智能寻找可执行命令
  let cmd = 'pi-web';
  let cmdArgs = [];
  let useShell = false;

  // 检查是否全局有 pi-web 命令
  try {
    const checkCmd = isWindows ? 'where pi-web' : 'which pi-web';
    execSync(checkCmd, { stdio: 'ignore' });
    cmd = isWindows ? 'pi-web.cmd' : 'pi-web';
    useShell = isWindows;
  } catch (e) {
    // 若未全局安装，使用 npx 实时拉起最新版
    cmd = isWindows ? 'npx.cmd' : 'npx';
    cmdArgs = ['@agegr/pi-web@latest'];
    useShell = isWindows;
  }

  console.log(`[1/3] 正在启动官方原生 pi-web 服务 (${cmd} ${cmdArgs.join(' ')})...`);

  const proc = spawn(cmd, cmdArgs, {
    stdio: ['ignore', 'pipe', 'inherit'],
    shell: useShell,
    env: { ...process.env, PORT: '30141' }
  });

  proc.on('error', (err) => {
    console.error('[pi-web 启动异常]:', err.message);
  });

  proc.stdout.on('data', (chunk) => {
    const text = chunk.toString();
    // 过滤展示关键就绪日志
    if (text.includes('Ready') || text.includes('ready') || text.includes('http://')) {
      process.stdout.write(`    [pi-web] ${text.trim()}\n`);
    }
  });

  return proc;
}

// 4. 等待服务就绪轮询
async function waitForReady(url, maxWaitMs = 25000) {
  const startTime = Date.now();
  while (Date.now() - startTime < maxWaitMs) {
    const alive = await checkUrlAlive(url);
    if (alive) return true;
    await new Promise((r) => setTimeout(r, 600));
  }
  return false;
}

// 主启动流程
async function main() {
  console.log('\n======================================================');
  console.log('    🚀  piDroid 统合启动中心 (Unified Companion)     ');
  console.log('       [原生 pi-web 协同 + 工业级移动端安全网关]       ');
  console.log('======================================================\n');

  // 步骤 A: 检查 pi-web 是否已运行
  const alreadyRunning = await checkUrlAlive(piWebUrl, 1000);
  // 横幅里“pi-web 是否正常”必须反映当前真实状态：刚拉起的也可能还没就绪。
  let piWebReady = alreadyRunning;
  if (alreadyRunning) {
    console.log(`[1/3] ✓ 检测到本地已存在运行中的 pi-web (${piWebUrl})，直接接入。`);
  } else if (!skipPiWeb) {
    piWebProcess = await launchPiWeb();
    process.stdout.write('[2/3] 等待 pi-web 服务就绪中...');
    const isReady = await waitForReady(piWebUrl);
    piWebReady = isReady;
    if (isReady) {
      console.log(' ✓ 服务已就绪！');
    } else {
      console.log(' ⚠️ 探测超时，继续启动伴侣网关。');
    }
  } else {
    console.log('[1/3] 已跳过启动 pi-web (--no-pi-web)。');
  }

  // 步骤 B: 检查伴侣网关是否已在运行
  const isGatewayRunning = await checkGatewayRunning(gatewayPort);
  if (isGatewayRunning) {
    // 网关由其他窗口托管。这里必须用与 gateway.start() 相同的解析逻辑（环境变量 →
    // cloudflared 配置），否则会退回到「展示局域网 IP」：用户扫了那张二维码，
    // 一旦离开家里 Wi-Fi 就连不上，表现仍是「一直自动重连中」。
    const stable = gateway.resolveStableTunnel(gatewayPort);
    const stableHost = stable.tunnel ? stable.tunnel.hostname : '';
    for (const note of stable.notes) {
      console.warn(`[tunnel] ⚠️ ${note}`);
    }
    const connectHost = stableHost
      ? `https://${stableHost}`
      : `http://${gateway.getLocalIp()}:${gatewayPort}`;
    console.log(`[2/2] ✓ 检测到伴侣安全网关已在端口 ${gatewayPort} 运行中，直接呈现连接凭据：`);
    gateway.printBannerAndQr(connectHost, piWebReady, gatewayPort);
    if (stableHost) {
      // 已经在跑的网关未必接管了隧道（可能是用 start-server.bat 起的，
      // 也可能隧道进程自己退出了），直接探一下公网地址，
      // 别把一张打不开的二维码交给用户。
      const reachable = await probePublicUrl(stableHost);
      if (reachable) {
        console.log(`\n✅ ${connectHost} 连通正常：网关与固定域名隧道都在运行，无需重启。`);
        console.log('   （本窗口只是查看器，Ctrl+C 不会停掉后台服务。）');
      } else {
        console.warn(`\n⚠️  ${connectHost} 暂时探测不通。`);
        console.warn(`   本地网关是正常的（${gatewayPort} 端口在听），常见原因是隧道进程已退出。`);
        console.warn('   处理：关掉旧网关窗口后重新运行本脚本 —— 新版启动器会把隧道拉起，并在它断开时自动重连。');
      }
    }
    console.log('💡 提示: 网关服务已在后台常驻。随时按 Ctrl+C 可退出此查看窗口。\n');
    // 保持挂起，等待用户扫码或按 Ctrl+C
    if (process.stdin.isTTY) {
      process.stdin.setRawMode && process.stdin.setRawMode(false);
    }
    process.stdin.resume();
    return;
  }

  // 步骤 C: 启动伴侣安全网关
  console.log('[2/2] 正在启动安全伴侣网关与配对中枢...');
  try {
    const result = await gateway.start({
      port: gatewayPort,
      target: piWebUrl,
      autoTunnel: autoTunnel
    });
    gatewayServer = result.server;
    tunnelProcess = result.tunnelProcess;
  } catch (err) {
    console.error('[网关启动失败]:', err.message);
    shutdown(1);
  }
}

main().catch((err) => {
  console.error('[启动致命错误]:', err);
  shutdown(1);
});
