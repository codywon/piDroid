# AGENTS.md

> **Notice**: These rules are mandatory for all AI agents working on this project.

---

## 1. APK 编译与发版规范（核心重点）

本仓库为 **piDroid**（Android 客户端 + 伴侣网关）。正式发布证书（Release Keystore）严格受云端安全密钥保护，而本机通常没有 Android SDK / NDK。因此**编译与验证只有一条合法通道：GitHub Actions**。

### 1.1 铁律：一律禁止本地编译（No Local Builds）

**任何形式的本地编译/构建都不允许**，包括但不限于：

- `./gradlew assembleDebug` / `assembleRelease` / `compileDebugKotlin` / `test` / `help` 等任何 Gradle 任务；
- 直接用 `kotlinc`（或 Gradle 缓存里的 `kotlin-compiler-embeddable`）单独编译若干 Kotlin 文件；
- 用「本地编译器 + 手写 stub/假类型」的方式绕过缺失的 Android / Compose 依赖做语法验证；
- Android Studio 的 Build / Sync 产物的本地构建；
- 本地签名的任何 APK。

为什么连「只编一下语法」也不行：这种验证不覆盖真实依赖与真实类型签名，产出的“已验证”是假信号，反而会让未经 CI 检验的代码被当成合格品推上去；本机缺 SDK 不是尝试的理由，而是明确的停止信号。

**本地唯一允许的动作**：读源码、改代码、写单元测试源码、看 CI 日志与 CI 产物。

**所有编译、单元测试、签名、校验打包与发版，一律在代码提交并推送到 GitHub 后，由 GitHub Actions (`.github/workflows/build-and-release.yml`) 统一完成**。

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
   - 将改动推送到远程仓库（`git push origin main`）；
   - 推送前逐条人工复核：新增/改动的单元测试引用的符号是否都还存在、新增文件是否已被 `git add`（历史上出现过“测试引用已删除常量 → CI 红 → 该版本没有安装包”）。

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
   - 运行 `./gradlew :app:testDebugUnitTest`（单元测试不过就没有安装包）；
   - 运行 `./gradlew assembleRelease` 产出 Release APK；
   - 运行 `sha256sum` 生成 `checksums.txt`（供 App 内自动更新做完整性校验）；
   - 从 `CHANGELOG.md` 提取日志并在 GitHub Releases 创建对应版本发布，用户端打开 App 即可一键检测并无感升级。

### 1.3 验证闭环（以 CI 为唯一标准）

- 判断“改好了没有”的唯一依据是 GitHub Actions 的 `Run Unit Tests` 与 `Build Signed APK` 两个步骤的结论，以及产出的 APK；
- CI 失败时：读日志定位 → 修代码 → 重新提交。**若该版本号对应的 Tag 已经推送过（CI 已产生记录），不要复用同一个 Tag**，改为递增补丁版本号（如 `v1.8.16` → `v1.8.17`）重新走一遍 1.2；
- 严禁为了“先让 CI 变绿”而删除失败断言、跳过测试或临时注释校验逻辑。

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
  - 单元测试**不得依赖 Android 运行时**（不碰 `android.*`、`Uri`、`Context` 等），倾向把易错的纯逻辑抽成独立文件后再测，这样即使本机没有 SDK，测试的语义也不会模糊；
  - **禁止本地编译**（见 §1.1）：不跑 Gradle、不跑 `kotlinc`、不写 stub 伪造依赖。测试是否通过以 CI 的 `Run Unit Tests` 为准；
  - 因此凡是新增/修改测试，都要先把被引用的符号在源码里确认一遍（历史故障：测试引用已删除的常量，导致 CI 测试源集编译失败、该版本没有安装包）；
  - 遇到 Bug 修复，先明确根因与失败用例，修复后再交由 CI 完成回归验证。

---

### 3.0 说明性标签（subtitle）的使用边界

新增或修改「一行标题 + 一行灰色说明」的列表行时，必须先读 `docs/DESIGN-labels.md`。三条硬约束：

- **一项一行**：`subtitle` 默认 `null`，只有满足「不写会造成不可逆损失 / 方向性错误 / 误以为失败」
  或它本身是**数据/状态**时才允许填；复述标题、状态重复、操作步骤说明一律不得写。
- **一屏一说明**：单个 tab / 面板的常驻说明 ≤ 2 条，第 3 条起改用 `InfoTitle` 的 `ⓘ`。
- **形态唯一**：同一面板内不得同时存在 `subtitle`、`ⓘ`、脚注三种解释方式；subtitle ≤ 18 个汉字且 1 行。

---

## 4. Git 提交与交互语言规范

- **Git Commit Messages**：
  - **Strict English Only**: 所有提交信息必须使用英文；
  - 采用 Conventional Commits 格式：`fix(...)`, `feat(...)`, `refactor(...)`, `docs(...)`, `chore(...)`。
- **User Interaction**:
  - 与用户的交流、思考过程总结及问题分析说明一律使用清晰的中文。
