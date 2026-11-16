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
const { UPSTREAM_TTFB_DEADLINE_MS, UPSTREAM_SLOW_MS, LONG_UPSTREAM_TTFB_DEADLINE_MS, UPLOAD_TTFB_DEADLINE_MS, resolveUpstreamDeadlineMs, armUpstreamDeadline } = require('../gateway.js');

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

// pi-web 0.17.0 把上传放开到「单次合计 100MB」，单文件不再限 25MB。
// 上传的响应头要等整个 body 送到才有，所以截止线必须按手机上行带宽算，
// 而不是按「服务端多久算完」算 —— 否则大文件永远传不完就被判死。
test('an upload gets the upload window, not the compile/export window', () => {
  const upload = { method: 'POST', url: '/api/files/D%3A%5Cwork%2Frepo?type=upload&conflict=overwrite' };
  assert.equal(resolveUpstreamDeadlineMs(upload), UPLOAD_TTFB_DEADLINE_MS);

  const preview = { method: 'GET', url: '/api/files/x.pdf?type=preview' };
  const exportRoute = { method: 'POST', url: '/api/sessions/x/export' };
  const ordinary = { method: 'GET', url: '/api/sessions' };

  assert.equal(resolveUpstreamDeadlineMs(preview), LONG_UPSTREAM_TTFB_DEADLINE_MS);
  assert.equal(resolveUpstreamDeadlineMs(exportRoute), LONG_UPSTREAM_TTFB_DEADLINE_MS);
  // 普通接口仍然守着 5s：卡死的上游必须立刻暴露，不能跟着上传一起变宽松。
  assert.equal(resolveUpstreamDeadlineMs(ordinary), UPSTREAM_TTFB_DEADLINE_MS);
});

// `POST /api/agent/<id>` 同时挂着 prompt / get_state / compact：命令类型在 body 里，
// 网关流式转发读不到。所以只认调用方在 URL 上标的 `ttfb=long`（App 只给 compact 带）。
test('a marked slow command gets the long window; an unmarked one keeps 5s', () => {
  const compact = { method: 'POST', url: '/api/agent/abc?ttfb=long' };
  const prompt = { method: 'POST', url: '/api/agent/abc' };

  assert.equal(resolveUpstreamDeadlineMs(compact), LONG_UPSTREAM_TTFB_DEADLINE_MS);
  // 关键性质：放宽只针对被标注的那一个请求，提示词发送仍然 5s 内失败。
  assert.equal(resolveUpstreamDeadlineMs(prompt), UPSTREAM_TTFB_DEADLINE_MS);
});

test('the upload window covers a 100MB upload on a slow mobile uplink', () => {
  // 100MB 在 200KB/s（弱网 4G 的常见上行）需要 512s，必须仍然小于截止线。
  const hundredMbAt200KbPerSecond = (100 * 1024 * 1024) / (200 * 1024);
  assert.ok(
    UPLOAD_TTFB_DEADLINE_MS / 1000 > hundredMbAt200KbPerSecond,
    `upload deadline ${UPLOAD_TTFB_DEADLINE_MS}ms must exceed ${Math.round(hundredMbAt200KbPerSecond)}s`
  );
});
