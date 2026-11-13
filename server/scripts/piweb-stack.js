#!/usr/bin/env node
// 抓 pi-web 卡住那一刻的 JS 调用栈 / CPU 采样。
//
// 为什么必须用 Profiler 而不是 Debugger.pause：实测卡顿时 CPU 可能只有 0~6%、线程
// 全在 Wait/Unknown。主线程被同步阻塞时，inspector 的 pause 请求根本无法被处理
// （调试协议也跑在那个线程上），只有 V8 的采样剖析器能记录阻塞期间的栈。
//
// 为什么分段采样：pi-web 被看门狗回收时 inspector 连接会断，一次性采 15 分钟等于
// 把前面的样本全部丢掉（2026-11-12 09:35 那次就是这么丢的）。所以每 chunk 秒
// stop 一次、落盘、再 start，断了就重连继续。
//
// 前提：pi-web 以 inspector 模式启动（launcher.js 里 PIWEB_DEBUG_STACK=1）。
//
// 用法：
//   node scripts/piweb-stack.js --pause                   暂停一次，打印当前栈（仅空闲时有意义）
//   node scripts/piweb-stack.js --profile=900 --chunk=30  采样 15 分钟，每 30 秒落一段
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');

const argOf = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.split('=')[1] : fallback;
};
const port = Number(argOf('port', 9229));
const profileSeconds = Number(argOf('profile', 0));
const chunkSeconds = Number(argOf('chunk', 30));
const pauseOnce = process.argv.includes('--pause');

function listTargets() {
  return new Promise((resolve, reject) => {
    http
      .get({ host: '127.0.0.1', port, path: '/json/list', timeout: 4000 }, (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(body));
          } catch {
            reject(new Error(`调试端口 ${port} 返回的不是 JSON`));
          }
        });
      })
      .on('error', (e) => reject(new Error(`连不上 127.0.0.1:${port}：${e.message}（pi-web 是不是没以 inspector 模式启动？）`)))
      .on('timeout', function () {
        this.destroy();
        reject(new Error(`调试端口 ${port} 超时`));
      });
  });
}

function stripFileUrlPrefix(rawUrl) {
  return String(rawUrl || '')
    .replace(/^file:\/\/\/([A-Za-z]:)/, '$1')
    .replace(/^file:\/\//, '');
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let nextId = 1;
    const pending = new Map();
    const events = new Map();
    let opened = false;

    const failPending = (reason) => {
      for (const { rej } of pending.values()) {
        rej(new Error(reason));
      }
      pending.clear();
    };

    ws.addEventListener('open', () => {
      opened = true;
      resolve({
        on: (method, handler) => events.set(method, handler),
        send: (method, params = {}) =>
          new Promise((res, rej) => {
            if (ws.readyState !== WebSocket.OPEN) {
              rej(new Error('inspector WebSocket 已关闭'));
              return;
            }
            const id = nextId++;
            pending.set(id, { res, rej });
            try {
              ws.send(JSON.stringify({ id, method, params }));
            } catch (err) {
              pending.delete(id);
              rej(err);
            }
          }),
        close: () => {
          failPending('inspector WebSocket 已主动关闭');
          try {
            ws.close();
          } catch {}
        },
      });
    });
    ws.addEventListener('message', (event) => {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }
      if (msg.id && pending.has(msg.id)) {
        const { res, rej } = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) rej(new Error(msg.error.message));
        else res(msg.result);
        return;
      }
      const handler = events.get(msg.method);
      if (handler) handler(msg.params);
    });
    ws.addEventListener('close', () => {
      const errMsg = 'inspector WebSocket 已断开';
      failPending(errMsg);
      if (!opened) reject(new Error(errMsg));
    });
    ws.addEventListener('error', () => {
      failPending('inspector WebSocket 出错');
      if (!opened) reject(new Error('inspector WebSocket 出错'));
    });
  });
}

/** 把 profile 聚合成「按自耗时排序的函数列表」。 */
function summarizeProfile(profile) {
  const nodes = new Map();
  for (const node of profile.nodes) nodes.set(node.id, node);
  const selfByNode = new Map();
  const { samples = [], timeDeltas = [] } = profile;
  for (let i = 0; i < samples.length; i += 1) {
    const delta = timeDeltas[i] || 0;
    selfByNode.set(samples[i], (selfByNode.get(samples[i]) || 0) + delta);
  }
  const rows = [];
  for (const [id, micros] of selfByNode) {
    const node = nodes.get(id);
    if (!node) continue;
    const frame = node.callFrame || {};
    rows.push({
      ms: micros / 1000,
      fn: frame.functionName || '(anonymous)',
      url: stripFileUrlPrefix(frame.url || ''),
      line: (frame.lineNumber ?? -1) + 1,
    });
  }
  rows.sort((a, b) => b.ms - a.ms);
  const total = rows.reduce((sum, r) => sum + r.ms, 0) || 1;
  return { rows, totalMs: total, sampleCount: samples.length };
}

async function openTarget() {
  const targets = await listTargets();
  const target = targets.find((t) => t.webSocketDebuggerUrl);
  if (!target) throw new Error(`调试端口 ${port} 上没有可连接目标`);
  const client = await connect(target.webSocketDebuggerUrl);
  return { client, title: target.title || '(no title)' };
}

async function main() {
  let { client, title } = await openTarget();
  console.log(`# 目标: ${title}`);

  if (pauseOnce) {
    client.on('Debugger.paused', (params) => {
      const frames = (params.callFrames || []).slice(0, 15).map((f, i) => {
        const loc = f.url ? `${stripFileUrlPrefix(f.url)}:${f.location.lineNumber + 1}` : '(native)';
        return `  ${i === 0 ? '→' : ' '} ${f.functionName || '(anonymous)'} ${loc}`;
      });
      console.log(frames.join('\n'));
      client.send('Debugger.resume').then(() => client.close()).catch(() => client.close());
    });
    await client.send('Debugger.enable');
    await client.send('Debugger.pause');
    return;
  }

  if (!profileSeconds) {
    console.log('用法: --pause | --profile=<秒数> [--chunk=30] [--port=9229]');
    client.close();
    return;
  }

  const outDir = path.join(__dirname, '..', 'logs');
  fs.mkdirSync(outDir, { recursive: true });
  const summaryFile = path.join(outDir, 'piweb-profile-summary.txt');
  const end = Date.now() + profileSeconds * 1000;
  let chunk = 0;

  while (Date.now() < end) {
    chunk += 1;
    try {
      await client.send('Profiler.enable');
      await client.send('Profiler.setSamplingInterval', { interval: 500 });
      await client.send('Profiler.start');
      await new Promise((r) => setTimeout(r, Math.min(chunkSeconds * 1000, Math.max(1000, end - Date.now()))));
      const { profile } = await client.send('Profiler.stop');
      const { rows, totalMs, sampleCount } = summarizeProfile(profile);
      const rawFile = path.join(outDir, `piweb-cpuprofile-chunk${String(chunk).padStart(2, '0')}.json`);
      fs.writeFileSync(rawFile, JSON.stringify(profile));
      const idle = rows.find((r) => r.fn === '(idle)') || { ms: 0 };
      const busyMs = Math.max(0, totalMs - idle.ms);
      const header =
        `--- chunk ${chunk} @ ${new Date().toISOString()} samples=${sampleCount} ` +
        `total=${Math.round(totalMs)}ms busy=${Math.round(busyMs)}ms (${((busyMs / totalMs) * 100).toFixed(1)}%) raw=${path.basename(rawFile)}`;
      const body = rows
        .filter((r) => r.fn !== '(idle)')
        .slice(0, 12)
        .map(
          (r) =>
            `    ${String(Math.round(r.ms)).padStart(6)}ms ${((r.ms / totalMs) * 100)
              .toFixed(1)
              .padStart(5)}%  ${r.fn}  ${r.url}:${r.line}`
        );
      const text = [header, ...body].join('\n');
      fs.appendFileSync(summaryFile, text + '\n');
      console.log(text);
    } catch (e) {
      console.log(`[chunk ${chunk}] 中断：${e.message}（重连后继续）`);
      try {
        client.close();
      } catch {}
      await new Promise((r) => setTimeout(r, 2000));
      try {
        const reopened = await openTarget();
        client = reopened.client;
      } catch (e2) {
        console.log(`[chunk ${chunk}] 重连失败：${e2.message}，5s 后重试`);
        await new Promise((r) => setTimeout(r, 5000));
      }
    }
  }
  console.log(`# 汇总已追加到 ${summaryFile}`);
  try {
    client.close();
  } catch {}
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
