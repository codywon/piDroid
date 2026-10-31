import assert from 'node:assert/strict';
import test from 'node:test';
import { readEnhancementConfig } from '../prompt-enhancement/config.js';
import { createEnhancementService } from '../prompt-enhancement/service.mjs';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 从 buildUserPayload 的输出里取回 JSON（假模型不需要理解提示词，只需要拿到材料）。 */
function parsePayload(userText) {
  const start = userText.indexOf('{');
  const end = userText.lastIndexOf('}');
  return JSON.parse(userText.slice(start, end + 1));
}

function fakeModelClient({ transform = (payload) => payload.draft, stopReason = 'stop', error = null, delayMs = 0 } = {}) {
  return {
    calls: [],
    async generate(input) {
      this.calls.push(input);
      if (delayMs > 0) await sleep(delayMs);
      if (error) throw error;
      const payload = parsePayload(input.userText);
      return {
        text: transform(payload),
        stopReason,
        usage: { input: 100, output: 20, total: 120 },
        provider: input.provider,
        modelId: input.modelId,
      };
    },
  };
}

function baseBody(overrides = {}) {
  return {
    requestId: 'req-1',
    sessionId: 'sess-1',
    draftRevision: 3,
    branchRevision: 1,
    provider: 'test-provider',
    modelId: 'test-model',
    draft: '帮我看看 @A.kt 这个报错，先给建议别改代码。',
    context: [{ role: 'user', text: '上一轮在排查启动失败' }],
    ...overrides,
  };
}

function serviceWith(modelClient, env = {}) {
  return createEnhancementService({
    modelClient,
    config: readEnhancementConfig({ PROMPT_ENHANCE_RATE_LIMIT: '50', ...env }),
  });
}

test('rewrites the draft and keeps protected segments verbatim', async () => {
  const modelClient = fakeModelClient({
    transform: (payload) => payload.draft.replace('帮我看看', '请检查并说明原因：'),
  });
  const service = serviceWith(modelClient);
  const result = await service.enhance(baseBody(), { subject: 'subject-1' });

  assert.equal(result.ok, true);
  assert.equal(result.value.changed, true);
  assert.equal(result.value.enhancedText, '请检查并说明原因： @A.kt 这个报错，先给建议别改代码。');
  assert.equal(result.value.draftRevision, 3);
  assert.equal(result.value.branchRevision, 1);
  assert.deepEqual(result.value.model, { provider: 'test-provider', modelId: 'test-model' });
  assert.equal(result.value.contextUsed, true);
});

test('reports unchanged when the model keeps the draft intact', async () => {
  const service = serviceWith(fakeModelClient());
  const result = await service.enhance(baseBody(), { subject: 'subject-1' });
  assert.equal(result.ok, true);
  assert.equal(result.value.changed, false);
  assert.equal(result.value.enhancedText, baseBody().draft.trim());
});

test('rejects malformed or unsafe input without calling the model', async () => {
  const modelClient = fakeModelClient();
  const service = serviceWith(modelClient);

  const cases = [
    [null, 'INVALID_INPUT'],
    [baseBody({ draft: '   ' }), 'INVALID_INPUT'],
    [baseBody({ draft: '/plan 继续' }), 'INVALID_INPUT'],
    [baseBody({ draft: '```kotlin\nval a = 1' }), 'INVALID_INPUT'],
    [baseBody({ draft: 'x'.repeat(7000) }), 'INVALID_INPUT'],
    [baseBody({ provider: '' }), 'INVALID_INPUT'],
    [baseBody({ draftRevision: -1 }), 'INVALID_INPUT'],
    [baseBody({ context: [{ role: 'system', text: 'x' }] }), 'INVALID_INPUT'],
    [baseBody({ context: Array.from({ length: 11 }, () => ({ role: 'user', text: 'x' })) }), 'CONTEXT_TOO_LARGE'],
    [baseBody({ context: [{ role: 'user', text: 'x'.repeat(900) }] }), 'CONTEXT_TOO_LARGE'],
  ];

  for (const [body, code] of cases) {
    const result = await service.enhance(body, { subject: 'subject-1' });
    assert.equal(result.ok, false, JSON.stringify(body));
    assert.equal(result.code, code, JSON.stringify(body));
  }
  assert.equal(modelClient.calls.length, 0);
});

test('never applies output that lost a protected segment or kept a placeholder', async () => {
  const draft = '看 @A.kt 和 @B.kt，别改。';
  const lost = serviceWith(fakeModelClient({ transform: () => '看 [[PE_1_aaaaaa]]，别改。' }));
  const lostResult = await lost.enhance(baseBody({ draft }), { subject: 'subject-1' });
  assert.equal(lostResult.ok, false);
  assert.equal(lostResult.code, 'INVALID_OUTPUT');

  const leftover = serviceWith(
    fakeModelClient({ transform: (payload) => `${payload.draft} [[PE_9_zzzzzz]]` }),
  );
  const leftoverResult = await leftover.enhance(baseBody({ draft }), { subject: 'subject-1' });
  assert.equal(leftoverResult.ok, false);
  assert.equal(leftoverResult.code, 'INVALID_OUTPUT');
});

test('rejects truncated output and unexpected wrapping fences', async () => {
  const truncated = serviceWith(fakeModelClient({ stopReason: 'length' }));
  const truncatedResult = await truncated.enhance(baseBody(), { subject: 'subject-1' });
  assert.equal(truncatedResult.code, 'INVALID_OUTPUT');

  const fenced = serviceWith(fakeModelClient({ transform: (payload) => '```kotlin\n' + payload.draft + '\n```' }));
  const fencedResult = await fenced.enhance(baseBody(), { subject: 'subject-1' });
  assert.equal(fencedResult.code, 'INVALID_OUTPUT');

  const wrapped = serviceWith(fakeModelClient({ transform: (payload) => '```\n' + payload.draft + '\n```' }));
  const wrappedResult = await wrapped.enhance(baseBody(), { subject: 'subject-1' });
  assert.equal(wrappedResult.ok, true);
  assert.equal(wrappedResult.value.enhancedText, baseBody().draft.trim());
});

test('maps provider failures to stable codes', async () => {
  const authFailed = serviceWith(fakeModelClient({ error: new Error('Provider is not configured: openai') }));
  const authResult = await authFailed.enhance(baseBody(), { subject: 'subject-1' });
  assert.equal(authResult.ok, false);
  assert.equal(authResult.code, 'MODEL_AUTH_FAILED');

  const unknown = serviceWith(fakeModelClient({ error: new Error('Unknown provider: x') }));
  const unknownResult = await unknown.enhance(baseBody(), { subject: 'subject-1' });
  assert.equal(unknownResult.code, 'UNSUPPORTED_MODEL');
});

test('enforces per-session single flight, global concurrency and rate limits', async () => {
  const slow = fakeModelClient({ delayMs: 50 });
  const service = serviceWith(slow, { PROMPT_ENHANCE_MAX_CONCURRENT: '1' });

  const first = service.enhance(baseBody({ sessionId: 'sess-a' }), { subject: 'subject-1' });
  const sameSession = await service.enhance(baseBody({ sessionId: 'sess-a', requestId: 'req-2' }), {
    subject: 'subject-1',
  });
  assert.equal(sameSession.code, 'RATE_LIMITED');

  const otherSession = await service.enhance(baseBody({ sessionId: 'sess-b', requestId: 'req-3' }), {
    subject: 'subject-1',
  });
  assert.equal(otherSession.code, 'RATE_LIMITED');

  assert.equal((await first).ok, true);

  const limited = serviceWith(fakeModelClient(), { PROMPT_ENHANCE_RATE_LIMIT: '2' });
  assert.equal((await limited.enhance(baseBody(), { subject: 's' })).ok, true);
  assert.equal((await limited.enhance(baseBody(), { subject: 's' })).ok, true);
  const third = await limited.enhance(baseBody(), { subject: 's' });
  assert.equal(third.code, 'RATE_LIMITED');
  // 不同主体不受影响
  assert.equal((await limited.enhance(baseBody(), { subject: 'other' })).ok, true);
});

test('times out and cancels without leaking inflight slots', async () => {
  const hanging = {
    async generate({ signal }) {
      await new Promise((resolve, reject) => {
        if (signal.aborted) return reject(new Error('aborted'));
        signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
      });
    },
  };
  const service = serviceWith(hanging, { PROMPT_ENHANCE_TIMEOUT_MS: '5000', PROMPT_ENHANCE_MAX_CONCURRENT: '1' });

  const controller = new AbortController();
  const pending = service.enhance(baseBody({ sessionId: 'sess-x' }), { subject: 's', signal: controller.signal });
  setTimeout(() => controller.abort(), 20);
  const cancelled = await pending;
  assert.equal(cancelled.code, 'CANCELLED');

  // 外部取消后必须释放并发名额与会话锁，下一次请求才能进入
  const afterAbort = serviceWith(fakeModelClient());
  assert.equal((await afterAbort.enhance(baseBody(), { subject: 's' })).ok, true);
});

test('reports unavailable when disabled', async () => {
  const disabled = createEnhancementService({
    modelClient: fakeModelClient(),
    config: readEnhancementConfig({ PROMPT_ENHANCE_DISABLED: '1' }),
  });
  assert.equal(disabled.capability().available, false);
  const result = await disabled.enhance(baseBody(), { subject: 's' });
  assert.equal(result.code, 'ENHANCEMENT_UNAVAILABLE');
});
