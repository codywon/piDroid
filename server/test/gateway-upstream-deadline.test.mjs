// 上游「响应头截止时间」（server/gateway.js）—— 假 req/res/proxyReq，不启服务。
//
// 为什么值得测：pi-web 卡死时现场是「手机端点一下就没反应，一直转圈」。
// 这条 5s 截止线是唯一让客户端能立刻拿到明确错误去重试的地方，而它必须同时满足：
// 卡死时必须真的放弃上游并回 503 + Retry-After；一旦响应已经开始（SSE 已冲出头部、
// 或普通响应已经写出）就绝不能再去写 503，否则会把正常的流打断成半截报文。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { UPSTREAM_TTFB_DEADLINE_MS, UPSTREAM_SLOW_MS, armUpstreamDeadline } = require('../gateway.js');

function fakeRes({ headersSent = false } = {}) {
  const res = {
    headersSent,
    writableEnded: false,
    statusCode: 200,
    headers: {},
    body: null,
    status(code) {
      res.statusCode = code;
      return res;
    },
    set(name, value) {
      res.headers[name] = value;
      return res;
    },
    json(payload) {
      res.body = payload;
      res.writableEnded = true;
      return res;
    },
  };
  return res;
}

function fakeProxyReq() {
  return {
    destroyed: false,
    destroy() {
      this.destroyed = true;
    },
  };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('gives up on a stalled upstream and answers 503 with Retry-After', async () => {
  const res = fakeRes();
  const upstream = fakeProxyReq();
  armUpstreamDeadline(upstream, { method: 'GET', url: '/api/sessions' }, res, 20);
  assert.equal(res.body, null, 'must not answer before the deadline elapses');
  await sleep(60);
  assert.equal(upstream.destroyed, true, 'the stalled upstream request must be abandoned');
  assert.equal(res.statusCode, 503);
  assert.equal(res.headers['Retry-After'], '5');
  assert.match(res.body.message, /秒内没有响应/);
});

test('never writes 503 over a response that already started', async () => {
  // 响应头已发出（SSE 心跳正在流、或普通响应写了一半）：此时再写 503 会打断报文。
  const res = fakeRes({ headersSent: true });
  const upstream = fakeProxyReq();
  armUpstreamDeadline(upstream, { method: 'GET', url: '/api/agent/x/events' }, res, 20);
  await sleep(60);
  assert.equal(res.body, null);
  assert.equal(res.statusCode, 200);
  assert.equal(upstream.destroyed, false);
});

test('thresholds stay in the range the measured stalls need', () => {
  // 实测阻塞 3~4s、最长 >60s：截止线必须明显大于正常请求（2~3ms）又小于一次整段卡死。
  assert.equal(UPSTREAM_TTFB_DEADLINE_MS, 5000);
  assert.equal(UPSTREAM_SLOW_MS, 2000);
});
