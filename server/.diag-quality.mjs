import { createEnhancementService } from './prompt-enhancement/service.mjs';
import { createSdkModelClient } from './prompt-enhancement/model-client.mjs';
const svc = createEnhancementService({ modelClient: createSdkModelClient() });
for (const draft of ['帮我写一个登录页面', '分析下内存泄漏', '帮我看看 @ChatScreen.kt 这个报错，先给建议别改代码']) {
  const t0 = Date.now();
  const r = await svc.enhance({ requestId: 'q', sessionId: 'q-' + Math.random(), draftRevision: 1, branchRevision: 1,
    provider: 'codywon', modelId: 'deepseek-flash', draft, context: [] }, { subject: 'q' });
  console.log('[' + (Date.now() - t0) + 'ms] 原始:', draft);
  console.log(r.ok ? r.value.enhancedText : 'FAIL ' + r.code + ' ' + r.message);
  console.log('-'.repeat(60));
}
