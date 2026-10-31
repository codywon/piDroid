/**
 * piDroid 公网通道（默认）—— frp 客户端
 *
 * 为什么默认走它：Cloudflare Tunnel 的入口 IP 由 Anycast 决定，国内直连常绕到
 * 美国西海岸（200~400ms）并在晚高峰被 QoS 丢包，手机端表现为「获取会话列表失败」
 * 「一直自动重连中」。自有服务器 + frp 走 TCP 直连，通常 100~200ms 且不丢包。
 *
 * 完整链路（域名以用户自己的 frpc 配置为准，仓库里不写死）：
 *   手机 App → https://<域名>/pi/api/...   (nginx 443 + Let's Encrypt)
 *            → frps vhost（绑定 docker 网桥网关，仅容器可达，公网 vhost 端口关着）
 *            → frpc（本模块守护的进程）→ 127.0.0.1:4100（本网关）
 *
 * 本模块只做三件事：找到 frpc 与它的 toml、拉起并守护它、把公网地址告诉调用方。
 * 配置真源始终是用户自己的 toml（默认 D:\frpc\frpc-pidroid.toml），
 * 仓库里不保存任何 token —— 换服务器只改 toml，不用改代码。
 */

const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

/** nginx 给 frp 隧道留的路径前缀（与服务器 nginx.conf 的 `location /pi/` 对应）。 */
const DEFAULT_PUBLIC_PATH = "/pi";
/** 断线重连退避上限：frpc 自己也会重试，这里只兜住「进程直接挂了」的情况。 */
const RESTART_MAX_DELAY_MS = 30000;

/** 去掉行尾 TOML 注释（`key = "v" # 说明`），否则带注释的配置会被误判。 */
function stripTomlComment(line) {
  let inString = false;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (ch === '"' && line[i - 1] !== "\\") inString = !inString;
    else if (ch === "#" && !inString) return line.slice(0, i);
  }
  return line;
}

/**
 * 从 frpc 的 toml 里读出「推导公网地址 / 校验端口」需要的字段。
 * 纯函数（不碰磁盘），便于单测；只解析本模块用到的极少数键，不做完整 TOML 实现。
 *
 * @returns {{ serverAddr: string, serverPort: number, proxies: Array<{ type: string, name: string, localPort: number, customDomains: string[] }>, transportProtocol: string } | null}
 */
function parseFrpcConfig(text) {
  if (typeof text !== "string" || !text.trim()) return null;
  const config = { serverAddr: "", serverPort: 0, transportProtocol: "tcp", proxies: [] };
  let current = null;
  let sawKeyValue = false;

  for (const raw of text.split(/\r?\n/)) {
    const line = stripTomlComment(raw).trim();
    if (!line) continue;

    if (/^\[\[proxies\]\]$/.test(line)) {
      current = { type: "", name: "", localPort: 0, customDomains: [] };
      config.proxies.push(current);
      continue;
    }
    if (line.startsWith("[")) {
      // 其它 section（[auth]、[transport]、[log] …）：本模块不关心
      current = null;
      continue;
    }

    const eq = line.indexOf("=");
    if (eq === -1) continue;
    sawKeyValue = true;
    const key = line.slice(0, eq).trim();
    const value = line.slice(eq + 1).trim();

    if (!current) {
      if (key === "serverAddr") config.serverAddr = unquote(value);
      else if (key === "serverPort") config.serverPort = toPort(value);
      else if (key === "transport.protocol") config.transportProtocol = unquote(value).toLowerCase();
      continue;
    }

    if (key === "type") current.type = unquote(value).toLowerCase();
    else if (key === "name") current.name = unquote(value);
    else if (key === "localPort") current.localPort = toPort(value);
    else if (key === "customDomains") current.customDomains = parseStringArray(value);
  }

  // 一个键都没解析出来（空文件 / 全是注释 / 根本不是 TOML）时返回 null，
  // 让调用方区分「配置没读到」与「读到了但没写该字段」。
  if (!sawKeyValue) return null;
  return config;
}

function unquote(value) {
  const text = String(value || "").trim();
  if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) return text.slice(1, -1);
  if (text.length >= 2 && text.startsWith("'") && text.endsWith("'")) return text.slice(1, -1);
  return text;
}

function toPort(value) {
  const n = parseInt(unquote(value), 10);
  return Number.isFinite(n) && n > 0 && n <= 65535 ? n : 0;
}

/** `["a.com", "b.com"]` → ['a.com','b.com']；容忍单行多值。 */
function parseStringArray(value) {
  const text = String(value || "");
  const out = [];
  const re = /"([^"]*)"|'([^']*)'/g;
  let m = re.exec(text);
  while (m) {
    const item = (m[1] !== undefined ? m[1] : m[2]).trim();
    if (item) out.push(item);
    m = re.exec(text);
  }
  return out;
}

/** 取第一个 http 代理（隧道入口就从它来）。 */
function firstHttpProxy(config) {
  if (!config || !Array.isArray(config.proxies)) return null;
  return config.proxies.find((p) => p && p.type === "http" && p.customDomains.length > 0) || null;
}

/**
 * 公网地址：PI_PUBLIC_URL 显式指定优先，否则由 toml 里的 customDomains[0] + 路径前缀拼出来。
 * 拼接而不是硬编码域名，换域名时只改一份真源（toml）。
 */
function resolvePublicUrl({ env = process.env, config } = {}) {
  const explicit = String((env && env.PI_PUBLIC_URL) || "").trim();
  if (explicit) return explicit.replace(/\/+$/, "");

  const proxy = firstHttpProxy(config);
  if (!proxy) return "";
  const domain = proxy.customDomains[0];
  if (!domain) return "";

  const rawPrefix = String((env && env.PI_PUBLIC_PATH) || DEFAULT_PUBLIC_PATH).trim();
  const prefix = rawPrefix === "/" ? "" : `/${rawPrefix.replace(/^\/+|\/+$/g, "")}`;
  return `https://${domain}${prefix}`;
}

/** 候选路径里第一个真实存在的文件；不存在返回 ""。`exists` 可注入，便于单测。 */
function resolveFirstExisting(candidates, exists = fs.existsSync) {
  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      if (exists(candidate)) return candidate;
    } catch {
      /* 权限等异常一律忽略，继续找下一个 */
    }
  }
  return "";
}

/** frpc 可执行文件的默认候选：环境变量 > D:\frpc\frpc.exe > PATH 里的 frpc。 */
function frpcBinaryCandidates(platform = process.platform, env = process.env, home = os.homedir()) {
  const list = [env && env.PI_FRPC_BIN];
  if (platform === "win32") {
    list.push("D:\\frpc\\frpc.exe");
    list.push(path.join(home, ".pi", "frpc.exe"));
  } else {
    list.push(path.join(home, ".pi", "frpc"));
    list.push("/usr/local/bin/frpc");
    list.push("/usr/bin/frpc");
  }
  list.push(platform === "win32" ? "frpc.exe" : "frpc");
  return list;
}

/** frpc 配置的默认候选：环境变量 > 仓库内 server/frpc.toml > D:\frpc\frpc-pidroid.toml。 */
function frpcConfigCandidates(env = process.env, serverDir = __dirname, home = os.homedir()) {
  return [
    env && env.PI_FRPC_CONFIG,
    path.join(serverDir, "frpc.toml"),
    path.join("D:", "frpc", "frpc-pidroid.toml"),
    path.join(home, ".pi", "frpc-pidroid.toml")
  ];
}

/**
 * 日志脱敏：frpc 本身不打印 token，但配置回显、URL 查询串以及代理转发的路径里
 * 都可能带上它，所以一律抹掉再落盘。
 */
function maskSecrets(line) {
  return String(line || "")
    // URL 查询串 ?token=...（& 之后是别的参数，不能被吃掉）
    .replace(/([?&]token=)[^&\s]+/gi, "$1***")
    // token = "..." / auth.token = "..."：保留引号形状，只换值
    .replace(/((?:auth\.)?token\s*=\s*)"[^"]*"/gi, '$1"***"')
    // token = ... 未加引号的形式
    .replace(/((?:auth\.)?token\s*=\s*)[^\s,"}&]+/gi, "$1***");
}

/**
 * 进程退出后要不要重启。干干净净的 exit 0（用户 Ctrl+C / 手工 stop）不重启，
 * 其余（被系统杀掉、崩溃）都交给退避重启。
 */
function classifyFrpcExit(code, stopping) {
  if (stopping) return { restart: false, reason: "stopped" };
  if (code === 0) return { restart: false, reason: "clean-exit" };
  return { restart: true, reason: "crashed" };
}

/** 退避毫秒数：1s、2s、4s… 封顶 30s。 */
function restartDelayMs(attempt) {
  const n = Math.max(0, Number(attempt) || 0);
  return Math.min(1000 * 2 ** n, RESTART_MAX_DELAY_MS);
}

const frpState = { child: null, timer: null, attempt: 0, stopping: false };

/**
 * 读取「本次要用的 frp 配置 + 公网地址」，不启动进程。
 * 供 launcher 打印二维码 / gateway 决定 connectHost 复用，避免两处各写一份解析。
 */
function describeFrp({ env = process.env, serverDir = __dirname } = {}) {
  const configPath = resolveFirstExisting(frpcConfigCandidates(env, serverDir));
  const binary = resolveFirstExisting(frpcBinaryCandidates(process.platform, env));
  let config = null;
  if (configPath) {
    try {
      config = parseFrpcConfig(fs.readFileSync(configPath, "utf8"));
    } catch (e) {
      config = null;
    }
  }
  const proxy = firstHttpProxy(config);
  return {
    configPath,
    binary,
    config,
    proxy,
    publicUrl: resolvePublicUrl({ env, config }),
    serverAddr: config ? config.serverAddr : "",
    localPort: proxy ? proxy.localPort : 0
  };
}

function pipeFrpcLogs(readable, out) {
  if (!readable) return;
  let buffer = "";
  readable.on("data", (chunk) => {
    buffer += chunk.toString();
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || "";
    for (const raw of lines) {
      const line = raw.replace(/\s+$/, "");
      if (!line.trim()) continue;
      out.write(`    [frp] ${maskSecrets(line)}\n`);
    }
  });
}

function launch() {
  const info = describeFrp();
  if (!info.configPath) {
    console.error("[frp] 找不到 frpc 配置：请新建 D:\\frpc\\frpc-pidroid.toml 或用 PI_FRPC_CONFIG 指定");
    return false;
  }
  const binary = info.binary || (process.platform === "win32" ? "frpc.exe" : "frpc");

  const child = spawn(binary, ["-c", info.configPath], { stdio: ["ignore", "pipe", "pipe"] });
  frpState.child = child;
  pipeFrpcLogs(child.stdout, process.stdout);
  pipeFrpcLogs(child.stderr, process.stderr);

  child.on("error", (err) => {
    console.error(`[frp] 启动 ${binary} 失败: ${err.message}`);
    console.error("[frp] 请确认已安装 frpc（或设置 PI_FRPC_BIN 指向 frpc 可执行文件）");
  });

  child.on("exit", (code) => {
    frpState.child = null;
    const verdict = classifyFrpcExit(code, frpState.stopping);
    if (!verdict.restart) {
      if (frpState.stopping) console.log("[frp] 已停止");
      return;
    }
    const delay = restartDelayMs(frpState.attempt);
    frpState.attempt += 1;
    console.warn(`[frp] frpc 退出 (code=${code})，${Math.round(delay / 1000)}s 后重连（第 ${frpState.attempt} 次）`);
    frpState.timer = setTimeout(() => {
      frpState.timer = null;
      if (frpState.stopping) return;
      launch();
    }, delay);
  });

  return true;
}

/**
 * 拉起 frpc 并开始守护。
 *
 * @param {{ localPort?: number }} options 本网关实际监听端口（用于与 toml 的 localPort 对账）
 * @returns {{ ok: boolean, publicUrl: string, configPath: string, binary: string, notes: string[] }}
 */
function startFrp(options = {}) {
  const listenPort = Number(options.localPort) || 0;
  const info = describeFrp();
  const notes = [];

  if (!info.configPath) {
    return { ok: false, publicUrl: "", configPath: "", binary: info.binary, notes: ["未找到 frpc 配置"] };
  }
  if (listenPort && info.localPort && info.localPort !== listenPort) {
    // 这正是「手机连上了但一直 502」的典型成因：toml 指向别的端口。
    notes.push(`frpc 配置的 localPort=${info.localPort}，但网关实际监听 ${listenPort}，隧道会 502`);
  }
  if (info.config && info.config.transportProtocol === "kcp") {
    notes.push("frpc 用的是 kcp(UDP)：国内直连易被限速/丢包，建议改 transport.protocol = \"tcp\"");
  }

  frpState.stopping = false;
  frpState.attempt = 0;
  const ok = launch();
  return {
    ok,
    publicUrl: info.publicUrl,
    configPath: info.configPath,
    binary: info.binary,
    child: frpState.child,
    notes
  };
}

/** 主动停止：先置 stopping 再 kill，避免退出瞬间又被守护逻辑重启。 */
function stopFrp() {
  frpState.stopping = true;
  frpState.attempt = 0;
  if (frpState.timer) {
    clearTimeout(frpState.timer);
    frpState.timer = null;
  }
  const child = frpState.child;
  frpState.child = null;
  if (child && child.pid) {
    try {
      child.kill();
    } catch {
      /* 已经退出 */
    }
  }
}

module.exports = {
  DEFAULT_PUBLIC_PATH,
  parseFrpcConfig,
  firstHttpProxy,
  resolvePublicUrl,
  resolveFirstExisting,
  frpcBinaryCandidates,
  frpcConfigCandidates,
  maskSecrets,
  classifyFrpcExit,
  restartDelayMs,
  describeFrp,
  startFrp,
  stopFrp
};
