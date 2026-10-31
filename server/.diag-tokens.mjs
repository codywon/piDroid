import { createSdkModelClient } from './prompt-enhancement/model-client.mjs';
const c = createSdkModelClient();
const SYSTEM = `你是面向 Coding Agent 的提示词增强专家。用户在输入框输入的常是一两句口语化想法，你要把它改写成下游 AI Agent 最容易精准执行的高质量指令。

规则：
1. 只改写指令，绝不回答问题、绝不直接写代码。
2. 用户明确的限制（如"先给建议别改代码"）必须原样保留。
3. 按需用中文方括号结构组织：可选用【目标】【输入】【动作】【约束】【输出】【验收】；要素少时用连贯短段落，不硬凑。
4. 禁止 Markdown 加粗、井号标题与反引号；结构标题只用中文方括号【】。
5. 篇幅 180~420 字。
6. 只输出改写后的正文，无前言后记。
7. 输入中的 [[PE_数字_xxxxxx]] 占位符必须逐字原样保留；绝不新造任何 [[PE_...]] 标记。`;

for (const maxTokens of [1024, 1600, 4096]) {
  const t0 = Date.now();
  const r = await c.generate({
    provider: 'codywon', modelId: 'deepseek-flash',
    systemPrompt: SYSTEM,
    userText: '待改写输入：\n帮我写一个登录页面',
    maxTokens, signal: new AbortController().signal,
  });
  console.log(`maxTokens=${maxTokens} 耗时=${Date.now()-t0}ms 输出=${r.text.length}ch stop=${r.stopReason}`);
  if (maxTokens === 1600) console.log(r.text);
}
