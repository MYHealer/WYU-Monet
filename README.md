# WYU-Monet (WPS-Miuix-Module)

WPS Office (`com.wps.koa`) 的 LSPosed / Xposed 增强模块，基于 Miuix Compose 构建原生质感界面。

## 功能

- **莫奈取色**：hook `Resources.getColor` / `TypedArray.getColor`，让 WPS 跟随系统动态取色（Monet Material You）
- **液态玻璃底栏**：Miuix 风格毛玻璃底部导航
- **去除 Root 检测**：屏蔽 WPS 对 Root 环境的检测提示
- **去除水印**：移除文档上的牛皮癣水印
- **自动打卡**：定时自动提交 WPS 表单（含 root 常驻轮询定时器 + 独立 CheckinWorker，无需打开 App）
- **Claude Code 集成**：WebSocket 连接远程 AI 助手，支持宠物气泡 UI

## 架构

```
app/          LSPosed 模块主体（Xposed 入口、Hook 逻辑、Miuix UI）
checkin/      CheckinWorker 独立 DEX 源码（root 后台自动打卡）
libxposed-stubs/   libxposed API 本地编译 stub
miuix-*       本地嵌入的 Miuix (HyperOS) Compose 组件库
```

### 自动打卡流程

模块通过以下链路实现"零后台依赖"的定时打卡：

1. 用户在 WPS 内打卡设置里配置时间 / 地点 / 表单
2. 模块 App（有 root）派发一个常驻 shell 定时器（`app_process` 独立 DEX），每分钟轮询配置
3. 到点后 `CheckinWorker` 携带运行时捕获的 Cookie / CSRF，通过 kdocs API 静默提交表单
4. 开机后 `BootReceiver` 自动重新派发定时器（含 su 就绪重试）

> Cookie / CSRF / 学号 / 姓名 / 院系均为运行时从 WPS 会话与表单历史自动解析，不写死。

## 构建

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

## 使用

1. 在 LSPosed 中激活模块，勾选作用域 `com.wps.koa`
2. 重启 WPS Office（务必 force-stop）
3. 打开 WPS，在侧边栏/设置中找到模块入口，按需开启各功能
4. 自动打卡：先打开"学生打卡入口"表单完成一次 Cookie 捕获，再在打卡设置里配置时间地点

## 免责声明

本模块仅供学习 Android Hook 技术与 Compose UI 开发研究使用。请勿用于违反任何平台服务条款、校规或法律法规的场景。使用本模块产生的一切后果由使用者自行承担。

## License

[MIT](LICENSE)

模块内置的 Miuix 组件库版权归其原作者所有，遵循其开源许可。
