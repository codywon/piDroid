import assert from 'node:assert/strict';
import http from 'node:http';
import test from 'node:test';
import express from 'express';
import { readEnhancementConfig } from '../prompt-enhancement/config.js';
import { createEnhancementService } from '../prompt-enhancement/service.mjs';
import { createPromptEnhancementHandler } from '../prompt-enhancement/routes.mjs';

const AUTH = 'Bearer test-token';

function parsePayload(userText) {
  const start = userText.indexOf('{');
  const end = userText.lastIndexOf('}');
  return JSON.parse(userText.slice(start, end + 1));
}

function modelClient({ onGenerate } = {}) {
  return {
    async generate(input) {
      if (onGenerate) return onGenerate(input);
      const payload = parsePayload(input.userText);
      return {
        text: payload.draft.replace('帮我看看', '请分析'),
        stopReason: 'stop',
        usage: { input: 1, output: 1, total: 2 },
        provider: input.provider,
        modelId: input.modelId,
      };
    },
  };
}

function body(overrides = {}) {
  return JSON.stringify({
    requestId: 'req-http',
    sessionId: 'sess-http',
    draftRevision: 1,
    branchRevision: 1,
    provider: 'p',
    modelId: 'm',
    draft: '帮我看看这个报错',
    context: [],
    ...overrides,
  });
}

async function startServer(service, { requireAuth = true } = {}) {
  const app = express();
  app.get('/api/ping', (req, res) => {
    res.json({ status: 'ok', capabilities: { promptEnhancement: service.capability() } });
  });
  app.post(
    '/api/mobile/prompt-enhance',
    (req, res, next) => {
      if (requireAuth && req.headers.authorization !== AUTH) {
        return res.status(401).json({ code: 'TOKEN_INVALID', message: 'Unauthorized' });
      }
      next();
    },
    express.json({ limit: '64kb' }),
    createPromptEnhancementHandler({ service }),
  );
  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

function serviceWith(client, env = {}) {
  return createEnhancementService({
    modelClient: client,
    config: readEnhancementConfig({ PROMPT_ENHANCE_RATE_LIMIT: '50', ...env }),
  });
}

test('requires the gateway token and never caches the response', async () => {
  const server = await startServer(serviceWith(modelClient()));
  try {
    const unauthorized = await fetch(`${server.baseUrl}/api/mobile/prompt-enhance`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: body(),
    });
    assert.equal(unauthorized.status, 401);

    const ok = await fetch(`${server.baseUrl}/api/mobile/prompt-enhance`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: AUTH },
      body: body(),
    });
    assert.equal(ok.status, 200);
    assert.equal(ok.headers.get('cache-control'), 'no-store');
    assert.equal(ok.headers.get('x-content-type-options'), 'nosniff');
    const json = await ok.json();
    assert.equal(json.enhancedText, '请分析这个报错');
    assert.equal(json.requestId, 'req-http');
    assert.equal(json.draftRevision, 1);
    assert.deepEqual(json.model, { provider: 'p', modelId: 'm' });
  } finally {
    await server.close();
  }
});

test('exposes capability through ping without touching the model', async () => {
  const server = await startServer(serviceWith(modelClient()));
  try {
    const res = await fetch(`${server.baseUrl}/api/ping`);
    const json = await res.json();
    assert.equal(json.capabilities.promptEnhancement.available, true);
    assert.equal(json.capabilities.promptEnhancement.version, 1);
  } finally {
    await server.close();
  }
});

test('maps service failures to stable HTTP status codes', async () => {
  const server = await startServer(serviceWith(modelClient()));
  try {
    const invalid = await fetch(`${server.baseUrl}/api/mobile/prompt-enhance`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: AUTH },
      body: body({ draft: '/plan 继续' }),
    });
    assert.equal(invalid.status, 400);
    const invalidJson = await invalid.json();
    assert.equal(invalidJson.code, 'INVALID_INPUT');
    assert.equal(invalidJson.requestId, 'req-http');
  } finally {
    await server.close();
  }
});

test('rate limits repeated requests', async () => {
  const server = await startServer(serviceWith(modelClient(), { PROMPT_ENHANCE_RATE_LIMIT: '2' }));
  try {
    const call = () =>
      fetch(`${server.baseUrl}/api/mobile/prompt-enhance`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: AUTH },
        body: body(),
      });
    assert.equal((await call()).status, 200);
    assert.equal((await call()).status, 200);
    const limited = await call();
    assert.equal(limited.status, 429);
    assert.equal((await limited.json()).code, 'RATE_LIMITED');
  } finally {
    await server.close();
  }
});

test('client disconnect aborts the model call and frees capacity', async () => {
  let firstCall = true;
  const client = modelClient({
    onGenerate: async (input) => {
      if (firstCall) {
        firstCall = false;
        await new Promise((resolve, reject) => {
          if (input.signal.aborted) return reject(new Error('aborted'));
          input.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
        });
      }
      const payload = parsePayload(input.userText);
      return {
        text: payload.draft,
        stopReason: 'stop',
        usage: null,
        provider: input.provider,
        modelId: input.modelId,
      };
    },
  });
  const server = await startServer(serviceWith(client, { PROMPT_ENHANCE_MAX_CONCURRENT: '1' }));
  try {
    const controller = new AbortController();
    const pending = fetch(`${server.baseUrl}/api/mobile/prompt-enhance`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: AUTH },
      body: body({ sessionId: 'sess-abort' }),
      signal: controller.signal,
    });
    setTimeout(() => controller.abort(), 60);
    await assert.rejects(pending);

    // 客户端离开后名额必须释放；否则后续请求会一直被 429 挡住。
    const second = await fetch(`${server.baseUrl}/api/mobile/prompt-enhance`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: AUTH },
      body: body({ sessionId: 'sess-2', requestId: 'req-2' }),
    });
    assert.equal(second.status, 200);
  } finally {
    await server.close();
  }
});
