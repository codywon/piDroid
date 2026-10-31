import { createSdkModelClient } from './prompt-enhancement/model-client.mjs';
import { maskProtectedSegments, restoreProtectedSegments } from './prompt-enhancement/protect.mjs';
import { SYSTEM_PROMPT, buildUserPayload } from './prompt-enhancement/prompt.mjs';
const c = createSdkModelClient();
const draft = '帮我看看 @ChatScreen.kt 这个报错，先给建议别改代码';
const { masked, protections } = maskProtectedSegments(draft);
console.log('masked:', masked);
console.log('protections:', JSON.stringify(protections));
const r = await c.generate({ provider: 'codywon', modelId: 'deepseek-flash', systemPrompt: SYSTEM_PROMPT,
  userText: buildUserPayload({ draft: masked, context: [] }), maxTokens: 1600, signal: new AbortController().signal });
console.log('--- raw ---');
console.log(r.text);
console.log('--- restore ---');
const rest = restoreProtectedSegments(r.text.trim(), protections);
console.log(rest.ok ? 'OK' : 'FAIL ' + rest.reason);
