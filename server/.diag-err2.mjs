import { ModelRuntime } from '@earendil-works/pi-coding-agent';
import { readProviderDeclaration, synthesizeModel } from './prompt-enhancement/model-client.mjs';
import { getAgentDir } from '@earendil-works/pi-coding-agent';

const rt = await ModelRuntime.create({ allowModelNetwork: false, refreshOnCreate: false });
const decl = readProviderDeclaration('codywon', getAgentDir);
const model = synthesizeModel({ provider: 'codywon', modelId: 'deepseek-flash', declaration: decl });
console.log('synthesized:', JSON.stringify(model));
try {
  const msg = await rt.completeSimple(model, {
    systemPrompt: 'Reply OK.',
    messages: [{ role: 'user', content: [{ type: 'text', text: 'ping' }], timestamp: Date.now() }],
  }, { maxTokens: 32, maxRetries: 0, cacheRetention: 'none', sessionId: 'e2e-diag' });
  console.log('stop=', msg.stopReason, 'err=', msg.errorMessage, 'text=', JSON.stringify((msg.content||[]).filter(b=>b.type==='text').map(b=>b.text).join('')));
} catch (e) {
  console.log('THROW name=', e?.name, 'code=', e?.code, 'msg=', e?.message);
  console.log('cause:', e?.cause?.message);
  console.log('stack:', String(e?.stack).split('\n').slice(0,6).join('\n'));
}
