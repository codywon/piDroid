# 设计决策：文件 / 输出查看器的容器形态（手机全屏、平板分栏）

- 状态：**已采纳**（v1.7.43 起生效）
- 范围：`ArtifactViewerFullScreen`（聊天里的文件预览、bash 完整输出、Word/PDF/音视频预览）
- 相关代码：`app/src/main/java/com/pi/app/ui/chat/ArtifactViewerFullScreen.kt`、`ChatScreen.kt`

## 1. 背景：为什么必须换掉半屏 `ModalBottomSheet`

旧实现（`ChatScreen.kt` 里的 `ModalBottomSheet(onDismissRequest = ...)`）有三个可复现的问题：

1. **手势归属冲突**：`LazyColumn` 与 `ModalBottomSheet` 各自注册一个 nested-scroll
   connection，fling 速度会串到 sheet 自身的拖拽手势上 —— 表现为「内容滑到底，
   sheet 继续动，然后弹回半屏」（AOSP issue 353304855 的同一现象）。用户侧观感就是
   「一上滑查看正文，弹窗变大又缩回半屏」。
2. **WebView 完全不参与 Compose 的 nested scroll**：`.docx` 与 SVG 预览是 WebView，
   在里面的滚动手势会被外层 sheet 抢走。
3. **误关闭**：读到文档末尾继续下拉即 `onDismissRequest`，长报告要重新找位置。

Material 3 对 bottom sheet 的定位是「补充屏幕主内容的次要区域」，并只在
*at full-screen height* 时视为独立页面；一次性、长内容的阅读流程属于
**full-screen dialog / 独立页面**（对照 iOS：Quick Look / 文件阅读一律全屏）。

## 2. 决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 手机（< 600dp）容器 | **全屏页面**（`Scaffold` + `TopAppBar`），不再使用 `ModalBottomSheet` | 消灭容器拖拽与内容滚动的冲突；阅读空间最大化 |
| 平板 / 折叠屏展开（≥ 600dp） | 规划为 **左聊天 + 右文档分栏**（`WindowSizeClass`） | 与 pi-web 桌面端 `FileViewer` 的右侧常驻面板对齐；M3 breakpoints 对 ≥600dp 的建议也是双栏 |
| 关闭方式 | 系统返回键 / 系统手势返回 / 顶栏返回按钮（48dp） | **不自制**全屏 swipe-to-dismiss：md 里的宽表格、代码块横向滚动、图片左右翻页都会与它冲突 |
| 沉浸态 | **点击正文区域**切换顶栏显隐（Quick Look 式），非「滚动收起」 | 滚动收起会让用户找不到出口；点击切换可预期 |
| 动作层级 | 顶栏 `⋮` 收口「复制全部 / 复制文件路径 / 用其他应用打开 / 重新加载」；「预览 ÷ 源码」作为模式开关常驻正文上方；「加载更多」保持行内 | 一个主操作原则，避免同一动作在底条与菜单各出现一次 |
| 分块续读 | **手动**「加载更多」（与 pi-web `FileViewer` 的 Load more 1:1） | 阅读器的「读到哪」必须可预期；自动追加只适合 feed/日志流 |
| 触控目标 | ≥ 48dp | Material 规范（iOS HIG 的 44pt 不适用） |
| 错误态 | 1 个主操作（重新加载）+ 1 个次操作（用其他应用打开）+ 可长按选中的路径 | 三个等权按钮没有层级（上一版方案的错误） |
| 返回栈 | 查看器内部 `BackHandler` 先关自身，再由外层「返回会话列表」 | `ChatScreen` 顶部有一个无条件的 `BackHandler { onBack() }`；后注册者优先，否则在全屏里按返回会直接丢掉阅读位置 |

## 3. 与 pi-web 的关系（偏离说明）

`AGENTS.md §2` 要求 1:1 对齐 `@agegr/pi-web`。pi-web 的 `FileViewer` 是**右侧常驻面板**，
手机没有留出侧栏的宽度，因此本实现是**有意识的偏离**：

- 手机：用「全屏页面 + 明确返回」承载同一份接口与同一套语义
  （`GET /api/files/<path>?type=read|meta|download|preview`、`sessionId`、256KB 分块、
  「必须整篇加载完才渲染预览」的规则都不变）；
- 平板：按 pi-web 的分栏复原，不让手机端的妥协扩散到大屏。

判断是否「破坏对齐」的口径：**协议与行为对齐（接口、分块、渲染时机、错误语义）**；
**容器形态按窗口尺寸适配**。

## 4. 未完成（后续）

- 平板 `WindowSizeClass` 分栏（当前手机全屏路径已生效）。
- `Modifier.systemGestureExclusion()`：源码/宽表格的横向滚动区需排除系统边缘返回手势热区，
  当前先用左右 14dp 内边距缓解。
- 图片查看器：`ImageDecoder` 采样尺寸 + 双指缩放（现在的 `BitmapFactory` 全量解码对超大图有 OOM 风险）。
- 长文档按 top-level block 分块进 `LazyColumn`（含 `key` 与追加后滚动位置保持）。
