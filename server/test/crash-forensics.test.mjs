// 崩溃取证（server/crash-forensics.js）—— 纯逻辑单测，不启服务、不读真实进程。
//
// 这个模块唯一的使命是「下次 pi-web 崩了，日志里得有证据可看」。
// 因此测试重点不在正常路径，而在：不掉行、不刷屏、不把 token 写进日志、异常输入不抛。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  CrashForensics,
  redactLine,
  parseWindowsTasklistCsv,
  parseProcessRows,
  sumProcessTreeMb,
  FORENSIC_TAIL_LINES
} = require('../crash-forensics.js');

test('ring buffer keeps only the last N lines', () => {
  const f = new CrashForensics({ tailLines: 3 });
  for (let i = 1; i <= 10; i++) f.recordLine(`line ${i}`);
  const text = f.report({ code: 1 }).join('\n');
  assert.ok(text.includes('line 10'));
  assert.ok(text.includes('line 8'));
  assert.equal(text.includes('line 7'), false, 'older lines must be dropped');
  assert.ok(FORENSIC_TAIL_LINES >= 10);
});

test('secrets never reach the log', () => {
  const f = new CrashForensics({ tailLines: 5 });
  f.recordLine('[config] auth.token = 3b9OG3lnPRxHpHzx');
  f.recordLine('[env] PI_WEB_PASSWORD=hunter2');
  f.recordLine('connect with Bearer pi_286a541b423d82902d608a2765819862');
  const text = f.report({ code: 1 }).join('\n');
  assert.equal(text.includes('3b9OG3lnPRxHpHzx'), false);
  assert.equal(text.includes('hunter2'), false);
  assert.equal(text.includes('pi_286a541b423d82902d608a2765819862'), false);
  assert.ok(text.includes('auth.token = ***'));
});

test('redactLine leaves ordinary text alone', () => {
  assert.equal(redactLine('✓ Ready in 176ms'), '✓ Ready in 176ms');
  assert.equal(redactLine('model=gpt-5.1'), 'model=gpt-5.1');
});

test('long lines are clipped, not dropped', () => {
  const f = new CrashForensics({ tailLines: 3, maxLineChars: 40 });
  f.recordLine('y'.repeat(500));
  const report = f.report({ code: 1 });
  const body = report.find((l) => l.startsWith('  | '));
  assert.ok(body, 'the line must still be present');
  assert.ok(body.length <= 60, `clipped body too long: ${body.length}`);
});

test('multi-line chunk becomes separate tail lines', () => {
  const f = new CrashForensics({ tailLines: 5 });
  f.recordLine('first\nsecond\nthird');
  const text = f.report({ code: 1 }).join('\n');
  for (const word of ['first', 'second', 'third']) assert.ok(text.includes(word));
});

test('report carries uptime, exit code and last memory reading', () => {
  const f = new CrashForensics({ tailLines: 5, now: () => 1_000 });
  f.markStarted();
  f.recordLine('ready');
  f.recordMemory(1536);
  const report = f.report({ code: 1, at: 61_000 }).join('\n');
  assert.ok(report.includes('code=1'));
  assert.ok(report.includes('60s'));
  assert.ok(report.includes('1536 MB'));
});

test('memory sampling is quiet in steady state, loud on a jump', () => {
  const f = new CrashForensics({ tailLines: 5, now: () => 1_000 });
  f.markStarted();
  assert.equal(f.recordMemory(400), true, 'first sample is always reported');
  let told = 0;
  for (let i = 1; i <= 20; i++) if (f.recordMemory(400 + i * 2)) told++;
  assert.ok(told <= 3, `too chatty: ${told}`);
  assert.equal(f.recordMemory(900), true, 'a doubling must be reported');
});

test('OOM hints are surfaced, not invented', () => {
  const quiet = new CrashForensics({ tailLines: 5 });
  quiet.recordLine('server ready');
  assert.equal(quiet.report({ code: 1 }).join('\n').includes('OOM'), false);

  const oom = new CrashForensics({ tailLines: 5 });
  oom.recordLine('FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory');
  assert.ok(oom.report({ code: 1 }).join('\n').includes('OOM'));
});

test('empty tail says so instead of printing a blank block', () => {
  const f = new CrashForensics({ tailLines: 5 });
  const text = f.report({ code: 1 }).join('\n');
  assert.ok(text.includes('没有打印任何东西'));
});

test('tasklist CSV parsing takes the fattest node and ignores headers', () => {
  assert.equal(parseWindowsTasklistCsv('"Image Name","Mem Usage"\r\n"node.exe","512,000 K"\r\n'), 500);
  assert.equal(parseWindowsTasklistCsv('"node.exe","100 K"\r\n"node.exe","2,048,000 K"\r\n'), 2000);
  assert.equal(parseWindowsTasklistCsv('"node.exe","1,048,576 K"\r\n'), 1024);
  assert.equal(parseWindowsTasklistCsv(''), null);
  assert.equal(parseWindowsTasklistCsv('garbage'), null);
  assert.equal(parseWindowsTasklistCsv('"node.exe","No Tasks Are Running"\r\n'), null);
});

test('process tree memory sums descendants, not just the wrapper pid', () => {
  // cmd.exe(100) → node pi-web.js(200) → node next start(300) + node worker(310)
  const rows = [
    { pid: 100, ppid: 1, mb: 11 },
    { pid: 200, ppid: 100, mb: 90 },
    { pid: 300, ppid: 200, mb: 800 },
    { pid: 310, ppid: 200, mb: 150 },
    { pid: 999, ppid: 1, mb: 500 } // 无关进程，不能计入
  ];
  assert.equal(sumProcessTreeMb(rows, 100), 11 + 90 + 800 + 150);
  assert.equal(sumProcessTreeMb(rows, 200), 90 + 800 + 150);
  assert.equal(sumProcessTreeMb(rows, 999), 500);
  assert.equal(sumProcessTreeMb(rows, 12345), null, 'unknown root must be null, not 0');
  assert.equal(sumProcessTreeMb([], 100), null);
  assert.equal(sumProcessTreeMb(rows, null), null);
});

test('process tree walk survives a parent cycle instead of hanging', () => {
  const rows = [
    { pid: 1, ppid: 2, mb: 10 },
    { pid: 2, ppid: 1, mb: 20 },
  ];
  assert.equal(sumProcessTreeMb(rows, 1), 30);
});

test('process row parsing ignores junk', () => {
  const rows = parseProcessRows('123,45,67\r\ngarbage\n\n9,9\nabc,def,ghi\n0,0,5\n');
  assert.deepEqual(rows, [{ pid: 123, ppid: 45, mb: 67 }]);
  assert.deepEqual(parseProcessRows(''), []);
  assert.deepEqual(parseProcessRows(null), []);
});

test('never throws on odd input', () => {
  const f = new CrashForensics();
  assert.doesNotThrow(() => f.recordLine(null));
  assert.doesNotThrow(() => f.recordLine(undefined));
  assert.doesNotThrow(() => f.recordMemory(NaN));
  assert.doesNotThrow(() => f.recordMemory(-5));
  assert.doesNotThrow(() => f.report({}));
  assert.doesNotThrow(() => f.report({ code: null, at: 0 }));
});
