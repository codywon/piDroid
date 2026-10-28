# AGENTS.md

> **Notice**: These rules are mandatory for all AI agents working on this project.

---

## 1. APK 编译与发版规范（核心重点）

本仓库为 **piDroid**（Android 客户端 + 伴侣网关）。由于本地开发机环境不一定配置完整 Android SDK / NDK，且正式发布证书（Release Keystore）严格受云端安全密钥保护：

### 1.1 禁止本地编译 Release APK
- **严禁**在本地开发环境直接运行打包或尝试生成正式 release APK；
- 本地的修改只需要在代码层面完成修改、语法与单元测试验证；
- **所有 APK 的编译、签名、校验打包与发版，一律在代码提交并推送到 GitHub 后，由 GitHub Actions (`.github/workflows/build-and-release.yml`) 统一完成**。

### 1.2 标准发版流程（SOP）

当需要发布新版本或打包新 APK 时，严格按以下步骤执行：

1. **更新版本号** (`app/build.gradle.kts`):
   - `versionCode`: 整数自增（例如 57 -> 58）；
   - `versionName`: 语义化版本号自增（例如 `"1.7.31"` -> `"1.7.32"` 或 `"1.7.33"`）。

2. **更新发布日志** (`CHANGELOG.md`):
   - 必须在最顶部新增对应版本的发布说明；
   - **标题格式必须严格匹配**：`## [X.Y.Z] - YYYY-MM-DD`（如 `## [1.7.33] - 2026-10-28`）；
   - CI 构建脚本通过 `awk -v ver="$CLEAN_VERSION"` 解析该格式自动提取 Release Notes；若格式不符将回退为默认提示。

3. **提交与推送至 GitHub**:
   - Git Commit Message 必须使用英文并严格遵循 Conventional Commits（例如 `fix(model): resolve session model override bug and sync with pi-web`）；
   - 将改动推送到远程仓库（`git push origin main`）。

4. **触发 GitHub Actions 构建**:
   - **方式 A（打 Tag 触发，推荐）**:
     ```bash
     git tag v1.7.33
     git push origin v1.7.33
     ```
     工作流监听到 `v*` 开头的 Tag 推送，将自动开始编译、签名并发布 Release。
   - **方式 B（手动触发）**:
     在 GitHub 网页端的 `Actions` 页面，选择 `Build & Release piDroid APK`，点击 `Run workflow`，输入对应的版本 Tag（例如 `v1.7.33`）并触发。

5. **云端流水线自动完成的事项**:
   - 读取 GitHub Secrets 自动解码 `keystore/release.jks`（杜绝密钥本地泄漏）；
   - 运行 `./gradlew assembleRelease` 产出 Release APK；
   - 运行 `sha256sum` 生成 `checksums.txt`（供 App 内自动更新做完整性校验）；
   - 从 `CHANGELOG.md` 提取日志并在 GitHub Releases 创建对应版本发布，用户端打开 App 即可一键检测并无感升级。

---

## 2. 架构与协议对齐规范 (Align with pi-web)

- **以 `@agegr/pi-web` 为权威依据**：
  - 本项目是移动端控制器，必须严格 1:1 复刻或对齐官方 `pi-web` 的 API、RPC 及 SSE 长流行为；
  - 遇到状态冲突、模型切换、工具调用、思考级别处理时，必须查阅 `pi-web` 原生源码（如 `hooks/useAgentSession.ts`、`app/api/sessions/...`），不要主观臆造私有协议。
- **模型与运行态状态管理**：
  - 会话内的模型与思考级别优先级为：`本地切换保护 > 活跃运行时 (runtimeAlive) > 会话快照历史`；
  - 严禁将全局默认模型（如 `/api/models` 中的 defaultModel）盲目覆盖到已有会话；
  - 会话快照仅在无模型元数据时保留未知，不得硬编码伪造兜底模型。

---

## 3. 代码修改与验证原则

- **Surgical Modifications（最小改动）**：
  - 只修改与当前任务强相关的代码，严禁大面积非必要代码重构与格式化；
  - 保持现有代码规范（命名、缩进、Kotlin/Jetpack Compose 惯用法）。
- **单元测试与验证**：
  - 复杂数据流与解析逻辑必须在 `app/src/test/` 编写纯 JVM 单元测试覆盖；
  - 本地验证优先运行轻量级单元测试与语法检查，不依赖重量级完整 Android SDK 编译；
  - 遇到 Bug 修复，先明确根因与失败用例，修复后再执行回归验证。

---

## 4. Git 提交与交互语言规范

- **Git Commit Messages**:
  - **Strict English Only**: 所有提交信息必须使用英文；
  - 采用 Conventional Commits 格式：`fix(...)`, `feat(...)`, `refactor(...)`, `docs(...)`, `chore(...)`。
- **User Interaction**:
  - 与用户的交流、思考过程总结及问题分析说明一律使用清晰的中文。
