import { ModelRuntime } from '@earendil-works/pi-coding-agent';
const rt = await ModelRuntime.create({ allowModelNetwork: false, refreshOnCreate: false });
const prov = rt.getProvider('codywon');
console.log('provider 存在:', Boolean(prov));
const reg = rt.getRegisteredProviderConfig?.('codywon');
console.log('registeredConfig keys:', reg ? Object.keys(reg) : null);
console.log('registeredConfig (截断):', reg ? JSON.stringify(reg).slice(0, 400) : null);
// 尝试合成
const model = {
  id: 'deepseek-flash',
  name: 'deepseek-flash',
  provider: 'codywon',
  api: 'openai-completions',
  baseUrl: 'https://dl.codywon.top:8317/v1',
  input: ['text'],
  contextWindow: 1000000,
  maxTokens: 384000,
  reasoning: false,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
};
const t0 = Date.now();
try {
  const msg = await rt.completeSimple(model, {
    systemPrompt: 'Reply with the single word OK.',
    messages: [{ role: 'user', content: [{ type: 'text', text: 'ping' }], timestamp: Date.now() }],
  }, { maxTokens: 32, maxRetries: 0, cacheRetention: 'none', sessionId: 'diag-synth' });
  const text = (msg.content || []).filter(b => b.type === 'text').map(b => b.text).join('');
  console.log('合成模型调用成功 耗时:', Date.now() - t0, 'ms | stopReason:', msg.stopReason, '| text:', JSON.stringify(text));
} catch (e) {
  console.log('合成模型调用失败 耗时:', Date.now() - t0, 'ms | err:', e?.message);
}
