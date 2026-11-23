# MD3阅读 NAS 正式版与覆盖升级

## 固定安装身份

用户的日常 NAS 客户端从 Debug 阶段使用 `io.legato.kazusa.debug`。NAS 分支的 Release
也沿用这个 applicationId 和原签名，才能直接升级并保留书架、数据库、阅读进度、设置及任务记录。
包名中的 `.debug` 是历史安装身份，不表示构建可调试。

- `defaultConfig.applicationId` 仍为 `io.legato.kazusa`，代码 namespace 仍为 `io.legado.app`。
- `nas-md3` 的 `release.applicationIdSuffix` 固定为 `.debug`，与旧 Debug 相同。
- 正式交付只使用 Comic 完整版 `appComicRelease`，含本地漫画引擎：`debuggable=false`、R8 和资源压缩开启，版本名无 `_debug`。Core 源码随上游完整保留，不运行其构建和交付。
- Debug 变体继续用于开发和 CI 验证。现在两种变体在 NAS 分支使用同一安装身份，安装前确认签名相同。
- `main` 保留上游身份约定；第二阶段同步时保留 NAS Release 的后缀配置，不能整块覆盖回上游。
- 签名与包名同时匹配才可升级。不能卸载旧包、清空数据、强行降级或换证书来掩盖升级失败。

之前交付的 `io.legato.kazusa` 是独立 App，无法跨包名覆盖旧 `.debug`。
应在旧 App 上安装本约定的包；两个 App 之间不自动迁移或合并数据。

## 本机构建与签名

使用 JDK 21、当前 Android SDK；只交付 arm64-v8a。APK 留在工程默认
`app/build/outputs/apk/appComic/release/`，文件名含 `-comic-arm64-v8a`，不复制到 Downloads。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\11017\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleAppComicRelease --project-prop ksp.incremental=false --init-script .github/scripts/arm64-only.gradle --no-configuration-cache --max-workers=1 --no-parallel
```

默认本机版本号必须高于上次交付，构建前核对已安装/交付的 versionCode。
每次交付显式递增 `app/build.gradle.kts` 中的 `nasReleaseVersionCode`；环境提供的
`COMMIT_NUMBER` 推导值不能低于此基线。一次设置下限不能替代以后每次递增。
不要带入会覆盖版本号的旧 `COMMIT_NUMBER` 或 `APP_VERSION_NAME` 环境变量。
有内存压力时不同时运行 Gradle 与模拟器。

签名资料只保存在本机仓库外的 `D:\DevCache\AndroidSigning\md3-reading`。
沿用活跃的历史兼容签名；该目录内未启用的新证书不能用于升级。
历史证书名称包含 Android Debug，不影响 Release 构建属性。不能重新生成签名文件。

本机没有配置 Gradle Release 签名时，先得到 unsigned APK，再调用私有签名脚本：

```powershell
& 'D:\DevCache\AndroidSigning\md3-reading\sign-apk.ps1' `
  -InputApk 'D:\pythonproject\yuedu-md3\app\build\outputs\apk\appComic\release\app-app-comic-arm64-v8a-release-unsigned.apk' `
  -OutputApk '<同一 release 目录中尚不存在的正式 APK 绝对路径>'
```

签名脚本拒绝覆盖已有文件，检查 APK 对齐并验证签名。不要交付 unsigned APK，
也不要把私钥、密码、签名属性文件或 APK 提交到 Git。

## 交付验收

先执行与改动相关的构建/测试；正式产物再检查：

1. 实际包名 `io.legato.kazusa.debug`，版本号递增，名称“MD3阅读”，Comic 产品及 `MANGA_LOCAL_ENGINE=true`，仅 arm64-v8a。
2. APK 的 `debuggable=false`，R8 和资源压缩任务通过；无 `_debug` 版本名。
3. APK 的证书指纹与此前已交付 Debug/兼容正式包一致，签名及 16 KiB 对齐校验通过。
4. 在保留旧 Debug 数据的模拟器上 `adb install -r`，确认同 UID、首次安装时间不变，
   书架、阅读进度和设置保留；检查冷启动和核心页面的崩溃日志。
5. 报告真实验证范围、安装包路径、提交号、SHA256 与未验证的真机/NAS场景。

## 飞书交付：发送到「发给自己」

此步骤接在正式包验收之后，同时适用于完成两阶段上游同步和 `nas-md3` 自有功能更新。
只在本轮已完成验证、将交付提交推送并核对远端、且生成新的签名 APK 时执行。
上游有更新但尚未合入 `nas-md3`、只读检查、验证或推送失败、CI/Debug 包以及历史版本均不触发交付。

1. 从本轮验收记录取唯一的 APK 完整路径、版本号、提交号和 SHA-256；不要扫描目录选「看起来最新」
   的文件。只发送标准 Release 输出目录中与该记录一致的签名 Comic arm64 APK。
2. 通过已登录的飞书桌面客户端打开用户的自聊。以输入框提示「可以向自己发送文件或转发消息」核实收件人，
   不按昵称选择普通私聊，不改发机器人或群聊。客户端不可用、未登录或无法确认是自聊时，保留 APK 并报告未发送。
3. 在自聊的「文件」或消息中按完整 APK 文件名查重。若已存在则不重发；若发送结果不明，先回查，不盲目重试。
   用客户端文件选择器选择该 APK 发送，不把本机路径或下载链接当作交付，不发送签名材料、日志或其他文件。
4. 等上传完成，核实自聊中出现同名 APK 文件卡片且没有失败提示。只有核实到文件才报告「已发送」；否则保留
   本机 APK，报告具体阻断和安装包路径。飞书账号、会话标识和凭据不写入仓库。
5. 确认 APK 文件卡片后，在同一「发给自己」会话发送一条简短中文更新说明：版本名和版本号、
   1–3 条用户能感知的变化或修复，以及有必要的升级提醒。只写本版实际交付内容。
   分别核对文件和文字是否发送成功；如本轮发送部分成功，只补发经核实尚未送达的部分，不重发已送达的内容，也不补发历史版本。

这一步由执行本次交付的本机 Codex 任务完成，不是定时扫描，也不从 GitHub Actions 发送。
飞书开放平台的消息文件上传接口限制 30 MB；超过此限制的 APK 使用桌面客户端，不拆包、不改发网盘链接。

应用内现有更新检查面向上游发行渠道。本 Fork 的 NAS 正式包按本机验证与交付流程升级，
不能把上游 APK 当成同包名、同签名且含 NAS 功能的更新。
