# 服务守护与自愈（pi-web + 伴侣网关）

> 面向运维：手机端「服务又死了」时，先读这一页，再动进程。

## 1. 唯一托管者：`server/supervisor.js`

历史上这一栈被三样东西同时托管，互相不知道对方存在：

| 旧托管者 | 缺陷 |
| --- | --- |
| `server/start-hidden.cmd`（while 循环） | 端口被占就 `exit /b 0`，计划任务变 Ready，等于没有守护 |
| `C:\Users\29092\pi-web-supervisor.ps1`（已卸载） | 只看 30141 能否 **TCP 连上**，僵死进程永远判活 |
| `launcher.js` 自带 exit→重启 | 只管 pi-web，网关/隧道崩了没人管 |

三者叠加会拉起第二份 pi-web → `listen EADDRINUSE 127.0.0.1:30141` → 日志里出现
`Next.js exited unexpectedly (code 1)`。**这条"崩溃"是守护自己制造的**，不是 pi-web 的 bug。

现在只有 `supervisor.js` 有资格拉起 `launcher.js`（pi-web + 网关 + frp/cloudflared 仍由
launcher 负责）。旧的两个入口保留文件，但计划任务已停用：

- `piDroid Gateway (frp)` → **Disabled**（改由 supervisor 顶替）
- `pi-web supervisor`（ps1）→ **已注销**

## 2. 为什么必须用 HTTP 判活

实测（2026-11-09 09:45）pi-web 的另一种死法：**进程在、端口在听、TCP 能连、CPU 0%、HTTP 永不返回**。

```
GET http://127.0.0.1:30141/            → 15s 超时（连续 6 轮）
GET http://127.0.0.1:30141/api/models  → 15s 超时（连续 6 轮）
进程 CPU 增量 10s = 0.00s，线程 29 个全在 Wait
最后一条日志：[pi-web] session_start dispatched to extensions for session <id> ×7
```

也就是「事件循环空转等一个永不 settle 的扩展/MCP 初始化 Promise」，所有路由一起挂住。
`netstat` 只能看到 `LISTENING`，`TcpClient.Connect()` 也只能拿到 `Connected` ——
所以任何 TCP 级探活都会把这种状态判成"活着"。

supervisor 的判据是：

- `GET /api/models`（pi-web）与 `GET /api/ping`（网关）**任何 HTTP 应答**（含 401/404）都算活着；
- 8s 超时、20s 一轮、**连续 3 次失败**（≈60s）才动手，避免把"正在建会话索引"误判成死。

## 3. 四件自愈动作

| 场景 | 动作 |
| --- | --- |
| 整栈退出（`launcher.js` 挂了） | 清掉端口残留 → 5s/15s/60s/300s 退避重启，稳定 5 分钟后退避清零 |
| 探活连续失败（僵死） | `taskkill /T /F` 整棵 launcher 树 → 重新拉起 |
| pi-web RSS ≥ 1500 MB（`PIWEB_RSS_RECYCLE_MB` 可调） | 主动整栈回收，抢在它 `code 1` 静默退出之前 |
| supervisor 自己挂了 | 计划任务 `piDroid Stack` 每分钟重试 + `piDroid Stack Watchdog` 每 5 分钟 `ensure` 幂等补起 |

每次死亡都会追加一行证据到 `server/logs/deaths.log`：
退出码、运行时长、退出前 RSS 峰值、最后 15 行输出。诊断「为什么死」先看这个文件。

## 4. 计划任务

| 任务 | 触发器 | 作用 |
| --- | --- | --- |
| `piDroid Stack` | 登录（+开机，需管理员执行 install） | `node supervisor.js run` |
| `piDroid Stack Watchdog` | 每 5 分钟 | `node supervisor.js ensure`，supervisor 不在就补起 |

```powershell
# 以管理员身份再跑一次可获得开机触发器（普通用户只有登录触发器）
cd D:\myscript\pi-app\server
node supervisor.js install
```

## 5. 常用命令

```powershell
cd D:\myscript\pi-app\server
node supervisor.js status     # 守护 PID / 两个端口的真实 HTTP 状态 / pi-web RSS
node supervisor.js restart    # 整栈换新（更新扩展、更新 pi-web 之后必须来一下）
node supervisor.js stop       # 停守护并清端口
node supervisor.js ensure     # 幂等补起（给计划任务用）
tail logs\stack.log           # 守护自己的日志
tail logs\deaths.log          # 死亡账本
```

## 6. 已知坑（血泪）

1. **不要在线重装扩展**：`pi update --extensions` / 在 `~/.pi/agent/npm` 里 `npm install`
   会把 `node_modules/context-mode` 之类整包替换（v1.0.169 → v2.1.7 就把
   `server.bundle.mjs` 改名成 `server.mjs`）。正在跑的 pi-web 手里攥着旧路径，
   下一次 `session_start` 派发就可能卡死整个事件循环（见 §2 的实测）。
   **更新扩展后立刻 `node supervisor.js restart`**；忘了也没事，supervisor 一分钟内会自己判死并重启。
2. **别用 `schtasks /Create` 挂两个触发器**：第二个带 `/F` 会把第一个覆盖掉。
   install 走的是 `Register-ScheduledTask` 一次性注册。
3. **端口探测 ≠ 服务可用**（本页 §2），命令行手查请用：
   `curl -s -o NUL -w "%{http_code}" http://127.0.0.1:30141/api/models`
4. **以管理员身份起的进程，非管理员杀不掉**（`拒绝访问`）。若发现 4100/30141 上挂着
   高权限的孤儿进程，supervisor 清不掉它，重启一次即可恢复成同权限托管。
