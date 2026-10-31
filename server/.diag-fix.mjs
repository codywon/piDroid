import { ModelRuntime } from '@earendil-works/pi-coding-agent';
const rt = await ModelRuntime.create({ allowModelNetwork: false, refreshOnCreate: false });
const base = rt.getModel('codywon', 'deepseek-flash');
const model = { ...base, compat: { ...(base.compat || {}), supportsDeveloperRole: false } };
console.log('compat=', JSON.stringify(model.compat));
try {
  const msg = await rt.completeSimple(model, {
    systemPrompt: 'Reply with the single word OK.',
    messages: [{ role: 'user', content: [{ type: 'text', text: 'ping' }], timestamp: Date.now() }],
  }, { maxTokens: 200, maxRetries: 0, cacheRetention: 'none', sessionId: 'fix-1' });
  const text = (msg.content || []).filter(b => b.type === 'text').map(b => b.text).join('');
  console.log('stop=', msg.stopReason, '| err=', msg.errorMessage, '| text=', JSON.stringify(text));
} catch (e) {
  console.log('THROW', e?.message);
}
