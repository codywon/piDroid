# 设计决策：色彩 Token 体系与承接面约束

- 状态：**已采纳**（v1.8.21 起生效，v1.8.27 补齐本文档）
- 范围：`app/src/main/java/com/pi/app/ui/` 下所有组件；`app/src/main/java/com/pi/app/ui/theme/`
- 相关代码：`ui/theme/ThemePalette.kt`（唯一色板）、`ui/theme/ThemeManager.kt`（语义映射）、`ui/theme/Theme.kt`（M3 接线）、`ui/theme/ArgbContrast.kt`（唯一对比度实现）、`app/src/test/java/com/pi/app/ui/theme/ColorContrastTest.kt`（门禁）

## 1. 三层结构

```
ThemePalette（纯 Kotlin Int 色板，无 Compose/Android 依赖）
        │ 映射
AppTheme 计算属性（textPrimary / textMuted / success / warning / info / pinActive / onSuccess …）
        │ 应用
业务组件（只允许消费 Token，不得出现裸 Color(0x...)）
```

- **`error` 只有一份**：它只存在于 `PiDroidTheme` 写入的 M3 `ColorScheme` 四个槽位
  （`error` / `onError` / `errorContainer` / `onErrorContainer`），`AppTheme` 上**不设** `error` 属性。
  历史上 dark 用 `#F85149`、light 用 `#CF222E`、另三个槽位未定义，就是双源导致的。
- `success` / `warning` / `info` / `pinActive` 这类 M3 没有对应槽位的概念，只放在 `AppTheme` 一层，不重复进 `ColorScheme`。

## 2. 为什么色板是纯 Kotlin `Int`

`ColorContrastTest` 必须在纯 JVM 单元测试里跑（`AGENTS.md §3`：不碰 Android 运行时）。
若色板用 `androidx.compose.ui.graphics.Color`，测试就要加载 Compose 图形类；
用 `Int` ARGB 之后，生产代码与测试读的是**同一份数值**，测试不再可能「复述自己的假设」。

## 3. 承接面约束（本体系最容易踩的一条）

AA 不是 Token 的属性，而是 **Token × 表面** 的属性。实测不存在一个 `textMuted` 能在全部四级表面上
都 ≥ 4.5:1、同时仍与 `textSecondary` 可区分。因此按「允许出现在哪些表面」写死规则：

| Token | 允许的承载表面 | 5 套主题实测最低值 |
|---|---|---|
| `textPrimary` | `background` / `surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh` | 11.21:1 |
| `textSecondary` | `background` / `surfaceContainerLow` / `surfaceContainer` | 4.95:1 |
| `textMuted` | **仅** `background` / `surfaceContainerLow` | 4.58:1 |
| `textPrimary`（嵌套面替代品） | `surfaceContainerHigh` 上不放 `muted/secondary`，改用 `textPrimary` | 11.21:1 |

`textMuted` 取值（浅 `#7D8590` 3.73:1 / 深 `#6E7681` 3.77:1）是**有意低于正文 4.5:1** 的：
它承载时间戳、条数一类元数据；若推到 4.5:1 就会与 `textSecondary` 几乎同亮，
层级分离度从 2.10×/2.69× 塌到 1.22×/1.21×，整屏会变吵——这正是 1.8.19 首版被判「排版不如以前」的原因。
为此测试同时锁两条：`muted ≥ 3.0:1` 与 `secondary ÷ muted ≥ 1.45×`。

## 4. 组件级 Token 归属

| 概念 | Token | 是否随主题 |
|---|---|---|
| 主操作前景 | `onPrimary`（`ArgbContrast.bestOn(primary)`） | 是 |
| 成功按钮前景 | `onSuccess` | 是 |
| 卡片发丝线 | `outline`（`#E1E6EB` / `#30363D`，装饰性，只锁 [1.15, 2.0] 区间） | 是 |
| 代码块 | `CodeBgLight` / `CodeHeaderLight` / `CodeBgDark` / `CodeTextDark` … | 是（浅走 Primer，深走 Catppuccin） |
| Diff 行 | `DiffAddFgLight` / `DiffDelFgLight` / `DiffHunkFgLight` + 深色前景 | 是 |
| git 视图 tint | `GitDiff*`（16 个） | **否**（组件视觉标识） |
| 沉浸层 | `ScrimBase` / `ScrimLightbox` / `ScrimSurface` / `ScrimOnSurface` | **否**（相机/暗房场景） |
| 图表分类色 | `ChartBlue/Green/Amber/Red/Pink/Cyan/Violet` | **否**（数据色，表达"第几类"） |
| 行内代码底纹 | `InlineCodeTint`（`0x1F88888F`，12% 中性灰） | 否 |
| 品牌 logo | `BrandLogoBackground` + `bestOn` 推导的 π | **否** |
| 思考等级阶梯色 | `thinkingHigh` (深金) / `thinkingXHigh` (炽橙) / `thinkingMax` (烈焰红) | **是**（浅色 Rose-700/Orange-700，深色 Rose-400/Orange-400，均 >= 5.18:1） |

「否」的这几组不是「还没做主题化」，而是**不该主题化**：换主题时同一份数据的颜色映射、
相机取景的对比度、品牌标识都不应该变。写在这里是为了让后来者不必重新论证一遍。

## 5. 有意偏离主流规范的三处

| 偏离 | 对上游/规范的关系 | 理由 |
|---|---|---|
| 流速徽章前景不再固定白字 | 色相与 pi-web 阈值完全一致，只有前景按底色亮度分派 | 白字压 `#F9C22E` 仅 1.64:1、`#9BC53D` 2.01:1、`#53B3CB` 2.42:1 |
| 扫码 / 大图查看器恒深色 | pi-web 无对应界面 | 相机取景与暗房看图属功能需求（见 §4「否」列） |
| logo 恒深底白 π | 无对应 | 避免 AMBER 主题下出现亮黄块；π 与底块 14.6:1 |

## 6. 门禁

- **单测**：`ColorContrastTest`（16 组断言，5 套主题逐项测量 + 公式自检 21:1 / 1:1）。
  改任何色值，先跑它；断言失败时按提示调数值，不要放宽阈值。
- **CI 裸色守卫**：`build-and-release.yml` 的 `Guard hardcoded colors` 步骤对
  `app/src/main/java` 下新增的 `Color(0x...)` 直接判红（白名单见该步骤注释：
  `ui/theme/` 定义层，以及被显式允许的沉浸层/图表色所在文件——但那些值现已全部下沉到 `ThemePalette`，
  所以白名单只剩 `ui/theme/`）。

## 7. 已知未做（技术债）

- Gradle 8.11.1 → 新版本的升级：需与 AGP 8.7.3 / Compose 版本联动验证，且本机不能编译，
  验证代价是一次 tag 发布（失败即烧版本号），故单独立项。
- `ThemePalette` 之外仍有 `Theme.kt` 里 `onPrimaryContainer = Color.White`、`outlineVariant = Color(0xFFE5EAF0)`
  两处字面量，属主题定义层，可后续一并下沉。
