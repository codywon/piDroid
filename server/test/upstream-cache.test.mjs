// /api/git/status 缓存（server/upstream-cache.js）—— 注入时钟与上游，不启服务。
//
// 为什么值得测：这层缓存的全部价值是「把每 N 秒一次的 5 秒阻塞变成每 TTL 一次」，
// 判错就两头空——要么照旧每次轮询都阻塞（等于没做），要么把 cwd 串了/把错误缓存住。
import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { UpstreamCache, DEFAULT_TTL_MS } = require("../upstream-cache.js");

assert.ok(DEFAULT_TTL_MS >= 60_000, "TTL must be long enough to cut the 5s rescan away from every poll");

function makeCache({ ttlMs = 120_000, responses = [] } = {}) {
  const calls = [];
  const clock = { now: 1_000_000 };
  const cache = new UpstreamCache({
    ttlMs,
    now: () => clock.now,
    fetchStatus: async (cwd) => {
      calls.push({ cwd, at: clock.now });
      const next = responses.shift();
      return next || { status: 200, contentType: "application/json", body: `{"cwd":"${cwd}","v":${calls.length}}` };
    },
  });
  return { cache, calls, clock };
}

test("serves a cached body without touching the upstream again inside the TTL", async () => {
  const { cache, calls } = makeCache();
  const first = await cache.get("D:\\myscript");
  assert.equal(first.source, "miss");
  const second = await cache.get("D:\\myscript");
  assert.equal(second.source, "fresh");
  assert.equal(second.response, first.response);
  assert.equal(calls.length, 1, "a repeat poll must not rescan the repo");
});

test("after the TTL it answers with the stale body and refreshes in the background once", async () => {
  // 真实场景里重扫要 5 秒：用一个手动 resolve 的 promise 模拟，否则测不到「正在刷新的窗口」。
  const calls = [];
  const clock = { now: 1_000_000 };
  let resolveRefresh;
  let first = true;
  const cache = new UpstreamCache({
    ttlMs: 1000,
    now: () => clock.now,
    fetchStatus: (cwd) => {
      calls.push(cwd);
      if (first) {
        first = false;
        return Promise.resolve({ status: 200, body: "first" });
      }
      return new Promise((resolve) => {
        resolveRefresh = () => resolve({ status: 200, body: "second" });
      });
    },
  });

  await cache.get("D:\\myscript");
  clock.now += 1001;

  const stale = await cache.get("D:\\myscript");
  assert.equal(stale.source, "stale");
  assert.equal(stale.response.body, "first", "the client must not wait for the rescan");

  // 重扫期间的并发轮询不能各自再触发一次重扫（阻塞叠加）。
  const alsoStale = await cache.get("D:\\myscript");
  assert.equal(alsoStale.source, "stale");
  assert.equal(calls.length, 2, "exactly one background rescan in flight");

  resolveRefresh();
  await new Promise((r) => setTimeout(r, 10));
  const after = await cache.get("D:\\myscript");
  assert.equal(after.source, "fresh");
  assert.equal(after.response.body, "second");
});

test("concurrent misses share a single upstream call", async () => {
  const { cache, calls } = makeCache();
  const [a, b, c] = await Promise.all([cache.get("D:\\x"), cache.get("D:\\x"), cache.get("D:\\x")]);
  assert.deepEqual([a.source, b.source, c.source], ["miss", "miss", "miss"]);
  assert.equal(calls.length, 1);
  assert.equal(a.response, b.response);
});

test("keys stay separate per cwd, so one slow repo cannot poison another", async () => {
  const { cache, calls } = makeCache();
  await cache.get("D:\\big");
  const other = await cache.get("C:\\Users\\29092");
  assert.equal(other.source, "miss");
  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map((c) => c.cwd), ["D:\\big", "C:\\Users\\29092"]);
});

test("never caches a failed upstream response", async () => {
  const { cache, calls } = makeCache({ responses: [{ status: 502, body: "bad gateway" }] });
  const failed = await cache.get("D:\\x");
  assert.equal(failed.response.status, 502);
  const retry = await cache.get("D:\\x");
  assert.equal(retry.source, "miss", "a 502 must not be served for the next two minutes");
  assert.equal(calls.length, 2);
});
