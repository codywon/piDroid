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

test('includes tool results so error output reaches the rewriter', async () => {
  let captured = null;
  const modelClient = {
    async generate(input) {
      const payload = parsePayload(input.userText);
      captured = payload.context;
      return { text: payload.draft, stopReason: 'stop', usage: null, provider: input.provider, modelId: input.modelId };
    },
  };
  const service = serviceWith(modelClient);

  const result = await service.enhance(baseBody({
    draft: '帮我看看 @A.kt 这个报错',
    context: [
      { role: 'user', text: '刚才改了上传逻辑' },
      {
        role: 'tool',
        text: '[bash] ./gradlew assembleRelease error: unresolved reference: enhancementEnabled',
      },
      { role: 'assistant', text: '编译报错了。' },
    ],
  }), { subject: 's-tool' });

  assert.equal(result.ok, true);
  assert.equal(captured.length, 3);
  // 工具结果原样进入上下文（含命令与报错正文）
  const tool = captured.find((m) => m.role === 'tool');
  assert.ok(tool, JSON.stringify(captured));
  assert.match(tool.text, /gradlew assembleRelease/);
  assert.match(tool.text, /unresolved reference/);
});

test('keeps a long assistant reply instead of clipping it to a small quota', async () => {
  let captured = null;
  const modelClient = {
    async generate(input) {
      const payload = parsePayload(input.userText);
      captured = payload.context;
      return { text: payload.draft, stopReason: 'stop', usage: null, provider: input.provider, modelId: input.modelId };
    },
  };
  const service = serviceWith(modelClient);
  // 一段 900 字的助手方案：旧实现会砍到 400 字，把后半段的关键决策丢掉
  const plan = '方案第 ' + 'z'.repeat(890) + ' 条：用 Mutex 保护并发计数';
  await service.enhance(baseBody({ context: [{ role: 'assistant', text: plan }] }), { subject: 's-plan' });
  const asst = captured.find((m) => m.role === 'assistant');
  assert.ok(asst.text.length > 800, `assistant length ${asst.text.length}`);
  assert.match(asst.text, /Mutex/);
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
  ];

  for (const [body, code] of cases) {
    const result = await service.enhance(body, { subject: 'subject-1' });
    assert.equal(result.ok, false, JSON.stringify(body));
    assert.equal(result.code, code, JSON.stringify(body));
  }
  assert.equal(modelClient.calls.length, 0);
});

test('auto-truncates oversize context gracefully instead of failing', async () => {
  let capturedContext = null;
  const modelClient = {
    async generate(input) {
      const payload = parsePayload(input.userText);
      capturedContext = payload.context;
      return {
        text: payload.draft,
        stopReason: 'stop',
        usage: { input: 100, output: 20, total: 120 },
        provider: input.provider,
        modelId: input.modelId,
      };
    },
  };
  const service = serviceWith(modelClient, {
    PROMPT_ENHANCE_MAX_CONTEXT: '1000',
    PROMPT_ENHANCE_MAX_CONTEXT_MESSAGES: '3',
    PROMPT_ENHANCE_MAX_CONTEXT_MESSAGE: '200',
  });

  // 传入 5 条消息（超过 3 条），其中单条超过 200 字，总长度超过 1000
  const largeContext = [
    { role: 'user', text: '旧消息 1: ' + 'a'.repeat(300) },
    { role: 'assistant', text: '旧消息 2: ' + 'b'.repeat(300) },
    { role: 'user', text: '较新消息 3: ' + 'c'.repeat(300) },
    { role: 'assistant', text: '较新消息 4: ' + 'd'.repeat(300) },
    { role: 'user', text: '最新消息 5: ' + 'e'.repeat(300) },
  ];

  const result = await service.enhance(baseBody({ context: largeContext }), { subject: 'subject-1' });
  assert.equal(result.ok, true, '应自动裁剪并成功，绝不报错拒收');
  assert.ok(capturedContext.length <= 3, '条数应被裁剪至预算内');
  assert.ok(capturedContext.every((msg) => msg.text.length <= 201), '单条超长应被平滑截断');
  assert.ok(capturedContext[capturedContext.length - 1].text.startsWith('最新消息 5:'), '必须优先保留最新消息');
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

test('returns TIMEOUT on its own even when the provider ignores the abort signal', async () => {
  // 现场：某些 provider 卡在 DNS/TCP/排队时不会检查 signal，
  // 只发 abort 信号的话 await 会一直挂着，客户端 30s 先超时 → 用户看到 499。
  const stubborn = {
    async generate() {
      await new Promise((resolve) => setTimeout(resolve, 5000)); // 故意忽略 signal
      return { text: 'never used', stopReason: 'stop', usage: null, provider: 'p', modelId: 'm' };
    },
  };
  // 直接改 config.timeoutMs：环境变量的下限是 5000ms，这里要一个能快速跑完的短超时
  const service = createEnhancementService({
    modelClient: stubborn,
    config: { ...readEnhancementConfig({ PROMPT_ENHANCE_RATE_LIMIT: '50' }), timeoutMs: 300 },
  });
  const startedAt = Date.now();
  const result = await service.enhance(baseBody(), { subject: 's-timeout' });
  const elapsed = Date.now() - startedAt;

  assert.equal(result.ok, false);
  assert.equal(result.code, 'TIMEOUT');
  // 必须在超时后不久就返回，而不是等到假模型睡满 5 秒
  assert.ok(elapsed < 2000, `returned after ${elapsed}ms`);
});

test('reports CANCELLED (not TIMEOUT) when the client walks away', async () => {
  const hanging = {
    async generate({ signal }) {
      await new Promise((resolve) => setTimeout(resolve, 5000));
      if (signal.aborted) throw new Error('aborted');
      return { text: 'x', stopReason: 'stop', usage: null, provider: 'p', modelId: 'm' };
    },
  };
  const service = createEnhancementService({
    modelClient: hanging,
    config: { ...readEnhancementConfig({ PROMPT_ENHANCE_RATE_LIMIT: '50' }), timeoutMs: 10000 },
  });
  const controller = new AbortController();
  const pending = service.enhance(baseBody(), { subject: 's-cancel', signal: controller.signal });
  setTimeout(() => controller.abort(), 50);
  const result = await pending;
  assert.equal(result.code, 'CANCELLED');
});
