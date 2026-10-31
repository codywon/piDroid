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

function clipCodePoints(text, maxChars) {
  if (maxChars <= 0) return '';
  const points = Array.from(text);
  if (points.length <= maxChars) return text;
  return points.slice(0, Math.max(0, maxChars - 1)).join('') + '…';
}

/**
 * 社区最佳实践（Roo Code / Claude / Cursor）：
 * 上下文仅作为消解代词（指代）的参考背景，绝对不能因为“历史消息太长”而阻断用户主任务！
 * 无论传入多长、多少条上下文，服务端自动平滑滑动裁剪（Rolling Window & Auto-truncation），
 * 永远不向客户端抛出 CONTEXT_TOO_LARGE 错误。
 */
function fitContextBudget(rawContext, config) {
  if (!Array.isArray(rawContext) || config.maxContextChars <= 0 || config.maxContextMessages <= 0) {
    return [];
  }
  // 1. 过滤有效角色与非空文本
  const valid = [];
  for (const item of rawContext) {
    if (!isPlainObject(item)) continue;
    const role = item.role === 'user' || item.role === 'assistant' ? item.role : null;
    if (!role || typeof item.text !== 'string') continue;
    const text = item.text.trim();
    if (!text) continue;
    valid.push({ role, text });
  }

  // 2. 最多取最近的 maxContextMessages 条（尾部保留）
  const recent = valid.slice(-config.maxContextMessages);

  // 3. 从最新往最早累计预算（最近的对话最关键，优先保留）
  const picked = [];
  let totalChars = 0;
  for (let i = recent.length - 1; i >= 0; i--) {
    const item = recent[i];
    // 单条超长自动平滑截断，绝不报错
    const clippedText = clipCodePoints(item.text, config.maxContextMessageChars);
    const chars = codePoints(clippedText);
    if (totalChars + chars > config.maxContextChars) {
      // 若当前未纳入任何消息但单条超总预算，按总预算截取一次保证至少有最新线索
      if (picked.length === 0 && config.maxContextChars > 20) {
        const fitText = clipCodePoints(clippedText, config.maxContextChars);
        picked.unshift({ role: item.role, text: fitText });
      }
      break;
    }
    totalChars += chars;
    picked.unshift({ role: item.role, text: clippedText });
  }

  return picked;
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

    const context = fitContextBudget(body.context, config);

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
