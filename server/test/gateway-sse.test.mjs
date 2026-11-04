// SSE 空闲看门狗（server/gateway.js）—— 纯逻辑单测，不启服务、不碰真实 socket。
//
// 为什么值得测：这个看门狗的唯一职责是「没有消费者的长连接必须被断开」。
// 它要同时满足两件相反的事：有心跳就绝不能误杀（否则手机端正常读着也会被切断），
// 长时间零字节就必须杀（否则 pi-web 的事件队列会涨到 2 MB 把 Next.js 拖崩）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  SSE_HEARTBEAT_MS,
  SSE_IDLE_TIMEOUT_MS,
  isEventStreamResponse,
  guardEventStreamIdle,
} = require('../gateway.js');

function fakeStream() {
  const stream = new EventEmitter();
  stream.destroyed = false;
  stream.destroy = () => {
    stream.destroyed = true;
  };
  return stream;
}

test('idle timeout is a multiple of pi-web heartbeat (30s), not an isolated number', () => {
  assert.equal(SSE_HEARTBEAT_MS, 30_000);
  assert.equal(SSE_IDLE_TIMEOUT_MS % SSE_HEARTBEAT_MS, 0);
  assert.ok(SSE_IDLE_TIMEOUT_MS >= SSE_HEARTBEAT_MS * 2, 'must allow at least two heartbeats');
});

test('recognises event-stream content type only', () => {
  assert.equal(isEventStreamResponse({ 'content-type': 'text/event-stream' }), true);
  assert.equal(isEventStreamResponse({ 'content-type': 'text/event-stream; charset=utf-8' }), true);
  assert.equal(isEventStreamResponse({ 'content-type': 'application/json' }), false);
  assert.equal(isEventStreamResponse({}), false);
  assert.equal(isEventStreamResponse(null), false);
});

test('kills a stream that never produces a byte', async () => {
  const upstream = fakeStream();
  const downstream = fakeStream();
  guardEventStreamIdle(upstream, { method: 'GET', url: '/api/agent/x/events' }, downstream, 30);

  assert.equal(upstream.destroyed, false);
  assert.equal(downstream.destroyed, false);
  await new Promise((r) => setTimeout(r, 80));
  assert.equal(upstream.destroyed, true, 'upstream to pi-web must be released');
  assert.equal(downstream.destroyed, true, 'client side must not hang either');
});

test('heartbeats keep the stream alive', async () => {
  const upstream = fakeStream();
  const downstream = fakeStream();
  guardEventStreamIdle(upstream, { method: 'GET', url: '/api/agent/x/events' }, downstream, 60);

  // 每 25ms 一条心跳，总共 150ms：远超单次 idle 阈值，但不该被杀
  const heartbeat = setInterval(() => upstream.emit('data', 'data: {"type":"heartbeat"}\n\n'), 25);
  await new Promise((r) => setTimeout(r, 150));
  clearInterval(heartbeat);

  assert.equal(upstream.destroyed, false);
  assert.equal(downstream.destroyed, false);
});

test('a clean end disarms the watchdog', async () => {
  const upstream = fakeStream();
  const downstream = fakeStream();
  guardEventStreamIdle(upstream, { method: 'GET', url: '/api/agent/x/events' }, downstream, 30);

  upstream.emit('end');
  await new Promise((r) => setTimeout(r, 80));
  assert.equal(upstream.destroyed, false, 'a finished stream must not be destroyed again');
  assert.equal(downstream.destroyed, false);
});

test('client closing disarms the watchdog instead of double-killing', async () => {
  const upstream = fakeStream();
  const downstream = fakeStream();
  guardEventStreamIdle(upstream, { method: 'GET', url: '/api/agent/x/events' }, downstream, 30);

  downstream.emit('close');
  await new Promise((r) => setTimeout(r, 80));
  assert.equal(upstream.destroyed, false);
});
