# MD3阅读 NAS 正式版与覆盖升级

## 固定安装身份

用户的日常 NAS 客户端从 Debug 阶段使用 `io.legato.kazusa.debug`。NAS 分支的 Release
也沿用这个 applicationId 和原签名，才能直接升级并保留书架、数据库、阅读进度、设置及任务记录。
包名中的 `.debug` 是历史安装身份，不表示构建可调试。

- `defaultConfig.applicationId` 仍为 `io.legato.kazusa`，代码 namespace 仍为 `io.legado.app`。
- `nas-md3` 的 `release.applicationIdSuffix` 固定为 `.debug`，与旧 Debug 相同。
- 正式交付使用 `appRelease`：`debuggable=false`、R8 和资源压缩开启，版本名无 `_debug`。
- Debug 变体继续用于开发和 CI 验证。现在两种变体在 NAS 分支使用同一安装身份，安装前确认签名相同。
- `main` 保留上游身份约定；第二阶段同步时保留 NAS Release 的后缀配置，不能整块覆盖回上游。
- 签名与包名同时匹配才可升级。不能卸载旧包、清空数据、强行降级或换证书来掩盖升级失败。

之前交付的 `io.legato.kazusa` 是独立 App，无法跨包名覆盖旧 `.debug`。
应在旧 App 上安装本约定的包；两个 App 之间不自动迁移或合并数据。

## 本机构建与签名

使用 JDK 21、当前 Android SDK；只交付 arm64-v8a。APK 留在工程默认
`app/build/outputs/apk/app/release/`，不复制到 Downloads。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\11017\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleAppRelease --project-prop ksp.incremental=false --init-script .github/scripts/arm64-only.gradle --no-configuration-cache --max-workers=1 --no-parallel
```

默认本机版本号必须高于上次交付，构建前核对已安装/交付的 versionCode。
不要带入会覆盖版本号的旧 `COMMIT_NUMBER` 或 `APP_VERSION_NAME` 环境变量。
有内存压力时不同时运行 Gradle 与模拟器。

签名资料只保存在本机仓库外的 `D:\DevCache\AndroidSigning\md3-reading`。
沿用活跃的历史兼容签名；该目录内未启用的新证书不能用于升级。
历史证书名称包含 Android Debug，不影响 Release 构建属性。不能重新生成签名文件。

本机没有配置 Gradle Release 签名时，先得到 unsigned APK，再调用私有签名脚本：

```powershell
& 'D:\DevCache\AndroidSigning\md3-reading\sign-apk.ps1' `
  -InputApk 'D:\pythonproject\yuedu-md3\app\build\outputs\apk\app\release\app-app-arm64-v8a-release-unsigned.apk' `
  -OutputApk '<同一 release 目录中尚不存在的正式 APK 绝对路径>'
```

签名脚本拒绝覆盖已有文件，检查 APK 对齐并验证签名。不要交付 unsigned APK，
也不要把私钥、密码、签名属性文件或 APK 提交到 Git。

## 交付验收

先执行与改动相关的构建/测试；正式产物再检查：

1. 实际包名 `io.legato.kazusa.debug`，版本号不降低，名称“MD3阅读”，仅 arm64-v8a。
2. APK 的 `debuggable=false`，R8 和资源压缩任务通过；无 `_debug` 版本名。
3. APK 的证书指纹与此前已交付 Debug/兼容正式包一致，签名及 16 KiB 对齐校验通过。
4. 在保留旧 Debug 数据的模拟器上 `adb install -r`，确认同 UID、首次安装时间不变，
   书架、阅读进度和设置保留；检查冷启动和核心页面的崩溃日志。
5. 报告真实验证范围、安装包路径、提交号、SHA256 与未验证的真机/NAS场景。

应用内现有更新检查面向上游发行渠道。本 Fork 的 NAS 正式包按本机验证与交付流程升级，
不能把上游 APK 当成同包名、同签名且含 NAS 功能的更新。
