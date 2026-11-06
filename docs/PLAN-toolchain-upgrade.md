# PLAN — 工具链升级（KGP → AGP → Gradle wrapper）

> 状态：**第 1–3 步已完成**（2.0.25 / 2.0.26 / 2.0.27）。第 4 步（Gradle 9）**未排期**——
> 它不是一次 checklist 式的升级（Gradle 9 强制 AGP 9 + build-tools 36 + KGP 2.3），
> 动手前先读该节的「风险 / 收益 / 触发条件」。
> 记录时间：2026-11-06，兼容矩阵核对日期：2026-11-06

## 为什么要单独做

CI 每次构建都会 annotate 一条：

```
Gradle 8.11.1 is out of date: consider updating to the latest Gradle version.
```

但**真正的约束不是 wrapper，而是 Kotlin Gradle Plugin**。查 Kotlin 官方兼容表
（`kotlinlang.org/docs/gradle-configure-project.html`）：

| KGP | Gradle 支持范围 | AGP 支持范围 |
| --- | --- | --- |
| **2.0.20–2.0.21（当前）** | 6.8.3–**8.8** | 7.1.3–**8.5** |
| 2.1.20–2.1.21 | 7.6.3–8.12.1 | 7.3.1–8.7.2 |
| 2.2.20–2.2.21 | 7.6.3–**8.14** | 7.3.1–**8.11.1** |
| 2.3.0 | 7.6.3–9.0.0 | 8.2.2–8.13.0 |
| 2.3.20–2.3.21 | 7.6.3–9.3.0 | 8.2.2–9.0.0 |

项目现状：KGP **2.0.21** + Gradle **8.11.1** + AGP **8.7.3** —— 已经**超出 KGP 的
支持上限两档**（它只到 Gradle 8.8 / AGP 8.5），只是"能跑且 CI 绿"。

**所以原计划里"先升 wrapper"的顺序是错的**：在 KGP 还是 2.0.21 的前提下把 wrapper 推到
8.14，只会把差距拉得更大，风险上升而收益为零（那句 EOL 提示纯属装饰）。
正确的顺序是从绑定最紧的一环开始。

## 步骤（每步 = 一个补丁版本 + 一次 CI，失败即回滚该步、递增补丁号重来）

### 第 1 步：KGP 2.0.21 → 2.2.21（✅ 已随 2.0.25 发布）

- 只改 `gradle/libs.versions.toml` 的 `kotlin`（`kotlin-compose` 插件共用同一个版本号，
  会一起升级——这正是 2026-11-06 把 `kotlinOptions` 迁到 `kotlin { compilerOptions {} }`
  的用处，老 DSL 在新 KGP 上已不可用）；
- 升级后 Gradle 8.11.1 与 AGP 8.7.3 **同时**落回支持区间（8.14 / 8.11.1 之内）；
- 验收：CI 的 `Run Unit Tests` + `Build Release APK` 全绿，且安装后能启动、能进会话。

### 第 2 步：AGP 8.7.3 → 8.10.1（✅ 已随 2.0.26 发布）

- AGP 8.10 的最低 Gradle 恰好是 8.11.1（= 当前值），所以这步不用动 wrapper；
- 仍在 KGP 2.2 的 AGP 上限 8.11.1 之内；
- 选 8.10.1 而不是 8.11.x：后者把最低 Gradle 抬到 8.13，会跟第 3 步混在一起；
- 若 AGP 提高 `compileSdk` / build-tools 要求，同步处理（当前 compileSdk 35）。

### 第 3 步：Gradle wrapper 8.11.1 → 8.14.6（✅ 已随 2.0.27 发布）

- 8.14.6 是 8.14 线的最后一个安全补丁版本；KGP 2.2 的上限正好是 8.14，落点刚好；
- 只改 `distributionUrl`：该文件没有 `distributionSha256Sum`，`gradle-wrapper.jar` 也不用重生成
  （wrapper 只负责按 URL 拉起发行版；本地按 §1.1 不跑 `gradle wrapper`）；
- **实测结论（2026-11-06，v2.0.27 的 CI）**：注解**没有**消失，只是变成
  `Gradle 8.14.6 is out of date`。因为 Gradle **8 整条线**都已是“仅安全修复”
  （active support 2025-07-31 结束），只要还在 8.x 上，提示就会一直存在。
  因此第 3 步的收益是“落在 KGP 支持上限内”，而不是“消警告”。

### 第 4 步：Gradle 9.x（AGP 9 + KGP 2.3 + build-tools 36）—— **未排期，动手前先读风险**

> ⚠️ 一开始把它写成「可选第 4 步」是低估了：Gradle 9 **不是**改一行 wrapper 能到的。

#### 官方矩阵（2026-11-06 核对：developer.android.com 与 kotlinlang.org）

| 目标 | 硬要求 |
| --- | --- |
| **AGP 9.0** | Gradle **9.1+**、JDK 17、**SDK Build Tools 36.0.0**、KGP 2.0 最低（2.3+ 推荐）；官方描述是 "major release that brings API and behavior changes" |
| AGP 8.13.x | 最低 Gradle **8.13**（当前 8.14.6 够）；8.13.2 内置 R8 8.13.19，支持 Kotlin 2.3 |
| KGP 2.3.0 | Gradle 7.6.3–9.0、AGP 8.2.2–8.13.0 |
| KGP 2.3.20–2.3.21 | Gradle 7.6.3–9.3.0、AGP 8.2.2–9.0.0 |
| **KGP 2.2.21（现状）** | AGP 上限 **8.11.1** ← 所以想去 AGP 8.13 / 9.x，必须**先**动 KGP |

所以第 4 步实际是**四元耦合升级**：Gradle 9.1+ + AGP 9.x + KGP 2.3.x + build-tools 36
（大概率还要把 `compileSdk` / `targetSdk` 抬到 36）。

#### 收益（很小，且不是新增能力）

1. CI 那条 EOL 注解消失 —— 纯装饰，用户零感知；
2. 不再停在「只收安全补丁」的版本线上（Gradle 8 自 2025-07-31 起仅安全修复，AGP 8.10 也迟早 EOL）—— 是“技术债不再增长”，不是“获得新能力”；
3. AGP 9 附带新版 R8 与构建性能改进；但本仓 CI 的主要耗时是 Kotlin 编译 + R8 + 单测且已有依赖缓存，能省的很有限；
4. 目前**没有任何缺失能力**只能靠它拿到：`compileSdk` / `targetSdk` 仍在 35，也没用到 AGP 9 的新 API。

#### 风险（按严重度）

1. **绿 CI ≠ 能跑的 APK（最大风险）**。R8 会从 AGP 8.10 跨好几代，默认规则与优化行为都变；而 CI
   **只跑纯 JVM 单测 + 组装 APK，从不上真机/模拟器**。Gson 反射反序列化、Compose 运行时都在这条链路上，
   缩错类只会表现为用户手机上的启动闪退（1.8.90 那类事故的失败模式）。
2. **`compileSdk`/`targetSdk` 36 的行为变更**：强制的 edge-to-edge、更严的预测式返回。2.0.14 刚把返回手势
   交还系统，这块很可能再冒一轮只能靠真机看的 UI 回归。
3. **KGP 2.2 → 2.3 的语言级变动**：几十个 Kotlin 文件可能出现新告警/新错误，每轮修正都要一次 CI。
4. **签名前置校验可能静默失效**：`app/build.gradle.kts` 的 `releasePackagingTaskNames` 是按**任务名**匹配的，
   AGP 9 若改任务命名，不会报错，只会不再触发那句中文提示（静默降级）。升级时必须显式验证一次。
5. **成本**：本仓禁止本地编译，每次尝试 = 一个 tag + 一轮 CI；同类小改动今天已烧掉 3 个版本号
   （2.0.17 / 2.0.21 / 2.0.23）。四元升级现实里按 2–4 次失败预算，且中间态发出去的包要靠真机冒烟确认。

#### 触发条件（满足任一再开）

- 需要 `targetSdk 36`（Play 政策或某个 SDK 硬要求）；
- 需要 KGP 2.3 / AGP 9 的某个具体能力（目前没有）；
- Gradle 8 出现影响本仓的安全公告或构建 bug。

#### 如果要做：拆成三步，别一步跨到 Gradle 9

每步仍是一个补丁版本 + 一次 CI，且**每步都要在真机上冒烟**（启启动、进会话、发一条消息）后才走下一步：

1. **KGP 2.2.21 → 2.3.x**（Gradle 8.14.6、AGP 8.10.1 都不动，落在 2.3 的支持区间内）；
2. **AGP 8.10.1 → 8.13.x**（最低 Gradle 8.13，当前 8.14.6 满足；`compileSdk` 视 AGP 要求再议）；
3. **Gradle 8.14.6 → 9.1+ 与 AGP 8.13 → 9.x 同时做**（AGP 9 要求 Gradle 9.1+，拆不开）+ build-tools 36。

第 3 步完成后额外验证：

- 签名前置校验的任务名是否仍匹配（在构建日志里看是否有缺 secret 的中文报错）；
- R8 是否仍保留 Gson / Compose 需要的类（只能靠真机启动 + 进一个历史会话验证）；
- `targetSdk` 抬到 36 后：返回手势、边到边布局、通知行为。

## 约束（不可协商）

- 本地不跑 Gradle / kotlinc，验证只看 GitHub Actions 的 `Run Unit Tests` + `Build Release APK`（`AGENTS.md` §1.1）；
- 签名材料仍全部来自环境变量（`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），本页不涉及任何 keystore 变更；
- 一个版本号对应一个 Tag，CI 失败后**不复用**该 Tag，改为递增补丁号（`AGENTS.md` §1.3）；
- 发版流水线的 `Verify tag matches versionName and CHANGELOG` 门禁会挡住 tag 与 `versionName` 漂移。

## 每步动手前的自检（用血换来的）

今天有两次 Tag 因为"测试源集"编译失败而报废（2.0.17 / 2.0.21 / 2.0.23），共同点是
**只检查了 `app/src/main`**。所以每次改版本/依赖前后固定做：

1. `grep -rn <被删或改名的符号> app/src`（main **和** test 都要）；
2. 在 `app/src/test` 里搜新符号的调用点是否与声明位置一致（class 体 vs `companion object`）；
3. 只在这些都对上之后才 `git tag`。

## 不做的事

- 不借升级改任何业务代码或依赖版本（除 KGP / AGP / Gradle 三者本身）；
- 不动 `minSdk`（26）与 `targetSdk`（35），除非目标 AGP 强制要求。
