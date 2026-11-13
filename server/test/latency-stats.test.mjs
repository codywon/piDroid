import assert from "node:assert/strict";
import test from "node:test";

const stats = (await import("../latency-stats.js")).default;
const { percentile, summarize } = stats;

test("a single 3.8s stall hides in p50 but shows up in p99", () => {
  // 2026-11-11 实测形态：正常 2–3ms，每隔一阵整段阻塞 3–4s。
  const samples = [...Array(49).fill(3), 3800];
  const summary = summarize(samples, 2000);
  assert.equal(summary.count, 50);
  assert.equal(summary.p50, 3);
  assert.equal(summary.p99, 3800);
  assert.equal(summary.max, 3800);
  assert.equal(summary.slow, 1);
});

test("percentile uses nearest-rank instead of inventing values between samples", () => {
  assert.equal(percentile([10, 20], 50), 10);
  assert.equal(percentile([10, 20], 99), 20);
  assert.equal(percentile([7], 99), 7);
});

test("summarize reports zeros for an empty window instead of NaN", () => {
  assert.deepEqual(summarize([], 2000), { count: 0, p50: 0, p95: 0, p99: 0, max: 0, slow: 0 });
  assert.equal(percentile([], 99), 0);
});

test("slow counts strictly over the threshold, not at it", () => {
  assert.equal(summarize([1999, 2000, 2001], 2000).slow, 1);
});
