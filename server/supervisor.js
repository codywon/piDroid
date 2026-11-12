#!/usr/bin/env node
/**
 * piDroid 整栈守护（ps1 版 `pi-web-supervisor.ps1` 的继任者）。
 *
 * 为什么不是「又一个 watchdog」：历史上这一栈同时被三样东西托管过——
 *   * `start-hidden.cmd` 的 while 循环（端口被占就 exit，等于没有守护）；
 *   * `pi-web-supervisor.ps1`（只看 30141 能不能 TCP 连上）；
 *   * `launcher.js` 自己的 exit→重启（只管 pi-web，不管网关）。
 * 三者互相不知道对方存在，于是拉起第二份 pi-web → EADDRINUSE → "Next.js exited
 * unexpectedly"，看起来像 pi-web 崩了，其实是守护自己在制造崩溃。
 *
 * 这个进程是唯一的托管者，职责只有四件：
 *   1. 只拉起**一份** `launcher.js`（pi-web + 网关 + 隧道都由它负责）；
 *   2. 用 **HTTP** 而不是 TCP 判活——端口在听但事件循环已经卡死（GC 抖动、
 *      SSE 队列堆到 GB 级）时 TCP 依然连得上，这正是「服务死了但守护认为活着」；
 *   3. pi-web 的 RSS 到阈值就整栈回收（主动止损，别等它自己以 code 1 静默退出）；
 *   4. 每次死亡都留一行证据：退出码、运行时长、退出前 RSS、最后几行输出。
 *
 * 用法：
 *   node supervisor.js run         前台跑（计划任务用这个）
 *   node supervisor.js ensure      幂等拉起（给「每 5 分钟」的计划任务当保险丝）
 *   node supervisor.js status
 *   node supervisor.js stop
 *   node supervisor.js install     注册计划任务（需要管理员）
 *   node supervisor.js uninstall
 */

'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn, spawnSync } = require('child_process');
const { PIWEB_ONLY_RESTARTS_BEFORE_FULL, planRecovery } = require('./recovery-plan');

const SERVER_DIR = __dirname;
const STATE_DIR = path.join(SERVER_DIR, 'logs');
const STACK_LOG = path.join(STATE_DIR, 'stack.log');
const DEATH_LOG = path.join(STATE_DIR, 'deaths.log');
const SUPERVISOR_PID_FILE = path.join(STATE_DIR, 'supervisor.pid');
const LAUNCHER = path.join(SERVER_DIR, 'launcher.js');
const TOKEN_FILE = path.join(SERVER_DIR, '.token');

const PIWEB_PORT = Number(process.env.PIWEB_PORT || 30141);
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 4100);

// 探活参数：launcher.js 里"首次编译可能 1s 以上才应答"的坑，这里用「连续 N 次失败」而不是一次失败就判死。
const PROBE_INTERVAL_MS = 20_000;
const PROBE_TIMEOUT_MS = 8_000;
const FAILURES_BEFORE_RESTART = 3;
// 冷启动预算：Next.js 首编译 + resolveProject 逐个 git 探测，机器一忙就会超过 60s。
// 窗口太短会在「还在启动」时判死，杀掉再拉一份 —— 于是永远起不来。
const BOOT_GRACE_MS = 120_000;
// pi-web 实测在 1.3~1.4 GB 之后会以 code 1 静默退出（SSE 慢消费者把每条会话的队列堆到 MB 级）。
// 在 1.5 GB 主动回收，代价是几秒重连，而不是手机端 502 到下一次重启。
const RSS_RECYCLE_MB = Number(process.env.PIWEB_RSS_RECYCLE_MB || 1500);
const RSS_SAMPLE_INTERVAL_MS = 60_000;
const BACKOFF_MS = [5_000, 15_000, 60_000, 300_000];

const TASKNAME = 'piDroid Stack';
const WATCHDOG_TASKNAME = 'piDroid Stack Watchdog';

fs.mkdirSync(STATE_DIR, { recursive: true });

function log(message) {
  const line = `[${new Date().toISOString()}] ${message}`;
  console.log(line);
  try {
    fs.appendFileSync(STACK_LOG, line + '\n');
  } catch (e) {
    /* 日志写不进去不该拖垮守护 */
  }
}

function recordDeath(entry) {
  try {
    fs.appendFileSync(DEATH_LOG, `${new Date().toISOString()} ${JSON.stringify(entry)}\n`);
  } catch (e) {}
}

/**
 * 死亡取证用的「最后几行输出」。
 *
 * 子进程的 stdout/stderr 直接写 STACK_LOG 的 fd（不走管道：守护被 kill -9 时子进程不会卡在
 * 写满的管道上），所以取证只能回读日志尾部。以前在 stdout 上挂 onData 抓 chunk，但 stdio 用的
 * 是 fd，`child.stdout` 恒为 null，`tail` 从来没有过内容。
 */
function readLogTail(maxLines = 15) {
  try {
    const size = fs.statSync(STACK_LOG).size;
    const from = Math.max(0, size - 8 * 1024);
    const fd = fs.openSync(STACK_LOG, 'r');
    try {
      const buf = Buffer.alloc(size - from);
      fs.readSync(fd, buf, 0, buf.length, from);
      return buf.toString('utf8').split(/\r?\n/).filter((line) => line.trim()).slice(-maxLines);
    } finally {
      fs.closeSync(fd);
    }
  } catch (e) {
    return [];
  }
}

/** 探活走的是网关鉴权：不带 token 每 20s 落一条 401，真实的鉴权失败会被这些噪音淹没。 */
function authHeaders() {
  try {
    const token = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
    return token ? { authorization: `Bearer ${token}` } : {};
  } catch (e) {
    return {};
  }
}

/** 任何 HTTP 应答（含 401/404）都算活着：我们要的是事件循环还在转，不是业务成功。 */
function probe(url, headers = {}) {
  return new Promise((resolve) => {
    const req = http.get(url, { timeout: PROBE_TIMEOUT_MS, headers }, (res) => {
      res.resume();
      resolve({ ok: true, status: res.statusCode });
    });
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, reason: 'timeout' });
    });
    req.on('error', (err) => resolve({ ok: false, reason: err.code || err.message }));
  });
}

function stackHealth() {
  return Promise.all([
    probe(`http://127.0.0.1:${PIWEB_PORT}/api/models`),
    probe(`http://127.0.0.1:${GATEWAY_PORT}/api/ping`, authHeaders())
  ]);
}

/** 端口占用者 PID（netstat 比 Process API 便宜，且这台机器上没有 lsof）。 */
function portOwnerPid(port) {
  const r = spawnSync('netstat', ['-ano', '-p', 'TCP'], { encoding: 'utf8', windowsHide: true });
  if (!r.stdout) return null;
  const needle = `:${port} `;
  for (const line of r.stdout.split(/\r?\n/)) {
    if (!line.includes(needle) || !line.includes('LISTENING')) continue;
    const parts = line.trim().split(/\s+/);
    const pid = Number(parts[parts.length - 1]);
    if (Number.isInteger(pid) && pid > 0) return pid;
  }
  return null;
}

/** 单进程 RSS（MB），用 tasklist 免得再拉一个 PowerShell 起来。 */
function processRssMb(pid) {
  const r = spawnSync('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'], { encoding: 'utf8', windowsHide: true });
  if (!r.stdout) return null;
  const m = r.stdout.match(/"([^"]+)","[^"]*","[^"]*","([\d\s.,]+) K"/);
  if (!m) return null;
  return Math.round(Number(m[2].replace(/[^\d]/g, '')) / 1024);
}

function killTree(pid, why) {
  if (!pid) return;
  log(`kill tree pid=${pid} (${why})`);
  spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true });
}

class Supervisor {
  constructor() {
    this.child = null;
    this.childStartedAt = 0;
    this.healthySince = 0;
    this.failures = 0;
    this.restarts = 0;
    this.piwebOnlyRestarts = 0;
    this.rssPeakMb = 0;
    this.stopping = false;
    this.probeTimer = null;
    this.rssTimer = null;
    this.restartTimer = null;
  }

  startChild(reason) {
    // 上一次退出排的「稍后重启」还没到点就又被拉起（接管分支 / 手动 ensure）：取消它，
    // 否则同一个守护会拉起两份 launcher，后一份把 this.child 顶掉，前一份成了没人管的孤儿。
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
    // launcher.js 自己会拉起 pi-web / 网关 / frp / cloudflared，并且 pi-web 退出后自己重启。
    const out = fs.openSync(STACK_LOG, 'a');
    this.child = spawn(process.execPath, ['--no-deprecation', LAUNCHER], {
      cwd: SERVER_DIR,
      stdio: ['ignore', out, out],
      env: {
        ...process.env,
        GATEWAY_PORT: String(GATEWAY_PORT),
        PORT: String(GATEWAY_PORT),
        PI_WEB_NO_OPEN: '1',
        // 给 pi-web 的堆设一个上限：真到了极限也是带 FATAL 信息退出（可取证），
        // 而不是涨到 GB 级以后以 code 1 无声消失。
        NODE_OPTIONS: [process.env.NODE_OPTIONS, '--max-old-space-size=3072'].filter(Boolean).join(' ')
      },
      windowsHide: true
    });
    this.childStartedAt = Date.now();
    log(`stack started (pid ${this.child.pid}) reason=${reason}`);

    this.child.on('exit', (code, signal) => {
      const uptimeS = Math.round((Date.now() - this.childStartedAt) / 1000);
      const normal = this.stopping;
      log(`stack exited code=${code} signal=${signal} uptime=${uptimeS}s`);
      recordDeath({ kind: 'stack', code, signal, uptimeS, rssPeakMb: this.rssPeakMb, tail: readLogTail() });
      this.child = null;
      this.rssPeakMb = 0;
      if (normal) return;
      // 有 launcher 之外的残留（比如 ps1 守护、手敲的一次性实例）时先清掉，否则新的那份只会 EADDRINUSE 秒退。
      for (const port of [PIWEB_PORT, GATEWAY_PORT]) killTree(portOwnerPid(port), `残留占用 ${port}`);
      const delay = BACKOFF_MS[Math.min(this.restarts, BACKOFF_MS.length - 1)];
      this.restarts += 1;
      log(`restarting stack in ${delay / 1000}s (restart #${this.restarts})`);
      this.restartTimer = setTimeout(() => {
        this.restartTimer = null;
        if (!this.stopping) this.startChild(`after exit code=${code}`);
      }, delay);
    });
  }

  async tick() {
    if (this.stopping) return;
    const [piweb, gateway] = await stackHealth();
    if (piweb.ok && gateway.ok) {
      this.failures = 0;
      this.piwebOnlyRestarts = 0;
      if (!this.healthySince) {
        this.healthySince = Date.now();
        log(`healthy: pi-web ${piweb.status} / gateway ${gateway.status}`);
      } else if (this.restarts > 0 && Date.now() - this.healthySince > 300_000) {
        // 稳了 5 分钟就把退避清零，否则一次抖动会让之后所有重启都等 5 分钟。
        log(`stable for 5min, resetting restart backoff (was #${this.restarts})`);
        this.restarts = 0;
      }
      return;
    }
    // 刚拉起的实例还没到冷启动预算：不判死，否则会把"正在启动"当成"死了"再拉一份。
    if (this.child && Date.now() - this.childStartedAt < BOOT_GRACE_MS) return;
    this.healthySince = 0;
    this.failures += 1;
    log(
      `probe failed ${this.failures}/${FAILURES_BEFORE_RESTART}: pi-web=${piweb.ok ? piweb.status : piweb.reason} gateway=${gateway.ok ? gateway.status : gateway.reason}`
    );
    if (this.failures < FAILURES_BEFORE_RESTART) return;

    this.failures = 0;
    const action = planRecovery({ gatewayOk: gateway.ok, piwebOnlyRestarts: this.piwebOnlyRestarts });
    recordDeath({
      kind: 'unresponsive',
      piweb: piweb.ok ? piweb.status : piweb.reason,
      gateway: gateway.ok ? gateway.status : gateway.reason,
      childPid: this.child && this.child.pid,
      action
    });
    if (!this.child) {
      // 端口上挂着一份没人托管的实例（手敲的 / 上一代守护的孤儿）：清掉再起自己的。
      for (const port of [PIWEB_PORT, GATEWAY_PORT]) killTree(portOwnerPid(port), `无主实例占用 ${port}`);
      this.startChild('adopted stack unresponsive');
      return;
    }
    if (action === 'piweb') {
      // 只换 pi-web：launcher 的 exit 钩子会在 5s 后把它重新拉起来，网关与 frp 隧道全程不断。
      this.piwebOnlyRestarts += 1;
      const owner = portOwnerPid(PIWEB_PORT);
      log(
        `recycling pi-web only (${this.piwebOnlyRestarts}/${PIWEB_ONLY_RESTARTS_BEFORE_FULL}) owner=${owner || 'none'} — gateway 与隧道保持不动`
      );
      killTree(owner, 'unresponsive pi-web');
      return;
    }
    this.piwebOnlyRestarts = 0;
    killTree(this.child.pid, 'unresponsive stack');
  }

  sampleRss() {
    const pid = portOwnerPid(PIWEB_PORT);
    if (!pid) return;
    const mb = processRssMb(pid);
    if (!mb) return;
    if (mb > this.rssPeakMb) this.rssPeakMb = mb;
    if (mb < RSS_RECYCLE_MB) return;
    recordDeath({ kind: 'rss-recycle', rssMb: mb, thresholdMb: RSS_RECYCLE_MB, childPid: this.child && this.child.pid });
    log(`pi-web RSS ${mb} MB >= ${RSS_RECYCLE_MB} MB, recycling whole stack`);
    if (this.child) killTree(this.child.pid, 'rss recycle');
    else killTree(portOwnerPid(PIWEB_PORT), 'rss recycle (unmanaged)');
  }

  run() {
    this.stopping = false;
    fs.writeFileSync(SUPERVISOR_PID_FILE, String(process.pid));
    log(`supervisor started (pid ${process.pid}) ports pi-web=${PIWEB_PORT} gateway=${GATEWAY_PORT} rssRecycle=${RSS_RECYCLE_MB}MB`);

    const shutdown = (why) => {
      if (this.stopping) return;
      this.stopping = true;
      log(`supervisor stopping (${why})`);
      clearInterval(this.probeTimer);
      clearInterval(this.rssTimer);
      clearTimeout(this.restartTimer);
      if (this.child) killTree(this.child.pid, 'supervisor stopping');
      try {
        fs.unlinkSync(SUPERVISOR_PID_FILE);
      } catch (e) {}
      setTimeout(() => process.exit(0), 500);
    };

    process.on('SIGINT', () => shutdown('SIGINT'));
    process.on('SIGTERM', () => shutdown('SIGTERM'));

    // 启动时如果整栈已经健康（上一代实例还在跑），先接管观察，不制造第二份。
    stackHealth().then(([piweb, gateway]) => {
      if (piweb.ok && gateway.ok) {
        log('existing stack is healthy, adopting it (no new process spawned)');
        this.healthySince = Date.now();
      } else {
        if (portOwnerPid(PIWEB_PORT) || portOwnerPid(GATEWAY_PORT)) {
          log('stale instance found on port but stack is unhealthy, clearing it first');
          for (const port of [PIWEB_PORT, GATEWAY_PORT]) killTree(portOwnerPid(port), 'stale at startup');
        }
        this.startChild('startup');
      }
      this.probeTimer = setInterval(() => this.tick(), PROBE_INTERVAL_MS);
      this.rssTimer = setInterval(() => this.sampleRss(), RSS_SAMPLE_INTERVAL_MS);
      if (this.probeTimer.unref) this.probeTimer.unref();
      if (this.rssTimer.unref) this.rssTimer.unref();
      // unref 过的 interval 会让事件循环空转退出，得留一个真句柄
      this.keepalive = setInterval(() => {}, 1 << 30);
    });
  }
}

function readSupervisorPid() {
  try {
    const raw = fs.readFileSync(SUPERVISOR_PID_FILE, 'utf8').trim();
    const pid = Number(raw);
    if (!Number.isInteger(pid) || pid <= 0) return null;
    process.kill(pid, 0);
    return pid;
  } catch (e) {
    return null;
  }
}

function scheduleRun() {
  spawn(process.execPath, [__filename, 'run'], { detached: true, stdio: 'ignore', cwd: SERVER_DIR }).unref();
}

function schtasks(args) {
  const r = spawnSync('schtasks', args, { encoding: 'utf8', windowsHide: true });
  return { code: r.status, out: `${r.stdout || ''}${r.stderr || ''}`.trim() };
}

// schtasks 的 /Create 只能挂一个 /SC，第二次带 /F 会把第一个触发器整条覆盖掉。
// 「开机 + 登录」两个触发器必须一次注册，所以这里走 Register-ScheduledTask。
function install() {
  const node = process.execPath;
  const sup = path.join(SERVER_DIR, 'supervisor.js');
  const ps = `
$ErrorActionPreference = 'Stop'
$action = New-ScheduledTaskAction -Execute '${node.replace(/'/g, "''")}' -Argument '${sup.replace(/'/g, "''")} run' -WorkingDirectory '${SERVER_DIR.replace(/'/g, "''")}'
$triggers = @((New-ScheduledTaskTrigger -AtStartup), (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME))
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Seconds 0) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName '${TASKNAME}' -Action $action -Trigger $triggers -Settings $settings -Force | Out-Null

$wAction = New-ScheduledTaskAction -Execute '${node.replace(/'/g, "''")}' -Argument '${sup.replace(/'/g, "''")} ensure' -WorkingDirectory '${SERVER_DIR.replace(/'/g, "''")}'
$wTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 3650)
$wSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
Register-ScheduledTask -TaskName '${WATCHDOG_TASKNAME}' -Action $wAction -Trigger $wTrigger -Settings $wSettings -Force | Out-Null
Get-ScheduledTask -TaskName '${TASKNAME}','${WATCHDOG_TASKNAME}' | Select-Object TaskName,State | Format-Table -AutoSize | Out-String
`;
  const r = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', ps], {
    encoding: 'utf8',
    windowsHide: true
  });
  const out = `${r.stdout || ''}${r.stderr || ''}`.trim();
  if (r.status === 0) {
    log('install: ok');
    console.log(out || 'registered');
    return;
  }
  // AtStartup 触发器需要管理员（普通用户 Register-ScheduledTask 会报 0x80070005），
  // 回退到只挂登录触发器：服务本来就要在用户会话里跑，ONLOGON 已经够用。
  const psLogonOnly = ps.replace('@((New-ScheduledTaskTrigger -AtStartup), (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME))', '(New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME)');
  const r2 = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', psLogonOnly], {
    encoding: 'utf8',
    windowsHide: true
  });
  const out2 = `${r2.stdout || ''}${r2.stderr || ''}`.trim();
  log(`install (logon-only fallback): ${r2.status === 0 ? 'ok' : out2}`);
  if (r2.status !== 0) {
    console.error(out2);
    console.error('提示：以管理员身份再执行一次 `node supervisor.js install` 可以拿到开机触发器。');
    process.exitCode = 1;
    return;
  }
  console.log(out2 || 'registered (logon trigger only)');
  console.log('提示：以管理员身份再执行一次 `node supervisor.js install` 可额外获得开机触发器。');
}

function uninstall() {
  const ps = `Get-ScheduledTask -TaskName '${TASKNAME}','${WATCHDOG_TASKNAME}' -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false`;
  const r = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', ps], {
    encoding: 'utf8',
    windowsHide: true
  });
  console.log(`uninstall: ${r.status === 0 ? 'ok' : `${r.stdout || ''}${r.stderr || ''}`}`);
}

async function main() {
  const action = process.argv[2] || 'run';
  const supervisor = new Supervisor();

  switch (action) {
    case 'run':
      supervisor.run();
      break;
    case 'ensure': {
      const pid = readSupervisorPid();
      if (pid) {
        console.log(`supervisor alive (pid ${pid})`);
        return;
      }
      console.log('supervisor not running, spawning');
      scheduleRun();
      break;
    }
    case 'status': {
      const pid = readSupervisorPid();
      const [piweb, gateway] = await stackHealth();
      console.log(`supervisor: ${pid ? `pid ${pid}` : 'not running'}`);
      console.log(`pi-web  ${PIWEB_PORT}: ${piweb.ok ? `HTTP ${piweb.status}` : `DOWN (${piweb.reason})`} rss=${processRssMb(portOwnerPid(PIWEB_PORT)) || '?'}MB`);
      console.log(`gateway ${GATEWAY_PORT}: ${gateway.ok ? `HTTP ${gateway.status}` : `DOWN (${gateway.reason})`}`);
      console.log(`logs: ${STACK_LOG} / ${DEATH_LOG}`);
      break;
    }
    case 'restart': {
      // 给「扩展自更新」这类必须重启 pi-web 的流程用：一条命令把整栈换成新的，
      // 而不是让人手动 kill 再指望某个守护把它拉起来。
      const pid = readSupervisorPid();
      if (pid) {
        console.log(`stopping supervisor ${pid}`);
        try {
          process.kill(pid);
        } catch (e) {}
        for (let i = 0; i < 40 && readSupervisorPid(); i += 1) await new Promise((r) => setTimeout(r, 250));
      }
      for (const port of [PIWEB_PORT, GATEWAY_PORT]) {
        const owner = portOwnerPid(port);
        if (owner) killTree(owner, `restart requested, clearing ${port}`);
      }
      scheduleRun();
      console.log('supervisor respawned with a fresh stack');
      break;
    }
    case 'stop': {
      const pid = readSupervisorPid();
      if (pid) {
        try {
          process.kill(pid);
        } catch (e) {}
        console.log(`asked supervisor ${pid} to stop`);
      } else {
        console.log('supervisor not running; clearing ports');
        for (const port of [PIWEB_PORT, GATEWAY_PORT]) killTree(portOwnerPid(port), `manual stop ${port}`);
      }
      break;
    }
    case 'install':
      install();
      break;
    case 'uninstall':
      uninstall();
      break;
    default:
      console.log('usage: node supervisor.js [run|ensure|status|restart|stop|install|uninstall]');
      process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
