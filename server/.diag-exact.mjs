import { ModelRuntime } from '@earendil-works/pi-coding-agent';
import { randomUUID } from 'node:crypto';
import { pickThinkingLevel } from './prompt-enhancement/model-client.mjs';

const rt = await ModelRuntime.create({ allowModelNetwork: false, refreshOnCreate: false });
const model = rt.getModel('codywon', 'deepseek-flash');
const reasoning = pickThinkingLevel(model);
const modelLimit = Number(model.maxTokens) > 0 ? Number(model.maxTokens) : 0;
const maxTokens = modelLimit > 0 ? Math.min(1600, modelLimit) : 1600;
console.log('reasoning=', reasoning, 'maxTokens=', maxTokens);

const opts = {
  maxTokens, maxRetries: 0, cacheRetention: 'none',
  sessionId: randomUUID(), signal: new AbortController().signal,
  ...(reasoning ? { reasoning } : {}),
};
console.log('opts keys=', Object.keys(opts).join(','));
try {
  const msg = await rt.completeSimple(model, {
    systemPrompt: 'Reply OK.',
    messages: [{ role: 'user', content: [{ type: 'text', text: 'ping' }], timestamp: Date.now() }],
  }, opts);
  const text = (msg.content || []).filter(b => b.type === 'text').map(b => b.text).join('');
  console.log('stop=', msg.stopReason, '| err=', msg.errorMessage, '| text=', JSON.stringify(text));
} catch (e) {
  console.log('THROW name=', e?.name, 'code=', e?.code, '| msg=', e?.message, '| cause=', e?.cause?.message);
}
