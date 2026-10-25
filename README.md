# piDroid 📱

[![Build & Release piDroid APK](https://github.com/codywon/piDroid/actions/workflows/build-and-release.yml/badge.svg)](https://github.com/codywon/piDroid/actions/workflows/build-and-release.yml)
[![Latest Release](https://img.shields.io/github/v/release/codywon/piDroid?color=blue&label=Latest%20Release)](https://github.com/codywon/piDroid/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

基于 [agegr/pi-web](https://github.com/agegr/pi-web) 深度扩展的安卓端智能体移动掌控系统。

让你在离开电脑时，通过安卓手机**完全掌控 PC 上的 Pi 任务**，享受单手便捷语音输入、按需语音播报、锁屏异步任务常驻与关键命令触觉审批体验！已内置全局签名 Key，支持无缝平滑覆盖升级！

---

## 🌟 核心特性与架构

1. **输入框“高能胶囊控制条”（Power Capsule Bar）**：
   - ⚡ **快速切换模型**：点击一键切换 Claude 3.7 Sonnet、DeepSeek R1、GPT-4o、Gemini 等，支持上下文容量与厂商分类；
   - 🧠 **思考深度调节 (Thinking Level)**：快速切换 Off（关闭）、Low（1k）、Medium（4k）、High（16k 深度推理）；
   - 🗜️ **一键压缩上下文 (Compact)**：随时紧缩会话历史，释放 Token 容量，保持长程记忆；
   - 📊 **Token & 缓存用量 HUD 监控**：实时展示上下文使用百分比进度条、输入/输出 Token 分布，以及 **Prompt Caching 缓存命中率 (%)**，直观展现省钱效率。

2. **工作区快速切换与多项目管理**：
   - 顶栏项目下拉切换器（`pi-app ▾`），可在不同项目工程间自由切换，会话按项目隔离与管理。

3. **能力与扩展中心 (Capabilities Hub)**：
   - 🛠️ **技能管理 (Skills)**：查看与一键启用/禁用工作区加载的技能；
   - 🔌 **MCP 协议服务 (Model Context Protocol)**：监控已连接的 MCP 服务器状态与暴露的工具列表；
   - ⚙️ **常用配置**：自动压缩上下文阈值提醒、代码块与控制符语音纯化开关。

4. **专业级保活与系统唤醒机制 (参考 Termius & MQTT 助手)**：
   - 🛡️ **高优先级前台服务 (`PiForegroundService`)**：在任务执行中启动前台通知，提升进程至最高优先级，彻底杜绝切后台被系统误杀；
   - ⚡ **智能 Wakelock & WifiLock**：长异步任务执行时自动持有唤醒锁，息屏状态 CPU 依然不中断，任务结束自动释放省电；
   - 🔋 **电池优化白名单引导**：一键检测并引导加入系统 `IGNORE_BATTERY_OPTIMIZATIONS` 白名单，绕过各品牌厂商的激进杀后台策略。

5. **应用内一键无感平滑升级 (参考 Termius & 开源应用标准)**：
   - 🔄 **GitHub Releases 实时检测**：启动时自动异步请求 GitHub 官方 API 比对版本；
   - 📦 **断点流式下载与进度展示**：应用内弹出升级对话框，展示版本号、更新日志与下载百分比；
   - 🚀 **FileProvider 自动拉起覆盖安装**：下载后自动调用系统安装器；配合项目固化的全局 Key，实现一键平滑覆盖，原有配置与 Token 零丢失。

6. **Termius 极客视觉与交互规范 (告别花哨紫色，全面转向硬核纯暗色)**：
   - 🎨 **Termius 纯深色主题**：以 `#0D1117` 极夜黑为底色，搭配 Termius 电光蓝 `#58A6FF` 与终端绿 `#3FB950`，克制沉稳；
   - 📱 **专有应用图标 (App Icon)**：自适应矢量图标设计，融合极客终端外框、活动指示绿灯与现代几何 $\pi$ 符号；
   - ℹ️ **极简标签与感叹号气泡 (InfoTitle)**：界面文字极简利落，补充说明收敛在末尾微型感叹号 `ℹ️` 中，点击即可查看，信息密度高且清爽专业；
   - 🛡️ **非侵入式空状态**：首次打开绝不强弹设置框，由用户主动扫码或配置。

7. **PC 端安全伴侣网关 (`server/`)**：
   - **利旧 `pi-web`**：100% 复用其 RESTful API 与 SSE（Server-Sent Events）长连接机制；
   - **高熵 Token 安全鉴权**：杜绝远程代码执行（RCE）与公网未经授权扫描；
   - **终端 ASCII 二维码生成**：PC 启动后，终端直接打印专属配对二维码；
   - **外网穿透友好**：支持局域网直连、Tailscale 或 Cloudflare 隧道一键穿透。

5. **安卓端原生体验 (`android/`)**：
   - **扫码一键配对**：基于 CameraX + MLKit，扫 PC 终端二维码直接连接；
   - **单手便捷语音输入**：大拇指悬浮按键，按住说话或点击说话，极速转写；
   - **按需语音播报（默认静音）**：卡片右下角提供 `[🔊 朗读摘要]` 按钮，智能剔除代码和控制符，只朗读关键结论；
   - **异步长任务监控**：后台常驻通知（Live Notification），随时看进度；
   - **触觉马达反馈 (Haptics)**：任务跑完轻震两下，高危操作请求长震提醒；
   - **全局持久签名 Key**：内置全局 Release 证书，所有升级版本平滑覆盖安装，数据零丢失。

---

## 🚀 快速上手使用

### 第一步：在 PC 端启动 `pi-web` 与安全伴侣网关

1. 确保电脑已安装 `pi` 智能体与 `pi-web`：
   ```bash
   npx @agegr/pi-web@latest
   ```
2. 进入本项目 `server/` 目录：
   ```bash
   cd server
   npm install
   npm start
   # 或者 Windows 下直接双击 start.bat
   ```
3. 终端将输出一个带有高熵密钥的安全配对二维码。

#### （可选）开启公网外网访问：
如果要在外面（蜂窝网络或离开家）访问，使用免费免配置的 Cloudflare Quick Tunnel：
```bash
cloudflared tunnel --url http://127.0.0.1:4100
```
把生成的临时域名传给环境变量：
```bash
set TUNNEL_URL=https://your-tunnel-name.trycloudflare.com
node gateway.js
```
终端将自动生成带有外网安全域名的配对二维码！

---

### 第二步：在安卓手机上运行 App

1. 使用 **Android Studio** 打开 `d:/myscript/pi-app/android` 工程；
2. 连接手机或使用模拟器，点击 **Run 'app'** 安装到手机；
3. 打开手机 App，点击右上角 **【扫码配对】**，扫描 PC 终端打印出的二维码；
4. 握手成功后，手机即可完全掌控 PC 上的所有任务！

---

## 📂 目录结构

```text
pi-app/
├── server/                     # PC 端安全伴侣网关 (Node.js)
│   ├── gateway.js              # Token 鉴权中间件 + 反向代理 + 二维码打印
│   ├── package.json
│   ├── start.bat               # Windows 一键启动脚本
│   └── README.md
├── android/                    # Android 原生应用 (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── src/main/java/com/pi/app/
│   │   │   ├── data/           # 会话与事件模型、持久化存储
│   │   │   ├── network/        # OkHttp SSE 长流客户端、Bearer Token 拦截器
│   │   │   ├── voice/          # 原生语音识别与精炼摘要 TTS 引擎
│   │   │   ├── notification/   # 任务后台通知与线性马达触觉反馈
│   │   │   ├── ui/             # 扫码界面、会话列表、工作台界面
│   │   │   └── MainActivity.kt
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── README.md
```
