// 上游慢接口的网关侧缓存（stale-while-revalidate + 单飞），按 cwd 为键。
//
// 为什么需要：pi-web 里有几条路由是**同步**遍历文件系统的，实测（2026-11-12/13）：
//   /api/git/status?cwd=D:\myscript    → 4.8 秒
//   /api/file-index?cwd=D:\myscript    → **29 秒**（返回 272 KB），直接把探针打到判死
// 期间 pi-web 整个不应答，而 CPU 只有 0~6%——时间花在 lstat/open/read 的元数据等待上。
// 这就是查了很久的「没 CPU 却卡 1~30 秒」。
//
// 我们不改 pi-web（零侵入），所以在网关这一层挡：
//   * 按 cwd 缓存响应，TTL 按「重建一次多贵」定；
//   * 过期后**立刻返回旧值**，后台刷新一次（stale-while-revalidate），客户端不等；
//   * 同一个 cwd 同时只允许一个上游请求（单飞），避免并发重扫把阻塞叠加成几十秒。
// 代价：数据最多旧一个 TTL；换来的是「每次轮询都触发 5~29 秒阻塞」变成「每 TTL 最多一次」。

'use strict';

const DEFAULT_TTL_MS = 120_000;
const DEFAULT_MAX_ENTRIES = 64;

class UpstreamCache {
  /**
   * @param {object} options
   * @param {(cwd: string) => Promise<{status:number, contentType?:string, body:Buffer|string}>} options.fetchStatus
   * @param {number} [options.ttlMs]
   * @param {number} [options.maxEntries]
   * @param {() => number} [options.now]
   */
  constructor({ fetchStatus, ttlMs = DEFAULT_TTL_MS, maxEntries = DEFAULT_MAX_ENTRIES, now = () => Date.now() } = {}) {
    this.fetchStatus = fetchStatus;
    this.ttlMs = ttlMs;
    this.maxEntries = maxEntries;
    this.now = now;
    this.entries = new Map();
    this.inflight = new Map();
  }

  /** 拉一次上游；同一 cwd 并发调用共享同一个 Promise（单飞）。 */
  refresh(cwd) {
    const existing = this.inflight.get(cwd);
    if (existing) return existing;
    const promise = (async () => {
      try {
        const response = await this.fetchStatus(cwd);
        // 只缓存成功结果：上游 5xx 不该被当成「正常状态」留 2 分钟。
        if (response && response.status === 200) {
          this.entries.delete(cwd);
          this.entries.set(cwd, { response, storedAt: this.now() });
          while (this.entries.size > this.maxEntries) {
            const oldestKey = this.entries.keys().next().value;
            if (oldestKey === undefined) break;
            this.entries.delete(oldestKey);
          }
        } else {
          // 上游返回错误时驱逐超期老缓存，避免长期返回失效状态
          this.entries.delete(cwd);
        }
        return response;
      } catch (err) {
        const old = this.entries.get(cwd);
        if (old && this.now() - old.storedAt > this.ttlMs * 3) {
          this.entries.delete(cwd);
        }
        throw err;
      } finally {
        this.inflight.delete(cwd);
      }
    })();
    this.inflight.set(cwd, promise);
    return promise;
  }

  /**
   * @param {string} cwd
   * @returns {Promise<{source:'fresh'|'stale'|'miss', response?:object}>}
   */
  async get(cwd) {
    const entry = this.entries.get(cwd);
    if (entry && this.now() - entry.storedAt < this.ttlMs) {
      return { source: 'fresh', response: entry.response };
    }
    if (entry) {
      // 后台刷新：故意不 await，客户端拿旧值先走。
      this.refresh(cwd).catch(() => {});
      return { source: 'stale', response: entry.response };
    }
    const response = await this.refresh(cwd);
    return { source: 'miss', response };
  }
}

module.exports = { UpstreamCache, DEFAULT_TTL_MS };
