# WYU-Monet 发版 Checklist

双仓库发布流程：原仓库（开发）+ 官方仓库（驱动 LSPosed 列表）。

## 每次发版步骤

### 1. 开发与构建
- [ ] 代码改动提交到原仓库 `MYHealer/WYU-Monet`
- [ ] 提升版本号（build.gradle.kts + module.prop 同步）：
  - `versionCode` +1（LSPosed 靠它判断更新，必须递增）
  - `versionName` 同步更新
- [ ] 构建：`./gradlew :app:assembleRelease`
- [ ] 验证签名：`apksigner verify --verbose --print-certs app-release.apk`（v2/v3 = true）
- [ ] 提交并推送原仓库：`git push origin master`

### 2. 原仓库发布（自用/展示）
```bash
gh release create "v<versionName>" \
  "app/build/outputs/apk/release/app-release.apk#WYU-Monet-v<versionName>.apk" \
  --repo MYHealer/WYU-Monet --title "v<versionName>" --notes "更新内容"
```

### 3. 官方仓库发布（驱动 LSPosed 列表）
```bash
gh release create "<versionCode>-<versionName>" \
  "app/build/outputs/apk/release/app-release.apk#WYU-Monet-v<versionName>.apk" \
  --repo Xposed-Modules-Repo/com.wps.enhancer --title "v<versionName>" --notes "更新内容"
```
> ⚠️ tag 必须是 `<versionCode>-<versionName>`（如 `6-5.0.1`），且**必须附 APK 资产**，否则 bot 不更新。

### 4. 验证
- [ ] modules.lsposed.org 模块页 5 分钟内出现新版本
- [ ] 手机 LSPosed 刷新列表能看到更新
- [ ] 原仓库 release 正常

## 常用命令速查

| 操作 | 命令 |
|------|------|
| 构建 | `./gradlew :app:assembleRelease` |
| 验签 | `"C:/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs app-release.apk` |
| 原仓库发版 | `gh release create "v<name>" ... --repo MYHealer/WYU-Monet` |
| 官方仓库发版 | `gh release create "<code>-<name>" ... --repo Xposed-Modules-Repo/com.wps.enhancer` |

## 仓库地址
- 开发仓库：https://github.com/MYHealer/WYU-Monet
- 官方仓库：https://github.com/Xposed-Modules-Repo/com.wps.enhancer
- 模块页：https://modules.lsposed.org

## 注意事项
- **versionCode 必须递增**，否则 LSPosed 检测不到更新
- 官方仓库 Release tag 用 `<versionCode>-<versionName>`，原仓库随意（`v<name>` 即可）
- 只改 APK 不重建 Release，bot 收不到事件，不会更新列表
- 签名：`wyu-monet-release.jks` + `wyu-monet-2026`（见 key.properties）
