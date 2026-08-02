# WYU-Monet

> WPS Office (`com.wps.koa`) 的 LSPosed / Xposed 增强模块，基于 Miuix (HyperOS) Compose 构建原生质感界面。

---

## ✨ 功能

| 功能 | 说明 |
|------|------|
| 🎨 **莫奈取色** | Hook `Resources.getColor` / `TypedArray.getColor`，让 WPS 全局跟随系统动态取色（Material You / Monet） |
| 🌈 **莫奈染色底栏** | WPS 底部导航栏图标、文字、背景跟随系统 accent 色动态染色 |
| 🛡️ **去除 Root 检测** | 屏蔽 WPS 对 Root 环境的检测提示 |
| 🧹 **去除水印** | 移除文档上的牛皮癣水印 |
| ⏰ **自动打卡** | Root 常驻轮询定时器 + 独立 CheckinWorker，定时自动提交 WPS 表单，无需打开任何 App |
| 🤖 **Claude Code 集成** | WebSocket 连接远程 AI 助手，宠物气泡 UI + Webhook 过滤 |

---

## 🗂️ 项目结构

```
├── app/                    LSPosed 模块主体
│   └── src/main/java/com/wps/enhancer/
│       ├── WPSModule.java      Xposed 入口 + 全部 Hook 逻辑 + KIM 菜单注入
│       ├── MainActivity.kt     独立 App UI（功能总开关）
│       ├── BootReceiver.kt     开机自启，重派 root 定时器
│       └── DoujieActivity.java 彩蛋页（未启用）
├── checkin/                CheckinWorker 独立 DEX 源码（root 后台打卡）
├── libxposed-stubs/        libxposed API 本地编译 stub
└── miuix-*/                Miuix (HyperOS) Compose 组件库
```

---

## ⏰ 自动打卡架构

模块采用 **"零后台依赖"** 设计：打卡进程独立于 WPS 与模块 App，即使两者都被杀掉，root 定时器仍能到点执行。

```
┌─────────────┐  开关   ┌──────────────────┐
│  模块 App    │───────▶│ /data/local/tmp/ │  wyu-checkin-enabled
└─────────────┘         │   wyu-*.txt      │  wps-miuix-checkin.txt
                        └──────────────────┘
                                │ 读取
┌────────────────────────────────▼─────────────────────┐
│  root 常驻轮询循环（sh 脚本 + PID 自锁）                 │
│  每 30s 读配置 → 到点调 CheckinWorker.dex              │
└────────────────────────────────┬─────────────────────┘
                                 │ app_process 独立进程
                        ┌────────▼─────────┐
                        │  CheckinWorker   │──▶ kdocs API 静默提交
                        └──────────────────┘
```

### 关键机制

- **配置轮询**：定时器脚本每轮重新读取配置文件，改配置后下一轮即生效，无需重启定时器
- **PID 自锁**：脚本写 PID 文件 + `kill -0` 探测，防止重复派发
- **开机自启**：`BootReceiver` 等待 Magisk `su` 就绪（最多 5 分钟重试）后重新派发
- **凭证运行时捕获**：Cookie / CSRF / 学号 / 姓名 / 院系均为运行时从 WPS 会话与表单历史自动解析，不写死

---

## 🚀 使用

### 1. 安装激活

1. 在 LSPosed 中激活模块，勾选作用域 `com.wps.koa`
2. **Force-stop 后重启 WPS Office**（Xposed 模块必须重启目标进程才生效）

### 2. 开启功能

打开模块 App，通过开关启用所需功能（自动打卡、莫奈取色、去 Root 检测、去水印等）。

### 3. 配置自动打卡

> ⚠️ 打卡配置入口在 **WPS 的 KIM 机器人菜单**里，不在模块 App 中。

```
① 模块 App 打开「自动打卡」开关
   → 使 KIM 机器人菜单出现「打卡设置」按钮

② 打开 WPS 里的「学生打卡入口」机器人会话
   → 菜单末尾出现「打卡设置」

③ 点「打卡设置」→ 配置打卡时间 / 地点 / 表单 URL

④ 打开一次打卡表单完成 Cookie 捕获
   → WebView 自动抓取表单 URL、Cookie、CSRF
```

---

## 🛠️ 构建

需要 Android SDK（`compileSdk 37`）、JDK 17+。

```bash
./gradlew :app:assembleDebug
```

### 重新编译 CheckinWorker.dex

自动打卡依赖 `app/src/main/assets/CheckinWorker.dex`。修改 `checkin/CheckinWorker.java` 后需重新编译：

```bash
# 编译（需 classpath 含 android.jar 或 org.json 依赖）
javac -encoding UTF-8 -cp <android.jar> checkin/CheckinWorker.java

# 转 dex（d8 位于 Android SDK build-tools）
d8 checkin/CheckinWorker.class --lib <android.jar> --output app/src/main/assets/
```

---

## ⚠️ 免责声明

本模块仅供学习 Android Hook 技术与 Compose UI 开发研究使用。请勿用于违反任何平台服务条款、校规或法律法规的场景。使用本模块产生的一切后果由使用者自行承担。

---

## 📄 License

[MIT](LICENSE)

模块内置的 Miuix 组件库版权归其原作者所有，遵循其开源许可。
