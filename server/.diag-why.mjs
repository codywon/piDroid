import { ModelRuntime } from '@earendil-works/pi-coding-agent';
import { pickThinkingLevel } from './prompt-enhancement/model-client.mjs';
const rt = await ModelRuntime.create({ allowModelNetwork: false, refreshOnCreate: false });
const m = rt.getModel('codywon', 'deepseek-flash');
console.log('getModel 命中:', Boolean(m));
if (m) {
  console.log('  api=', m.api, 'reasoning=', m.reasoning, 'maxTokens=', m.maxTokens, 'contextWindow=', m.contextWindow);
  console.log('  thinkingLevelMap=', JSON.stringify(m.thinkingLevelMap));
  console.log('  pickThinkingLevel =>', pickThinkingLevel(m));
}
