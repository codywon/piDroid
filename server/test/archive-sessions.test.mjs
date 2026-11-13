import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { selectCandidates, MIN_BYTES } = require("../scripts/archive-sessions.js");

const DAY = 86_400_000;
const NOW = Date.parse("2026-11-12T08:00:00Z");
const MB = 1024 * 1024;

function file(id, sizeMb, idleDays) {
  return { id, absPath: `/${id}.jsonl`, relPath: `${id}.jsonl`, sizeBytes: sizeMb * MB, mtimeMs: NOW - idleDays * DAY };
}

test("archives big files that have not been touched for a week", () => {
  const { selected } = selectCandidates([file("a", 74, 8.8), file("b", 60, 30)], { nowMs: NOW });
  assert.deepEqual(selected.map((f) => f.id), ["a", "b"]);
  assert.ok(selected.every((f) => f.sizeBytes >= MIN_BYTES));
});

test("keeps small files and files still in use, no matter how old", () => {
  const { selected } = selectCandidates([file("small", 19, 90), file("fresh", 123, 2.6)], { nowMs: NOW });
  assert.deepEqual(selected, []);
});

test("never touches a session pi-web still references, even when it matches the rule", () => {
  // 60.8 MB / 9 天本该归档，但 pi-web-session-state.json 还引用着它 —— App 里正开着。
  const referenced = new Set(["inuse"]);
  const { selected, skippedReferenced } = selectCandidates([file("inuse", 60.8, 9.1), file("free", 57, 21)], {
    nowMs: NOW,
    referencedIds: referenced,
  });
  assert.deepEqual(selected.map((f) => f.id), ["free"]);
  assert.deepEqual(skippedReferenced.map((f) => f.id), ["inuse"]);
});

test("selects the largest first so a partial run still removes the worst offenders", () => {
  const { selected } = selectCandidates([file("m", 30, 9), file("xl", 123, 9), file("s", 21, 9)], { nowMs: NOW });
  assert.deepEqual(selected.map((f) => f.id), ["xl", "m", "s"]);
});
