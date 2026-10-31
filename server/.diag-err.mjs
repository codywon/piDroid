import { createSdkModelClient } from './prompt-enhancement/model-client.mjs';
const c = createSdkModelClient();
try {
  const r = await c.generate({ provider: 'codywon', modelId: 'deepseek-flash',
    systemPrompt: 'Reply OK.', userText: 'ping', maxTokens: 32, signal: new AbortController().signal });
  console.log('OK stop=', r.stopReason, 'text=', JSON.stringify(r.text));
} catch (e) {
  console.log('ERR code=', e.code, '| msg=', e.message);
  console.log('cause=', e.cause && e.cause.message);
}
