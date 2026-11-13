// 上游延迟的分位数统计。
//
// 单独成文件的原因和 startup-probe.js / recovery-plan.js 一致：网关与守护都要
// 回答「现在上游有多慢」，而这个问题的唯一正确答案是分位数——平均值会把一次
// 3.8s 的阻塞平摊成 90ms 的「正常」，正是我们花了半天才看清的陷阱。纯函数，
// 采集与写日志留在各自的进程里。

'use strict';

/**
 * 最近秩（nearest-rank）分位数。samples 必须已按升序排好。
 * 选它而不是线性插值：样本量小的时候插值会造出观察中并不存在的值，
 * 而我们要的恰恰是「最慢的那几次到底多慢」。
 */
function percentile(sortedSamples, p) {
  if (sortedSamples.length === 0) return 0;
  const rank = Math.ceil((p / 100) * sortedSamples.length);
  const index = Math.min(sortedSamples.length - 1, Math.max(0, rank - 1));
  return sortedSamples[index];
}

/**
 * @param {number[]} samples 毫秒样本
 * @param {number} slowMs 超过这个值算一次「慢请求」
 * @returns {{count:number,p50:number,p95:number,p99:number,max:number,slow:number}}
 */
function summarize(samples, slowMs) {
  if (samples.length === 0) return { count: 0, p50: 0, p95: 0, p99: 0, max: 0, slow: 0 };
  const sorted = [...samples].sort((a, b) => a - b);
  let slow = 0;
  for (const sample of samples) {
    if (sample > slowMs) slow += 1;
  }
  return {
    count: samples.length,
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1],
    slow
  };
}

module.exports = { percentile, summarize };
