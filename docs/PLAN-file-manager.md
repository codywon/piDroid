# 实施计划：把 pi-web 的文件管理能力移植到 πDroid

- 状态：**已立项（决策已定，待开工）**
- 目标版本：`1.8.32` → `1.8.34`（分三批发版，见 §8）
- 上游依据：`@agegr/pi-web@0.13.0`（源码 `github.com/agegr/pi-web`，`HEAD d128bae "Release v0.13.0"`，与本机已装 npm 包版本一致）
- 关联文档：`docs/DESIGN-artifact-viewer.md`（查看器容器形态，已采纳）、`docs/DESIGN-labels.md`（subtitle 使用边界）

> 本文所有 pi-web 侧文件/行号均来自实际阅读的 0.13.0 源码；πDroid 侧文件/行号来自当前工作树
> （`git HEAD = 40debfe`，`versionCode 137 / versionName 1.8.31`）。改动前请以当时源码为准复核一次行号。

---

## 1. 摘要

三块能力，三种成熟度：

| 场景 | 现状 | 本轮要做的事 |
|---|---|---|
| ① 以资源管理器方式浏览/操作文件 | **完全没有** | 新写 Compose 文件树（懒加载 + 搜索 + git 变更 + 上传） |
| ② 在页面内打开电脑上的文档/图片 | **约 90% 已有**（`ArtifactViewerFullScreen` 已支持 TEXT/MARKDOWN/IMAGE/SVG/DOCX/AUDIO/VIDEO；PDF 只给"交给外部应用"的提示） | 把加载逻辑从 `ChatScreen` 抽成可复用 loader；补 PDF 内置翻页 |
| ③ 把文件下载到手机上 | **部分**（只会落到 `cacheDir/opened` 并交给外部应用；无"保存到系统目录"） | 新增 `MediaStore` / SAF 两条落点，流式写盘 + 进度 |

服务端 **零新增接口**：`server/gateway.js` 是纯反代（`createProxyMiddleware` + Bearer 鉴权），pi-web 的
`/api/files/*`、`/api/file-index`、`/api/git/*` 已原样穿透，App 现有的 `getFileMeta` / `getFileTextChunk` /
`getFilePreviewHtml` / `getFileBytes` / `downloadFileToCache` / `uploadFiles` 全部打到这些路由上并且已在线上跑通。

---

## 2. 决策落定（D1–D8 已按建议定稿）

| # | 决策 | 结论 | 理由 / 影响面 |
|---|---|---|---|
| D1 | 入口形态 | **工作区长按菜单 → 「浏览文件」→ 全屏 `FilesScreen`** | 与 pi-web "files tab 属于某个项目（cwd）"语义一致；不动首页信息架构 |
| D2 | 是否允许浏览任意目录 | **V1 只允许已有工作区**；「打开文件夹」不做 | `POST /api/cwd/validate` 会 `allowFileRoot()`，等于把该目录加入可读白名单 —— 这是安全边界，不是体验项。后续若做，必须显式二次确认并写进 `SECURITY_UPGRADES.md` |
| D3 | 语法高亮 | **不做**，源码视图为等宽纯文本 + 行号 | 本仓库当前没有任何高亮引擎（`MarkdownRenderer` 的代码块也是等宽）；pi-web 用的是 `react-syntax-highlighter`，Kotlin 侧无等价物 |
| D4 | PDF | **内置 `PdfRenderer` 逐页翻页**，同时保留「用其他应用打开」 | 聊天附件已有一套跑通的实现（`MediaPreviewComponents.kt:1048-1072`），只需抽成通用函数 |
| D5 | 越界软链（`outsideLinkTarget`） | **V1 只显示不可点**（灰色行 + "指向允许根之外"），不实现「允许」 | 克隆来的仓库可能自带指向 `/` 或 `~` 的软链，手机误点等于把整盘加白名单 |
| D6 | `/api/open-in-explorer` | **不移植** | 语义是 PC 桌面窗口；且网关 `changeOrigin` 会让 `isLoopbackHost()` 判真，等于给手机一个远程弹窗能力，超出需求 |
| D7 | 文件实时刷新 | **树不开 watch**；预览页提供「实时」开关（默认关），仅对当前打开的一个文件开 `type=watch` | 每个 watch 在 PC 上常驻一个 `fs.watch`（`lib/file-watch-registry.ts` 有计数）；移动端按需开启 |
| D8 | 上传 | **做，但排在最后一批** | 接口与冲突语义现成（`?type=upload&conflict=`），唯一新增成本是带进度的 `RequestBody` |
| — | 平板 ≥600dp 双栏 | **不在本轮** | 与 `docs/DESIGN-artifact-viewer.md §4` 的"左聊天 + 右文档"一起排期，避免两套布局同时改 |

### 由决策派生的硬约束

1. **不做目录级危险操作**：pi-web 的文件 API 只有 `GET`（list/read/download/meta/preview/watch）与
   `POST`（upload/upload-check/allow-link），**没有 rename / delete / mkdir / zip**。
   手机端不得自造这类接口（那会绕开 pi-web 的授权模型）。
2. **下载必须走 OkHttp + `Authorization` 头**：网关只认 Bearer（`server/gateway.js:354`），
   `?token=` 仅对 `/events` 放行（`QUERY_TOKEN_PATH_PATTERN`）。所以 `DownloadManager`、裸 URL、`<a download>` 一律不可用。
3. **图片预览走 `type=download` 而不是 `type=read`**：后者对 >10MB 图片返回 `413`（`app/api/files/[...path]/route.ts` read 分支）。
4. **文本只按 UTF-8 解码**（`lib/text-preview.ts`），GBK 文本会乱码 —— UI 不自行猜测编码，文案说明即可。

---

## 3. pi-web 侧实现索引（供实现时对照）

### 3.1 服务端

| 文件 | 职责 | 本计划是否依赖 |
|---|---|---|
| `app/api/files/[...path]/route.ts` | 唯一的文件 API（GET 6 个 type + POST 3 个 type，730 行） | ✅ 全部 |
| `app/api/file-index/route.ts` | 文件搜索索引（git ls-files 优先，退 readdir BFS） | ✅ 搜索 |
| `app/api/git/status/route.ts`、`git/diff/route.ts` | 变更列表与单文件 diff | ✅ 变更区 |
| `lib/file-access.ts` | `getAllowedFileRoots()`：会话 cwd + projectRoot + 运行期追加，5s 缓存 | 契约依据（决定"只能浏览工作区"） |
| `lib/path-security.ts` | `isPathWithinRoots` / `hasParentDirectorySegment` / realpath 二次校验 | 契约依据（不需要客户端重写） |
| `lib/paths.ts` | `filePathFromApiSegments` / `samePath` / `resolveExistingPath` | 编码规则依据 |
| `lib/file-paths.ts` | `encodeFilePathForApi`（含 UNC `//host` 合并进首段） | ✅ 客户端编码必须对齐 |
| `lib/file-types.ts` | 扩展名→MIME、`TEXT_PREVIEW_MAX_BYTES=256KB`、`IMAGE_PREVIEW_MAX_BYTES=10MB`、`DOCX_PREVIEW_MAX_BYTES=10MB` | ✅ 常量对齐 |
| `lib/text-preview.ts` | 256KB 分块 + UTF-8 边界回退 + 二进制嗅探（前 8000 字节含 NUL） | 契约依据 |
| `lib/file-tree-visibility.ts` | 隐藏项三态 `ignored` / `excluded` / `always` | ✅ 隐藏开关 |
| `lib/linked-directory.ts` | 越界软链上报与 allow-link 审批 | ✅ 只读展示 |
| `lib/file-upload.ts`、`lib/bounded-form-data.ts` | 上传 25MB/文件、100MB/批、原子替换 | ✅ 上传 |
| `lib/file-version.ts` | `size:mtime` 版本串（watch 重连时避免重复加载） | ✅ 实时开关 |
| `lib/request-security.ts` | Host / Origin / Sec-Fetch 守卫（pi-web 自身无 token） | 契约依据 |

### 3.2 前端

| 文件 | 职责 | 处理方式 |
|---|---|---|
| `components/FileExplorer.tsx`（1414 行） | 树 / 搜索 / 变更 / 上传 / 隐藏开关 / @提及 | 行为移植到 Compose |
| `components/FileViewer.tsx`（1957 行） | 预览分派 + 5 个 viewer + 分块续读 + diff | 本项目已等价实现，只补 PDF |
| `components/SessionSidebar.tsx`（2682/2853/3044/3161） | files tab 与 `explorerCwd` | 形态参考（落到工作区菜单） |
| `components/AppShell.tsx:2842`、`file-tab-state.ts` | 主区文件 tab 与状态保持 | 不做（手机单文件全屏） |
| `components/FileIcons.tsx` + `public/icons/catppuccin/{latte,mocha}/*.svg`（各 32 个） | 扩展名→图标 | 可选搬运资产 |
| `lib/file-tree-expansion.ts` | 每 cwd 展开目录记忆（LRU 20） | 移植为纯 Kotlin |
| `lib/search-tree.ts` | 搜索结果折叠成树 | 移植为纯 Kotlin |
| `lib/file-upload-client.ts` | XHR 上传 + 进度 + 冲突策略 | 行为移植（OkHttp 版） |

---

## 4. 接口契约清单（客户端需要实现的调用）

### 4.1 `GET /api/files/{path…}?type=…`

路径编码：绝对路径按 `/` 切段，**逐段** `encodeURIComponent`（`lib/file-paths.ts:6`）。
App 已有等价实现 `PiApiClient.encodeFilePath`（`PiApiClient.kt:2249`），但**它会拍平 UNC**（`//host/share` 变成 `/host/share`），
pi-web 特意把 `//host` 合并进首段；若后续要支持 UNC 需按上游改写这一段。

| type | 参数 | 返回（成功） | 主要错误 |
|---|---|---|---|
| `list` | `hidden=1` | `{entries:[{name,isDir,size:0,modified:"",outsideLinkTarget?,outsideLinkEncloses?,hidden?}], path}` | `400 Not a directory` / `403 Access denied` |
| `read`（文本） | `offset`（`^\d+$`，≤size） | `{content,nextOffset,truncated,binary,language,size,mtime}`，单次 ≤256KB | `400 Invalid text preview offset` |
| `read`（图/音/视/pdf/docx） | `Range` 可选 | 原始流 + `Accept-Ranges` + `Content-Disposition: inline` | 图片 >10MB → `413` |
| `download` | `Range` 可选 | 原始流 + `Content-Disposition: attachment; filename*=UTF-8''…` | `400 Not a file` |
| `meta` | — | `{size,mtime,language,mime,previewKind:"pdf"\|"docx"\|null}` | `400 Not a file` |
| `preview` | — | 仅 `.docx`：整页 HTML（mammoth，图片内联 data URI，CSP 禁脚本） | `400` / `413 >10MB` |
| `watch` | `holder=`（可选） | `text/event-stream`：`connected{filePath}` / `change{mtime,size}` / `error` | 文件不存在也允许 |

> `?sessionId=` 仅用于"文件不在允许根内、但被该会话引用过"的兜底放行（`lib/session-file-references.ts`）。
> 本计划的文件浏览器只浏览允许根内路径，**可以不传**；预览沿用现有逻辑带 `sessionId` 更稳。

### 4.2 `POST /api/files/{dir…}?type=…`

| type | 参数 | 返回 |
|---|---|---|
| `upload` | `?conflict=error\|overwrite\|skip` + multipart `files` | `{uploaded[],skipped[],errors[{name,error}]}`；部分失败 `207`；`error` 策略遇重名 `409 {conflicts[],nonReplaceable[]}` |
| `upload-check` | JSON `{fileNames:string[]}` | `{conflicts[],nonReplaceable[]}` |
| `allow-link` | JSON `{target}` | `{path}`（V1 不调用） |

### 4.3 搜索与变更

| 接口 | 参数 | 返回 |
|---|---|---|
| `GET /api/file-index` | `cwd`（必填绝对路径）、`q`（≤500） | 无 q：`{files[],truncated}`（≤5000）；有 q：`{matches:[{path,isDir}]}` |
| `GET /api/git/status` | `cwd` | `{isGitRepository,repositoryRoot,files[{filePath,status,code,indexStatus,worktreeStatus}],additions,deletions}` |
| `GET /api/git/diff` | `cwd`、`path` | `{supported,status,patch}` |

App 侧已有：`GitStatusResult` / `GitDiffResult` / `getGitStatus` / `getGitDiff`（`PiApiClient`），`GitChangesFullScreen.kt` 已消费。

---

## 5. 可复用性判断表

| pi-web 资产 | 判断 | πDroid 落点 |
|---|---|---|
| 文件 API 全部 9 种 type | **直接复用** | 网关已反代；`PiApiClient` 已覆盖 meta/read/preview/download/upload |
| file-index / git status / git diff | **直接复用** | 新增客户端方法（index）；git 已有 |
| `path-security` / `file-access` / `linked-directory` | **留在服务端** | 客户端只负责正确编码、不制造 `..` |
| `FileExplorer.tsx` 行为 | **移植（不可复用代码）** | 新增 `ui/files/*` |
| `FileViewer.tsx` 的各 viewer | **已等价（除 PDF 内置）** | `ArtifactViewerFullScreen.kt` + `MediaPreviewComponents.kt` |
| docx→HTML→WebView | **已有** | `ChatScreen.kt:3904 DocxArtifactCard` |
| SVG→WebView（禁 JS） | **已有** | `MediaPreviewComponents.kt:741 SvgWebViewCanvas` |
| 图片降采样解码 | **已有但 private** | `MediaPreviewComponents.kt:366` → 改 `internal` |
| 图片字节内存+磁盘缓存（带 Bearer） | **已有** | `MediaPreviewComponents.kt:107 MediaByteCache` + `MediaLoadPolicy` |
| PDF 渲染 | **已有但只服务聊天附件** | 抽 `MediaPreviewComponents.kt:1048-1072` 为通用函数 |
| 下载到手机缓存并外部打开 | **已有** | `PiApiClient.downloadFileToCache` + `ChatScreen.kt:3941 openFileWithOtherApp` |
| 下载到系统目录 | **缺口，模式已有** | 仿 `GenerativeUi.kt:640-720 WidgetExportHelper`（MediaStore + API<29 回退） |
| 拖拽上传 | **不适用** | 用 `ACTION_OPEN_DOCUMENT` 多选 |
| open-in-explorer | **不移植**（D6） | — |

---

## 6. 实施计划（4 批 · 12 个任务）

依赖图：

```
批次 1（数据层，纯 JVM 可测）
   ├── 批次 2（预览复用缝）──┐
   └── 批次 4（下载到手机）──┼── 批次 3（资源管理器 UI）
```

- 批次 1 无 UI 依赖，先做。
- 批次 2 与 4 都只依赖批次 1，可并行；但 3 需要 2 的预览入口与 4 的下载入口，所以最后做。
- 每批独立出包（§8），任一批失败不影响前一批已发布的行为。

---

### 批次 1 · 数据层（v1.8.32）

| 任务 | 文件 | 改动 |
|---|---|---|
| **T1.1** 模型 | `app/src/main/java/com/pi/app/data/model/Models.kt` | 新增 `FileEntry`、`FileDirectoryListing`、`FileIndexMatch`；`FileMeta` 补 `mtime: String?`、`previewKind: String?`，并加派生 `val version: String? get() = mtime?.let { "$size:$it" }`（对齐 `lib/file-version.ts`） |
| **T1.2** 纯解析 | `app/src/main/java/com/pi/app/network/FileListParsing.kt`（新） | `parseListing` / `parseIndexMatches` / `parseIndexFiles` / `parseError` / `joinPath` / `parentPath` / `relativePath` / `displayName` / `sortEntries` / `isHiddenEntry`。**不得 import `android.*`**（`FileLinks` 里用了 `android.net.Uri`，不要依赖它） |
| **T1.3** 客户端方法 | `app/src/main/java/com/pi/app/network/PiApiClient.kt` | 新增<br>`suspend fun listFileDirectory(dirPath: String, showHidden: Boolean = false, sessionId: String? = null): Result<FileDirectoryListing>`<br>`suspend fun searchFileIndex(cwd: String, query: String): Result<List<FileIndexMatch>>`<br>`getFileMeta` 多解析 `mtime` / `previewKind` |
| **T1.4** 单测 | `app/src/test/java/com/pi/app/network/FileListParsingTest.kt`（新） | 见 §7.1 |

改动幅度：`Models.kt` +60、`FileListParsing.kt` 新 ~150、`PiApiClient.kt` +110、测试 ~150。

**验收**：CI `Run Unit Tests` 绿；`Build Signed APK` 绿。

---

### 批次 2 · 预览复用缝（v1.8.32 同版本）

目标：把"打开一个远端文件"从 `ChatScreen` 内部函数变成可被文件浏览器复用的能力，**同时不改变聊天页现有行为**。

| 任务 | 文件 | 改动 |
|---|---|---|
| **T2.1** loader | `app/src/main/java/com/pi/app/ui/files/RemoteFilePreview.kt`（新） | `internal class FilePreviewSource(onMeta, onTextChunk, onPreviewHtml, onBytes, onDownload, onPlaybackSource)`；`internal data class FilePreviewPayload(path,title,kind,mime,meta,text,imageBytes,pdfFile,docxHtml,message,nextOffset,truncated,sizeBytes,isBinary)`；`internal suspend fun loadFilePreview(path, source): FilePreviewPayload`。把 `ChatScreen.openFilePreview` 里"meta→按类型分派→docx 10MB 判断→binary 提示→分块 offset"整套逻辑搬进来 |
| **T2.2** 暴露判定函数 | `app/src/main/java/com/pi/app/ui/chat/ChatScreen.kt` | `private fun artifactKindOf(...)` → `internal fun artifactKindOf(...)`（`ChatScreen.kt:3704`，一行改动）；`openFilePreview` 改为调用 `loadFilePreview` 并赋值既有 `artifact*` 状态（对外行为、BackHandler、Toast 文案保持不变） |
| **T2.3** 图片采样对外开放 | `app/src/main/java/com/pi/app/ui/components/MediaPreviewComponents.kt` | `private fun decodeSampledImage` → `internal`；新增 `internal fun decodeTargetLongEdgePx(density, screenWidthDp, screenHeightDp): Int`（把 `FullscreenMediaLightboxDialog:1017-1025` 的公式提出，避免三处重复） |
| **T2.4** PDF 内置渲染 | `app/src/main/java/com/pi/app/ui/components/PdfDocumentView.kt`（新） | `@Composable internal fun PdfDocumentView(file: File, modifier: Modifier)`：`ParcelFileDescriptor.open` + `PdfRenderer`，页图按 `page.width*2.2` 上限渲染（防 OOM），`LazyColumn` 逐页 + 页码指示；逻辑与 `MediaPreviewComponents.kt:1048-1072` 一致 |
| **T2.5** 查看器接 PDF | `app/src/main/java/com/pi/app/ui/chat/ArtifactViewerFullScreen.kt` | `ArtifactKind.PDF` 分支：有 `pdfFile` 时渲染 `PdfDocumentView`，否则保留现有提示卡（`ArtifactViewerFullScreen.kt:330`）；顶栏 ⋮ 保留「用其他应用打开」 |
| **T2.6** 单测 | `app/src/test/java/com/pi/app/ui/files/LoadFilePreviewTest.kt`（新） | loader 的分派矩阵（见 §7.2），用假 `FilePreviewSource`，不碰 Android |

改动幅度：新文件 ~220 + ~200，`ChatScreen.kt` −80/+25，`MediaPreviewComponents.kt` +60/−30，`ArtifactViewerFullScreen.kt` +25。

**验收**：
- CI 绿；
- 真机回归聊天页：md / png / svg / docx / pdf / mp3 / 普通 txt / 二进制 zip 打开后的表现与 v1.8.31 **完全一致**（唯一可见差异：PDF 从"提示交给外部应用"变成"可内置翻页，⋮ 仍可外部打开"）。

---

### 批次 3 · 资源管理器 UI（v1.8.33）

| 任务 | 文件 | 改动 |
|---|---|---|
| **T3.1** 树状态（纯逻辑） | `app/src/main/java/com/pi/app/ui/files/FileTreeState.kt`（新） | `internal object FileTreeState`：`rememberExpanded(cwd)`（LRU 20，移植 `lib/file-tree-expansion.ts`）、`buildSearchTree(paths)`（移植 `lib/search-tree.ts`）、`changedDirectoryPaths(statuses, cwd)`（移植 `FileExplorer.tsx:846-861`）、`visibleEntries(entries, showHidden)` |
| **T3.2** 单测 | `app/src/test/java/com/pi/app/ui/files/FileTreeStateTest.kt`（新） | 见 §7.3 |
| **T3.3** 行渲染 | `app/src/main/java/com/pi/app/ui/files/FileRow.kt`（新） | 缩进、图标（目录/文件，扩展名分组）、git 徽标、加载中转圈、错误重试行、"empty"行、越界软链灰行（不可点，D5）、长按菜单（预览 / 下载到手机 / 用其他应用打开 / 复制路径 / 上传到此处） |
| **T3.4** 页面 | `app/src/main/java/com/pi/app/ui/files/FilesScreen.kt`（新） | `TopAppBar`（返回 / 路径面包屑 / 刷新 / 搜索 / 隐藏开关 / 上传）+ 树（懒加载）+ 变更区（复用 `getGitStatus` + `StructuredDiffView`）+ 上传进度与冲突对话框。**subtitle 遵守 `docs/DESIGN-labels.md`：常驻说明 ≤2 条，其余用 `InfoTitle.kt` 的 ⓘ** |
| **T3.5** 接线 | `app/src/main/java/com/pi/app/MainActivity.kt` | 新增 `var filesWorkspaceCwd: String?`；`when { … }`（2025 行）加 `filesWorkspaceCwd != null -> FilesScreen(...)`；传 `apiClient` 回调（照 `MainActivity.kt:2596-2645` 的既有写法） |
| **T3.6** 入口 | `app/src/main/java/com/pi/app/ui/session/SessionListScreen.kt` | 工作区长按菜单（`WorkspaceMenuActionItem` 列表，1761-1862 行）在「在新会话中打开」后插入「浏览文件」；新增参数 `onBrowseFiles: (WorkspaceInfo) -> Unit` |
| **T3.7** 图标资产（可选） | `app/src/main/assets/icons/catppuccin/**` | 从 pi-web `public/icons/catppuccin/{latte,mocha}` 搬 32×2 个 SVG；先只搬 6 个高频（ts/js/py/md/json/word/pdf） |

改动幅度：`FileTreeState.kt` ~200、`FileRow.kt` ~260、`FilesScreen.kt` ~650、`MainActivity.kt` +70、`SessionListScreen.kt` +20、测试 ~140。

**行为对照（逐条对齐 pi-web，避免"看起来像"）**

| 行为 | pi-web 依据 | 落点 |
|---|---|---|
| 点开目录才请求 | `FileExplorer.tsx:273-289` | `FileRow` 内部 `children/loaded/loading/loadError` 四态 |
| 目录优先排序 | `route.ts` list 分支 `.sort()` | 服务端已排好；客户端仅对搜索结果重排 |
| 空目录 / 失败 | `FileExplorer.tsx:594-603` | 行内 "empty" / 红色错误行（可点重试） |
| 变更区 | `FileExplorer.tsx:1078`（拉取）+ `1323-1345`（渲染） | `GitChangesFullScreen.kt` 的解析 + `StructuredDiffView` |
| 搜索防抖 150ms（`setTimeout(...,150)`）+ 服务端 q | `FileExplorer.tsx:758-800` | `onSearchQueryChange` + `buildSearchTree` |
| 隐藏开关默认关 | `route.ts` `hidden=1`；`lib/file-tree-visibility.ts` | 关（打开会为每目录跑 `git check-ignore`） |
| 大目录保护 | pi-web 无分页 | V1 加：>500 项时只渲染前 500 并给出提示行 |

**验收**：CI 绿；真机：进入工作区 → 逐层展开 → 打开 md/png/docx/pdf → 搜索命中 → 变更区进入 diff → 返回不丢会话状态。

---

### 批次 4 · 下载到手机（v1.8.34）

| 任务 | 文件 | 改动 |
|---|---|---|
| **T4.1** 流式下载器 | `app/src/main/java/com/pi/app/data/storage/FileDownloader.kt`（新） | `suspend fun downloadToDownloads(context, path, fileName, onProgress): Result<String>`：`GET ?type=download` + Bearer 流式 → API29+ `MediaStore.Downloads` + `RELATIVE_PATH="Download/πDroid"` + `IS_PENDING` 两段式；API26-28 `Environment.getExternalStoragePublicDirectory`（权限已在 Manifest 声明 `maxSdkVersion=28`） |
| **T4.2** SAF 落点 | 同上 | `suspend fun downloadToUri(context, path, uri, onProgress)` 配合 `ACTION_CREATE_DOCUMENT`（长按菜单里的「另存为…」） |
| **T4.3** 上传进度 | `app/src/main/java/com/pi/app/network/PiApiClient.kt` | 新增带进度的 `RequestBody` 包装 + `uploadFiles(cwd, files, onProgress)` 重载；冲突策略 `error/overwrite/skip` 与 `upload-check` 先查（T1 已有） |
| **T4.4** 单测 | `app/src/test/java/com/pi/app/data/storage/FileNamingTest.kt`（新） | 文件名净化（`\ / : * ? " < > |`）、同名去时间戳策略、路径末段取名的边界（结尾斜杠、UNC、中文名） |

改动幅度：`FileDownloader.kt` 新 ~250、`PiApiClient.kt` +80、测试 ~80。

**验收**：CI 绿；真机：下载 png/pdf/docx 各一个 → 系统「下载」App 能看到 `Download/πDroid/…` → 断网/重试有明确错误；>200MB 视频文件下载时内存平稳（流式写入，不 `ByteArray`）。

---

## 7. 测试计划

> 按 `AGENTS.md §3`：**单元测试不得依赖 Android 运行时**（不碰 `android.*`、`Uri`、`Context`），
> 复杂解析/纯逻辑先抽独立文件再测。测试是否通过**以 CI 的 `Run Unit Tests` 为唯一标准**，本地不编译。

### 7.1 `FileListParsingTest`（批次 1）

| 用例 | 断言 |
|---|---|
| list 正常响应（含 `outsideLinkTarget` / `outsideLinkEncloses` / `hidden`） | 字段完整映射，未知字段忽略 |
| list 空目录 | `entries` 为空、`path` 保留 |
| 错误体 `{"error":"Access denied"}` | `parseListing` 返回 null，`parseError` 返回原文 |
| Windows 盘符 `D:/a/b.md` | `joinPath/parentPath/displayName` 正确 |
| UNC `//host/share/x` | `parentPath` 不吞掉 host 段 |
| POSIX `/home/u/a` | 同上 |
| `..` 出现在输入 | `sortEntries`/`joinPath` 不生成 `..`（拒绝或规范化） |
| `previewKind`/`mtime` | `FileMeta.version` == `"size:mtime"` |
| 索引 `{matches:[{path,isDir}]}` | 解析为 `FileIndexMatch` |
| 索引 `{files,truncated}` | 解析出 `truncated` 标志 |

### 7.2 `LoadFilePreviewTest`（批次 2，假 source）

| 输入 | 期望 `FilePreviewPayload` |
|---|---|
| `png`（meta mime `image/png`） | `kind=IMAGE`，调 `onBytes`，`text=null` |
| `svg` | `kind=SVG`，取文本 |
| `md` 且 `truncated=true` | `kind=MARKDOWN`，`truncated=true`（UI 才知道不渲染预览） |
| `docx` size > 10MB | `message` 命中上限文案，**不调** `onPreviewHtml` |
| `docx` size 正常 | 调 `onPreviewHtml`，`docxHtml` 非空 |
| `pdf` | `kind=PDF`，调 `onDownload`，`pdfFile != null` |
| `zip`（`binary=true`） | `isBinary=true`，`text=null`，`message` 为二进制提示 |
| meta 失败 | `message` 以「读取文件失败」开头（与现有错误态判定一致，`ArtifactViewerFullScreen.kt:123`） |
| mp3 / mp4 | `kind=AUDIO/VIDEO`，`playbackSource != null`，不预读字节 |

### 7.3 `FileTreeStateTest`（批次 3）

| 用例 | 断言 |
|---|---|
| 展开记忆 LRU | 第 21 个 cwd 进入后最老的被淘汰 |
| `buildSearchTree(["a/b.md","a/c/d.md"])` | 目录在前、逐层排序、重复路径合并 |
| `changedDirectoryPaths` | 变更文件的祖先链全部命中，且不越过 cwd |
| `visibleEntries(showHidden=false)` | `hidden != null` 的项被过滤 |
| 大目录截断 | >500 项返回前 500 且 `truncated=true` |

### 7.4 真机手测清单（每批发布前，仅记录不阻塞 CI）

1. 聊天页打开 md/png/svg/docx/pdf/mp3/txt/zip（批次 2 回归）。
2. 工作区长按 → 浏览文件 → 逐层展开 3 层 → 返回 → 再进入（展开状态保留）。
3. 打开 12MB png（走 download 路径，不 413）、300KB md（分块，出现"加载更多"）。
4. 搜索 `route.ts` 命中并可直接打开。
5. 变更区 → 点一个文件 → diff 渲染。
6. 下载 3 个文件到 `Download/πDroid/`，系统「下载」可见；同名再下一次不覆盖。
7. 断网/网关停掉：树、预览、下载三处都能给出可读错误（不是空白）。

---

## 8. 发版计划（严格按 `AGENTS.md §1.2`）

| 版本 | 内容 | versionCode | 分支/动作 |
|---|---|---|---|
| `1.8.32` | 批次 1 + 批次 2（数据层 + 预览复用缝 + PDF 内置） | 138 | 提交 → tag `v1.8.32` |
| `1.8.33` | 批次 3（资源管理器 UI） | 139 | tag `v1.8.33` |
| `1.8.34` | 批次 4（下载到手机 + 上传进度） | 140 | tag `v1.8.34` |

每次发版必须同步：

1. `app/build.gradle.kts`：`versionCode` / `versionName` 自增；
2. `CHANGELOG.md` 顶部新增 `## [X.Y.Z] - YYYY-MM-DD`（格式必须严格，CI 用它提取 Release Notes）；
3. 提交信息英文 Conventional Commits，例如：
   - `feat(files): add directory listing and file index clients`
   - `refactor(files): extract remote file preview loader and add in-app pdf pages`
   - `feat(files): add compose file explorer screen for a workspace`
   - `feat(files): save remote files to device downloads with progress`
4. 推送后用 **tag 触发** `Build & Release πDroid APK`；失败则改递增补丁号重来，**不复用旧 tag**。

**禁止**：本地 Gradle / kotlinc / 手写 stub 验证（`AGENTS.md §1.1`）。本地只做读码、改码、写测试源码、看 CI。

---

## 9. 风险登记册

| # | 风险 | 触发条件 | 缓解（已写进任务） |
|---|---|---|---|
| R1 | 用户以为能浏览整台电脑 | 进入 FilesScreen 只有工作区 | 标题写明工作区名；空态文案说明"只列出有会话的工作区"（D2） |
| R2 | 路径穿越 | 拼接/编码出错 | 服务端 `lib/path-security.ts` 已封；客户端 `joinPath` 拒绝 `..`（T1.2 用例） |
| R3 | 大图 413 | 用 `type=read` 取图 | 预览一律 `type=download`（D-约束 3） |
| R4 | 长文本卡顿 | 一次渲染 256KB+ | 沿用「加载更多」，`truncated` 不自动续读 |
| R5 | 大目录卡死 | 5000 项目录 | V1 截断 500 项 + 提示（T3.4） |
| R6 | PC 上 watch 泄漏 | 每行都开 watch | 树不开 watch；预览页默认关（D7） |
| R7 | 远程弹 PC 资源管理器 | 若移植 open-in-explorer | 明确不移植（D6） |
| R8 | GBK 文本乱码 | `type=read` 只按 UTF-8 解码 | 不改行为，文案说明"仅 UTF-8" |
| R9 | 上传无进度 | 普通 RequestBody | T4.3 自定义 RequestBody |
| R10 | docx 外链图丢失 | mammoth `externalFileAccess:false` | 预览页保留"保存到手机/外部打开"兜底 |
| R11 | 弱网长下载被网关 30s 空闲超时断开 | `proxyAgent.timeout: 30000`（inactivity） | 下载失败可重试；若确定为痛点再评估网关 `proxyTimeout` |
| R12 | 同名文件 | MediaStore 自动改名 | 文案说明；或加时间戳（T4.4 用例） |
| R13 | 越界软链误点 | 克隆仓库自带 | V1 只读展示（D5） |
| R14 | 抽出 loader 改变聊天页行为 | 重构 `ChatScreen.kt` | T2.2 只做"改调用 + 提函数"，不重排状态；批次 2 发布前跑 §7.4 第 1 条全类型回归 |

---

## 10. 明确不做（Out of scope）

- 文件重命名 / 删除 / 新建目录 / 打包下载（pi-web 都没有，见 §2 硬约束 1）。
- "浏览任意目录"（D2）与越界软链放行（D5）—— 后续批次另行评估，届时同步更新 `docs/SECURITY_UPGRADES.md`。
- 平板双栏（左树 + 右预览）：与 `docs/DESIGN-artifact-viewer.md §4` 一起排期。
- 语法高亮（D3）。
- `/api/open-in-explorer`（D6）。
- `type=watch` 的全树实时刷新（D7）。

---

## 11. 附录：上游位置与如何复核

- 源码：`git clone --depth 1 https://github.com/agegr/pi-web.git`，`git log -1` 应为 `Release v0.13.x`；
- 本机已装包只有编译产物：`C:\Users\29092\AppData\Roaming\npm\node_modules\@agegr\pi-web\.next\**`（`app/api/files/[...path]/route.js` 是 webpack 产物，**不要**当源码读）；
- 复核顺序建议：`app/api/files/[...path]/route.ts` → `lib/file-types.ts` / `lib/text-preview.ts` → `lib/file-access.ts` / `lib/path-security.ts` → `components/FileExplorer.tsx` / `components/FileViewer.tsx`。
