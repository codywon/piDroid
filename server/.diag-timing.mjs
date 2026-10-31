import { createEnhancementService } from './prompt-enhancement/service.mjs';
import { createSdkModelClient } from './prompt-enhancement/model-client.mjs';

const svc = createEnhancementService({ modelClient: createSdkModelClient() });
const PROVIDER = process.env.P || 'codywon';
const MODEL = process.env.M || 'deepseek-flash';

function bigContext(chars) {
  const per = Math.floor(chars / 4) - 10;
  return [0,1,2,3].map(i => ({
    role: i % 2 === 0 ? 'user' : 'assistant',
    text: `第${i}轮: ` + '这是一段较长的技术讨论内容，包含结论与后续计划。'.repeat(Math.ceil(per / 22)),
  }));
}

for (const chars of [0, 2000, 8000, 12000]) {
  const t0 = Date.now();
  const r = await svc.enhance({
    requestId: 'diag', sessionId: 'diag-' + chars, draftRevision: 1, branchRevision: 1,
    provider: PROVIDER, modelId: MODEL,
    draft: '帮我写一个登录页面',
    context: chars === 0 ? [] : bigContext(chars),
  }, { subject: 'diag' });
  const ms = Date.now() - t0;
  const outLen = r.ok ? r.value.enhancedText.length : 0;
  console.log(`context=${String(chars).padStart(5)}ch  elapsed=${String(ms).padStart(6)}ms  ok=${r.ok}  out=${outLen}ch  ${r.ok ? '' : r.code + ' ' + r.message}`);
}
