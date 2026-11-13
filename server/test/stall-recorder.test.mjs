// 卡顿现场记录器（server/stall-recorder.js）—— 纯逻辑，注入时钟与输出，不启服务。
//
// 为什么值得测：它唯一的产出就是「卡顿那 3 秒里发生了什么」，
// 判错窗口就会把一次 4 秒阻塞写成 6 行日志，或者把 60s 内的峰值丢掉。
import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { StallRecorder, STALL_THRESHOLD_MS } = require("../stall-recorder.js");

function makeRecorder({ clock = { now: 0 }, diagnostics = () => "rss=547MB cpu=35%" } = {}) {
  const out = [];
  const recorder = new StallRecorder({
    log: (line) => out.push(line),
    diagnostics,
    now: () => clock.now,
    thresholdMs: 1000,
    windowMs: 60_000,
  });
  return { recorder, out, clock };
}

test("ignores healthy responses so the steady state does not flood the log", () => {
  const { recorder, out } = makeRecorder();
  assert.equal(recorder.observe({ ok: true, ms: 3 }), false);
  assert.equal(recorder.observe({ ok: true, ms: 98 }), false);
  assert.equal(out.length, 0);
});

test("records a stall with cpu/rss snapshot and the tail of pi-web output", () => {
  const { recorder, out } = makeRecorder();
  recorder.noteLine("✓ Ready in 121ms\n[pi-web] session_start dispatched to extensions");
  const recorded = recorder.observe({ ok: true, ms: 3150 });
  assert.equal(recorded, true);
  assert.match(out[0], /3150ms/);
  assert.match(out[1], /现场: rss=547MB cpu=35%/);
  assert.match(out[2], /Ready in 121ms \| \[pi-web\] session_start/);
});

test("treats a failed probe as a stall even when it reports no latency", () => {
  const { recorder, out } = makeRecorder();
  assert.equal(recorder.observe({ ok: false, ms: 2000 }), true);
  assert.match(out[0], /无响应/);
});

test("one record per window, but the peak keeps climbing", () => {
  const { recorder, out, clock } = makeRecorder();
  assert.equal(recorder.observe({ ok: true, ms: 3100 }), true);
  clock.now = 20_000;
  assert.equal(recorder.observe({ ok: true, ms: 4609 }), false, "same window stays quiet");
  assert.equal(recorder.peakMs, 4609, "peak must include the record it suppressed");
  clock.now = 61_000;
  assert.equal(recorder.observe({ ok: true, ms: 2000 }), true, "next window records again");
  assert.equal(out.filter((line) => /\[stall\] \S+ pi-web 响应/.test(line)).length, 2);
});

test("keeps only the last few output lines so the evidence stays readable", () => {
  const { recorder } = makeRecorder();
  for (let i = 1; i <= 20; i += 1) recorder.noteLine(`line ${i}`);
  const tail = recorder.lastLines();
  assert.equal(tail.length, 6);
  assert.equal(tail.at(-1), "line 20");
  assert.ok(!tail.includes("line 14"));
});

test("a broken diagnostics probe never breaks the recorder", () => {
  const { recorder, out } = makeRecorder({
    diagnostics: () => {
      throw new Error("powershell 挂了");
    },
  });
  assert.equal(recorder.observe({ ok: true, ms: 5000 }), true);
  assert.match(out.at(-1), /最后 0 行/);
  assert.ok(STALL_THRESHOLD_MS === 1000, "threshold must stay in the 3~4s-stall range");
});
