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
