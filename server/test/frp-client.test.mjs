import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

// frp 通道的纯函数：不读磁盘、不拉起进程，便于单测。
const {
  parseFrpcConfig,
  firstHttpProxy,
  resolvePublicUrl,
  resolveFirstExisting,
  frpcBinaryCandidates,
  frpcConfigCandidates,
  maskSecrets,
  classifyFrpcExit,
  restartDelayMs,
} = await import("../frp-client.js");

// gateway.js 顶层会建 token、注册路由，但不会 listen；只取纯函数。
const { resolveTunnelMode } = await import("../gateway.js");

const CLIENT_CONFIG = `
serverAddr = "agent.example.cn"
serverPort = 7000

auth.method = "token"
auth.token = "secret-token"   # 行尾注释

transport.protocol = "tcp"

[[proxies]]
name = "pidroid_gateway"
type = "http"
localIP = "127.0.0.1"
localPort = 4100
customDomains = ["agent.example.cn"]
locations = ["/"]
`;

test("reads server addr, port and the http proxy from frpc toml", () => {
  const config = parseFrpcConfig(CLIENT_CONFIG);
  assert.equal(config.serverAddr, "agent.example.cn");
  assert.equal(config.serverPort, 7000);
  assert.equal(config.transportProtocol, "tcp");

  const proxy = firstHttpProxy(config);
  assert.equal(proxy.name, "pidroid_gateway");
  assert.equal(proxy.localPort, 4100);
  assert.deepEqual(proxy.customDomains, ["agent.example.cn"]);
});

test("comments inside values are not treated as comments", () => {
  // `auth.token = "a#b"` 里的 # 属于值本身，不能把值截断成 "a
  const config = parseFrpcConfig('serverAddr = "a#b.example.cn"\nserverPort = 7000\n');
  assert.equal(config.serverAddr, "a#b.example.cn");
});

test("picks the http proxy even when tcp proxies come first", () => {
  const text = `
serverAddr = "x.example.cn"
serverPort = 7000

[[proxies]]
name = "rdp"
type = "tcp"
localPort = 3389
remotePort = 53389

[[proxies]]
name = "web"
type = "http"
localPort = 4100
customDomains = ["pi.example.cn"]
`;
  assert.equal(firstHttpProxy(parseFrpcConfig(text)).name, "web");
});

test("an http proxy without customDomains is not a tunnel entry", () => {
  const text = `
serverAddr = "x.example.cn"
[[proxies]]
type = "http"
localPort = 4100
`;
  assert.equal(firstHttpProxy(parseFrpcConfig(text)), null);
});

test("malformed input never throws and yields null", () => {
  assert.equal(parseFrpcConfig(undefined), null);
  assert.equal(parseFrpcConfig(null), null);
  assert.equal(parseFrpcConfig(""), null);
  assert.equal(parseFrpcConfig("   \n\n"), null);
  assert.equal(parseFrpcConfig("garbage without equals"), null);
});

test("public url comes from customDomains plus the nginx prefix", () => {
  const config = parseFrpcConfig(CLIENT_CONFIG);
  assert.equal(resolvePublicUrl({ env: {}, config }), "https://agent.example.cn/pi");
});

test("explicit PI_PUBLIC_URL wins and loses its trailing slash", () => {
  const config = parseFrpcConfig(CLIENT_CONFIG);
  assert.equal(
    resolvePublicUrl({ env: { PI_PUBLIC_URL: "https://other.example.cn/pi/" }, config }),
    "https://other.example.cn/pi"
  );
});

test("PI_PUBLIC_PATH=/ targets the root path", () => {
  const config = parseFrpcConfig(CLIENT_CONFIG);
  assert.equal(resolvePublicUrl({ env: { PI_PUBLIC_PATH: "/" }, config }), "https://agent.example.cn");
});

test("no config means no public url, never a bogus one", () => {
  assert.equal(resolvePublicUrl({ env: {}, config: null }), "");
  assert.equal(resolvePublicUrl({ env: {}, config: parseFrpcConfig("serverAddr = \"x\"\n") }), "");
});

test("resolveFirstExisting skips empty and missing candidates", () => {
  const exists = (p) => p === "/opt/frpc/frpc";
  assert.equal(resolveFirstExisting(["", undefined, "/nope/frpc", "/opt/frpc/frpc"], exists), "/opt/frpc/frpc");
  assert.equal(resolveFirstExisting(["/nope/frpc"], exists), "");
  // exists 抛异常也不能把启动流程带崩
  assert.equal(resolveFirstExisting(["/boom"], () => { throw new Error("EACCES"); }), "");
});

test("binary candidates put the explicit env override first", () => {
  const win = frpcBinaryCandidates("win32", { PI_FRPC_BIN: "C:\\tools\\frpc.exe" }, "C:\\Users\\me");
  assert.equal(win[0], "C:\\tools\\frpc.exe");
  assert.ok(win.includes("D:\\frpc\\frpc.exe"));

  const linux = frpcBinaryCandidates("linux", {}, "/root");
  assert.equal(linux[0], undefined);
  assert.ok(linux.includes("/usr/local/bin/frpc"));
});

test("config candidates put the explicit env override first", () => {
  const list = frpcConfigCandidates({ PI_FRPC_CONFIG: "/etc/frpc.toml" }, "/app/server", "/root");
  assert.equal(list[0], "/etc/frpc.toml");
  // 仓库内的 server/frpc.toml 是第二候选（路径随平台变化，用 path.join 拼期望值）
  assert.equal(list[1], path.join("/app/server", "frpc.toml"));
});

test("logs never leak the gateway token", () => {
  assert.equal(
    maskSecrets("GET /api/agent/x/events?token=pi_abc123&foo=1"),
    "GET /api/agent/x/events?token=***&foo=1"
  );
  assert.equal(maskSecrets("auth.token = \"secret\""), 'auth.token = "***"');
  assert.equal(maskSecrets("plain line"), "plain line");
});

test("clean exit stops the supervisor, crashes restart it", () => {
  assert.deepEqual(classifyFrpcExit(0, false), { restart: false, reason: "clean-exit" });
  assert.deepEqual(classifyFrpcExit(1, false), { restart: true, reason: "crashed" });
  assert.deepEqual(classifyFrpcExit(0, true), { restart: false, reason: "stopped" });
  // 用户 Ctrl+C 时哪怕非 0 退出也不能再拉起来
  assert.deepEqual(classifyFrpcExit(137, true), { restart: false, reason: "stopped" });
});

test("restart backoff doubles but stays capped", () => {
  assert.equal(restartDelayMs(0), 1000);
  assert.equal(restartDelayMs(1), 2000);
  assert.equal(restartDelayMs(3), 8000);
  assert.equal(restartDelayMs(99), 30000);
  assert.equal(restartDelayMs(-1), 1000);
  assert.equal(restartDelayMs(undefined), 1000);
});

test("frp is the default tunnel mode", () => {
  assert.equal(resolveTunnelMode({ argv: [] }), "frp");
  assert.equal(resolveTunnelMode({ argv: ["--frp"] }), "frp");
});

test("flags switch the tunnel mode explicitly", () => {
  assert.equal(resolveTunnelMode({ argv: ["--tunnel"] }), "cloudflare");
  assert.equal(resolveTunnelMode({ argv: ["--cloudflare"] }), "cloudflare");
  assert.equal(resolveTunnelMode({ argv: ["--local"] }), "none");
  assert.equal(resolveTunnelMode({ argv: ["--no-tunnel"] }), "none");
});

test("launcher-provided mode beats the command line", () => {
  assert.equal(resolveTunnelMode({ explicit: "none", argv: ["--tunnel"] }), "none");
  assert.equal(resolveTunnelMode({ explicit: "frp", argv: ["--local"] }), "frp");
  assert.equal(resolveTunnelMode({ explicit: " CLoudflare ", argv: [] }), "cloudflare");
  // 未知取值不能把通道关掉，应回落到命令行/默认
  assert.equal(resolveTunnelMode({ explicit: "wireguard", argv: [] }), "frp");
});

test("legacy autoTunnel flag keeps its old meaning", () => {
  assert.equal(resolveTunnelMode({ autoTunnel: false, argv: [] }), "none");
  assert.equal(resolveTunnelMode({ autoTunnel: true, argv: [] }), "cloudflare");
  // 命令行仍然是更高优先级
  assert.equal(resolveTunnelMode({ autoTunnel: false, argv: ["--frp"] }), "frp");
});
