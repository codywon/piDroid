# PLAN — 工具链升级（Gradle wrapper → AGP）

> 状态：**待排期**（不随功能发版混做）
> 记录时间：2026-11-06

## 为什么要单独做

CI 每次构建都会 annotate 一条：

```
Gradle 8.11.1 is out of date: consider updating to the latest Gradle version.
```

`gradle/wrapper/gradle-wrapper.properties` 仍指向 `gradle-8.11.1-bin.zip`（EOL）。同时
`gradle/libs.versions.toml` 的 `agp = "8.7.3"`、`kotlin = "2.0.21"` 也到了该抬的时候。

**为什么不能跟功能发版混在一起**：这是一次纯工具链替换，失败模式是「配置阶段就红」，
和业务改动的失败模式（编译错、断言失败）完全不同。混在一起会导致 CI 红了之后分不清
是谁的问题，而本项目**禁止本地编译**（见 `AGENTS.md` §1.1），唯一的验证通道就是一次发版。

## 约束（不可协商）

- 本地不跑 Gradle / kotlinc，验证只看 GitHub Actions 的 `Run Unit Tests` + `Build Release APK`；
- 签名材料仍全部来自环境变量（`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
  本页不涉及任何 keystore 变更；
- 一个版本号对应一个 Tag。CI 失败后**不复用**该 Tag，改为递增补丁号（`AGENTS.md` §1.3）；
- 发版流水线的 `Verify tag matches versionName and CHANGELOG` 门禁会挡住 tag 与
  `versionName` 漂移（2026-11-06 加入）。

## 步骤

1. **先只升 wrapper**：`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl`
   指向 `gradle-8.14.x-bin.zip`（`gradle-wrapper.properties` 里的 `networkTimeout` /
   `validateDistributionUrl` 保持不变），打一个补丁版本看 CI 是否全绿。
2. **再升 AGP**：`gradle/libs.versions.toml` 的 `agp` 抬到与 Gradle 8.14 兼容的版本，
   同步确认 `compileSdk` / `targetSdk`（当前 35）与 `kotlin` 版本矩阵不冲突。
3. **顺手收掉同批提示**：`kotlinOptions` 已在 2026-11-06 迁到
   `kotlin { compilerOptions { } }`，升级时确认这条 DSL 在新 AGP 上仍可用。
4. 每一步都是一个独立补丁版本 + 一次 CI，失败就回滚该步、递增补丁号重来。

## 验收

- CI 的 `Run Unit Tests` 与 `Build Release APK` 全绿，产出 APK 与 `checksums.txt`；
- `CHANGELOG.md` 有对应版本段落（Release notes 由它抽取）；
- 装到真机能启动、能连网关、能进会话（这是升级唯一无法由 CI 覆盖的部分）。

## 不做的事

- 不借这次升级改任何业务代码或依赖版本（除 AGP/Kotlin/Gradle 三者本身）；
- 不动 `minSdk`（26）与 `targetSdk`（35），除非目标 AGP 强制要求。
