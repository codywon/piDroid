/**
 * 提示词增强的服务端配置（CommonJS）。
 *
 * 网关（CJS）与增强服务（ESM）共用同一份默认值，避免 `/api/ping` 报的能力
 * 与实际执行时的预算不一致。所有值都可以用环境变量覆盖，便于在不改代码的
 * 情况下关闭功能或收紧限制。
 */

const DEFAULTS = {
  // 关闭开关：PROMPT_ENHANCE_DISABLED=1 后能力探测直接返回 available=false，
  // 手机端入口随之隐藏，原有聊天与标题功能不受影响。
  disableEnv: 'PROMPT_ENHANCE_DISABLED',
  // ── 预算原则：效果优先 ──
  // 我们面对的是 1M 上下文的模型，却曾只给 6000 字上下文、1600 token 输出，
  // 预算比模型能力低一两个数量级，模型只能在信息真空里扩写——这才是「像 demo」的根因。
  // 现在的取值按「够用且安全」定，而不是按「省 token」定：
  //   40000 字中文 ≈ 27k tokens，占 1M 窗口的 3%、占 200k 窗口的 13%，都安全。
  maxDraftChars: 12000,
  maxContextChars: 40000,
  maxContextMessages: 24,
  maxContextMessageChars: 4000,
  maxOutputChars: 20000,
  // 输出不再卡「够不够写 700 字」：4096 token（约 2500~3000 中文字）给足余量，
  // 避免模型写到一半撞 length 被判定为无效输出（那是纯损失，不是省钱）。
  outputTokens: 4096,
  // 预算放宽后单次生成会更久（实测 12k 字上下文 P90 8.5s），超时同步放宽，
  // 宁可多等几秒拿到可用的结果，也不要频繁超时让用户重来。
  timeoutMs: 50000,
  rateLimitPerMinute: 10,
  maxConcurrentRequests: 2,
};

function readInt(env, name, fallback, min, max) {
  const raw = env[name];
  if (raw === undefined || raw === null || String(raw).trim() === '') return fallback;
  const parsed = Number.parseInt(String(raw), 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

/**
 * @param {NodeJS.ProcessEnv} [env]
 */
function readEnhancementConfig(env = process.env) {
  return {
    enabled: String(env[ DEFAULTS.disableEnv ] || '') !== '1',
    maxDraftChars: readInt(env, 'PROMPT_ENHANCE_MAX_DRAFT', DEFAULTS.maxDraftChars, 200, 40000),
    maxContextChars: readInt(env, 'PROMPT_ENHANCE_MAX_CONTEXT', DEFAULTS.maxContextChars, 0, 200000),
    maxContextMessages: readInt(env, 'PROMPT_ENHANCE_MAX_CONTEXT_MESSAGES', DEFAULTS.maxContextMessages, 0, 200),
    maxContextMessageChars: readInt(env, 'PROMPT_ENHANCE_MAX_CONTEXT_MESSAGE', DEFAULTS.maxContextMessageChars, 100, 40000),
    maxOutputChars: readInt(env, 'PROMPT_ENHANCE_MAX_OUTPUT', DEFAULTS.maxOutputChars, 200, 80000),
    outputTokens: readInt(env, 'PROMPT_ENHANCE_OUTPUT_TOKENS', DEFAULTS.outputTokens, 128, 32000),
    timeoutMs: readInt(env, 'PROMPT_ENHANCE_TIMEOUT_MS', DEFAULTS.timeoutMs, 5000, 300000),
    rateLimitPerMinute: readInt(env, 'PROMPT_ENHANCE_RATE_LIMIT', DEFAULTS.rateLimitPerMinute, 1, 240),
    maxConcurrentRequests: readInt(env, 'PROMPT_ENHANCE_MAX_CONCURRENT', DEFAULTS.maxConcurrentRequests, 1, 8),
  };
}

module.exports = { readEnhancementConfig, DEFAULTS };
