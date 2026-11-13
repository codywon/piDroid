// pi-web 卡顿现场记录器（launcher 侧，零侵入）。
//
// 为什么放在 launcher 而不是 pi-web 里：仓库的原则是不改 @agegr/pi-web 源码。
// launcher 正好同时握着两样东西——pi-web 的整段输出（环形缓冲），和「隔几秒探一次
// 它多慢」的能力。所以卡顿现场没必要进 pi-web 进程，在这里收尸就够。
//
// 为什么要「只在慢时记录」：pi-web 空转时 CPU 也有 6~22%，天天全量采样会把日志
// 淹掉，而且真正要看的是「卡住的那 3 秒里它在干什么」，不是稳态。
//
// 记录什么：延迟（含 60s 内峰值）、进程 RSS/CPU/线程数（由调用方注入，判「CPU 打满
// 还是空转」），以及 pi-web 卡住前最后几行输出。这三样合起来能决定下一步往哪查：
// CPU 打满 = JS/GC 侧阻塞；CPU 空转 = 在等 I/O 或锁。

'use strict';

const { redactLine } = require('./crash-forensics');

/** 超过这个延迟才记录：pi-web 正常 2~3ms，劣化期 43~98ms，整段阻塞 3~4s。 */
const STALL_THRESHOLD_MS = 1000;
/** 同一个卡顿窗口内只记一条，避免 3 秒卡顿写 6 行日志。 */
const STALL_RECORD_WINDOW_MS = 60_000;
const STALL_LOG_LINES = 6;
const STALL_LOG_LINE_CHARS = 160;

class StallRecorder {
  /**
   * @param {object} [options]
   * @param {(line: string) => void} [options.log] 输出函数（launcher 传 console.log，测试传数组收集）
   * @param {() => (string | Promise<string>)} [options.diagnostics] 现场快照（RSS/CPU/线程数）
   * @param {() => number} [options.now]
   * @param {number} [options.thresholdMs]
   * @param {number} [options.windowMs]
   */
  constructor({ log = () => {}, diagnostics = () => '', now = () => Date.now(), thresholdMs = STALL_THRESHOLD_MS, windowMs = STALL_RECORD_WINDOW_MS } = {}) {
    this.log = log;
    this.diagnostics = diagnostics;
    this.now = now;
    this.thresholdMs = thresholdMs;
    this.windowMs = windowMs;
    this.lines = [];
    // -Infinity 而不是 0：时钟从 0 起算时，第一场卡顿不能被「窗口还没过」误吞掉。
    this.lastRecordAt = Number.NEGATIVE_INFINITY;
    this.peakMs = 0;
    this.stallCount = 0;
  }

  /** 吃掉 pi-web 的一段输出（保留最后 N 行，崩溃/卡顿取证都用它）。 */
  noteLine(text) {
    for (const raw of String(text).split(/\r?\n/)) {
      const line = redactLine(raw.trim());
      if (!line) continue;
      this.lines.push(line.length > STALL_LOG_LINE_CHARS ? line.slice(0, STALL_LOG_LINE_CHARS) + '…' : line);
      if (this.lines.length > STALL_LOG_LINES) this.lines.shift();
    }
  }

  lastLines() {
    return [...this.lines];
  }

  /**
   * 汇报一次探活结果；该记录时返回 true（调用方无需再做判断）。
   * @param {{ok: boolean, ms: number}} sample
   */
  observe({ ok, ms }) {
    const now = this.now();
    const stalled = !ok || ms >= this.thresholdMs;
    if (!stalled) {
      // 窗口过期后峰值归零，下一次卡顿的峰值只统计它自己那一轮。
      if (now - this.lastRecordAt > this.windowMs) this.peakMs = 0;
      return false;
    }
    if (now - this.lastRecordAt < this.windowMs) {
      this.peakMs = Math.max(this.peakMs, ms);
      return false;
    }
    this.lastRecordAt = now;
    this.peakMs = Math.max(this.peakMs, ms);
    this.stallCount += 1;
    this.write(ok, ms);
    return true;
  }

  write(ok, ms) {
    // 带时间戳：卡顿记录必须能和网关的 upstream-latency.log、supervisor 的
    // probe-latency.csv 按时间对齐，否则只能靠“大概那时候”猜。
    const at = new Date(this.now()).toISOString();
    const head = ok
      ? `[stall] ${at} pi-web 响应 ${ms}ms（60s 内峰值 ${this.peakMs}ms，第 ${this.stallCount} 次）`
      : `[stall] ${at} pi-web 无响应（探活失败，60s 内峰值 ${this.peakMs}ms，第 ${this.stallCount} 次）`;
    this.log(head);
    try {
      const info = this.diagnostics();
      if (typeof info === 'string') {
        if (info) this.log(`[stall] 现场: ${info}`);
      } else if (info && typeof info.then === 'function') {
        info.then((text) => text && this.log(`[stall] 现场: ${text}`)).catch(() => {});
      }
    } catch (e) {
      /* 取证失败不许影响 pi-web 本身 */
    }
    const tail = this.lastLines();
    this.log(`[stall] pi-web 最后 ${tail.length} 行: ${tail.length ? tail.join(' | ') : '（无输出）'}`);
  }
}

module.exports = { StallRecorder, STALL_THRESHOLD_MS, STALL_RECORD_WINDOW_MS };
