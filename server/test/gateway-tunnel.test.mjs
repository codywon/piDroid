import assert from "node:assert/strict";
import test from "node:test";

// 只取纯函数：gateway.js 顶层会建 token、注册路由，但不会 listen。
const {
  parseStableTunnelConfig,
  isSafeTunnelName,
  isSafeHostname,
} = await import("../gateway.js");

const CONFIG = `
tunnel: 8e192463-4636-4ec1-9664-e9cea401ef9a
credentials-file: /home/me/.cloudflared/8e192463.json

ingress:
  - hostname: pi.example.com
    service: http://127.0.0.1:4100
  - service: http_status:404
`;

test("picks the hostname whose ingress points at the gateway port", () => {
  const found = parseStableTunnelConfig(CONFIG, 4100);
  assert.equal(found.tunnelName, "8e192463-4636-4ec1-9664-e9cea401ef9a");
  assert.equal(found.hostname, "pi.example.com");
  assert.equal(found.targetPort, 4100);
});

test("ignores a hostname that points at another service", () => {
  // 这正是「手机一直自动重连中」的成因：配了隧道，但它指向别的端口。
  assert.equal(parseStableTunnelConfig(CONFIG, 5555), null);
});

test("tolerates quoted values and inline comments", () => {
  const config = `
tunnel: "pi"
ingress:
  - hostname: "pi.example.com"   # phone
    service: http://127.0.0.1:4100  # gateway
  - service: http_status:404
`;
  const found = parseStableTunnelConfig(config, 4100);
  assert.equal(found.tunnelName, "pi");
  assert.equal(found.hostname, "pi.example.com");
});

test("a config without a tunnel key yields nothing", () => {
  assert.equal(parseStableTunnelConfig("ingress:\n  - service: http_status:404\n", 4100), null);
});

test("a config whose ingress has no hostname yields nothing", () => {
  assert.equal(parseStableTunnelConfig("tunnel: pi\ningress:\n  - service: http_status:404\n", 4100), null);
});

test("non-string and empty input never throw", () => {
  assert.equal(parseStableTunnelConfig(undefined, 4100), null);
  assert.equal(parseStableTunnelConfig("", 4100), null);
  assert.equal(parseStableTunnelConfig(null, 4100), null);
});

test("tunnel names are whitelisted before they reach a shell", () => {
  assert.ok(isSafeTunnelName("pi"));
  assert.ok(isSafeTunnelName("8e192463-4636-4ec1-9664-e9cea401ef9a"));
  assert.ok(isSafeTunnelName("my_tunnel-1"));

  // 命令行注入：这些都会进入 `spawn(..., { shell: true })` 的命令行
  for (const bad of [
    "pi & calc",
    "pi;rm -rf /",
    "pi|cat /etc/passwd",
    "$(whoami)",
    "`id`",
    'pi" && calc',
    "pi' ; calc",
    "../pi",
    "",
    " ",
  ]) {
    assert.equal(isSafeTunnelName(bad), false, `must reject ${JSON.stringify(bad)}`);
  }
  assert.equal(isSafeTunnelName(undefined), false);
  assert.equal(isSafeTunnelName("a".repeat(65)), false);
});

test("hostnames are validated before being put into the URL and QR code", () => {
  assert.ok(isSafeHostname("pi.example.com"));
  assert.ok(isSafeHostname("a-b.c-d.example.co.uk"));

  for (const bad of [
    "",
    "example",
    "-bad.example.com",
    "bad-.example.com",
    "pi.example.com/evil",
    "pi.example.com:4100",
    "pi & calc",
    "$(whoami).example.com",
  ]) {
    assert.equal(isSafeHostname(bad), false, `must reject ${JSON.stringify(bad)}`);
  }
  assert.equal(isSafeHostname(undefined), false);
});
