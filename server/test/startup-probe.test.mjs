import assert from "node:assert/strict";
import test from "node:test";
import http from "node:http";
import net from "node:net";
import { EventEmitter } from "node:events";

const probe = (await import("../startup-probe.js")).default;
const { checkUrlAlive, isPortListening, probeWithRetry, waitForReady, waitForReadyOrExit } = probe;

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve) => server.close(() => resolve()));
}

test("isPortListening distinguishes a listening port from a closed one", async () => {
  const server = net.createServer();
  const port = await listen(server);
  try {
    assert.equal(await isPortListening(port), true);
  } finally {
    await close(server);
  }
  // 关闭后同一端口不应再报告在听（TIME_WAIT 不响应握手）
  assert.equal(await isPortListening(port, "127.0.0.1", 300), false);
  assert.equal(await isPortListening(0), false);
});

test("checkUrlAlive treats 2xx as alive and 5xx as dead", async () => {
  let status = 200;
  const server = http.createServer((req, res) => {
    res.statusCode = status;
    res.end("ok");
  });
  const port = await listen(server);
  try {
    assert.equal(await checkUrlAlive(`http://127.0.0.1:${port}`, 1500), true);
    status = 500;
    assert.equal(await checkUrlAlive(`http://127.0.0.1:${port}`, 1500), false);
  } finally {
    await close(server);
  }
  assert.equal(await checkUrlAlive(`http://127.0.0.1:${port}`, 500), false);
});

test("probeWithRetry stops at the first success and gives up after attempts", async () => {
  let calls = 0;
  const secondTime = async () => {
    calls += 1;
    return calls >= 2;
  };
  assert.equal(await probeWithRetry(secondTime, { attempts: 3, delayMs: 1 }), true);
  assert.equal(calls, 2);

  let failures = 0;
  const alwaysFalse = async () => {
    failures += 1;
    return false;
  };
  assert.equal(await probeWithRetry(alwaysFalse, { attempts: 3, delayMs: 1 }), false);
  assert.equal(failures, 3);
});

test("waitForReady polls until the service answers or the deadline passes", async () => {
  let calls = 0;
  const readyOnSecond = async () => {
    calls += 1;
    return calls >= 2;
  };
  assert.equal(
    await waitForReady("http://127.0.0.1:1", 2000, { pollMs: 1, check: readyOnSecond }),
    true
  );

  assert.equal(
    await waitForReady("http://127.0.0.1:1", 20, { pollMs: 1, check: async () => false }),
    false
  );
});

test("waitForReadyOrExit returns as soon as the child exits", async () => {
  const child = new EventEmitter();
  setTimeout(() => child.emit("exit", 1, null), 5);

  // 检查函数永远为 false：唯一的出路是子进程 exit，而不是等满 maxWaitMs。
  const startedAt = Date.now();
  const outcome = await waitForReadyOrExit("http://127.0.0.1:1", child, {
    maxWaitMs: 20000,
    pollMs: 5,
    check: async () => false,
  });
  assert.deepEqual(outcome, { ready: false, exited: true, code: 1, signal: null });
  assert.ok(Date.now() - startedAt < 2000, "must not wait for the full timeout");
});

test("waitForReadyOrExit reports readiness for a live child and tolerates no child", async () => {
  const child = new EventEmitter();
  const outcome = await waitForReadyOrExit("http://127.0.0.1:1", child, {
    maxWaitMs: 2000,
    pollMs: 1,
    check: async () => true,
  });
  assert.deepEqual(outcome, { ready: true, exited: false });

  const noChild = await waitForReadyOrExit("http://127.0.0.1:1", null, {
    maxWaitMs: 2000,
    pollMs: 1,
    check: async () => true,
  });
  assert.deepEqual(noChild, { ready: true, exited: false });
});
