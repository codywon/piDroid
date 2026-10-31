#!/usr/bin/env node
/**
 * 提示词增强的真实调用冒烟脚本（**会真实调用模型并产生费用**）。
 *
 * 用途：升级 pi SDK / 换 Provider / 改系统提示词后，人工确认「独立调用链」仍可用。
 * 日常回归不要跑它，跑 `npm test` 就够了（那里全部用假模型，零费用）。
 *
 * 用法：
 *   node scripts/smoke-prompt-enhancement.mjs --provider codywon --model deepseek-flash
 */
import { createEnhancementService } from '../prompt-enhancement/service.mjs';
import { createSdkModelClient } from '../prompt-enhancement/model-client.mjs';

function readArg(name) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

const provider = readArg('provider');
const modelId = readArg('model');
if (!provider || !modelId) {
  console.error('用法: node scripts/smoke-prompt-enhancement.mjs --provider <provider> --model <modelId>');
  process.exit(1);
}

const service = createEnhancementService({ modelClient: createSdkModelClient() });
const started = Date.now();
const result = await service.enhance(
  {
    requestId: `smoke-${Date.now()}`,
    sessionId: 'smoke-session',
    draftRevision: 1,
    branchRevision: 1,
    provider,
    modelId,
    draft: '帮我看看 @ChatScreen.kt 这个报错怎么回事，先给建议别改代码。',
    context: [{ role: 'user', text: '刚在排查启动失败的问题' }],
  },
  { subject: 'smoke' },
);

console.log(`elapsed_ms=${Date.now() - started}`);
console.log(JSON.stringify(result, null, 2));
// 期望：ok=true，enhancedText 里 @ChatScreen.kt 原样保留，"先给建议 / 别改代码" 仍然在
process.exit(result.ok ? 0 : 1);
