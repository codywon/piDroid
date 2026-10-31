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
  maxDraftChars: 6000,
  maxContextChars: 12000,
  maxContextMessages: 10,
  maxContextMessageChars: 2500,
  maxOutputChars: 10000,
  outputTokens: 4096,
  timeoutMs: 45000,
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
    maxDraftChars: readInt(env, 'PROMPT_ENHANCE_MAX_DRAFT', DEFAULTS.maxDraftChars, 200, 20000),
    maxContextChars: readInt(env, 'PROMPT_ENHANCE_MAX_CONTEXT', DEFAULTS.maxContextChars, 0, 60000),
    maxContextMessages: readInt(env, 'PROMPT_ENHANCE_MAX_CONTEXT_MESSAGES', DEFAULTS.maxContextMessages, 0, 50),
    maxContextMessageChars: readInt(env, 'PROMPT_ENHANCE_MAX_CONTEXT_MESSAGE', DEFAULTS.maxContextMessageChars, 100, 10000),
    maxOutputChars: readInt(env, 'PROMPT_ENHANCE_MAX_OUTPUT', DEFAULTS.maxOutputChars, 200, 40000),
    outputTokens: readInt(env, 'PROMPT_ENHANCE_OUTPUT_TOKENS', DEFAULTS.outputTokens, 128, 32000),
    timeoutMs: readInt(env, 'PROMPT_ENHANCE_TIMEOUT_MS', DEFAULTS.timeoutMs, 5000, 180000),
    rateLimitPerMinute: readInt(env, 'PROMPT_ENHANCE_RATE_LIMIT', DEFAULTS.rateLimitPerMinute, 1, 240),
    maxConcurrentRequests: readInt(env, 'PROMPT_ENHANCE_MAX_CONCURRENT', DEFAULTS.maxConcurrentRequests, 1, 8),
  };
}

module.exports = { readEnhancementConfig, DEFAULTS };
