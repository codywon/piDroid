/**
 * 确定性内容保护。
 *
 * 模型改写草稿时最危险的破坏不是「写得不漂亮」，而是把代码、引用、URL、
 * 路径、模板变量改掉/吞掉。这些片段一旦变化，用户发出的指令含义就变了。
 * 因此这里先把它们替换成占位符，改写完成后再逐字恢复；任何一个占位符
 * 在结果里找不到、或出现残留占位符，都判定为无效输出（保留原稿）。
 *
 * 说明：这套机制保证「受保护片段逐字不变」，不能保证语义不变。语义保真
 * 由系统提示词 + 真实模型评测保证，二者不可互相替代。
 */

/** 占位符形态：[[PE_序号_随机串]]，刻意使用模型不易翻译的纯 ASCII 结构。 */
const PLACEHOLDER_RE = /\[\[PE_\d+_[0-9a-z]{6}\]\]/g;

function placeholderPattern() {
  return /\[\[PE_\d+_[0-9a-z]{6}\]\]/;
}

/**
 * 受保护片段的原始值里如果已经包含占位符样式，就放弃随机串重试，避免
 * 恢复阶段产生歧义。
 */
function makePlaceholder(index, seed, existing) {
  const suffix = seed.toString(36).slice(-6).padStart(6, '0');
  const candidate = `[[PE_${index}_${suffix}]]`;
  return existing.includes(candidate) ? null : candidate;
}

/**
 * 保护规则按「长的先匹配」排列：代码围栏必须早于内联代码，URL 必须早于
 * 路径，否则会被更短的规则切碎。
 */
/**
 * 尾部标点排除在受保护片段之外：`@ChatScreen.kt，别改` 里的中文逗号
 * 属于用户正文，不能被一起锁进占位符（否则那句限制就丢了）。
 */
const TRAILING_PUNCTUATION = /[，。；：、,.;:!?！？)）】》"'”’]+$/;

function splitTrailingPunctuation(match) {
  const found = match.match(TRAILING_PUNCTUATION);
  if (!found) return [match, ''];
  const trailing = found[0];
  return [match.slice(0, match.length - trailing.length), trailing];
}

const RULES = [
  // 完整代码围栏（含未闭合围栏由 hasUnclosedFence 单独拦截）
  { name: 'fence', re: /```[\s\S]*?```/g },
  // 内联代码
  { name: 'inline_code', re: /`[^`\n]+`/g },
  // URL
  { name: 'url', re: /https?:\/\/[^\s<>"'`，。；：、！？（）【】“”]+/g, trim: true },
  // @ 引用：@ 开头直到空白或中文/句读分隔符（pi 的引用记号不会含这些）
  { name: 'reference', re: /@[^\s@，。；：、,;:!?！？()（）\[\]【】<>"'“”‘’]+/g, trim: true },
  // Windows 绝对路径（C:\... / \\server\share）
  { name: 'win_path', re: /[A-Za-z]:\\[^\s"'`]+/g, trim: true },
  // POSIX 路径：/a/b、./a、../a（单段 "a/b" 不算路径，避免误伤普通文本）
  { name: 'posix_path', re: /\B(?:\/|\.\.?\/)[\w.@-]+(?:\/[\w.@-]+)*/g, trim: true },
  // 模板变量：{{var}} / ${var} / {{ var }}
  { name: 'template_var', re: /\{\{[^{}\n]{1,80}\}\}|\$\{[^{}\n]{1,80}\}/g },
];

/**
 * 未闭合的代码围栏无法可靠判定边界，首版直接拒绝增强（调用方据此报错）。
 * @param {string} text
 * @returns {boolean}
 */
export function hasUnclosedFence(text) {
  const count = (text.match(/```/g) || []).length;
  return count % 2 === 1;
}

/**
 * 把受保护片段替换成占位符。
 * @param {string} text
 * @returns {{ masked: string, protections: Array<{placeholder: string, original: string, kind: string}> }}
 */
export function maskProtectedSegments(text) {
  const protections = [];
  let masked = text;
  // 计数器决定占位符序号，也充当随机种子的一部分，保证同一次请求内唯一。
  let counter = 0;
  for (const rule of RULES) {
    masked = masked.replace(rule.re, (match) => {
      // 已包含占位符的文本不再二次包装
      if (placeholderPattern().test(match)) return match;
      const [core, trailing] = rule.trim ? splitTrailingPunctuation(match) : [match, ''];
      if (!core) return match;
      counter += 1;
      let placeholder = null;
      let attempt = 0;
      while (placeholder === null && attempt < 8) {
        placeholder = makePlaceholder(counter, (counter * 2654435761 + attempt * 97) >>> 0, masked);
        attempt += 1;
      }
      if (placeholder === null) return match;
      protections.push({ placeholder, original: core, kind: rule.name });
      return placeholder + trailing;
    });
  }
  return { masked, protections };
}

/**
 * 去掉模型可能整体包上的一层代码围栏（只在原文没有围栏时才剥，
 * 避免破坏用户本来就要的代码块）。
 * @param {string} text
 * @returns {string}
 */
export function stripWrappingFence(text) {
  const trimmed = text.trim();
  const match = trimmed.match(/^```[A-Za-z0-9_+-]*\n([\s\S]*?)\n```$/);
  if (match) return match[1].trim();
  return trimmed;
}

/**
 * 把占位符恢复成原文，并做完整性校验。
 * @param {string} output
 * @param {Array<{placeholder: string, original: string}>} protections
 * @returns {{ ok: true, text: string } | { ok: false, reason: string }}
 */
export function restoreProtectedSegments(output, protections) {
  let restored = output;
  for (const item of protections) {
    const index = restored.indexOf(item.placeholder);
    if (index < 0) {
      return { ok: false, reason: 'missing_protected_segment' };
    }
    restored = restored.slice(0, index) + item.original + restored.slice(index + item.placeholder.length);
  }
  PLACEHOLDER_RE.lastIndex = 0;
  if (PLACEHOLDER_RE.test(restored)) {
    return { ok: false, reason: 'leftover_placeholder' };
  }
  return { ok: true, text: restored };
}

/**
 * 输出净化：只做「必须做且不会改变用户内容」的处理。
 * 刻意不做全局去 Markdown / 去反引号 / 强制截断 —— 那些会破坏代码与格式。
 * @param {string} raw
 * @param {{ maxChars: number }} options
 * @returns {{ ok: true, text: string } | { ok: false, reason: string }}
 */
export function normalizeModelOutput(raw, { maxChars }) {
  if (typeof raw !== 'string') return { ok: false, reason: 'empty_output' };
  let text = raw.replace(/\r\n/g, '\n').trim();
  if (!text) return { ok: false, reason: 'empty_output' };
  if (hasUnclosedFence(text)) return { ok: false, reason: 'unclosed_fence' };
  // 模型偶尔把结果放进 JSON 或标题式前缀里；只剥「整段被引号包住」这一种。
  if (/^"(?:[^"\\]|\\.)*"$/s.test(text)) {
    try {
      const parsed = JSON.parse(text);
      if (typeof parsed === 'string') text = parsed.trim();
    } catch {
      // 解析失败就按原文处理
    }
  }
  if (!text) return { ok: false, reason: 'empty_output' };
  if (Array.from(text).length > maxChars) return { ok: false, reason: 'output_too_long' };
  return { ok: true, text };
}

export const PROTECTED_KINDS = RULES.map((rule) => rule.name);

/** 原文里是否已有代码围栏（决定要不要剥模型整体包上的围栏）。 */
export function containsFence(text) {
  return /```/.test(text);
}
