# 轻点 · 安卓连点工具

中文、离线、无需 Root 的个人安卓连点应用。最低 Android 8.0。

## 安装和使用

1. 到 [Releases 下载最新版正式 APK](https://github.com/getl-x/lighttap/releases/latest)，安装 `lighttap-版本-release.apk`；按系统提示允许本次安装来源。
2. 打开「轻点」，点击「开启点击服务」，阅读说明后进入系统无障碍设置，手动开启「轻点 · 点击服务」。
3. 如果出现「受限制的设置」，到「设置 → 应用 → 轻点 → 应用信息」查看右上角菜单中的「允许受限制的设置」，再返回开启服务。HyperOS 的具体入口会随版本变化；不需要 Root，也不要关闭整个系统的安全防护。
4. 点击「显示悬浮控制条」，切换到目标应用，点击「＋ 点」，拖动编号圆点到目标位置。
5. 在控制条点「设置」返回轻点：选择单点、顺序或同时模式，设置点击间隔及停止条件。
6. 返回目标应用，移动控制条避开点位，点击「开始」。运行时标记隐藏；「暂停」后可调整，「停止」后进度清零。

打开轻点设置页会自动暂停，并暂时隐藏点位标记，避免误点设置项；回到目标应用后，通过控制条手动继续。

按住控制条标题可移动；「收起」保留开始/暂停、停止和展开按钮；「隐藏」会停止任务并移除面板。长按一个点 650 毫秒可删除，也可在点位列表删除、上移、下移。

## 执行规则

- 最多 10 个点；同时模式还受系统手势数量上限约束。
- 单点模式只执行列表第一个点，其他点保留。顺序模式依次执行全部点；同时模式通过一个多指手势同时触碰全部点。
- 每次按住 50 毫秒。点位的「等待」是释放后到下一次开始前的间隔，范围 10～60,000 毫秒，默认 100 毫秒。顺序模式各点独立设置；同时模式使用统一轮间隔。
- 顺序模式全部点点击一次算一轮；单点一次、同时模式一组各算一轮。只统计手势完成，不代表目标应用已经完成业务操作。
- 可选择手动停止、1～1,000,000 轮，或 1 秒～24 小时有效运行时长。暂停时间不计入时长。
- 暂停后直接继续保留下一点和剩余等待时间。暂停期间修改参数或点位后，从第一个点继续，已完成轮数和有效运行时间保留。
- 系统变慢时不补发积压点击。停止后不再派发新手势，已派发的短点击允许结束。
- 锁屏、熄屏、旋转、屏幕尺寸/密度变化、服务中断或手势失败时停止；不会自动恢复。屏幕参数变化后须检查并确认点位。
- 最近一套配置保存在应用内部 DataStore 中。卸载会删除配置；应用不联网、不录屏、不读取屏幕内容。

## 点击测试页

主页「打开点击测试页」提供网格和触点计数。把点位放在网格内，再从控制条开始：

- 单点/顺序模式：核对触点位置、先后顺序和手势组数。
- 同时模式：观察「最大同时触点」是否与点位数量一致。
- 顶部重置按钮清空统计；自动点击应只放在网格内。

测试页用于验证触摸输入；目标应用是否支持或接受多指模拟触摸，需要分别验证。运行连点时不保证能同时进行其他手动触摸。

## 本地构建

固定工具版本：JDK 25、Gradle 9.3.1、Android Gradle Plugin 9.1.1、内置 Kotlin/Compose 编译器 2.2.10、Android SDK 36、Build Tools 36.0.0。JVM 字节码目标 17。

```powershell
# 配置 JAVA_HOME 和 Android SDK；脚本只调整当前进程变量，退出时恢复
.\scripts\build.ps1 -UseSystemProxy
# 无代理环境可省略 -UseSystemProxy；只构建测试包：
.\scripts\build.ps1 -DebugApk
```

Windows 脚本需要 PowerShell 7。可在不入库的 `build.local.json` 中指定 `JAVA_HOME`、`GRADLE_USER_HOME`、`ANDROID_HOME`、`ANDROID_USER_HOME` 和可选的 `gradleExecutable`；未指定时使用现有环境和项目 Wrapper。不要把本机路径或凭据写入公开文件。

其他平台配置 `JAVA_HOME` 和 Android SDK（`ANDROID_HOME` 或本地 `local.properties`），执行：

```sh
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Release 签名从不入库的 `signing.properties`，或 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 环境变量读取。未配置签名时，Gradle 的 release 输出是未签名包，不作为可安装交付物；本机脚本会拒绝这种交付构建。

## 发布正式版本

正式安装包在 [GitHub Releases](https://github.com/getl-x/lighttap/releases) 下载，附带 SHA256 校验文件。Actions 的 Debug APK 用于开发验证，签名与正式包不同。

为让正式签名密钥只保留在本机，使用本地一键构建发布：

1. 更新 `versionName` 和递增的 `versionCode`，提交并推送源码。
2. 保留原有本地签名配置，确认 GitHub CLI 已登录。
3. 执行以下命令（不使用代理时省略 `-UseSystemProxy`）：

```powershell
.\scripts\publish.ps1 -UseSystemProxy
# 只检查发布配置，不构建、不上传：
.\scripts\publish.ps1 -CheckOnly
```

脚本会完成测试、Lint、Release 构建和签名验证，以当前版本创建草稿，仅上传正式 APK 与 SHA256SUMS.txt，核对 GitHub 返回的附件哈希后再公开发布。已有版本不会覆盖；不上传密钥、密码、日志、本机配置或整个交付目录。源码标签固定在已推送的提交。

## GitHub Actions

工作流在推送 main/master、PR 和手动触发时执行单元测试、Lint 并生成 Debug APK；在 Actions 的 Artifacts 中下载。运行状态及产物见 [Actions](https://github.com/getl-x/lighttap/actions)。

需要与本机 release APK 使用相同签名时，由仓库所有者配置这四个 Secrets：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 本机专用签名库文件的 Base64 内容 |
| `SIGNING_STORE_PASSWORD` | 签名库密码 |
| `SIGNING_KEY_ALIAS` | `lighttap` |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

手动 Run workflow 并勾选 `signed_release` 才会使用 Secrets 生成签名包。PR 构建不读取签名 Secrets，不使用 `pull_request_target`，工作流仅申请仓库内容读取权限。

签名库和配套密码应保存在仓库外或本地被忽略的配置中，并私下备份；不要粘贴到日志、Issue 或公开仓库。丢失后无法用新密钥覆盖升级旧安装。GitHub 默认 Debug 签名与个人 Release 签名不同，建议使用 Release 包长期安装。

## 目录与验证

- `app/src/main/java/cn/lighttap/app/`：配置、执行器、持久化、无障碍服务、悬浮控制条、Compose 设置页和触摸测试页。
- `app/src/test/`：时序、轮数、暂停、异常、配置读写和坐标校验测试，包含虚拟时间 30 分钟运行。
- `.github/workflows/android.yml`：构建工作流。
- `docs/ACCEPTANCE.md`：验收清单；真实设备测试和逻辑模拟测试分开记录。

首版不包含滑动、长按、录制回放、识图、多套方案、联网服务或商店上架。