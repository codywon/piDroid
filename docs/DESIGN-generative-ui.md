# 设计决策：生成式 UI（`show_widget`）的手机端承载形态

> 适用组件：`app/src/main/java/com/pi/app/ui/chat/GenerativeUi.kt`
> 关联决策：`docs/DESIGN-artifact-viewer.md`（长内容的查看器容器）、`docs/DESIGN-labels.md`（说明性标签口径）

## 1. 要解决的问题

`pi-generative-ui` 插件注册两个工具：

| 工具 | 载荷 | 用户可见性 |
| --- | --- | --- |
| `visualize_read_me(modules)` | 20~60KB 的设计指南纯文本 | **不可见**（插件明确要求不向用户提及） |
| `show_widget(title, widget_code, width, height)` | 一段 HTML 片段或裸 SVG（常见 5~50KB） | 用户要看的就是它 |

手机端要回答的是：这 50KB 的 HTML 放在哪里、什么时候出现、能不能直接操作。

## 2. 权威参照：claude.ai，不是 pi-web

这一条必须写死，否则每轮讨论都会被 `AGENTS.md §2` 的「以 pi-web 为权威依据」按回「一行入口卡」。

- **pi-web 没有这条链路**：pi-web 里的 `widgets` 是 TUI 状态栏微件，与 `show_widget` 无关。
  所以「对齐 pi-web」在本题上**不构成约束**，只是没有借鉴对象。
- **桌面端 pi** 用 Glimpse 开一个独立的原生窗口 —— 手机没有等价物，也没有必要复刻
  （复刻的结果就是「一行文字卡 + 手动全屏」，把「看见」变成「去查找」）。
- **claude.ai 是真实存在、且用户已经形成预期的形态**：内容在会话流里直接长出来、
  可以直接拖滑块、需要时再放大。手机端以它为准。

`AGENTS.md §2` 的「不得臆造私有协议」在这里仍然成立 —— 我们**没有改任何协议**，
只是选择了一个容器形态。数据仍然是 pi 的 SSE 契约。

## 3. 决策

### 3.1 容器：会话内嵌可交互预览 + 全屏接管手势

```
┌─────────────────────────────────┐
│ 标题                     800×600 ⛶ │  ← 点标题行进全屏
├─────────────────────────────────┤
│                                 │
│   固定高度预览区（180~320dp）     │  ← 可直接交互，纵向手势还给列表
│                                 │
└─────────────────────────────────┘
```

- 预览区高度 = 模型声明的桌面宽高比 × 可用宽度，夹在 `180~320dp`。
  800×600 在 360dp 宽的屏上约 250dp。
- **全屏入口只放在标题行右侧**，不做「点预览区就全屏」：那会把页内点击变成切换页面，
  用户点按钮时页面会跳走。
- 全屏页沿用 `DESIGN-artifact-viewer.md` 的容器决策（`Scaffold` + `TopAppBar` + `BackHandler`），
  不使用半屏 sheet。

### 3.2 位置：微组件是内容，不是过程

`show_widget` 虽然产生于「过程阶段」，但它是本回合的**内容产出**。
因此它**不进 `ProcessDetailsGroup`**，而是作为 `TurnOutline.widgetItems` 独立成行，
排在过程折叠组之后、最终答复之前。

> 反例（已被移除）：微组件混在 `processItems` 里，回合一旦答完，
> `ProcessDetailsGroup.defaultExpanded` 变 false 自动收起，用户必须先展开
> 「N 条消息 · M 次工具调用」那一行灰字才看得见图。两级点击，体验很差。

同理，`visualize_read_me` 既不在聊天流里渲染，**也不计入「M 次工具调用」** ——
报一个用户看不见的调用与所见不符。

### 3.3 手势走向：按主轴归属，而不是猜方向

`DESIGN-artifact-viewer.md` 记的「WebView 不参与 Compose 的 nested scroll」针对的是
**需要自己纵向滚动的长内容**（docx、代码、长图）。微组件不是那类东西：它是按桌面窗口
设计的固定尺寸组件，页面已设 `overflow: hidden`，内容溢出时本来就该进全屏。
既然内嵌态根本不需要纵向滚动，冲突就从根上消失了。

规则（`WidgetWebView.onTouchEvent`）：

| 手势 | 归属 |
| --- | --- |
| `DOWN` | 先 `requestDisallowInterceptTouchEvent(true)`，避免滑块拖到一半被列表抢走 |
| 纵向占优的第一个 `MOVE`（> 16px） | 交出，列表继续滚，widget 一动不动 |
| 横向拖拽 / 点击 / 控件拖动 | 留在 WebView |
| `UP` / `CANCEL` | 复位 |

### 3.4 内存：视口内 live + 离屏快照

claude.ai 用 iframe，浏览器帮它回收。Android 里每个 widget 是一个活的 Chromium 实例，
一个会话 8 个 widget 就是 8 个实例，内存和滚动都会崩。

因此：`LazyColumn` 把滑出视口的 item dispose 时，先 `webView.draw(canvas)` 抓一帧存进
`WidgetSnapshots`（LRU 8 条，宽度上限 720px），回来时先贴快照、再由新建的 WebView 顶上去。

- 缓存的是**渲染结果**，不是交互态：滚回去之后页内临时状态（拖过的滑块）会回到初始值。
  这是明确接受的代价，不要承诺「状态保持」。
- 视觉上接近 iframe 的效果：没有白屏闪烁，Chromium 实例数跟着视口走，不跟着会话长度走。

### 3.5 失败必须降级，白屏不可接受

模型输出的 HTML、CDN 可达性、WebView 内核版本三者任一都能让页面变成白板。
白屏是**不可接受的最低线**，因此三重哨兵：

1. **页面内哨兵**：外壳注入 `window.onerror` + `unhandledrejection` + 捕获阶段的脚本
   加载失败监听（外链脚本 404 不触发 `window.onerror`，必须看 `event.target`），
   统一以 `__PI_ERR:` 前缀打到 `console.log`，宿主 `WebChromeClient.onConsoleMessage` 捕获；
2. **白板检测**：`onPageFinished` 时检查 `#root` 子节点数，为 0 视为失败；
3. **主文档失败**：`onReceivedError`（`isForMainFrame`）。

命中任一条 → 切**源码视图**（等宽代码块 + 重试），与普通工具卡视觉一致。
不给解释性副标题 —— 失败原因对用户没有可操作性（口径见 `DESIGN-labels.md`）。

### 3.6 离线可用：CDN 本地化

`shouldInterceptRequest` 拦下 `cdnjs.cloudflare.com` / `cdn.jsdelivr.net` / `unpkg.com` /
`esm.sh` 上已知文件名（当前只有 `chart.umd.js`）的请求，改由 `assets/libs/` 回吐。
弱网与离线场景下 Chart.js 秒开，不再依赖网络往返。

只做 allowlist，不做通用代理：没命中的请求照常走网络（`mermaid` 压缩后 >1MB，不本地化）。

### 3.7 脚本顺序：不需要手工调度

参考实现里那套 `runScripts()`（克隆 `<script>` 到新元素、`await` 外链 `onload` 后再跑内联）
是**为流式追加片段准备的** —— `innerHTML` 插入的 `<script>` 不会执行。

我们走的是**整篇文档加载**：浏览器解析时按文档顺序同步执行脚本，普通外链脚本
（无 `async` / `defer`）还会阻塞解析直到下载执行完毕。所以 `Chart` 一定在内联脚本之前就位，
不需要任何手工排序，也不需要 `morphdom`。

### 3.8 安全

开启 JS（微组件依赖 Canvas / Chart.js），但：

- **不注入任何 JS 桥**（无 `addJavascriptInterface`）—— 页面内容来自模型输出，
  不给它任何与应用通信的能力；
- `allowFileAccess = false`、`allowContentAccess = false`；
- 页面内跳转一律交给系统浏览器（`Intent.ACTION_VIEW`），不在卡片里接管导航。

## 4. 已知边界与未做的事

| 项 | 现状 | 理由 |
| --- | --- | --- |
| 流式呈现 | **未接入**（`WidgetCodeStream` 与单测已就绪） | `toolcall_end` 才拿得到完整 `widget_code`；流式需要接 `toolcall_delta.argumentDelta` |
| 离屏交互态 | 不保留 | 见 3.4 |
| 平板分栏 | 未做 | 与 `DESIGN-artifact-viewer.md` 的平板规划一起做 |
| 会话内预览开关 | **不做** | 明确决定：不引入设置项 |

### 4.1 流式接入时必须遵守的两条

1. `toolcall_delta` 在 pi-web 里是 **droppable** 事件（客户端积压 >512KB 会被静默丢弃），
   且 SSE 重连不补发累积。所以流式画面**一定会有缺口**：终态必须用
   `toolcall_end` 的全量 `arguments` 再渲染一次，流式只是锦上添花，兜底在终态。
2. `delta` 是**原始 JSON 分片**，拼接过程中不是合法 JSON（字符串可能停在一半、
   `\uXXXX` 可能被劈成两半）。必须用 `WidgetCodeStream` 的状态机增量提取，
   不能用 `JsonParser` 反复解析。

## 5. 验收用例

1. 发消息后**不点任何东西**，预览区出现并显示图；
2. 拖滑块：滑块动、页面不滚、列表不滚；
3. 上下拖预览区：列表滚、widget 完全不动；
4. 点标题行 / ⛶ → 全屏；返回 → 回原位，内容一致；
5. 滑走 3 屏再滑回 → 先见快照、后恢复交互，无空白闪烁；
6. 断网重进 → 图表仍能渲染（Chart.js 本地化）；
7. 模型输出坏 HTML → 自动切源码视图，可重试；
8. 一个回合答完后，微组件**不在**过程折叠组里，无需展开即可见。
