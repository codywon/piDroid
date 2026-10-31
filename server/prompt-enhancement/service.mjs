/**
 * 增强服务：输入校验 → 内容保护 → 独立模型调用 → 输出校验。
 *
 * 所有失败路径都不修改用户草稿（服务端本来就不持有草稿），并把失败原因
 * 收敛成稳定错误码；路由层只负责把它们翻译成 HTTP。
 */

import { readEnhancementConfig } from './config.js';
import { PROMPT_VERSION, SYSTEM_PROMPT, buildUserPayload } from './prompt.mjs';
import {
  containsFence,
  hasUnclosedFence,
  maskProtectedSegments,
  normalizeModelOutput,
  restoreProtectedSegments,
  stripWrappingFence,
} from './protect.mjs';
import { EnhancementFailure, mapModelError } from './model-client.mjs';

export const CAPABILITY_VERSION = 1;

/** 首版明确不支持：斜杠命令与 shell 前缀不参与增强（避免改写执行语义）。 */
const COMMAND_PREFIX_RE = /^\s*(?:\/|!\s*\S)/;

function codePoints(text) {
  return Array.from(text).length;
}

function failure(code, message) {
  return { ok: false, code, message };
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asNonEmptyString(value, max) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > max) return null;
  return trimmed;
}

function asRevision(value) {
  return Number.isInteger(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER ? value : null;
}

/**
 * @param {{
 *   modelClient: { generate: (input: any) => Promise<any> },
 *   config?: ReturnType<typeof readEnhancementConfig>,
 *   now?: () => number,
 * }} options
 */
export function createEnhancementService({ modelClient, config = readEnhancementConfig(), now = () => Date.now() }) {
  /** @type {Map<string, number[]>} 滑动窗口：subject → 时间戳 */
  const rateBuckets = new Map();
  /** @type {Set<string>} 同一会话同时只允许一个生成请求 */
  const busySessions = new Set();
  let inFlight = 0;

  function capability() {
    return {
      version: CAPABILITY_VERSION,
      available: Boolean(config.enabled && modelClient),
      promptVersion: PROMPT_VERSION,
      maxDraftChars: config.maxDraftChars,
      maxContextChars: config.maxContextChars,
    };
  }

  function checkRateLimit(subject) {
    const at = now();
    const windowStart = at - 60_000;
    const bucket = (rateBuckets.get(subject) ?? []).filter((ts) => ts > windowStart);
    if (bucket.length >= config.rateLimitPerMinute) {
      rateBuckets.set(subject, bucket);
      return false;
    }
    bucket.push(at);
    rateBuckets.set(subject, bucket);
    return true;
  }

  /**
   * 校验请求体；返回归一化后的值或稳定错误。
   * @param {unknown} body
   */
  function validate(body) {
    if (!isPlainObject(body)) return failure('INVALID_INPUT', '请求体必须是 JSON 对象');

    const requestId = asNonEmptyString(body.requestId, 80);
    const sessionId = asNonEmptyString(body.sessionId, 200);
    const provider = asNonEmptyString(body.provider, 200);
    const modelId = asNonEmptyString(body.modelId, 200);
    const draftRevision = asRevision(body.draftRevision);
    const branchRevision = asRevision(body.branchRevision);

    if (!requestId) return failure('INVALID_INPUT', 'requestId 无效');
    if (!sessionId) return failure('INVALID_INPUT', 'sessionId 无效');
    if (!provider) return failure('INVALID_INPUT', '缺少当前会话的 Provider');
    if (!modelId) return failure('INVALID_INPUT', '缺少当前会话的模型');
    if (draftRevision === null) return failure('INVALID_INPUT', 'draftRevision 无效');
    if (branchRevision === null) return failure('INVALID_INPUT', 'branchRevision 无效');

    if (typeof body.draft !== 'string') return failure('INVALID_INPUT', 'draft 必须是字符串');
    const draft = body.draft.replace(/\r\n/g, '\n');
    if (!draft.trim()) return failure('INVALID_INPUT', '草稿为空');
    if (COMMAND_PREFIX_RE.test(draft)) {
      return failure('INVALID_INPUT', '以命令或 shell 前缀开头的内容不参与增强');
    }
    if (codePoints(draft) > config.maxDraftChars) {
      return failure('INVALID_INPUT', `草稿超过 ${config.maxDraftChars} 字上限`);
    }
    if (hasUnclosedFence(draft)) {
      return failure('INVALID_INPUT', '草稿含未闭合的代码围栏，无法安全增强');
    }

    const rawContext = body.context ?? [];
    if (!Array.isArray(rawContext)) return failure('INVALID_INPUT', 'context 必须是数组');
    if (rawContext.length > config.maxContextMessages) {
      return failure('CONTEXT_TOO_LARGE', `上下文条数超过 ${config.maxContextMessages} 条上限`);
    }

    const context = [];
    let contextChars = 0;
    for (const item of rawContext) {
      if (!isPlainObject(item)) return failure('INVALID_INPUT', 'context 条目格式错误');
      const role = item.role === 'user' || item.role === 'assistant' ? item.role : null;
      if (!role) return failure('INVALID_INPUT', 'context 角色必须是 user 或 assistant');
      if (typeof item.text !== 'string') return failure('INVALID_INPUT', 'context 文本必须是字符串');
      const text = item.text.trim();
      if (!text) continue;
      if (codePoints(text) > config.maxContextMessageChars) {
        return failure('CONTEXT_TOO_LARGE', `单条上下文超过 ${config.maxContextMessageChars} 字上限`);
      }
      contextChars += codePoints(text);
      if (contextChars > config.maxContextChars) {
        return failure('CONTEXT_TOO_LARGE', `上下文超过 ${config.maxContextChars} 字上限`);
      }
      context.push({ role, text });
    }

    return {
      ok: true,
      value: { requestId, sessionId, provider, modelId, draftRevision, branchRevision, draft, context },
    };
  }

  /**
   * @param {unknown} body
   * @param {{ signal?: AbortSignal, subject?: string }} [options]
   */
  async function enhance(body, options = {}) {
    if (!config.enabled || !modelClient) {
      return failure('ENHANCEMENT_UNAVAILABLE', '当前网关未启用提示词增强');
    }

    const subject = options.subject || 'anonymous';
    if (!checkRateLimit(subject)) {
      return failure('RATE_LIMITED', '增强请求过于频繁，请稍后再试');
    }

    const checked = validate(body);
    if (!checked.ok) return checked;
    const request = checked.value;

    if (busySessions.has(request.sessionId)) {
      return failure('RATE_LIMITED', '该会话已有增强请求在进行中');
    }
    if (inFlight >= config.maxConcurrentRequests) {
      return failure('RATE_LIMITED', '增强服务繁忙，请稍后重试');
    }

    busySessions.add(request.sessionId);
    inFlight += 1;

    const controller = new AbortController();
    let timedOut = false;
    const onExternalAbort = () => controller.abort();
    if (options.signal) {
      if (options.signal.aborted) controller.abort();
      else options.signal.addEventListener('abort', onExternalAbort, { once: true });
    }
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, config.timeoutMs);

    try {
      const { masked, protections } = maskProtectedSegments(request.draft);
      const userText = buildUserPayload({ draft: masked, context: request.context });

      let generated;
      try {
        generated = await modelClient.generate({
          provider: request.provider,
          modelId: request.modelId,
          systemPrompt: SYSTEM_PROMPT,
          userText,
          maxTokens: config.outputTokens,
          signal: controller.signal,
        });
      } catch (error) {
        const mapped = mapModelError(error);
        if (mapped.code === 'CANCELLED' || mapped.code === 'TIMEOUT') {
          return failure(timedOut ? 'TIMEOUT' : 'CANCELLED', timedOut ? '增强请求超时' : '增强请求已取消');
        }
        return failure(mapped.code, mapped.message);
      }

      if (controller.signal.aborted) {
        return failure(timedOut ? 'TIMEOUT' : 'CANCELLED', timedOut ? '增强请求超时' : '增强请求已取消');
      }
      if (generated?.stopReason === 'length') {
        return failure('INVALID_OUTPUT', '模型输出被截断，已保留原稿');
      }
      if (generated?.stopReason === 'toolUse') {
        return failure('INVALID_OUTPUT', '模型返回了工具调用，已保留原稿');
      }

      const normalized = normalizeModelOutput(generated?.text, { maxChars: config.maxOutputChars });
      if (!normalized.ok) {
        return failure('INVALID_OUTPUT', '模型输出不可用，已保留原稿');
      }

      // 原文没有代码围栏时，整体被包上围栏属于改写越界（规则第 9 条），
      // 只有「无语言标记的纯包裹」才当作模型瑕疵剥掉，其余直接判无效。
      let candidate = normalized.text;
      if (!containsFence(request.draft)) {
        if (/^```[A-Za-z0-9_+-]/.test(candidate)) {
          return failure('INVALID_OUTPUT', '模型给结果套了代码围栏，已保留原稿');
        }
        candidate = stripWrappingFence(candidate);
      }

      const restored = restoreProtectedSegments(candidate, protections);
      if (!restored.ok) {
        return failure('INVALID_OUTPUT', '模型改动了受保护片段，已保留原稿');
      }

      const text = restored.text.trim();
      if (!text) return failure('INVALID_OUTPUT', '模型输出为空，已保留原稿');
      if (codePoints(text) > config.maxOutputChars) {
        return failure('INVALID_OUTPUT', '模型输出过长，已保留原稿');
      }

      return {
        ok: true,
        value: {
          requestId: request.requestId,
          draftRevision: request.draftRevision,
          branchRevision: request.branchRevision,
          enhancedText: text,
          changed: text !== request.draft.trim(),
          model: { provider: request.provider, modelId: request.modelId },
          contextUsed: request.context.length > 0,
          promptVersion: PROMPT_VERSION,
          usage: generated?.usage ?? null,
        },
      };
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener?.('abort', onExternalAbort);
      busySessions.delete(request.sessionId);
      inFlight -= 1;
    }
  }

  // 供测试与诊断使用，不对外暴露
  return { capability, enhance, _internals: { validate, codePoints } };
}

export { EnhancementFailure };
