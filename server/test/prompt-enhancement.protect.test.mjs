import assert from 'node:assert/strict';
import test from 'node:test';
import {
  containsFence,
  hasUnclosedFence,
  maskProtectedSegments,
  normalizeModelOutput,
  restoreProtectedSegments,
  stripWrappingFence,
} from '../prompt-enhancement/protect.mjs';

function roundTrip(draft) {
  const { masked, protections } = maskProtectedSegments(draft);
  const restored = restoreProtectedSegments(masked, protections);
  return { masked, protections, restored };
}

test('protects references, urls and paths without swallowing following text', () => {
  const draft = '帮我看看 @ChatScreen.kt，别改代码。参考 https://example.com/a?b=1。然后看 /src/main/java/A.kt 这段。';
  const { masked, protections, restored } = roundTrip(draft);

  assert.equal(restored.ok, true);
  assert.equal(restored.text, draft);

  const kinds = protections.map((item) => `${item.kind}:${item.original}`).sort();
  assert.deepEqual(kinds, [
    'posix_path:/src/main/java/A.kt',
    'reference:@ChatScreen.kt',
    'url:https://example.com/a?b=1',
  ]);
  // 句读与正文不能被卷进占位符
  assert.match(masked, /别改代码/);
  assert.match(masked, /然后看/);
  assert.doesNotMatch(masked, /@ChatScreen/);
});

test('protects fenced code, inline code, windows paths and template variables literally', () => {
  const draft = [
    '把这段代码修一下：',
    '```kotlin',
    'val path = "C:\\\\work\\\\a.kt" // @not-a-ref',
    '```',
    '再检查 `resp.code` 和 {{project}}、${NAME} 变量。',
  ].join('\n');
  const { protections, restored } = roundTrip(draft);

  assert.equal(restored.ok, true);
  assert.equal(restored.text, draft);
  const kinds = new Set(protections.map((item) => item.kind));
  assert.equal(kinds.has('fence'), true);
  assert.equal(kinds.has('inline_code'), true);
  assert.equal(kinds.has('template_var'), true);
  assert.equal(containsFence(draft), true);
});

test('leaves ordinary prose with single slash untouched', () => {
  const draft = '把 a/b 这种普通写法保留，但 /src/x.kt 要保护。';
  const { masked, protections, restored } = roundTrip(draft);
  assert.equal(masked.includes('a/b'), true);
  assert.equal(restored.text, draft);
  assert.deepEqual(protections.map((item) => item.original), ['/src/x.kt']);
});

test('rejects outputs that lost a protected segment or kept a placeholder', () => {
  const { masked, protections } = maskProtectedSegments('看 @A.kt 和 @B.kt');
  const missing = restoreProtectedSegments(masked.replace(/\[\[PE_\d+_[0-9a-z]{6}\]\]/, 'X'), protections);
  assert.equal(missing.ok, false);
  assert.equal(missing.reason, 'missing_protected_segment');

  const leftover = restoreProtectedSegments(`${masked} [[PE_99_abcdef]]`, protections);
  assert.equal(leftover.ok, false);
  assert.equal(leftover.reason, 'leftover_placeholder');
});

test('detects unclosed fences and normalizes output without destroying markdown', () => {
  assert.equal(hasUnclosedFence('```kotlin\nval a = 1'), true);
  assert.equal(hasUnclosedFence('```kotlin\nval a = 1\n```'), false);

  const kept = normalizeModelOutput('目标：整理清单\n- 第一项\n- 第二项', { maxChars: 200 });
  assert.equal(kept.ok, true);
  assert.equal(kept.text, '目标：整理清单\n- 第一项\n- 第二项');

  assert.deepEqual(normalizeModelOutput('   ', { maxChars: 100 }), { ok: false, reason: 'empty_output' });
  assert.deepEqual(normalizeModelOutput('a'.repeat(20), { maxChars: 10 }), { ok: false, reason: 'output_too_long' });
  assert.deepEqual(normalizeModelOutput('```\n未闭合', { maxChars: 100 }), { ok: false, reason: 'unclosed_fence' });
  assert.equal(normalizeModelOutput('"整段被引号包住"', { maxChars: 100 }).text, '整段被引号包住');
  assert.equal(normalizeModelOutput('```kotlin\nval a = 1\n```', { maxChars: 100 }).text, '```kotlin\nval a = 1\n```');
});

test('strips a wrapper fence only when asked to', () => {
  assert.equal(stripWrappingFence('```\n结果\n```'), '结果');
  assert.equal(stripWrappingFence('```kotlin\nval a = 1\n```'), 'val a = 1');
  assert.equal(stripWrappingFence('普通正文\n```kotlin\nval a = 1\n```'), '普通正文\n```kotlin\nval a = 1\n```');
});
