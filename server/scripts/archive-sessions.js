#!/usr/bin/env node
// 历史大会话归档。
//
// 为什么需要：pi-web 在事件循环里解析会话 JSONL，单个文件越大，一次解析就越是
// 秒级阻塞（实测 123 MB 的会话能把探针打超时，最长 >60s）。索引本身是 size+mtime
// 指纹 + 断点续读的，所以「没变更的老文件」不重读；真正疼的是**体量**。把已经
// 不会再变的老会话挪出热目录，是唯一直接削减单次解析体积的动作。
//
// 为什么不开三分层（hot/warm/cold）：那是日志仓库的解法，我们的瓶颈是单文件大小
// 而不是总容量，三层只换来复杂度和误删风险。
//
// 为什么先 gzip 再校验再删：这 19 个文件是你 3 个月的历史，宁可慢一点也不能丢。
// 校验方式是把 .gz 重新解压算 sha256，和原文件逐字节比对，通过了才 unlink。
//
// 用法：
//   node scripts/archive-sessions.js list               只列出会被归档的文件（默认）
//   node scripts/archive-sessions.js apply              真正执行（gzip + 校验 + 删除）
//   node scripts/archive-sessions.js restore <id|--all> 还原回热目录
'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const zlib = require('zlib');
const { pipeline } = require('stream/promises');
const { createReadStream, createWriteStream } = require('fs');

// 阈值：20 MB 且 7 天没动。7 天是实测出来的——30 天门槛碰不到真正的大块头
// （74.7/71.4 MB 的会话只有 8.8/9.3 天 idle），而 2 天会切走还在用的会话。
const MIN_BYTES = 20 * 1024 * 1024;
const MAX_IDLE_DAYS = 7;

const AGENT_DIR = path.join(os.homedir(), '.pi', 'agent');
const SESSIONS_DIR = path.join(AGENT_DIR, 'sessions');
const ARCHIVE_DIR = path.join(AGENT_DIR, 'sessions-archive');
const MANIFEST_PATH = path.join(ARCHIVE_DIR, 'manifest.json');
const INDEX_PATH = path.join(ARCHIVE_DIR, 'index.jsonl');
const SESSION_STATE_PATH = path.join(AGENT_DIR, 'pi-web-session-state.json');

/** 递归列出 sessions/ 下的所有 .jsonl（相对路径 + 绝对路径 + size + mtimeMs）。 */
function listSessionFiles(dir = SESSIONS_DIR) {
  const out = [];
  const walk = (current, rel) => {
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch (e) {
      return;
    }
    for (const entry of entries) {
      const abs = path.join(current, entry.name);
      const relPath = rel ? path.join(rel, entry.name) : entry.name;
      if (entry.isDirectory()) {
        walk(abs, relPath);
        continue;
      }
      if (!entry.name.endsWith('.jsonl')) continue;
      const stat = fs.statSync(abs);
      out.push({
        absPath: abs,
        relPath,
        sizeBytes: stat.size,
        mtimeMs: stat.mtimeMs,
        id: entry.name.split('_').pop().replace(/\.jsonl$/, '')
      });
    }
  };
  walk(dir, '');
  return out;
}

/** pi-web 的会话界面状态里还引用着的会话不能动（App 里正开着它）。 */
function readReferencedIds(statePath = SESSION_STATE_PATH) {
  try {
    const raw = fs.readFileSync(statePath, 'utf8');
    const ids = new Set();
    for (const match of raw.matchAll(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g)) {
      ids.add(match[0]);
    }
    return ids;
  } catch (e) {
    return new Set();
  }
}

/**
 * 挑出该归档的文件。纯函数，便于单测——这条规则判错就是要么白干活、要么切走还在用的会话。
 * @returns {{selected: object[], skippedReferenced: object[]}}
 */
function selectCandidates(files, { nowMs = Date.now(), minBytes = MIN_BYTES, maxIdleDays = MAX_IDLE_DAYS, referencedIds = new Set() } = {}) {
  const selected = [];
  const skippedReferenced = [];
  for (const file of files) {
    if (file.sizeBytes < minBytes) continue;
    const idleDays = (nowMs - file.mtimeMs) / 86_400_000;
    if (idleDays < maxIdleDays) continue;
    if (referencedIds.has(file.id)) {
      skippedReferenced.push(file);
      continue;
    }
    selected.push({ ...file, idleDays });
  }
  selected.sort((a, b) => b.sizeBytes - a.sizeBytes);
  return { selected, skippedReferenced };
}

function sha256File(absPath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    createReadStream(absPath)
      .on('data', (chunk) => hash.update(chunk))
      .on('error', reject)
      .on('end', () => resolve(hash.digest('hex')));
  });
}

async function gzipWithHash(absPath, gzPath) {
  fs.mkdirSync(path.dirname(gzPath), { recursive: true });
  const hash = crypto.createHash('sha256');
  const source = createReadStream(absPath);
  source.on('data', (chunk) => hash.update(chunk));
  await pipeline(source, zlib.createGzip({ level: 9 }), createWriteStream(gzPath));
  return { sha256: hash.digest('hex'), gzBytes: fs.statSync(gzPath).size };
}

/** 把 .gz 解压回来算 sha256，和归档前比对；不一致就不许删原文件。 */
async function verifyGzip(gzPath, expectedSha) {
  const hash = crypto.createHash('sha256');
  await pipeline(createReadStream(gzPath), zlib.createGunzip(), async function* (chunks) {
    for await (const chunk of chunks) {
      hash.update(chunk);
      yield chunk;
    }
  }, new (require('stream').Writable)({
    write(chunk, enc, callback) {
      callback();
    }
  }));
  return hash.digest('hex') === expectedSha;
}

function readManifest() {
  try {
    const parsed = JSON.parse(fs.readFileSync(MANIFEST_PATH, 'utf8'));
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    return [];
  }
}

function writeManifest(entries) {
  fs.mkdirSync(ARCHIVE_DIR, { recursive: true });
  fs.writeFileSync(MANIFEST_PATH, JSON.stringify(entries, null, 2), 'utf8');
  fs.writeFileSync(INDEX_PATH, entries.map((entry) => JSON.stringify(entry)).join('\n') + (entries.length ? '\n' : ''), 'utf8');
}

function formatMb(bytes) {
  return `${(bytes / 1e6).toFixed(1)} MB`;
}

async function apply() {
  const files = listSessionFiles();
  const referencedIds = readReferencedIds();
  const { selected, skippedReferenced } = selectCandidates(files, { referencedIds });
  if (skippedReferenced.length > 0) {
    console.log(`跳过 ${skippedReferenced.length} 个仍被 pi-web 会话状态引用的会话：`);
    for (const file of skippedReferenced) console.log(`  - ${formatMb(file.sizeBytes)}  ${file.id}`);
  }
  if (selected.length === 0) {
    console.log('没有需要归档的会话。');
    return;
  }
  const totalBytes = selected.reduce((sum, file) => sum + file.sizeBytes, 0);
  console.log(`归档 ${selected.length} 个文件 / ${formatMb(totalBytes)} → ${ARCHIVE_DIR}\n`);

  const manifest = readManifest();
  let doneBytes = 0;
  for (const file of selected) {
    const gzPath = path.join(ARCHIVE_DIR, file.relPath + '.gz');
    if (fs.existsSync(gzPath)) {
      console.log(`跳过（归档已存在）${file.relPath}`);
      continue;
    }
    const startedAt = Date.now();
    const { sha256, gzBytes } = await gzipWithHash(file.absPath, gzPath);
    const ok = await verifyGzip(gzPath, sha256);
    if (!ok) {
      console.error(`校验失败，保留原文件：${file.relPath}（.gz 留在 ${gzPath}）`);
      process.exitCode = 1;
      break;
    }
    fs.unlinkSync(file.absPath);
    doneBytes += file.sizeBytes;
    manifest.push({
      id: file.id,
      originalPath: file.absPath,
      relPath: file.relPath,
      originalBytes: file.sizeBytes,
      gzBytes,
      sha256,
      mtime: new Date(file.mtimeMs).toISOString(),
      archivedAt: new Date().toISOString()
    });
    writeManifest(manifest);
    console.log(
      `✓ ${formatMb(file.sizeBytes)} → ${formatMb(gzBytes)}  ${((Date.now() - startedAt) / 1000).toFixed(1)}s  ${file.relPath}`
    );
  }

  const remaining = listSessionFiles();
  const remainingBytes = remaining.reduce((sum, file) => sum + file.sizeBytes, 0);
  console.log(
    `\n完成：归档 ${formatMb(doneBytes)}；热目录 ${formatMb(remainingBytes)} / ${remaining.length} 个文件`
  );
  console.log(`清单：${MANIFEST_PATH}\n还原：node scripts/archive-sessions.js restore --all`);
}

function list() {
  const files = listSessionFiles();
  const referencedIds = readReferencedIds();
  const { selected, skippedReferenced } = selectCandidates(files, { referencedIds });
  const totalBytes = files.reduce((sum, file) => sum + file.sizeBytes, 0);
  console.log(`热目录：${files.length} 个会话 / ${formatMb(totalBytes)}`);
  console.log(`命中规则（≥${formatMb(MIN_BYTES)} 且 ≥${MAX_IDLE_DAYS} 天未改动）：${selected.length} 个\n`);
  for (const file of selected) {
    console.log(`  ${formatMb(file.sizeBytes).padStart(9)}  idle ${file.idleDays.toFixed(1).padStart(5)}d  ${file.relPath}`);
  }
  for (const file of skippedReferenced) {
    console.log(`  [跳过：仍被会话状态引用] ${formatMb(file.sizeBytes)}  ${file.id}`);
  }
  console.log(`\n归档后将剩余：${formatMb(totalBytes - selected.reduce((sum, f) => sum + f.sizeBytes, 0))}`);
}

async function gunzipToFileWithHash(gzPath, destPath) {
  const hash = crypto.createHash('sha256');
  const tmpPath = destPath + '.tmp';
  try {
    const gunzip = zlib.createGunzip();
    await pipeline(
      createReadStream(gzPath),
      gunzip,
      async function* (chunks) {
        for await (const chunk of chunks) {
          hash.update(chunk);
          yield chunk;
        }
      },
      createWriteStream(tmpPath)
    );
    return { sha256: hash.digest('hex'), tmpPath };
  } catch (err) {
    try { fs.unlinkSync(tmpPath); } catch {}
    throw err;
  }
}

async function restore(target) {
  const manifest = readManifest();
  if (manifest.length === 0) {
    console.log('清单为空，没有可还原的归档。');
    return;
  }
  const entries = target === '--all' ? manifest : manifest.filter((entry) => entry.id === target || entry.relPath === target);
  if (entries.length === 0) {
    console.error(`清单里没有匹配 ${target} 的归档。可用 id：`);
    for (const entry of manifest) console.error(`  ${entry.id}  ${entry.relPath}`);
    process.exitCode = 1;
    return;
  }
  const restored = new Set();
  for (const entry of entries) {
    const gzPath = path.join(ARCHIVE_DIR, entry.relPath + '.gz');
    if (!fs.existsSync(gzPath)) {
      console.error(`归档文件不存在：${gzPath}`);
      process.exitCode = 1;
      continue;
    }
    fs.mkdirSync(path.dirname(entry.originalPath), { recursive: true });
    let tmpPath;
    let sha;
    try {
      const res = await gunzipToFileWithHash(gzPath, entry.originalPath);
      sha = res.sha256;
      tmpPath = res.tmpPath;
    } catch (err) {
      console.error(`解压失败：${entry.relPath} (${err.message})`);
      process.exitCode = 1;
      continue;
    }
    if (sha !== entry.sha256) {
      try { fs.unlinkSync(tmpPath); } catch {}
      console.error(`校验失败，拒绝还原：${entry.relPath}`);
      process.exitCode = 1;
      continue;
    }
    fs.renameSync(tmpPath, entry.originalPath);
    fs.unlinkSync(gzPath);
    restored.add(entry);
    console.log(`✓ 已还原 ${formatMb(entry.originalBytes)}  ${entry.relPath}`);
  }
  const remaining = manifest.filter((entry) => !restored.has(entry));
  writeManifest(remaining);
}

async function main() {
  const action = process.argv[2] || 'list';
  switch (action) {
    case 'list':
      list();
      break;
    case 'apply':
      await apply();
      break;
    case 'restore':
      await restore(process.argv[3]);
      break;
    default:
      console.log('usage: node scripts/archive-sessions.js [list|apply|restore <id|--all>]');
      process.exitCode = 1;
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}

module.exports = { selectCandidates, listSessionFiles, MIN_BYTES, MAX_IDLE_DAYS };
