import assert from "node:assert/strict";
import test from "node:test";

const recovery = (await import("../recovery-plan.js")).default;
const { PIWEB_ONLY_RESTARTS_BEFORE_FULL, planRecovery } = recovery;

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
