import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// 只取纯函数：gateway.js 顶层不会 listen（见 gateway-tunnel.test.mjs 同样用法）。
// 这些用例守的是「Token 泄漏之后，接口也不能被当任意文件读写器」这条边界。
const {
  isPathInside,
  isSafeNameSegment,
  resolveSkillDocument,
  resolveMediaReadPath,
} = await import("../gateway.js");

/** 主目录之外的合成路径：用来断言「不在任何白名单根目录内」。 */
function outsideHome(...segments) {
  return path.join(path.parse(os.homedir()).root, "__pidroid_path_guard__", ...segments);
}

function tempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

test("isPathInside accepts the root itself and its children only", () => {
  const root = path.join(os.tmpdir(), "root");
  assert.equal(isPathInside(root, root), true);
  assert.equal(isPathInside(root, path.join(root, "a", "b.md")), true);
  assert.equal(isPathInside(root, path.join(root, "..", "escape.md")), false);
  assert.equal(isPathInside(root, path.join(os.tmpdir(), "sibling", "a.md")), false);
  // 前缀相同的兄弟目录不能被当成子目录（root-evil 不是 root 的子路径）
  assert.equal(isPathInside(root, `${root}-evil${path.sep}a.md`), false);
});

test("name segments reject separators, dot-dot and blanks", () => {
  assert.equal(isSafeNameSegment("my-skill"), true);
  assert.equal(isSafeNameSegment("技能_1"), true);
  assert.equal(isSafeNameSegment(".."), false);
  assert.equal(isSafeNameSegment("."), false);
  assert.equal(isSafeNameSegment(""), false);
  assert.equal(isSafeNameSegment("../x"), false);
  assert.equal(isSafeNameSegment("a/b"), false);
  assert.equal(isSafeNameSegment("a\\b"), false);
});

test("skill documents are limited to skill roots", () => {
  const userSkill = path.join(os.homedir(), ".pi", "agent", "skills", "demo", "SKILL.md");
  assert.ok(resolveSkillDocument(userSkill));
  const claudeSkill = path.join(os.homedir(), ".claude", "skills", "demo", "SKILL.md");
  assert.ok(resolveSkillDocument(claudeSkill));
});

test("project-level skills resolve through the requested cwd", () => {
  const cwd = tempDir("pidroid-cwd-");
  try {
    const projectSkill = path.join(cwd, ".pi", "skills", "demo", "SKILL.md");
    assert.equal(resolveSkillDocument(projectSkill, cwd), projectSkill);
  } finally {
    fs.rmSync(cwd, { recursive: true, force: true });
  }
});

test("skill packages stay editable through their skills segment", () => {
  const packaged = path.join(
    os.homedir(), ".pi", "agent", "packages", "demo-pkg", "skills", "demo", "SKILL.md"
  );
  assert.ok(resolveSkillDocument(packaged));
});

test("secrets and arbitrary markdown outside skills are refused", () => {
  // 私钥没有 .md 后缀；邮件/文档目录里的 .md 不在技能库内且没有 skills 段。
  assert.equal(resolveSkillDocument(path.join(os.homedir(), ".ssh", "id_rsa")), null);
  assert.equal(resolveSkillDocument(path.join(os.homedir(), ".ssh", "notes.md")), null);
  assert.equal(resolveSkillDocument(path.join(os.homedir(), "Documents", "private.md")), null);
  assert.equal(resolveSkillDocument(outsideHome("secrets", "notes.md")), null);
  assert.equal(resolveSkillDocument(""), null);
  assert.equal(resolveSkillDocument(undefined), null);
});

test("traversal out of a skill root is refused", () => {
  const escaped = path.join(
    os.homedir(), ".pi", "agent", "skills", "..", "..", "..", ".ssh", "notes.md"
  );
  assert.equal(resolveSkillDocument(escaped), null);
  const escapedWithSkillsSegment = path.join(
    os.homedir(), ".pi", "agent", "skills", "..", "..", "..", "evil", "skills", "x.md"
  );
  // 归一化后仍然带 skills 段时放行是已知取舍：它只是个 .md，不构成敏感文件泄漏。
  assert.ok(resolveSkillDocument(escapedWithSkillsSegment));
});

test("media paths resolve relative to the session cwd", () => {
  const cwd = tempDir("pidroid-media-");
  try {
    const image = path.join(cwd, "shot.png");
    fs.writeFileSync(image, "png");
    const found = resolveMediaReadPath("shot.png", cwd);
    assert.deepEqual(found, { path: image, contentType: "image/png" });
    assert.equal(resolveMediaReadPath(image, cwd)?.contentType, "image/png");

    // 没有 cwd 时相对路径无从解析
    assert.equal(resolveMediaReadPath("shot.png", ""), null);

    // 扩展名不在白名单里
    const notes = path.join(cwd, "notes.txt");
    fs.writeFileSync(notes, "secret");
    assert.equal(resolveMediaReadPath(notes, cwd), null);
    assert.equal(resolveMediaReadPath(notes, ""), null);
  } finally {
    fs.rmSync(cwd, { recursive: true, force: true });
  }
});

test("media outside home and cwd is refused", () => {
  assert.equal(resolveMediaReadPath(outsideHome("shot.png"), ""), null);
  assert.equal(resolveMediaReadPath(outsideHome("shot.png"), outsideHome()), null);
});

// ── HTTP 层：确认接口真的把守卫接上了（不只测试纯函数）──────────────���─────────
const { app, AUTH_TOKEN } = await import("../gateway.js");
const http = await import("node:http");

const server = http.createServer(app);
const listening = new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
await listening;
const origin = `http://127.0.0.1:${server.address().port}`;

test.after(() => {
  // fetch 的 keep-alive 连接不会自己断，直接 close 可能让测试进程多等几秒
  server.closeAllConnections?.();
  server.close();
});

function authed(pathname, init = {}) {
  return fetch(`${origin}${pathname}`, {
    ...init,
    headers: { Authorization: `Bearer ${AUTH_TOKEN}`, ...(init.headers ?? {}) },
  });
}

test("GET /api/skills/content serves a skill inside a skills directory", async () => {
  const dir = tempDir("pidroid-http-skill-");
  try {
    const skillDir = path.join(dir, "skills", "demo");
    fs.mkdirSync(skillDir, { recursive: true });
    const file = path.join(skillDir, "SKILL.md");
    fs.writeFileSync(file, "# demo skill\n");
    const url = `/api/skills/content?filePath=${encodeURIComponent(file)}&cwd=${encodeURIComponent(dir)}`;
    const res = await authed(url);
    assert.equal(res.status, 200);
    const payload = await res.json();
    assert.equal(payload.filePath, file);
    assert.match(payload.content, /demo skill/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("GET /api/skills/content never returns a file outside the skill roots", async () => {
  const dir = tempDir("pidroid-http-secret-");
  try {
    const marker = `PIDROID_SECRET_${Date.now()}`;
    const secret = path.join(dir, "id_rsa");
    fs.writeFileSync(secret, marker);
    const url = `/api/skills/content?filePath=${encodeURIComponent(secret)}&cwd=${encodeURIComponent(dir)}`;
    const res = await authed(url);
    const text = await res.text();
    assert.ok(!text.includes(marker), "gateway must not leak files outside skill roots");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("PUT /api/skills/content refuses paths outside skill roots", async () => {
  const dir = tempDir("pidroid-http-put-");
  try {
    const outside = path.join(dir, "notes.md");
    fs.writeFileSync(outside, "original");
    const res = await authed("/api/skills/content", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filePath: outside, content: "overwritten" }),
    });
    assert.equal(res.status, 400);
    assert.equal(fs.readFileSync(outside, "utf8"), "original");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("PUT /api/skills/content writes inside a skills directory", async () => {
  const dir = tempDir("pidroid-http-put-ok-");
  try {
    const target = path.join(dir, "skills", "demo", "SKILL.md");
    const res = await authed("/api/skills/content", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filePath: target, content: "# saved\n" }),
    });
    assert.equal(res.status, 200);
    assert.equal(fs.readFileSync(target, "utf8"), "# saved\n");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("POST /api/cwd/browse only creates absolute, single-segment paths", async () => {
  const dir = tempDir("pidroid-http-cwd-");
  try {
    const relative = await authed("/api/cwd/browse", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: "relative/dir" }),
    });
    assert.equal(relative.status, 400);

    const traversal = await authed("/api/cwd/browse", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: dir, name: ".." + path.sep + "outside" }),
    });
    assert.equal(traversal.status, 400);

    const child = path.join(dir, "created");
    const ok = await authed("/api/cwd/browse", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: dir, name: "created" }),
    });
    assert.equal(ok.status, 200);
    assert.ok(fs.statSync(child).isDirectory());
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("GET /api/files/raw serves media inside the session cwd only", async () => {
  const dir = tempDir("pidroid-http-media-");
  try {
    const image = path.join(dir, "shot.png");
    fs.writeFileSync(image, Buffer.from([0x89, 0x50, 0x4e, 0x47]));
    const inside = await authed(
      `/api/files/raw?path=${encodeURIComponent(image)}&cwd=${encodeURIComponent(dir)}`
    );
    assert.equal(inside.status, 200);
    assert.equal(inside.headers.get("content-type"), "image/png");

    const outside = await authed(
      `/api/files/raw?path=${encodeURIComponent(outsideHome("shot.png"))}&cwd=${encodeURIComponent(dir)}`
    );
    assert.equal(outside.status, 403);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
