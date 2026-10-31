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
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

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
 * 读取 ~/.pi/agent/models.json 中某个 provider 的声明（baseUrl / api / modelOverrides）。
 *
 * 为什么需要这一步：
 * pi 1.4 起 models.json 的新格式用 `modelOverrides` 声明自定义 provider 的模型参数，
 * 而真正的模型目录来自网络拉取的 `models-store.json`。当该 provider 的目录缓存为空
 * （首次使用 / 网络拉取失败）时，`runtime.getModel()` 直接返回 undefined ——
 * 明明 models.json 里写清了 baseUrl 与 7 个模型 id，却被当成「不支持」。
 * 这里把声明读出来，用于就地合成一个可用的 Model。
 *
 * @param {string} providerId
 * @param {(dir: string) => string} [resolveAgentDir]
 * @returns {{ baseUrl?: string, api?: string, modelOverrides?: Record<string, any>, models?: any[] } | null}
 */
export function readProviderDeclaration(providerId, resolveAgentDir) {
  try {
    // 注意：本文件是 ESM（.mjs），不能用 require() —— 那会抛错并被 catch 吞成「未配置」，
    // 表现为「models.json 明明有 baseUrl 却说无法解析模型」。必须用顶层 import。
    const agentDir = resolveAgentDir ? resolveAgentDir('.') : null;
    if (!agentDir) return null;
    const modelsPath = join(agentDir, 'models.json');
    if (!existsSync(modelsPath)) return null;
    const parsed = JSON.parse(readFileSync(modelsPath, 'utf8'));
    const entry = parsed?.providers?.[providerId];
    return entry && typeof entry === 'object' ? entry : null;
  } catch {
    return null;
  }
}

/**
 * 把 provider 声明合成为 pi SDK 能直接使用的 Model 对象。
 *
 * 刻意不声明 `reasoning`：自定义 provider 的思考档位约定不明，冒然传 reasoning
 * 会被上游 400 拒（实测 codywon 只接受 low/medium/high），交给 provider 默认最安全。
 *
 * @param {{ provider: string, modelId: string, declaration: any }} input
 * @returns {any | null}
 */
export function synthesizeModel({ provider, modelId, declaration }) {
  if (!declaration || typeof declaration !== 'object') return null;
  const baseUrl = typeof declaration.baseUrl === 'string' ? declaration.baseUrl : undefined;
  const api = typeof declaration.api === 'string' ? declaration.api : 'openai-completions';
  // 没有 baseUrl 就不是可用的自定义 provider（内置 provider 的模型应由目录提供）
  if (!baseUrl) return null;
  const override = declaration.modelOverrides?.[modelId] ?? {};
  const fromArray = Array.isArray(declaration.models)
    ? declaration.models.find((m) => m && m.id === modelId)
    : undefined;
  return {
    id: modelId,
    name: typeof fromArray?.name === 'string' ? fromArray.name : modelId,
    provider,
    api,
    baseUrl,
    input: ['text'],
    contextWindow: Number(override.contextWindow) > 0 ? Number(override.contextWindow) : 200000,
    maxTokens: Number(override.maxTokens) > 0 ? Number(override.maxTokens) : 8192,
    reasoning: false,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  };
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
        // 关键：不打网络模型目录刷新。实测 allowModelNetwork 默认值会让首帧
        // 卡在拉取 provider 的 /v1/models 上十几秒（失败后还是空目录），而手机端
        // 看到的就是「点一下魔棒一直转圈」。模型目录用盘上的 models-store.json 即可。
        const runtime = await sdk.ModelRuntime.create({
          allowModelNetwork: false,
          refreshOnCreate: false,
        });
        const loadError = runtime.getError?.();
        if (loadError) {
          throw new EnhancementFailure('ENHANCEMENT_UNAVAILABLE', `模型配置加载失败: ${loadError}`);
        }
        const resolveAgentDir = typeof sdk.getAgentDir === 'function' ? sdk.getAgentDir : null;
        return { runtime, resolveAgentDir };
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
      const { runtime, resolveAgentDir } = await loadRuntime();
      let model = runtime.getModel(input.provider, input.modelId);
      // 目录里没有（缓存为空 / 自定义 provider 未拉目录）就按 models.json 的声明合成，
      // 而不是把用户明明配置好的模型判成「不支持」。
      if (!model) {
        const declaration = readProviderDeclaration(input.provider, resolveAgentDir);
        model = synthesizeModel({
          provider: input.provider,
          modelId: input.modelId,
          declaration,
        });
      }
      if (!model) {
        throw new EnhancementFailure(
          'UNSUPPORTED_MODEL',
          `网关无法解析模型 ${input.provider}/${input.modelId}（models.json 缺少 baseUrl 或模型定义）`,
        );
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
