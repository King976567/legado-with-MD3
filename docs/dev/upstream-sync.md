# MD3 两级同步

`main` 保存上游基线和本 Fork 的自动化配置；`nas-md3` 保存 NAS 功能。两级同步的 GitHub Actions 只创建或更新 PR 并验证，不直接修改目标分支、不自动合并、不自动解决冲突。用户要求同步时，由执行者在各阶段验证通过且候选与基线一致后合并对应 PR，无须重复请用户确认；只读检查或用户限定范围的请求不扩大授权。

## 流程

1. 每周一北京时间 11:17，或手动运行 **Sync upstream**：将 HapeLee 上游更新合并到 `automation/sync-upstream-main`，创建指向 `main` 的 PR。
2. 同一次运行直接调用 **Verify sync PR**，执行 JVM 测试、lint、架构检查和 arm64 Comic Debug APK 打包。检查结果通过 commit status 显示在 PR 中。
3. 验证通过并合并第一份 PR 后，`main` 的 push 触发第二级：将 `main` 合并到基于 `nas-md3` 的 `automation/sync-nas-main`，创建指向 `nas-md3` 的 PR。
4. 第二级也直接执行验证，用本次候选的 arm64 APK 完成必要试用，再合并第二份 PR。

手动触发可选择 `upstream`、`nas` 或 `all`。`all` 只使用当前已合入 main 的内容准备 NAS PR，不会提前带入未审批的上游 PR。定时运行也补查第二级，避免遗漏。

## 验证与试用

机器人使用 `GITHUB_TOKEN` 创建 PR，不依赖该事件触发其他工作流；准备步骤完成后通过 `workflow_call` 直接执行验证。人工更新同步 PR 时，`pull_request` 入口也会重新验证。

同步 PR 以 **Verify sync PR** 为唯一完整云端门禁。归属本 Fork 且目标匹配的 `automation/sync-upstream-*` → `main`、`automation/sync-nas-main` → `nas-md3` PR，跳过重复的常规 Verify 和 Auto Build；普通 PR、外部 Fork PR、手动发布与常规分支 push 入口不变。

同一候选 SHA 和目标基线已有机器人直接调用时，不再批准或另行触发重复检查。候选或基线变化后重新验证。本机只跑受影响的定向回归，不重复执行云端全量；NAS Comic 的正式签名构建与保留数据的覆盖升级仍在本机执行。

状态为 **Sync verification (upstream/nas)**；点击状态链接可查看报告和下载 APK，Artifacts 保留 14 天。此 APK 为 Comic Debug 测试包，不会自动发布 Release。构建只打包 arm64-v8a。

用户日常交付使用 `nas-md3` 的签名 Release，流程见 [Android 正式版交付](android-release.md)。NAS Release 保留 `io.legato.kazusa.debug` 安装身份与历史签名，可直接覆盖原 Debug 版并保留数据；它仍为启用 R8、资源压缩且不可调试的正式构建，版本名不带 `_debug`。`main` 继续保留上游身份配置；第二阶段整合必须保留 NAS 分支这一交付差异。正式 APK 留在工程默认 Release 输出目录。

本 Fork 只验证、构建和交付 Comic 漫画增强版（原 Full 完整版），包含本地 OCR、气泡检测及模型擦字引擎。Core 为轻量版，源码与上游一起保留，但不运行其构建流水线。上游公共源码仍完整同步，不按产品拆散 Git 提交。

Comic 的完整 JVM 测试按原始编译类清单分为四片并行运行。每片先独立编译 JVM 与 Android 测试源码，编译和测试步骤各限 20 分钟；片内仍逐类独立 JVM、串行执行。顶层类与其内部类始终在同一片，测试方法不拆分。

四片均上传来源 SHA、原始与实际选择的类清单及 JUnit 报告。独立聚合检查要求原始清单一致、分片无交叉、并集完整、测试报告无失败或重复；缺失报告也判失败。lint、架构和 arm64 打包由第零片执行，所有分片及聚合检查成功后才发布成功状态。每个验证 job 总时限保持 60 分钟，不通过扩大基线或跳过测试处理超时。

CI 在构建前从 Gradle 官方 GitHub release API 预取分发 ZIP，要求官方资产元数据、文件大小与 Wrapper 固定的 SHA256 全部一致，再交由 Wrapper 校验、解压。已完成的 Wrapper 缓存直接复用；不更换 Gradle 版本或分发 URL，不使用第三方镜像。下载步骤仅使用只读令牌，候选 Gradle 进程不继承该步骤的令牌。

同一步骤从 JetBrains 官方资产接口预取版本目录指定的 Kotlin 编译器 JAR，同时核对官方 SHA256、Maven Central SHA1 和文件大小，并保留经 Maven SHA1 验证的官方原始 POM。若 GitHub 资产二进制下载经过有限重试仍不可用，或接口只返回 JSON 元数据，可从官方 Maven Central 下载同一版本的 JAR，仍要求相同的官方 SHA256、Maven SHA1 和字节数；校验不符、非官方重定向或元数据无效仍直接失败。失败日志指出准备阶段，不输出签名 URL 或凭据。CI 初始化脚本仅将该模块的精确版本指向专用本地 Maven 仓库，Gradle 正常解析 POM 与传递依赖，不伪造 Gradle metadata；其他依赖继续使用原仓库。这样避开该大文件下载及 HEAD 重定向的临时 504，不替换依赖版本或跳过依赖验证。

验证绑定候选提交 SHA 和目标分支基线 SHA。若构建期间目标分支更新，则标记失败并要求重新运行同步；旧结果不能代表新的合并结果。构建任务仅有只读令牌，结果发布由独立 job 完成。

工作流不会设置自动合并。分支保护若已配置，仍需满足其要求；没有配置保护时，GitHub 可能允许人工忽略失败检查，合并前须确认上述状态通过。

## 冲突处理与续跑

GitHub Actions 只准备与验证。手动同步和已获授权的本机 Codex 定时同步使用同一策略：
Actions 因冲突退出后，执行者接手现有同步分支及 PR，在隔离工作区解决冲突、验证、推送候选并继续，
不需要用户逐次确认。第一阶段的上游侧是已选定的 HapeLee 提交，第二阶段是已经合入的 Fork `main`。

1. 用共同祖先、两侧 diff、调用方与相关测试判断冲突块归属；不能仅凭文件名、时间或 `ours`/`theirs` 标签判断。
2. **仅上游公共逻辑**：无 Fork 独有行为、发布约束或兼容旧数据的补丁时，优先保留上游结构及实现。
   上游已覆盖的旧补丁，确认行为等价并通过定向回归后可去掉重复实现。
3. **Fork 专用逻辑**：保留 NAS/CBZ、漫画补丁、缓存和下载、自定义配置及其回归测试；保持覆盖安装所需的
   包名、证书、单调递增的版本码和数据兼容。Fork 同步 CI、验证门禁及签名交付规则不随上游配置一起替换。
4. **同一处改到双方功能**：以新上游结构为基础逐块接回定制，测试双方行为；即使没有 Git 冲突，也验证语义交集。
   禁用全局 `-X theirs`、`-s ours` 和整文件单侧覆盖作为冲突处理手段。若无法同时保留预期行为和数据兼容性，
   才把具体的冲突、风险及建议交用户决定，不能用绿色基础编译替代针对性验证。
5. 检查候选差异和冲突标记、执行受影响的回归，正常推送原同步分支并对新 SHA 与目标基线重跑完整 Comic 门禁。
   新的目标或候选使旧验证失效；重新获取并重新验证，不直推同步目标分支、不强推、不绕过保护。

已有相同候选的 PR 或验证时复用。首次 CI 失败先查首个因果错误：有证据的瞬时故障只重试一次；
可定位的代码、测试或工具链问题先做最小修复，再对新候选验证。状态尚在运行、网络暂时断开或执行窗口不足，
记录最小检查点，下次从实时 PR、验证或交付进度继续，不把它们当作需要用户批准。后续触发优先完成未交付的合并版本，
但不会因为技能、文档或定时任务自身更新而单独打包；无更新且无待办时不触发 CI、不构建、不重发。

第二阶段合入后从实际合并提交构建签名 `appComicRelease`，验证覆盖升级，按正式交付流程核实飞书自聊文件和简报。
若本机签名、模拟器或飞书暂不可用，保留完成的阶段；已验收 APK 不因飞书不可用而重打包。
账号登录、必须由用户完成的系统授权、确实无法安全合并的兼容或功能取舍，才需要用户介入。

## 本地使用

```powershell
git fetch --prune upstream
git fetch --prune origin
git switch main
git pull --ff-only origin main
git switch nas-md3
git pull --ff-only origin nas-md3
```

人工处理第二级冲突时，检出其同步分支、合并最新 main 和 nas-md3、逐项解决冲突，再推送。不要把 NAS 代码合进 main。

## 配置与本地验证

仓库需要允许 Actions 创建 PR，工作流显式申请 contents/pull-requests/statuses 的必要权限；不需要个人 PAT。NAS 令牌不得写入源码、日志、PR 或备份。

```powershell
python -m unittest discover -s .github/scripts -p test_sync_pr.py -v
actionlint -shellcheck= -pyflakes= .github/workflows/sync-upstream.yml .github/workflows/verify-sync.yml
git diff --check
```
