import assert from "node:assert/strict";
import test from "node:test";

const recovery = (await import("../recovery-plan.js")).default;
const { PIWEB_ONLY_RESTARTS_BEFORE_FULL, planRecovery, shouldIgnoreDownProbe } = recovery;

test("recycles only the pi-web subtree while the gateway still answers", () => {
  // 2026-11-11 那次：pi-web 事件循环卡死、网关 200 —— 整栈重来白丢 2 分半。
  assert.equal(planRecovery({ gatewayOk: true, piwebOnlyRestarts: 0 }), "piweb");
  assert.equal(planRecovery({ gatewayOk: true, piwebOnlyRestarts: 1 }), "piweb");
});

test("escalates to a whole-stack recycle once the pi-web-only retries are used up", () => {
  assert.equal(
    planRecovery({ gatewayOk: true, piwebOnlyRestarts: PIWEB_ONLY_RESTARTS_BEFORE_FULL }),
    "stack",
  );
});

test("recycles the whole stack when the gateway is the one not answering", () => {
  // 网关和 pi-web 是同一份 launcher 拉起来的，网关都不答说明问题不只在 pi-web 那层。
  assert.equal(planRecovery({ gatewayOk: false, piwebOnlyRestarts: 0 }), "stack");
  assert.equal(planRecovery({ gatewayOk: false, piwebOnlyRestarts: 1 }), "stack");
});

test("ignores down probes while waiting on a restart backoff timer", () => {
  // 退避期间 this.child 为空且端口必不通，不能再累计失败误判为「无主孤儿栈」强行提前拉起。
  assert.equal(
    shouldIgnoreDownProbe({ hasRestartTimer: true, hasChild: false, childElapsedMs: 0, bootGraceMs: 120_000 }),
    true,
  );
  assert.equal(
    shouldIgnoreDownProbe({ hasRestartTimer: true, hasChild: true, childElapsedMs: 200_000, bootGraceMs: 120_000 }),
    true,
  );
});

test("ignores down probes during child cold-boot grace window", () => {
  assert.equal(
    shouldIgnoreDownProbe({ hasRestartTimer: false, hasChild: true, childElapsedMs: 119_999, bootGraceMs: 120_000 }),
    true,
  );
});

test("counts down probes when unmanaged or past cold-boot grace window", () => {
  assert.equal(
    shouldIgnoreDownProbe({ hasRestartTimer: false, hasChild: false, childElapsedMs: 0, bootGraceMs: 120_000 }),
    false,
  );
  assert.equal(
    shouldIgnoreDownProbe({ hasRestartTimer: false, hasChild: true, childElapsedMs: 120_000, bootGraceMs: 120_000 }),
    false,
  );
});
