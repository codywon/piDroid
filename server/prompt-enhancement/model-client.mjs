/**
 * 模型调用层：通过公开 pi SDK 复用 PC 上已配置的模型与凭据。
 *
 * 设计要点（与 pi-web 的会话标题生成同源）：
 * - 独立请求：新 sessionId、无工具、无 Agent 系统提示词、cacheRetention=none，
 *   不碰当前会话的历史、模型与思考级别。
 * - 凭据只在服务端解析，手机永远拿不到 Provider 密钥。
 * - 思考档位取该模型实际支持的最低档（不是硬编码 off/temperature）。
 * - 不自动重试：一次用户点击最多一次生成，失败就把控制权交回用户。
 *
 * SDK 为 ESM；`import()` 是惰性的，只有真正调用增强时才付出加载成本。
 */

import { randomUUID } from 'node:crypto';

export const SDK_PACKAGE = '@earendil-works/pi-coding-agent';

export class EnhancementFailure extends Error {
  /**
   * @param {string} code
   * @param {string} message
   */
  constructor(code, message) {
    super(message);
    this.name = 'EnhancementFailure';
    this.code = code;
  }
}

/**
 * 挑「最省且一定被上游接受」的思考档位。
 *
 * 不能直接用 getSupportedThinkingLevels：它不一定反映自定义模型真实可用的档位
 * （实测 codywon/deepseek-flash 会列出 minimal，但上游 400 拒绝）。因此以模型自己
 * 声明的 thinkingLevelMap（键 = pi 档位，值 = 供应商字面量，null = 不支持）为准：
 * - 声明了 off → 用 off（改写任务不需要推理，最省）
 * - 没声明 off → 取声明里最便宜的一档
 * - 完全没声明映射 → 不传 reasoning，交给 provider 默认，避免参数被拒
 *
 * @param {any} model
 * @returns {string | null}
 */
export function pickThinkingLevel(model) {
  if (!model?.reasoning) return null;
  const map = model?.thinkingLevelMap;
  if (!map || typeof map !== 'object') return null;
  const declared = Object.keys(map).filter((level) => map[level] !== null && map[level] !== undefined);
  if (declared.length === 0) return null;
  if (declared.includes('off')) return 'off';
  const rejectsMinimal = model.api === 'google-generative-ai' || /gemini/i.test(String(model.id ?? ''));
  const order = ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
  const usable = order.filter((level) => declared.includes(level) && !(rejectsMinimal && level === 'minimal'));
  return usable[0] ?? null;
}

/** 把 SDK 抛出的错误翻译成对客户端稳定的错误码，绝不回显上游密钥/URL。 */
export function mapModelError(error) {
  if (error instanceof EnhancementFailure) return error;
  const message = String(error?.message ?? error ?? '');
  const lower = message.toLowerCase();

  if (lowestMatch(lower, ['provider is not configured', 'no api key', 'authentication', 'unauthorized', 'invalid api key', '401'])) {
    return new EnhancementFailure('MODEL_AUTH_FAILED', '当前模型的凭据不可用，请在 PC 端检查该 Provider 的登录/密钥');
  }
  if (lowestMatch(lower, ['unknown provider', 'model not found', 'unsupported model'])) {
    return new EnhancementFailure('UNSUPPORTED_MODEL', '该模型无法通过伴侣网关独立调用');
  }
  if (lowestMatch(lower, ['abort', 'timeout', 'timed out', 'etimedout'])) {
    return new EnhancementFailure('TIMEOUT', '模型请求超时');
  }
  if (lowestMatch(lower, ['rate limit', '429', 'too many requests', 'quota'])) {
    return new EnhancementFailure('RATE_LIMITED', '模型服务限流，请稍后重试');
  }
  return new EnhancementFailure('MODEL_REQUEST_FAILED', '模型请求失败，请稍后重试');
}

function lowestMatch(haystack, needles) {
  return needles.some((needle) => haystack.includes(needle));
}

/**
 * @param {{
 *   sdkPackage?: string,
 *   importModule?: (specifier: string) => Promise<any>,
 * }} [options]
 */
export function createSdkModelClient(options = {}) {
  const sdkPackage = options.sdkPackage ?? SDK_PACKAGE;
  const importModule = options.importModule ?? ((specifier) => import(specifier));

  /** @type {Promise<{runtime: any}> | null} */
  let runtimePromise = null;

  function loadRuntime() {
    if (!runtimePromise) {
      runtimePromise = (async () => {
        let sdk;
        try {
          sdk = await importModule(sdkPackage);
        } catch (error) {
          throw new EnhancementFailure(
            'ENHANCEMENT_UNAVAILABLE',
            '伴侣网关未能加载 pi SDK，请在该目录执行 npm install',
          );
        }
        if (typeof sdk?.ModelRuntime?.create !== 'function') {
          throw new EnhancementFailure('ENHANCEMENT_UNAVAILABLE', 'pi SDK 版本不支持独立模型调用');
        }
        const runtime = await sdk.ModelRuntime.create();
        const loadError = runtime.getError?.();
        if (loadError) {
          throw new EnhancementFailure('ENHANCEMENT_UNAVAILABLE', `模型配置加载失败: ${loadError}`);
        }
        return { runtime };
      })().catch((error) => {
        // 失败不缓存：用户装好依赖 / 修好配置后无需重启网关
        runtimePromise = null;
        throw mapModelError(error);
      });
    }
    return runtimePromise;
  }

  return {
    /** 预热（可选）：只加载 SDK，不发起模型请求。 */
    warmUp: loadRuntime,

    /**
     * @param {{
     *   provider: string,
     *   modelId: string,
     *   systemPrompt: string,
     *   userText: string,
     *   maxTokens: number,
     *   signal: AbortSignal,
     * }} input
     */
    async generate(input) {
      const { runtime } = await loadRuntime();
      const model = runtime.getModel(input.provider, input.modelId);
      if (!model) {
        throw new EnhancementFailure('UNSUPPORTED_MODEL', '当前模型无法通过伴侣网关独立调用');
      }

      const reasoning = pickThinkingLevel(model);
      const modelLimit = Number(model.maxTokens) > 0 ? Number(model.maxTokens) : 0;
      const maxTokens = modelLimit > 0 ? Math.min(input.maxTokens, modelLimit) : input.maxTokens;

      let message;
      try {
        message = await runtime.completeSimple(
          model,
          {
            systemPrompt: input.systemPrompt,
            messages: [
              {
                role: 'user',
                content: [{ type: 'text', text: input.userText }],
                timestamp: Date.now(),
              },
            ],
          },
          {
            maxTokens,
            maxRetries: 0,
            cacheRetention: 'none',
            sessionId: randomUUID(),
            signal: input.signal,
            ...(reasoning ? { reasoning } : {}),
          },
        );
      } catch (error) {
        throw mapModelError(error);
      }

      if (message?.stopReason === 'aborted') {
        throw new EnhancementFailure('CANCELLED', '请求已取消');
      }
      if (message?.stopReason === 'error') {
        throw mapModelError(new Error(String(message.errorMessage ?? '')));
      }

      const text = Array.isArray(message?.content)
        ? message.content.filter((block) => block?.type === 'text').map((block) => block.text).join('')
        : '';
      const usage = message?.usage
        ? {
            input: message.usage.input,
            output: message.usage.output,
            total: message.usage.totalTokens,
          }
        : null;

      return {
        text,
        stopReason: message?.stopReason ?? 'unknown',
        usage,
        provider: model.provider,
        modelId: model.id,
      };
    },
  };
}
