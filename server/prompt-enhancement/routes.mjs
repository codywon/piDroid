/**
 * HTTP 适配层：只做鉴权前置、请求体上限、错误码映射与取消信号接线。
 *
 * 鉴权由网关的 authMiddleware 承担（Bearer token）；这里额外把 token 哈希成
 * 限流主体，绝不把 token 本身写进日志或桶键。
 */

import { createHash } from 'node:crypto';

const CODE_TO_STATUS = {
  INVALID_INPUT: 400,
  CONTEXT_TOO_LARGE: 413,
  UNSUPPORTED_MODEL: 422,
  INVALID_OUTPUT: 422,
  MODEL_AUTH_FAILED: 502,
  MODEL_REQUEST_FAILED: 502,
  RATE_LIMITED: 429,
  TIMEOUT: 504,
  ENHANCEMENT_UNAVAILABLE: 503,
  CANCELLED: 499,
};

/** token 只用于分桶，绝不回显或落盘。 */
export function subjectFromAuthorization(authorization) {
  const raw = typeof authorization === 'string' ? authorization : '';
  return createHash('sha256').update(raw).digest('hex').slice(0, 16);
}

/**
 * @param {{ service: { enhance: (body: unknown, options?: any) => Promise<any> } }} options
 */
export function createPromptEnhancementHandler({ service }) {
  return async function handlePromptEnhancement(req, res) {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');

    const controller = new AbortController();
    // 必须监听 res 的 close：请求体读完时 req 就会触发 close，监听 req 会把健康
    // 请求全部误取消；res close 才同时代表「正常结束」与「客户端断开」。
    const onClose = () => {
      if (!res.writableEnded) controller.abort();
    };
    res.on('close', onClose);

    try {
      const result = await service.enhance(req.body, {
        signal: controller.signal,
        subject: subjectFromAuthorization(req.headers?.authorization),
      });

      if (result.ok) {
        const { enhancedText, ...meta } = result.value;
        res.status(200).json({ ...meta, enhancedText });
        return;
      }

      const status = CODE_TO_STATUS[result.code] ?? 500;
      if (res.writableEnded || res.destroyed) return;
      if (status === 499) {
        // 客户端已离开：只结束，不写响应体
        res.status(499).end();
        return;
      }
      res.status(status).json({
        requestId: typeof req.body?.requestId === 'string' ? req.body.requestId : null,
        code: result.code,
        message: result.message,
      });
    } catch (error) {
      if (!res.writableEnded) {
        res.status(500).json({ requestId: null, code: 'INTERNAL_ERROR', message: '增强服务内部错误' });
      }
    } finally {
      res.off('close', onClose);
    }
  };
}
