import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  Supervisor,
  readSupervisorPid,
  readLogTail,
  parseNetstatPid,
  parsePosixPortPid,
  PIWEB_SETTLE_MS,
  BOOT_GRACE_MS,
} = require('../supervisor.js');
const { parsePiWebPort, main: launcherMain } = require('../launcher.js');

test('readSupervisorPid treats EPERM as alive and verifies node.exe on Windows', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pidroid-sup-pid-'));
  const pidFile = path.join(dir, 'supervisor.pid');
  try {
    fs.writeFileSync(pidFile, '4321', 'utf8');

    // EPERM + Windows tasklist 确认为 node.exe -> 视为存活
    const epermKill = () => {
      const err = new Error('operation not permitted');
      err.code = 'EPERM';
      throw err;
    };
    assert.equal(
      readSupervisorPid({
        pidFile,
        kill: epermKill,
        platform: 'win32',
        runTasklist: () => ({ status: 0, stdout: 'node.exe                      4321 Console                    1    65,432 K\r\n' }),
      }),
      4321,
    );

    // PID 被非 node 进程复用 -> 必须返回 null 防止误杀
    assert.equal(
      readSupervisorPid({
        pidFile,
        kill: () => {},
        platform: 'win32',
        runTasklist: () => ({ status: 0, stdout: 'svchost.exe                   4321 Services                   0    12,000 K\r\n' }),
      }),
      null,
    );

    // ESRCH -> 进程不存在
    const esrchKill = () => {
      const err = new Error('no such process');
      err.code = 'ESRCH';
      throw err;
    };
    assert.equal(readSupervisorPid({ pidFile, kill: esrchKill, platform: 'linux' }), null);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Supervisor.run enforces single-instance mutex when another supervisor pid is alive', () => {
  const logs = [];
  const sup = new Supervisor({
    readSupervisorPid: () => process.pid + 99999,
    log: (msg) => logs.push(msg),
    stackHealth: () => {
      throw new Error('duplicate supervisor must exit before probing');
    },
  });
  sup.run();
  assert.equal(logs.length, 1);
  assert.match(logs[0], /already running/);
});

test('readLogTail redacts tokens and pi_ hex secrets before writing deaths.log', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pidroid-sup-log-'));
  const logFile = path.join(dir, 'stack.log');
  try {
    const rawHexToken = 'pi_abcdef0123456789abcdef0123456789';
    fs.writeFileSync(
      logFile,
      [
        `GET /api/agent/1/events?token=${rawHexToken}`,
        `[auth] leaked=${rawHexToken}`,
        'normal line ok',
      ].join('\n'),
      'utf8',
    );
    const tail = readLogTail(10, logFile);
    assert.equal(tail.length, 3);
    for (const line of tail) {
      assert.equal(line.includes(rawHexToken), false, `secret leaked in: ${line}`);
    }
    assert.ok(tail[0].includes('token=***') || tail[0].includes('pi_***'));
    assert.ok(tail[1].includes('pi_***'));
    assert.equal(tail[2], 'normal line ok');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Supervisor.tick never masks gateway outage behind pi-web settle window', async () => {
  const logs = [];
  const sup = new Supervisor({
    stackHealth: async () => [
      { ok: true, status: 200, ms: 10 },
      { ok: false, reason: 'ECONNREFUSED', ms: 5 },
    ],
    portOwnerPid: () => null,
    recordProbe: () => {},
    recordDeath: () => {},
    killTree: () => {},
    log: (msg) => logs.push(msg),
  });
  sup.child = { pid: 12345 };
  sup.childStartedAt = Date.now() - BOOT_GRACE_MS - 10_000;
  sup.piwebSettleUntil = Date.now() + PIWEB_SETTLE_MS;

  await sup.tick();
  assert.equal(sup.failures, 1, 'gateway down must count toward failure threshold even during pi-web settle window');
  assert.match(logs[0], /probe failed 1\//);

  // 当网关健康、仅 pi-web 在预热期内变慢/未就绪时，仍应豁免
  sup.deps.stackHealth = async () => [
    { ok: false, reason: 'timeout', ms: 2000 },
    { ok: true, status: 200, ms: 5 },
  ];
  await sup.tick();
  assert.equal(sup.failures, 0, 'pi-web slow during settle when gateway is ok should not count as failure');
});

test('portOwnerPid parsers handle IPv6 [::]:port and POSIX lsof/ss fallbacks', () => {
  const netstatOut = [
    '  TCP    0.0.0.0:14100          0.0.0.0:0              LISTENING       1111',
    '  TCP    [::]:4100              [::]:0                 LISTENING       2222',
    '  UDP    0.0.0.0:4100           *:*                                    9999',
  ].join('\r\n');
  assert.equal(parseNetstatPid(netstatOut, 4100), 2222);
  assert.equal(parseNetstatPid(netstatOut, 14100), 1111);
  assert.equal(parseNetstatPid(netstatOut, 30141), null);

  assert.equal(parsePosixPortPid('5544\n5545\n', ''), 5544);
  assert.equal(parsePosixPortPid('', 'LISTEN 0 511 127.0.0.1:4100 0.0.0.0:* users:(("node",pid=7788,fd=21))'), 7788);
  assert.equal(parsePosixPortPid('', ''), null);
});

test('launcher.parsePiWebPort falls back to 30141 on invalid URL', () => {
  assert.equal(parsePiWebPort('http://127.0.0.1:30141'), 30141);
  assert.equal(parsePiWebPort('https://pi.example.com'), 443);
  assert.equal(parsePiWebPort('not-a-valid-url'), 30141);
});

test('launcher viewer mode checks gateway first and never spawns pi-web', async () => {
  let launchCalled = false;
  let probedUrl = '';
  let bannerPiWebReady = null;
  await launcherMain({
    checkGatewayRunning: async () => true,
    checkUrlAlive: async (url) => {
      probedUrl = url;
      return false;
    },
    launchPiWeb: () => {
      launchCalled = true;
      throw new Error('must not launch pi-web in viewer mode');
    },
    resolveConnectHost: () => ({ mode: 'none', connectHost: 'http://127.0.0.1:4100', configPath: '' }),
    printBannerAndQr: (_host, piWebReady) => {
      bannerPiWebReady = piWebReady;
    },
    resumeStdin: false,
  });

  assert.equal(launchCalled, false);
  assert.match(probedUrl, /^http:\/\/127\.0\.0\.1:30141/);
  assert.equal(bannerPiWebReady, false);
});
