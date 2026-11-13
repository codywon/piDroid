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

const { spawn, execSync, exec } = require('child_process');
const fs = require('fs');
const http = require('http');
const https = require('https');
const path = require('path');
const gateway = require('./gateway');
const frpClient = require('./frp-client');
const { CrashForensics, sampleProcessTreeMemoryMb } = require('./crash-forensics');
const { StallRecorder } = require('./stall-recorder');
const {
  checkUrlAlive,
  isPortListening,
  portOwnerPid,
  probeWithRetry,
  waitForReady,
  waitForReadyOrExit,
} = require('./startup-probe');

// 命令行参数解析
const args = process.argv.slice(2);
const skipPiWeb = args.includes('--no-pi-web');

// 公网通道：默认 frp（自建服务器 + TCP 直连）；--tunnel 改回 Cloudflare；--local 不出公网。
// 具体解析交给 gateway.resolveTunnelMode，避免命令行语义在这里再实现一份。
const explicitTunnelMode = args.includes('--tunnel') || args.includes('--cloudflare')
  ? 'cloudflare'
  : args.includes('--local') || args.includes('--no-tunnel')
    ? 'none'
    : args.includes('--frp')
      ? 'frp'
      : undefined;

let publicUrlOverride = process.env.PI_PUBLIC_URL || '';
const publicUrlIndex = args.indexOf('--public-url');
if (publicUrlIndex !== -1 && args[publicUrlIndex + 1]) {
  publicUrlOverride = args[publicUrlIndex + 1];
}

let gatewayPort = parseInt(process.env.GATEWAY_PORT || '4100', 10);
const portIndex = args.indexOf('--port');
if (portIndex !== -1 && args[portIndex + 1]) {
  const parsedPort = parseInt(args[portIndex + 1], 10);
  if (Number.isInteger(parsedPort) && parsedPort > 0 && parsedPort <= 65535) {
    gatewayPort = parsedPort;
  }
}

let piWebUrl = process.env.PI_WEB_URL || 'http://127.0.0.1:30141';
const piUrlIndex = args.indexOf('--pi-url');
if (piUrlIndex !== -1 && args[piUrlIndex + 1]) {
  piWebUrl = args[piUrlIndex + 1];
}

// 卡顿取证靠端口去找真正的 next 服务进程（spawn 拿到的是 cmd.exe）。
const PI_WEB_PORT = (() => {
  try {
    const parsed = new URL(piWebUrl);
    return Number(parsed.port || (parsed.protocol === 'https:' ? 443 : 80));
  } catch {
    return 30141;
  }
})();

if (args.includes('--help') || args.includes('-h')) {
  console.log(`
🚀 piDroid 统合启动器使用说明:
  node launcher.js [options]

选项:
  (默认)            frp 隧道：走自建服务器，公网地址固定（推荐）
  --frp             显式指定 frp 隧道（等同默认）
  --tunnel          改用 Cloudflare 隧道：命名隧道优先，否则临时 trycloudflare 域名
  --local           只开局域网，不建任何公网隧道
  --public-url <u>  手动指定公网地址（默认由 frpc 配置的 customDomains 推导）
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

// 1. 探测 HTTP 服务是否存活（实现搬到了 startup-probe.js，可单测）

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

// 公网/局域网地址可达性探测：只看“有没有人应答”，不关心 401/200。
// 重试几次：隧道刚重连时偶尔瞬断，一次失败就报警只会制造假警报。
function probePublicUrl(baseUrl, attempts = 3, timeoutMs = 2500) {
  const once = () =>
    new Promise((resolve) => {
      try {
        const target = new URL(baseUrl);
        const isHttps = target.protocol === 'https:';
        const client = isHttps ? https : require('http');
        const prefix = target.pathname.replace(/\/+$/, '');
        const req = client.request(
          {
            hostname: target.hostname,
            port: target.port || (isHttps ? 443 : 80),
            path: `${prefix}/api/ping`,
            method: 'GET',
            timeout: timeoutMs
          },
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

/**
 * 本次该把哪个地址写进二维码 —— 必须与 gateway.start() 用同一套规则：
 *   frp        → 从 frpc 配置的 customDomains 推导 https://域名/pi
 *   cloudflare → ~/.cloudflared/config.yml 里的命名隧道域名
 *   none       → 局域网地址
 * 不一致的后果很具体：用户扫到的是一张只在同一个 Wi-Fi 下能用的二维码，
 * 一出门就是「一直自动重连中」，而且看不出哪里错了。
 */
function resolveConnectHost() {
  const mode = gateway.resolveTunnelMode({ explicit: explicitTunnelMode, argv: args });
  const localHost = `http://${gateway.getLocalIp()}:${gatewayPort}`;

  if (publicUrlOverride) {
    return { mode, connectHost: publicUrlOverride.replace(/\/+$/, ''), configPath: '' };
  }

  if (mode === 'frp') {
    const info = frpClient.describeFrp();
    if (info.localPort && info.localPort !== gatewayPort) {
      // 与 gateway.start() 里的告警同因：toml 指错端口，隧道连通但一直 502
      console.warn(`[frp] ⚠️ frpc 配置的 localPort=${info.localPort}，而网关端口是 ${gatewayPort}，隧道会 502`);
    }
    return { mode, connectHost: info.publicUrl || localHost, configPath: info.configPath };
  }

  if (mode === 'cloudflare') {
    const stable = gateway.resolveStableTunnel(gatewayPort);
    for (const note of stable.notes) {
      console.warn(`[tunnel] ⚠️ ${note}`);
    }
    return {
      mode,
      connectHost: stable.tunnel ? `https://${stable.tunnel.hostname}` : localHost,
      configPath: ''
    };
  }

  return { mode, connectHost: localHost, configPath: '' };
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
function cleanupChildren() {
  if (piWebRestartTimer) {
    clearTimeout(piWebRestartTimer);
    piWebRestartTimer = null;
  }
  stopPiWebMemorySampling();
  stopStallWatch();

  if (piWebProcess && piWebProcess.pid) {
    killProcessTree(piWebProcess.pid);
    piWebProcess = null;
  }

  if (tunnelProcess && tunnelProcess.pid) {
    killProcessTree(tunnelProcess.pid);
    tunnelProcess = null;
  }
  // 命名隧道 / frp 客户端都会自己重连（换新 PID），所以要显式让它们停手，
  // 否则会留孤儿进程，甚至退出瞬间又被守护逻辑拉起来。
  try { gateway.stopTunnel(); } catch (e) {}
  try { gateway.stopFrp(); } catch (e) {}

  if (gatewayServer) {
    try { gatewayServer.close(); } catch (e) {}
    gatewayServer = null;
  }
}

function shutdown(exitCode = 0) {
  if (isShuttingDown) return;
  isShuttingDown = true;

  const hasChildren = Boolean(piWebProcess || tunnelProcess || gatewayServer);
  if (hasChildren) {
    console.log('\n🛑 正在停止 piDroid 服务并清理后台进程...');
  }

  cleanupChildren();

  setTimeout(() => {
    process.exit(exitCode);
  }, 200);
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));
process.on('exit', () => {
  if (!isShuttingDown) {
    isShuttingDown = true;
    cleanupChildren();
  }
});

// 3. 启动本地官方 pi-web 子进程
//
// 这个进程由计划任务（无窗口）托管，所以两件事必须自己做：
//   * 崩溃后自己重启 —— 没人看着窗口，一次退出就是手机端一直 502，
//     直到重启任务或电脑为止；
//   * 传 --no-open / PI_WEB_NO_OPEN=1 —— pi-web 默认会在就绪后打开浏览器，
//     后台常驻服务不该在桌面上自己弹标签页。
const PI_WEB_RESTART_DELAY_MS = 5000;
// `where pi-web` 是 PATH 查找，Windows 上 PATH 很长 / 杀软扫一遍时能卡到分钟级。
// 实测 2026-11-11 那次自动重启：stack 起来后 139s 才走到 spawn pi-web，全程卡在这一句上。
const PI_WEB_WHERE_TIMEOUT_MS = 5000;
// 栈采样诊断：pi-web 的 wrapper 不转发 execArgv，NODE_OPTIONS 又不接受 --inspect，
// 所以只有这个开关打开时，launcher 才自己用 node --inspect 直起 next，
// 卡顿时能连上去 Debugger.pause 拿 JS 调用栈。默认关闭，行为与以前完全一致。
const PIWEB_DEBUG_STACK = process.env.PIWEB_DEBUG_STACK === '1';
const PIWEB_INSPECT_PORT = Number(process.env.PIWEB_INSPECT_PORT || 9229);
let piWebRestartTimer = null;

// 崩溃取证：只保留最后 N 行输出 + 每 20s 采一次 RSS。
// 历史上那次 Next.js 退出只留下 `exited unexpectedly (code 1)`，没有栈也没内存记录，
// 只能靠猜。现在每次退出都会打一段可读证据（见 crash-forensics.js）。
const PI_WEB_MEMORY_SAMPLE_MS = 20_000;
let piWebForensics = null;
let piWebMemoryTimer = null;

function stopPiWebMemorySampling() {
  if (piWebMemoryTimer) {
    clearInterval(piWebMemoryTimer);
    piWebMemoryTimer = null;
  }
}

function startPiWebMemorySampling(proc) {
  stopPiWebMemorySampling();
  if (!proc || !proc.pid) return;
  let inFlight = false;
  piWebMemoryTimer = setInterval(() => {
    if (!piWebForensics || inFlight) return;
    inFlight = true;
    // 采整棵树的 RSS：spawn('pi-web.cmd') 拿到的是 cmd.exe（~11 MB），
    // 真正的内存大户是它孙子那层 `next start`。
    sampleProcessTreeMemoryMb(proc.pid)
      .then((mb) => {
        // recordMemory 只在「首次 / 明显变化」时返回 true。以前这里会打一行 RSS，
        // 但 GC 摆动会让它每次采样都「明显变化」，两小时就刷了几百行噪音；
        // 现在只喂给崩溃取证，稳态不输出（卡顿现场另有 [stall] 记录）。
        if (piWebForensics && mb) piWebForensics.recordMemory(mb);
      })
      .catch(() => {})
      .then(() => {
        inFlight = false;
      });
  }, PI_WEB_MEMORY_SAMPLE_MS);
  if (typeof piWebMemoryTimer.unref === 'function') piWebMemoryTimer.unref();
}

// 卡顿现场：每 5s 量一次 pi-web 的响应时间，超过 1s 就把现场写进日志。
// 不追单次请求（那是网关的 upstream-latency.log 的活），这里要的是
// 「卡住那一刻它在干什么」：RSS / CPU / 线程数 / 最后几行输出。
const STALL_PROBE_INTERVAL_MS = 5000;
const STALL_PROBE_TIMEOUT_MS = 8000;
let stallWatchTimer = null;

const stallRecorder = new StallRecorder({
  log: (line) => console.log(line),
  diagnostics: () => piWebDiagnostics(PI_WEB_PORT),
});

function measurePiWebLatency() {
  return new Promise((resolve) => {
    const startedAt = Date.now();
    const req = http.get(`${piWebUrl}/api/models`, { timeout: STALL_PROBE_TIMEOUT_MS }, (res) => {
      res.resume();
      resolve({ ok: res.statusCode < 500, ms: Date.now() - startedAt });
    });
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, ms: Date.now() - startedAt });
    });
    req.on('error', () => resolve({ ok: false, ms: Date.now() - startedAt }));
  });
}

/**
 * 现场快照：CPU 打满 = JS/GC 侧阻塞；CPU 空转 = 在等 I/O 或锁。这条判定决定下一步往哪查。
 * 用 exec（异步）而不是 execSync：网关和 pi-web 的托管都跑在这个进程里，
 * 取证时把 launcher 卡住 1s 会让手机端多等 1s。
 */
function piWebDiagnostics(port) {
  const pid = portOwnerPid(port);
  if (!pid) return 'pi-web 端口无人监听（进程已退出）';
  const script =
    `$p=Get-Process -Id ${pid}; $c1=$p.CPU; $t=Get-Date; Start-Sleep -Milliseconds 800; ` +
    `$p2=Get-Process -Id ${pid}; $dt=((Get-Date)-$t).TotalSeconds; ` +
    `'pid=${pid} rss={0}MB cpu={1}% threads={2}' -f [int]($p2.WorkingSet64/1MB), [math]::Round(($p2.CPU-$c1)/$dt*100,0), $p2.Threads.Count`;
  return new Promise((resolve) => {
    exec(
      `powershell -NoProfile -Command "${script}"`,
      { timeout: 5000, windowsHide: true },
      (error, stdout) => resolve(error ? `pid=${pid}（快照失败: ${error.message}）` : String(stdout).trim())
    );
  });
}

function startStallWatch() {
  stopStallWatch();
  let inFlight = false;
  stallWatchTimer = setInterval(() => {
    if (inFlight) return;
    inFlight = true;
    measurePiWebLatency()
      .then((sample) => stallRecorder.observe(sample))
      .catch(() => {})
      .then(() => {
        inFlight = false;
      });
  }, STALL_PROBE_INTERVAL_MS);
  if (typeof stallWatchTimer.unref === 'function') stallWatchTimer.unref();
}

function stopStallWatch() {
  if (stallWatchTimer) {
    clearInterval(stallWatchTimer);
    stallWatchTimer = null;
  }
}

/**
 * 找到已安装的 pi-web 包目录（全局 npm 的 shim 旁边就是 node_modules）。
 * 只为诊断路径服务：普通启动仍然走 pi-web.cmd，不碰包装器。
 */
function resolvePiWebPackageDir() {
  try {
    const out = execSync(process.platform === 'win32' ? 'where pi-web' : 'which pi-web', {
      encoding: 'utf8',
      timeout: PI_WEB_WHERE_TIMEOUT_MS,
      windowsHide: true
    });
    const shim = String(out).split(/\r?\n/).map((line) => line.trim()).filter(Boolean)[0];
    if (!shim) return null;
    const pkgDir = path.join(path.dirname(shim), 'node_modules', '@agegr', 'pi-web');
    return fs.existsSync(path.join(pkgDir, 'package.json')) ? pkgDir : null;
  } catch (e) {
    return null;
  }
}

/**
 * 诊断启动规格：以 node --inspect 直接起 next，跳过 wrapper（wrapper 不转发 execArgv）。
 * 保留 wrapper 干的两件事：preview secret 轮换、next start 的端口/主机参数。
 */
function piWebInspectSpec() {
  const pkgDir = resolvePiWebPackageDir();
  if (!pkgDir) return null;
  const nextBin = path.join(pkgDir, 'node_modules', 'next', 'dist', 'bin', 'next');
  if (!fs.existsSync(nextBin)) return null;
  try {
    const rotation = require(path.join(pkgDir, 'bin', 'rotate-preview-secrets.js'));
    rotation.rotatePreviewSecrets(path.join(pkgDir, '.next'));
  } catch (e) {
    console.warn('[warn] preview secret 轮换跳过:', e.message);
  }
  const port = new URL(piWebUrl).port || '30141';
  console.log(`[debug] inspector 模式：next 直起，调试端口 127.0.0.1:${PIWEB_INSPECT_PORT}`);
  return {
    cmd: process.execPath,
    args: [`--inspect=127.0.0.1:${PIWEB_INSPECT_PORT}`, nextBin, 'start', '-p', port, '-H', '127.0.0.1'],
    env: { PI_WEB_HOSTNAME: '127.0.0.1' },
    cwd: pkgDir
  };
}

function spawnPiWeb(cmd, cmdArgs, useShell, extraEnv = {}, cwd) {
  const proc = spawn(cmd, cmdArgs, {
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: useShell,
    // next start 靠 cwd 找 `.next`：inspector 诊断路径必须把 cwd 设到包目录，
    // 否则会以 “Could not find a production build” 秒退。普通路径继续用 launcher 自己的 cwd。
    cwd: cwd || undefined,
    env: {
      ...process.env,
      PORT: String(PI_WEB_PORT),
      PI_WEB_NO_OPEN: '1',
      ...extraEnv,
    }
  });

  piWebForensics = new CrashForensics();
  piWebForensics.markStarted();

  proc.on('error', (err) => {
    console.error('[pi-web 启动异常]:', err.message);
  });

  proc.stdout.on('data', (chunk) => {
    const text = chunk.toString();
    // 全部输出进环形缓冲（崩溃时用），只有就绪类日志才实时回显
    piWebForensics.recordLine(text);
    stallRecorder.noteLine(text);
    if (text.includes('Ready') || text.includes('ready') || text.includes('http://')) {
      process.stdout.write(`    [pi-web] ${text.trim()}\n`);
    }
  });

  // stderr 以前是 inherit（直接进日志但没有副本）；现在同时进环形缓冲，
  // 崩的时候才能把「最后到底骂了什么」一起带出来。
  proc.stderr.on('data', (chunk) => {
    const text = chunk.toString();
    piWebForensics.recordLine(text);
    stallRecorder.noteLine(text);
    process.stderr.write(text);
  });

  startPiWebMemorySampling(proc);
  startStallWatch();

  return proc;
}

function launchPiWeb() {
  const isWindows = process.platform === 'win32';
  const checkCmd = isWindows ? 'where pi-web' : 'which pi-web';

  // 智能寻找可执行命令
  let cmd = 'pi-web';
  let cmdArgs = ['--no-open'];
  let useShell = false;
  let extraEnv = {};

  // 诊断模式：直接把 inspector 挂到真正的 next 服务上（见 piWebInspectSpec）。
  const inspectSpec = PIWEB_DEBUG_STACK ? piWebInspectSpec() : null;
  if (inspectSpec) {
    cmd = inspectSpec.cmd;
    cmdArgs = inspectSpec.args;
    useShell = false;
    extraEnv = inspectSpec.env;
    return startPiWebProcess(cmd, cmdArgs, useShell, extraEnv, inspectSpec.cwd);
  } else if (PIWEB_DEBUG_STACK) {
    console.warn('[warn] PIWEB_DEBUG_STACK=1 但定位不到 pi-web 包目录，回退到普通启动');
  }

  // 检查是否全局有 pi-web 命令
  try {
    execSync(checkCmd, { stdio: 'ignore', timeout: PI_WEB_WHERE_TIMEOUT_MS });
    cmd = isWindows ? 'pi-web.cmd' : 'pi-web';
    useShell = isWindows;
  } catch (e) {
    // status === null 是「被超时打断」，不是「没装」。这时候退到 npx 会去下载一份
    // latest（断网直接起不来，且有网也要几十秒），所以仍按已安装处理。
    if (e && e.status === null) {
      console.warn(`[warn] ${checkCmd} 超过 ${PI_WEB_WHERE_TIMEOUT_MS / 1000}s 未返回，仍按已安装处理`);
      cmd = isWindows ? 'pi-web.cmd' : 'pi-web';
      useShell = isWindows;
    } else {
      // 若未全局安装，使用 npx 实时拉起最新版
      cmd = isWindows ? 'npx.cmd' : 'npx';
      cmdArgs = ['@agegr/pi-web@latest', '--no-open'];
      useShell = isWindows;
    }
  }

  console.log(`[1/3] 正在启动官方原生 pi-web 服务 (${cmd} ${cmdArgs.join(' ')})...`);

  return startPiWebProcess(cmd, cmdArgs, useShell, extraEnv);
}

/**
 * 起 pi-web 子进程并挂上取证/卡顿监测：两条启动路径（普通 / inspector 诊断）共用。
 */
function startPiWebProcess(cmd, cmdArgs, useShell, extraEnv = {}, cwd) {
  const proc = spawnPiWeb(cmd, cmdArgs, useShell, extraEnv, cwd);

  proc.on('exit', (code, signal) => {
    stopPiWebMemorySampling();
    stopStallWatch();
    // 先出证据再重启：谁在什么时候、凭什么退出的
    const forensics = piWebForensics;
    piWebForensics = null;
    if (forensics) {
      for (const line of forensics.report({ code, signal })) console.log(line);
    }
    // 已经换代（被重启后的新进程取代）或正在退出：不重复拉起
    if (proc !== piWebProcess || isShuttingDown) return;
    console.log(
      `[pi-web] 已退出 (code=${code}, signal=${signal})，${PI_WEB_RESTART_DELAY_MS / 1000}s 后自动重启...`
    );
    piWebRestartTimer = setTimeout(() => {
      piWebRestartTimer = null;
      if (isShuttingDown) return;
      piWebProcess = launchPiWeb();
    }, PI_WEB_RESTART_DELAY_MS);
  });

  return proc;
}

// 4. 等待服务就绪轮询（实现搬到了 startup-probe.js，可单测）

// 主启动流程
async function main() {
  console.log('\n======================================================');
  console.log('    🚀  piDroid 统合启动中心 (Unified Companion)     ');
  console.log('       [原生 pi-web 协同 + 工业级移动端安全网关]       ');
  console.log('======================================================\n');

  // 步骤 A: 检查 pi-web 是否已运行
  //
  // 先看端口再看 HTTP：Next.js 首次编译时 `/` 可能 1 秒以上才应答，只靠一次 HTTP 探测
  // 会把"正在跑的"判成"没在跑"，于是又拉起第二份 —— 必然 EADDRINUSE 30141 秒退，
  // 日志里留下一串假的 "Next.js exited unexpectedly"（线上真的碰过）。
  const piWebPort = Number(new URL(piWebUrl).port || 80);
  let alreadyRunning = await isPortListening(piWebPort);
  if (!alreadyRunning) {
    alreadyRunning = await probeWithRetry(() => checkUrlAlive(piWebUrl, 1200), {
      attempts: 3,
      delayMs: 600,
    });
  }

  // 横幅里"pi-web 是否正常"必须反映当前真实状态：刚拉起的也可能还没就绪。
  let piWebReady = alreadyRunning;
  if (alreadyRunning) {
    console.log(`[1/3] ✓ 检测到本地已存在运行中的 pi-web (${piWebUrl})，直接接入。`);
  } else if (!skipPiWeb) {
    piWebProcess = await launchPiWeb();
    process.stdout.write('[2/3] 等待 pi-web 服务就绪中...');
    // 同时盯 exit：自己拉起的那份如果因为端口被抢占而秒退，就不要白等 25 秒。
    const outcome = await waitForReadyOrExit(piWebUrl, piWebProcess);
    if (outcome.ready) {
      piWebReady = true;
      console.log(' ✓ 服务已就绪！');
    } else if (outcome.exited && (await isPortListening(piWebPort))) {
      // 端口另有实例：接管现有实例，并清除当前 piWebProcess 引用避免旧 exit 钩子 5s 后无限重拉撞端口。
      piWebProcess = null;
      if (piWebRestartTimer) {
        clearTimeout(piWebRestartTimer);
        piWebRestartTimer = null;
      }
      piWebReady = await waitForReady(piWebUrl, 8000);
      console.log(` ✓ 端口 ${piWebPort} 上已有其它 pi-web 实例，直接接入。`);
    } else if (outcome.exited) {
      console.log(` ⚠️ pi-web 启动失败（exit=${outcome.code}），继续启动伴侣网关。`);
    } else {
      console.log(' ⚠️ 探测超时，继续启动伴侣网关。');
    }
  } else {
    console.log('[1/3] 已跳过启动 pi-web (--no-pi-web)。');
  }

  // 步骤 B: 检查伴侣网关是否已在运行
  const isGatewayRunning = await checkGatewayRunning(gatewayPort);
  if (isGatewayRunning) {
    // 网关由其他窗口托管。这里必须用与 gateway.start() 相同的解析逻辑（frp → toml，
    // Cloudflare → cloudflared 配置），否则会退回到「展示局域网 IP」：用户扫了那张二维码，
    // 一旦离开家里 Wi-Fi 就连不上，表现仍是「一直自动重连中」。
    const { mode, connectHost, configPath } = resolveConnectHost();
    console.log(`[2/2] ✓ 检测到伴侣安全网关已在端口 ${gatewayPort} 运行中，直接呈现连接凭据：`);
    gateway.printBannerAndQr(connectHost, piWebReady, gatewayPort);
    if (mode !== 'none') {
      // 已经在跑的网关未必接管了隧道（可能是用 start-server.bat 起的，
      // 也可能隧道进程自己退出了），直接探一下公网地址，
      // 别把一张打不开的二维码交给用户。
      const reachable = await probePublicUrl(connectHost);
      if (reachable) {
        console.log(`\n✅ ${connectHost} 连通正常：网关与${mode === 'frp' ? ' frp ' : ' Cloudflare '}隧道都在运行，无需重启。`);
        console.log('   （本窗口只是查看器，Ctrl+C 不会停掉后台服务。）');
      } else {
        console.warn(`\n⚠️  ${connectHost} 暂时探测不通。`);
        console.warn(`   本地网关是正常的（${gatewayPort} 端口在听），常见原因是隧道进程已退出。`);
        if (configPath) console.warn(`   frp 配置: ${configPath}`);
        console.warn('   处理：关掉旧网关窗口后重新运行本脚本 —— 启动器会把隧道拉起，并在它断开时自动重连。');
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
      // undefined → gateway 按默认（frp）解析；显式 flag 则照办
      tunnelMode: explicitTunnelMode
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
