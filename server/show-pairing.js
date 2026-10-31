#!/usr/bin/env node
/**
 * 打印当前网关的配对二维码（不重启、不打断正在跑的服务）。
 *
 * 为什么需要这个：网关开机自启后是**无窗口**运行（计划任务 → wscript → cmd → node），
 * 启动横幅和二维码只写进了 `server/logs/gateway.log`。手机端一旦需要重新配对
 * （例如网关 Token 轮换、App 重装），用户没有窗口可看，就只能翻日志里那段被 ANSI 转义
 * 搅花的 ASCII art。这个脚本直接从 `server/.token` 与 frpc 配置现算现印，一条命令解决。
 *
 * 用法：
 *   server\show-pairing.cmd              （双击或命令行，带暂停）
 *   node server/show-pairing.js          （脚本调用，不暂停）
 */

const fs = require("fs");
const os = require("os");
const path = require("path");

let qrcode;
try {
  qrcode = require("qrcode-terminal");
} catch {
  qrcode = null;
}

const frp = require("./frp-client");

function localIPv4() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === "IPv4" && !net.internal) return net.address;
    }
  }
  return "127.0.0.1";
}

function readToken() {
  const file = path.join(__dirname, ".token");
  if (!fs.existsSync(file)) return "";
  const token = fs.readFileSync(file, "utf8").trim();
  return token.length >= 20 ? token : "";
}

function buildConnectUri(url, token, name) {
  return (
    "pidroid://connect?url=" + encodeURIComponent(url) +
    "&token=" + encodeURIComponent(token) +
    "&name=" + encodeURIComponent(name)
  );
}

function printPairing(label, url, token, hostName) {
  console.log("");
  console.log("------------------------------------------------------");
  console.log(`[${label}] ${url}`);
  if (!qrcode) {
    console.log("（未安装 qrcode-terminal，无法渲染二维码；请在 server 目录执行 npm install）");
    return;
  }
  qrcode.generate(buildConnectUri(url, token, hostName), { small: true });
}

function main() {
  const token = readToken();
  if (!token) {
    console.error("未找到有效的 server/.token —— 网关从未启动过，或刚被删除。");
    console.error("先启动一次网关（schtasks /Run /TN \"piDroid Gateway (frp)\" 或 npm start），再回来执行本命令。");
    process.exit(1);
  }

  const port = parseInt(process.env.GATEWAY_PORT || "4100", 10);
  const hostName = os.hostname();

  let publicUrl = "";
  let configPath = "";
  try {
    const info = frp.describeFrp();
    publicUrl = info.publicUrl || "";
    configPath = info.configPath || "";
    const proxyPort = info.localPort;
    if (proxyPort && proxyPort !== port) {
      console.warn(
        `⚠️  frpc 配置里 localPort=${proxyPort}，而本命令按 ${port} 生成局域网地址；` +
        `若两者不一致，公网入口会 502（改 ${configPath} 或加 --port）。`
      );
    }
  } catch {
    publicUrl = "";
  }
  if (!publicUrl) publicUrl = (process.env.PI_PUBLIC_URL || "").trim();

  console.log("======================================================");
  console.log("       🛡️  piDroid 配对信息（当前正在运行的服务）");
  console.log("======================================================");
  console.log(`[访问 Token] ${token}`);
  if (configPath) console.log(`[frpc 配置] ${configPath}`);
  if (!publicUrl) {
    console.log("[公网地址] 未解析到 —— frpc 配置里缺少 customDomains，或设置了 PI_PUBLIC_URL");
  }

  // 公网入口优先（离开局域网也能用）；局域网 IP 作为兜底。
  if (publicUrl) printPairing("公网地址 · 推荐", publicUrl, token, hostName);
  printPairing("局域网地址 · 同 Wi-Fi 可用", `http://${localIPv4()}:${port}`, token, hostName);

  console.log("");
  console.log("------------------------------------------------------");
  console.log("手机 App 打开【扫码配对】扫上面任意一个二维码即可。");
  console.log("也可以在 App 里手动填：服务器地址 = 上面[公网地址]，Token = 上面那串。");
  console.log("注意：本命令只读取现有配置，不会重启网关、也不影响正在跑的会话。");
  console.log("======================================================");
}

main();
