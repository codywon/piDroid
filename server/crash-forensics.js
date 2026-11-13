// pi-web 子进程崩溃取证 —— 纯逻辑 + 一个读内存的小工具，不启动服务。
//
// 背景：2026-11-04 那次 Next.js 退出只留下 `Next.js exited unexpectedly (code 1)`，
// 一句栈都没有，只能对着「2.2 MB 队列 / 26725 次丢增量」猜根因。这个模块把「猜」变成「看」：
//   * 保留最后 N 行输出（环形缓冲，不让日志无界增长）；
//   * 每 20s 采一次子进程 RSS，只在「明显变化」时报，避免刷屏；
//   * 崩溃时一次性打印 运行时长 / 退出码 / 内存曲线 / 最后 N 行输出 / 疑似 OOM。
//
// 为什么单独成文件：AGENTS.md 要求易错的纯逻辑抽出来做 JVM/Node 级单测，
// 而 launcher.js 一被 require 就会跑 main()，测试没法直接引它。

const { execSync } = require('child_process');
const childProcessExec = require('child_process').exec;
const path = require('path');

const FORENSIC_TAIL_LINES = 40;
const FORENSIC_MAX_LINE_CHARS = 200;
const MEMORY_LOG_MIN_DELTA_MB = 64;
const MEMORY_LOG_MIN_RATIO = 0.25;

/** 去掉 token / 密码这类会进日志的敏感值。写日志时不能把 .token 一起打出去。 */
function redactLine(line) {
  return String(line)
    .replace(/((?:auth\.)?token\s*=\s*)[^\s,"}&]+/gi, '$1***')
    .replace(/\b(pi_[0-9a-zA-Z]{8,})\b/g, 'pi_***')
    .replace(/((?:password|passwd|secret|api[_-]?key)\s*[=:]\s*)[^\s,"}&]+/gi, '$1***');
}

/** "1,048,576 K" → MB（KiB/1024）。非数字返回 null。 */
function parseMemoryCell(cell) {
  const digits = String(cell || '').replace(/[^\d]/g, '');
  if (!digits) return null;
  const kib = Number(digits);
  if (!Number.isFinite(kib) || kib <= 0) return null;
  return Math.round(kib / 1024);
}

/**
 * 解析 `tasklist /FI "PID eq N" /FO CSV /NH` 的输出，返回该进程占用的 MB（取最大值）。
 *
 * CSV 行形如 `"node.exe","1,048,576 K"`；`Mem Usage` 列在小数点/千分位上有区域差异，
 * 所以只用「纯数字以外的字符全部丢掉」的方式取值，不做本地化假设。
 * 表头行（`"Image Name","Mem Usage"`）天然解析为 null，自动跳过。
 */
function parseWindowsTasklistCsv(csv) {
  const text = String(csv || '');
  if (!text.trim()) return null;
  let max = null;
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    const cells = line.match(/"([^"]*)"/g);
    if (!cells || cells.length < 2) continue;
    const kb = parseMemoryCell(cells[cells.length - 1]);
    if (kb !== null && (max === null || kb > max)) max = kb;
  }
  return max;
}

/** 取某个 PID 的常驻内存（MB）；平台不支持或进程已退出时返回 null。绝不抛。 */
function sampleProcessMemoryMb(pid) {
  if (!pid) return null;
  try {
    if (process.platform === 'win32') {
      const out = execSync(`tasklist /FI "PID eq ${Number(pid)}" /FO CSV /NH`, {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
        timeout: 4000
      });
      return parseWindowsTasklistCsv(out);
    }
    const status = require('fs').readFileSync(`/proc/${Number(pid)}/status`, 'utf8');
    const m = status.match(/VmRSS:\s+(\d+)\s+kB/);
    return m ? Math.round(Number(m[1]) / 1024) : null;
  } catch (e) {
    return null;
  }
}

/**
 * 解析 process-tree.ps1 / 类 tasklist 输出的行：`pid,ppid,rssMB`。
 * 无效行（表头、空行、非数字）直接跳过，绝不抛。
 */
function parseProcessRows(text) {
  const rows = [];
  for (const raw of String(text || '').split(/\r?\n/)) {
    const parts = raw.trim().split(',');
    if (parts.length < 3) continue;
    const pid = Number(parts[0]);
    const ppid = Number(parts[1]);
    const mb = Number(parts[2]);
    if (!Number.isFinite(pid) || !Number.isFinite(ppid) || !Number.isFinite(mb)) continue;
    if (pid <= 0) continue;
    rows.push({ pid, ppid, mb });
  }
  return rows;
}

/**
 * 求一棵进程树的常驻内存（MB，取整），`rootPid` 不在表里时返回 null。
 *
 * 为什么必须算整棵树：在 Windows 上 `spawn('pi-web.cmd')` 拿到的是 cmd.exe 的 pid
 * （实测只有 11 MB），真正的内存大户是它的孙子 —— `next start`。只采一个 pid 等于没采。
 * 环状 parent 链（理论上不该有）用 visited 集合截断，防止死循环。
 */
function sumProcessTreeMb(rows, rootPid) {
  const root = Number(rootPid);
  if (!Number.isFinite(root) || root <= 0) return null;
  const byParent = new Map();
  const known = new Set();
  for (const row of rows || []) {
    known.add(row.pid);
    if (!byParent.has(row.ppid)) byParent.set(row.ppid, []);
    byParent.get(row.ppid).push(row);
  }
  if (!known.has(root)) return null;

  let total = 0;
  const visited = new Set();
  const queue = [root];
  while (queue.length) {
    const pid = queue.shift();
    if (visited.has(pid)) continue;
    visited.add(pid);
    const self = rows.find((r) => r.pid === pid);
    if (self) total += self.mb;
    for (const child of byParent.get(pid) || []) queue.push(child.pid);
  }
  return total;
}

/**
 * 异步采一次「pi-web 进程树」的内存（MB）。
 *
 * 用异步 exec 而不是 execSync：拉进程表要 ~400ms，同步版本会把 launcher 的
 * 事件循环卡住——它同时还在转发 SSE，卡 400ms 等于让手机端卡一下。
 */
function sampleProcessTreeMemoryMb(rootPid, { exec = childProcessExec, timeout = 5000 } = {}) {
  return new Promise((resolve) => {
    if (!rootPid) return resolve(null);
    if (process.platform !== 'win32') {
      require('fs').readFile(`/proc/${Number(rootPid)}/status`, 'utf8', (err, status) => {
        if (err || !status) return resolve(null);
        const m = status.match(/VmRSS:\s+(\d+)\s+kB/);
        resolve(m ? Math.round(Number(m[1]) / 1024) : null);
      });
      return;
    }

    const script = path.join(__dirname, 'scripts', 'process-tree.ps1');
    const command =
      `"${process.env.SystemRoot || 'C:\\Windows'}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"` +
      ` -NoProfile -ExecutionPolicy Bypass -File "${script}" -RootPid ${Number(rootPid)}`;

    exec(command, { timeout, windowsHide: true, maxBuffer: 4 * 1024 * 1024 }, (err, stdout) => {
      if (err && !stdout) return resolve(null);
      const rows = parseProcessRows(stdout);
      // 树里没有 node.exe/cmd.exe 时改为异步 tasklist 兜底，绝不阻塞事件循环
      const total = sumProcessTreeMb(rows, Number(rootPid));
      if (total !== null) return resolve(total);
      exec(`tasklist /FI "PID eq ${Number(rootPid)}" /FO CSV /NH`, { timeout: 4000, windowsHide: true }, (err2, csvOut) => {
        resolve(err2 || !csvOut ? null : parseWindowsTasklistCsv(csvOut));
      });
    });
  });
}

class CrashForensics {
  constructor({
    tailLines = FORENSIC_TAIL_LINES,
    maxLineChars = FORENSIC_MAX_LINE_CHARS,
    now = Date.now
  } = {}) {
    this.tailLines = Math.max(1, tailLines);
    this.maxLineChars = Math.max(20, maxLineChars);
    this.now = now;
    this.startedAt = null;
    this.tail = [];
    this.lastMemoryMb = null;
  }

  markStarted(at = this.now()) {
    this.startedAt = at;
    this.tail = [];
    this.lastMemoryMb = null;
  }

  recordLine(line) {
    if (line === null || line === undefined) return;
    for (const piece of String(line).split(/\r?\n/)) {
      const text = redactLine(piece).trim();
      if (!text) continue;
      const clipped =
        text.length > this.maxLineChars ? `${text.slice(0, this.maxLineChars)}…` : text;
      this.tail.push(clipped);
    }
    if (this.tail.length > this.tailLines) {
      this.tail.splice(0, this.tail.length - this.tailLines);
    }
  }

  /**
   * 记录一次内存采样。返回 true 表示「这次值得写进日志」：
   * 第一次、或相对上一次涨/跌超过 max(64 MB, 25%)。稳态下不刷屏。
   */
  recordMemory(mb) {
    if (typeof mb !== 'number' || !Number.isFinite(mb) || mb <= 0) return false;
    const previous = this.lastMemoryMb;
    this.lastMemoryMb = mb;
    if (previous === null) return true;
    const delta = Math.abs(mb - previous);
    if (delta >= Math.max(MEMORY_LOG_MIN_DELTA_MB, previous * MEMORY_LOG_MIN_RATIO)) return true;
    return false;
  }

  /** 生成崩溃报告（字符串数组，调用方决定写到哪）。不抛异常。 */
  report({ code, signal, at } = {}) {
    const endedAt = typeof at === 'number' ? at : this.now();
    const lines = [];
    const uptimeMs = this.startedAt === null ? null : Math.max(0, endedAt - this.startedAt);
    const uptime = uptimeMs === null ? '未知' : `${Math.round(uptimeMs / 1000)}s`;
    lines.push(`[pi-web 崩溃取证] 运行时长=${uptime} code=${code} signal=${signal}`);
    lines.push(
      `[pi-web 崩溃取证] 退出前 RSS=${this.lastMemoryMb === null ? '未采到' : `${this.lastMemoryMb} MB`}`
    );
    const joined = this.tail.join('\n');
    if (/heap out of memory|Reached heap limit|out of memory|FATAL ERROR/i.test(joined)) {
      lines.push('[pi-web 崩溃取证] 疑似 OOM（输出里出现 heap out of memory / FATAL ERROR）');
    }
    if (this.tail.length === 0) {
      lines.push('[pi-web 崩溃取证] 最后输出: （空 —— 崩溃前子进程没有打印任何东西）');
    } else {
      lines.push(`[pi-web 崩溃取证] 最后 ${this.tail.length} 行输出:`);
      for (const line of this.tail) lines.push(`  | ${line}`);
    }
    return lines;
  }
}

module.exports = {
  CrashForensics,
  redactLine,
  parseWindowsTasklistCsv,
  parseProcessRows,
  sumProcessTreeMb,
  sampleProcessMemoryMb,
  sampleProcessTreeMemoryMb,
  FORENSIC_TAIL_LINES,
  FORENSIC_MAX_LINE_CHARS
};
