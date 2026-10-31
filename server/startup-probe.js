/**
 * 启动期探测工具（纯 IO，可单测）。
 *
 * 抽出来的原因是一个线上踩到的竞态：`main()` 只用一次 1 秒的 HTTP 探测判断
 * "pi-web 是否已在运行"。Next.js 首次编译时 `/` 可能要 1 秒以上才应答，于是
 * 探测失败 → 又拉起一份 pi-web → 第二份必然 `EADDRINUSE 30141` 退出，日志里
 * 留下一串吓人的 "Next.js exited unexpectedly"。
 *
 * 现在的策略分三层，逐层收紧：
 *   1. [isPortListening] 先看 30141 端口有没有人在听 —— TCP 握手不会因为应用忙而超时，
 *      这是"有没有实例"最可靠的信号；
 *   2. [probeWithRetry] HTTP 探测失败时重试几次，覆盖瞬时忙；
 *   3. [waitForReadyOrExit] 等自己拉起的子进程时同时盯着 exit，子进程一死就不再空等 25 秒，
 *      交给调用方判断"是不是别的实例已经占了端口"。
 */
const http = require('http');
const net = require('net');

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * 探测 HTTP 服务是否存活：只看"有没有人应答"，500 以上视为不可用。
 * @param {string} targetUrl
 * @param {number} [timeoutMs]
 * @returns {Promise<boolean>}
 */
function checkUrlAlive(targetUrl, timeoutMs = 800) {
  return new Promise((resolve) => {
    try {
      const parsed = new URL(targetUrl);
      const req = http.request(
        {
          hostname: parsed.hostname,
          port: parsed.port,
          path: '/',
          method: 'GET',
          timeout: timeoutMs,
        },
        (res) => {
          res.resume();
          resolve(res.statusCode < 500);
        }
      );
      req.on('error', () => resolve(false));
      req.on('timeout', () => {
        req.destroy();
        resolve(false);
      });
      req.end();
    } catch (e) {
      resolve(false);
    }
  });
}

/**
 * 端口是否已被监听（TCP 层，不依赖应用是否已完成编译）。
 * 只判定"有人在听"，不关心对端是什么服务。
 */
function isPortListening(port, host = '127.0.0.1', timeoutMs = 600) {
  return new Promise((resolve) => {
    if (!Number.isInteger(port) || port <= 0) {
      resolve(false);
      return;
    }
    let settled = false;
    const socket = net.connect({ port, host });
    const done = (ok) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => done(true));
    socket.once('timeout', () => done(false));
    socket.once('error', () => done(false));
  });
}

/**
 * 反复执行一个返回 Promise<boolean> 的探测，任意一次成功即返回 true。
 * @param {() => Promise<boolean>} check
 * @param {{ attempts?: number, delayMs?: number }} [options]
 */
async function probeWithRetry(check, { attempts = 3, delayMs = 600 } = {}) {
  const total = Math.max(1, Number(attempts) || 1);
  for (let i = 0; i < total; i += 1) {
    if (await check()) return true;
    if (i < total - 1 && delayMs > 0) await delay(delayMs);
  }
  return false;
}

/**
 * 轮询等待服务就绪。
 *
 * @param {string} url
 * @param {number} [maxWaitMs]
 * @param {{ pollMs?: number, check?: (url: string, timeoutMs?: number) => Promise<boolean>, shouldStop?: () => boolean }} [options]
 */
async function waitForReady(
  url,
  maxWaitMs = 25000,
  { pollMs = 600, check = checkUrlAlive, shouldStop } = {}
) {
  const deadline = Date.now() + Math.max(0, Number(maxWaitMs) || 0);
  for (;;) {
    if (typeof shouldStop === 'function' && shouldStop()) return false;
    if (await check(url, 1000)) return true;
    if (Date.now() >= deadline) return false;
    await delay(pollMs);
  }
}

/**
 * 等"就绪"或"子进程先退出"，谁先到算谁。
 *
 * 旧实现的 `waitForReady` 不看子进程：pi-web 因为端口被占而秒退时，启动器还要
 * 空转满 25 秒才继续。返回值让调用方区分三种结局：
 *   - `{ ready: true }`            服务已就绪；
 *   - `{ ready: false, exited: true, code }`  自己拉起的那份先死了（可能是重复实例）；
 *   - `{ ready: false, exited: false }`       超时，进程还活着（启动慢）。
 */
async function waitForReadyOrExit(url, child, options = {}) {
  const { maxWaitMs = 25000, ...rest } = options;
  if (!child || typeof child.once !== 'function') {
    return { ready: await waitForReady(url, maxWaitMs, rest), exited: false };
  }

  let settled = false;
  let cancelled = false;

  const exitPromise = new Promise((resolve) => {
    child.once('exit', (code, signal) => {
      if (settled) return;
      settled = true;
      cancelled = true;
      resolve({ ready: false, exited: true, code, signal });
    });
  });

  const readyPromise = waitForReady(url, maxWaitMs, {
    ...rest,
    shouldStop: () => cancelled,
  }).then((ready) => {
    if (!settled) {
      settled = true;
      cancelled = true;
    }
    return { ready, exited: false };
  });

  return Promise.race([exitPromise, readyPromise]);
}

module.exports = {
  checkUrlAlive,
  isPortListening,
  probeWithRetry,
  waitForReady,
  waitForReadyOrExit,
};
