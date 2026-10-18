---
name: md3-upstream-sync
description: 检查或同步 HapeLee/legado-with-MD3 到用户 MD3阅读 Fork，按 upstream → main → nas-md3 两阶段 PR 整合、验证并交付 APK，保留 NAS 与漫画扩展。适用于上游更新、同步 PR 失败和同步后的回归检查；不用于冻结原版或 iOS 阅读工程。
---

# MD3阅读上游同步

## 先辨认工程和授权

- 本机正确工程为 `D:/pythonproject/yuedu-md3`；`D:/pythonproject/yuedu` 是冻结原版，不能因当前 cwd 在那里就修改它。
- `origin` 应为 `King976567/legado-with-MD3`，`upstream` 应为 `HapeLee/legado-with-MD3`。先核实 remote、工作树、分支、开放 PR 和实时 SHA；不沿用上轮的提交数、失败原因或 APK。
- 应用名称是“MD3阅读”；applicationId 为 `io.legato.kazusa`（debug 后缀 `.debug`），namespace 为 `io.legado.app`。不要替换成原版应用或其他分支的包。
- 读工程 `AGENTS.md` 和 `docs/dev/upstream-sync.md`。在工程外调用本技能时，从上述已验证工程定位这些文件，而不是相对当前目录猜测。
- 区分“看看有没有更新”和“去同步”：前者只读查询，不触发 Action、改文件、提交或合并。后者可准备/更新同步候选与验证；**不等于允许合并 PR**。逐阶段合并仍须用户明确确认，且当前候选验证通过。技能和历史授权不扩大当前权限。

## 检查模式

1. 用 GitHub 实时查询上游 main、Fork main、Fork nas-md3、开放同步 PR 和最近同步工作流。`gh` 显式指定仓库。
2. 用实际共同祖先和 compare/提交图确定新增提交；不要仅比较日期、commit 标题或本地缓存的 `upstream/main`。
3. 分清三个事实：上游已有的更新、已进入 Fork main 的更新、已进入 nas-md3 的更新。列出主要用户可见变化、PR 进度与验证失败证据，不把编译通过说成测试全通过。
4. 无更新或仅只读检查时，不创建空提交/PR。

## 执行模式：严格两阶段

### 第一阶段：upstream → main

1. 核实干净工作树并获取远端；保留其他人的修改。记录本轮上游来源 SHA、目标 main SHA、原 NAS SHA。
2. 先检查是否已有运行中的相同任务/开放 PR，避免重复运行。通常从 Fork `main` 触发 `sync-upstream.yml`，`stage=upstream`、`upstream_ref=main`。
3. 固定分支 `automation/sync-upstream-main` 和已存在 PR 优先复用；先获取并合入最新 `origin/main`，再整合上游，更新候选与目标基线记录。保留人工修复，正常 push，不重置分支、不强推。不要把 NAS 功能带入 main。
4. 遇冲突时 Action 一律中止、不发布半成品。交互处理先报告冲突文件；本轮已授权同步时，可逐块复核并处理有证据、行为等价的机械冲突。涉及功能取舍、数据语义或无法证明兼容的选择，停止并把具体差异交用户确认。禁止整文件 ours/theirs 或自动替用户选择业务语义。
5. 排查失败运行中的具体测试、异常、timeout 与 artifact。重跑旧 run 不会带入新上游或新修复；修改后必须验证新 SHA。禁止跳过失败测试、放宽架构基线或伪造成功状态。
6. 提交并推送已复核的候选修复，执行下述完整门禁。PR 描述记录实际来源、目标基线、候选 SHA、风险和验证链接。
7. 报告第一阶段结果并等待明确合并确认。没有确认不能直接更新 main，也不能将未批准上游提前带进正式 NAS 同步 PR。

### 第二阶段：main → nas-md3

1. 第一阶段获确认合并后，核实远端 main 确含批准的上游；由 main push 或从 main 手动 `stage=nas` 生成/更新 `automation/sync-nas-main` → `nas-md3`。
2. 重新获取 nas-md3 的最新基线，保留该分支的 NAS、CBZ、封面、任务历史和漫画选章修复。若目标已前进，合入新基线再验证，不能使用旧候选的绿色状态。
3. 逐项核对导航/DI、详情、多语言、数据库、缓存及漫画读写交集。读 [references/regression-gates.md](references/regression-gates.md)；涉及 Compose 时同时按工程现有 Compose review 技能审查，修复前读其实现约定。
4. 完整门禁与必要模拟器验证通过后，交付 NAS 候选 APK及第二阶段 PR，等待用户确认合并。main 阶段产物不含 NAS，不能当用户日常使用包交付。
5. 获准合并后再次核实远端分支和候选树相同/包含预期修复，再正常快进本地 nas-md3。临时分支只有已合并、无独有未保存改动且清理在授权范围内才删除；不用强制删除。

## 验证门槛

- 本机 JDK 21、Android SDK；从当前配置验证工具路径。保留 `ksp.incremental=false`，规避 Windows 跨盘 Glide KSP 故障。APK 保留在工程默认 `app/build/outputs/apk/app/debug/`，不复制到 Downloads。
- 源码冻结后先运行受影响定向测试，再执行当前完整门禁：

```powershell
.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --project-prop ksp.incremental=false --init-script .github/scripts/arm64-only.gradle --continue --no-configuration-cache
git diff --check
```

- 精确命令以当前工程和 CI 为准。记录测试数量/失败、lint、架构和 APK 各自结果；只跑定向测试不等于全量验证。
- 云端通常使用 `Sync verification (upstream)` / `Sync verification (nas)`，绑定候选 SHA 和目标基线。`action_required`、空 Checks 或旧绿色状态不等于本次成功。候选与目标有变化需更新并重验。
- 没有权限启用 Actions/需要账户操作时，报告准确提示并请求用户处理；本地验证可继续，但不能悄悄降低云端合并门槛。一次有证据的恢复重试后，仍失败则依据新错误诊断，不无限重触发。
- 构建测试失败须据证据修复再跑，不使用扩大超时掩盖死锁，也不终止无关 Java/模拟器进程。

## 交付

用中文简要说明：上游来源 SHA；两阶段各自的候选、目标、PR/运行链接及是否真正合并；保留的自定义功能；实际验证结果与未验真机/NAS路径；APK绝对路径、构建时间及可核实的哈希。未经当前候选验证的旧 APK不要称“新版”。

本技能只规定过程，不储存瞬时提交号、凭据或已过期任务状态。日常独立修改按工程约定验证、提交、推送；同步修复留在对应同步分支，不能借“自动提交”绕过 PR 与人工合并门槛。
