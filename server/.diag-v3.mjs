import { createEnhancementService } from './prompt-enhancement/service.mjs';
import { createSdkModelClient } from './prompt-enhancement/model-client.mjs';
const svc = createEnhancementService({ modelClient: createSdkModelClient(), config: { ...(await import('./prompt-enhancement/config.js')).readEnhancementConfig(), outputTokens: 1600, timeoutMs: 25000 } });
const cases = [
  ['帮我写一个登录页面', []],
  ['分析下内存泄漏', []],
  ['帮我看看 @ChatScreen.kt 这个报错，先给建议别改代码', []],
  ['把刚才那个接口改成异步', [{role:'user',text:'我们在重构网络层，改用 OkHttp 的 enqueue。'},{role:'assistant',text:'已确认 NetworkClient.fetch() 目前是阻塞调用。'}]],
];
for (const [draft, context] of cases) {
  const t0 = Date.now();
  const r = await svc.enhance({ requestId: 'v3', sessionId: 'v3-' + Math.random(), draftRevision: 1, branchRevision: 1,
    provider: 'codywon', modelId: 'deepseek-flash', draft, context }, { subject: 'v3' });
  console.log(`[${Date.now()-t0}ms] 原: ${draft}`);
  console.log(r.ok ? r.value.enhancedText : 'FAIL ' + r.code);
  console.log('='.repeat(70));
}
