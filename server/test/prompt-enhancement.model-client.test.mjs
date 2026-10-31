import assert from 'node:assert/strict';
import test from 'node:test';
import { mapModelError, pickThinkingLevel } from '../prompt-enhancement/model-client.mjs';

test('declares off when the model maps it (cheapest, always accepted)', () => {
  // 实测 codywon/deepseek-flash 的 map：off 存在，minimal/low/... 不存在
  const model = { reasoning: true, thinkingLevelMap: { off: 'none', xhigh: 'xhigh', max: 'max' } };
  assert.equal(pickThinkingLevel(model), 'off');
});

test('falls back to the cheapest declared level when off is absent', () => {
  const model = { reasoning: true, thinkingLevelMap: { high: 'high', low: 'low', medium: 'medium' } };
  assert.equal(pickThinkingLevel(model), 'low');
});

test('never sends an undeclared or unsupported level', () => {
  // 未声明映射：不传 reasoning，交给 provider 默认，避免 400 参数被拒
  assert.equal(pickThinkingLevel({ reasoning: true }), null);
  assert.equal(pickThinkingLevel({ reasoning: true, thinkingLevelMap: {} }), null);
  assert.equal(pickThinkingLevel({ reasoning: true, thinkingLevelMap: { off: null, minimal: null } }), null);
  // 非推理模型不传 reasoning
  assert.equal(pickThinkingLevel({ reasoning: false, thinkingLevelMap: { off: 'none' } }), null);
  // Gemini 系拒绝 minimal（pi 的 SDK 在关闭思考时会发这个值）
  assert.equal(
    pickThinkingLevel({ id: 'gemini-2.5-pro', api: 'google-generative-ai', reasoning: true, thinkingLevelMap: { minimal: 'minimal', low: 'low' } }),
    'low',
  );
});

test('maps provider errors to stable codes without swallowing real failures', () => {
  assert.equal(mapModelError(new Error('Provider is not configured: openai')).code, 'MODEL_AUTH_FAILED');
  assert.equal(mapModelError(new Error('401 Unauthorized')).code, 'MODEL_AUTH_FAILED');
  assert.equal(mapModelError(new Error('Model not found: p/m')).code, 'UNSUPPORTED_MODEL');
  assert.equal(mapModelError(new Error('429 too many requests')).code, 'RATE_LIMITED');
  assert.equal(mapModelError(new Error('request was aborted')).code, 'TIMEOUT');
  // 供应商参数错误必须落到通用失败，不能误报成「模型不支持」
  assert.equal(
    mapModelError(new Error('400: {"message":"level \\"minimal\\" not supported, valid levels: low, medium, high"}')).code,
    'MODEL_REQUEST_FAILED',
  );
});

// ── 模型解析回退（回归：models.json 用 modelOverrides 新格式、模型目录缓存为空）──
// 现场故障：models.json 里 codywon 声明了 baseUrl + 7 个 modelOverrides，但
// models-store.json 没有该 provider 的目录缓存，runtime.getModel() 返回 undefined，
// 增强请求被误判为 UNSUPPORTED_MODEL，手机端表现是「点魔棒转很久，内容没变」。
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readProviderDeclaration, synthesizeModel } from '../prompt-enhancement/model-client.mjs';

test('synthesizes a usable model from a modelOverrides-only provider declaration', () => {
  const declaration = {
    baseUrl: 'https://dl.example.top:8317/v1',
    api: 'openai-completions',
    apiKey: 'redacted',
    modelOverrides: {
      'deepseek-flash': { contextWindow: 1000000, maxTokens: 384000 },
    },
  };
  const model = synthesizeModel({ provider: 'codywon', modelId: 'deepseek-flash', declaration });
  assert.equal(model.provider, 'codywon');
  assert.equal(model.id, 'deepseek-flash');
  assert.equal(model.api, 'openai-completions');
  assert.equal(model.baseUrl, 'https://dl.example.top:8317/v1');
  assert.equal(model.contextWindow, 1000000);
  assert.equal(model.maxTokens, 384000);
  // 刻意不声明 reasoning：自定义 provider 的档位约定不明，冒然传会被上游 400 拒
  assert.equal(model.reasoning, false);
});

test('refuses to synthesize when the provider has no baseUrl', () => {
  // 内置 provider（只有 modelOverrides）应由官方模型目录提供，不能凭空合成
  assert.equal(synthesizeModel({ provider: 'deepseek', modelId: 'deepseek-flash', declaration: { modelOverrides: {} } }), null);
  assert.equal(synthesizeModel({ provider: 'x', modelId: 'y', declaration: null }), null);
  assert.equal(synthesizeModel({ provider: 'x', modelId: 'y', declaration: { baseUrl: '' } }), null);
});

test('unknown model id still synthesizes with safe defaults', () => {
  const model = synthesizeModel({
    provider: 'codywon',
    modelId: 'brand-new-model',
    declaration: { baseUrl: 'https://x/v1', api: 'openai-completions' },
  });
  assert.equal(model.id, 'brand-new-model');
  assert.ok(model.contextWindow > 0);
  assert.ok(model.maxTokens > 0);
});

test('reads a provider declaration from models.json and tolerates a missing file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pe-decl-'));
  try {
    writeFileSync(join(dir, 'models.json'), JSON.stringify({
      providers: { codywon: { baseUrl: 'https://x/v1', api: 'openai-completions', modelOverrides: {} } },
    }));
    const decl = readProviderDeclaration('codywon', () => dir);
    assert.equal(decl.baseUrl, 'https://x/v1');
    assert.equal(readProviderDeclaration('missing-provider', () => dir), null);
    // 目录里没有 models.json 时返回 null 而不是抛错
    assert.equal(readProviderDeclaration('codywon', () => join(dir, 'nope')), null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── compat：第三方中转端点不认 developer role ──
// 现场故障：codywon 返回 422 `messages[0].role: unknown variant developer`。
// pi 的 openai-completions 适配器默认把 system 构造成 role=developer 消息。
import { CUSTOM_PROVIDER_COMPAT, normalizeModelCompat } from '../prompt-enhancement/model-client.mjs';

test('synthesized models disable the developer role for custom providers', () => {
  const model = synthesizeModel({
    provider: 'codywon',
    modelId: 'deepseek-flash',
    declaration: { baseUrl: 'https://x/v1', api: 'openai-completions' },
  });
  assert.equal(model.compat.supportsDeveloperRole, false);
  assert.equal(model.compat.supportsStore, false);
  assert.equal(CUSTOM_PROVIDER_COMPAT.supportsDeveloperRole, false);
});

test('catalog-resolved models for a custom provider are normalized too', () => {
  // 目录命中的模型常常没有 compat 字段，同样会撞 422
  const fromCatalog = { provider: 'codywon', id: 'deepseek-flash', api: 'openai-completions' };
  const normalized = normalizeModelCompat(fromCatalog, true);
  assert.equal(normalized.compat.supportsDeveloperRole, false);

  // 已有 compat 时保留其它开关，只强制关掉 developer role
  const partial = normalizeModelCompat({ ...fromCatalog, compat: { supportsStore: true, maxTokensField: 'max_tokens' } }, true);
  assert.equal(partial.compat.supportsDeveloperRole, false);
  assert.equal(partial.compat.maxTokensField, 'max_tokens');

  // 内置 provider 不动：官方 OpenAI 端点确实支持 developer role
  const builtin = { provider: 'openai', id: 'gpt-4o' };
  assert.equal(normalizeModelCompat(builtin, false), builtin);
});

test('upstream 4xx rejections surface the upstream reason instead of a generic message', () => {
  const mapped = mapModelError(new Error(
    '422: {"message":"messages[0].role: unknown variant `developer`","type":"invalid_request_error"}',
  ));
  assert.equal(mapped.code, 'MODEL_REQUEST_FAILED');
  assert.match(mapped.message, /上游拒绝该请求/);
  assert.match(mapped.message, /developer/);
  // 普通网络抖动仍然给通用文案，不把无意义的堆栈抛给用户
  assert.equal(mapModelError(new Error('socket hang up')).message, '模型请求失败，请稍后重试');
});
