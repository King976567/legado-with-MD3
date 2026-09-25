# MD3 两级同步

`main` 保存上游基线和本 Fork 的自动化配置；`nas-md3` 保存 NAS 功能。两级同步只创建或更新 PR，不直接修改目标分支、不自动合并、不自动解决冲突。

## 流程

1. 每周一北京时间 11:17，或手动运行 **Sync upstream**：将 HapeLee 上游更新合并到 `automation/sync-upstream-main`，创建指向 `main` 的 PR。
2. 同一次运行直接调用 **Verify sync PR**，执行 JVM 测试、lint、架构检查和 arm64 Debug APK 打包。检查结果通过 commit status 显示在 PR 中。
3. 人工确认并合并第一份 PR 后，`main` 的 push 触发第二级：将 `main` 合并到基于 `nas-md3` 的 `automation/sync-nas-main`，创建指向 `nas-md3` 的 PR。
4. 第二级也直接执行验证，下载 Artifacts 中的 arm64 APK 试用并确认后，再手动合并第二份 PR。

手动触发可选择 `upstream`、`nas` 或 `all`。`all` 只使用当前已合入 main 的内容准备 NAS PR，不会提前带入未审批的上游 PR。定时运行也补查第二级，避免遗漏。

## 验证与试用

机器人使用 `GITHUB_TOKEN` 创建 PR，不依赖该事件触发其他工作流；准备步骤完成后通过 `workflow_call` 直接执行验证。人工更新同步 PR 时，`pull_request` 入口也会重新验证。

状态为 **Sync verification (upstream/nas)**；点击状态链接可查看报告和下载 APK，Artifacts 保留 14 天。此 APK 为 Debug 测试包，不会自动发布 Release。构建只打包 arm64-v8a。

验证绑定候选提交 SHA 和目标分支基线 SHA。若构建期间目标分支更新，则标记失败并要求重新运行同步；旧结果不能代表新的合并结果。构建任务仅有只读令牌，结果发布由独立 job 完成。

工作流不会设置自动合并。分支保护若已配置，仍需满足其要求；没有配置保护时，GitHub 可能允许人工忽略失败检查，合并前须确认上述状态通过。

### 验证失败的排查

- `Checks 0` 不代表没有验证：本流程另行发布 `Sync verification (nas)` commit status。点击其链接查看完整运行；PR 事件自身若显示 `action_required`，也不能代替这项明确验证。
- 若 NAS 分支在 PR 创建后新增了功能，重新从 `main` 手动运行 **Sync upstream**、选择 `nas`。流程会保留同步分支的人工修复，合入当前 NAS 基线并更新原 PR；仅重跑旧 run 仍验证旧 SHA。
- `concurrent-futures{,-ktx}` 的 `1.1.0` 与 `1.2.0` 冲突来自 AGP 对应用/Android 测试 classpath 的一致性约束，不是仓库下载失败，也没有需要删除的项目 lockfile。应用侧两个依赖约束与 Espresso 3.7 / AndroidX Test 1.7 对齐到至少 `1.2.0`，不关闭一致性检查或跳过 AndroidTest lint。
- `LatestChapterTaskSchedulerTest` 使用注入的测试调度器，明确执行到 worker 的 `finally` 清理完成再检查空闲状态，避免实际 IO 线程与 `Deferred.await()` 的竞争；生产调度和原断言不变。
- 本地 NAS 专项单测、APK 打包通过不等同于云端全量 JVM 测试及 lint 已通过，合并以当前候选 SHA 的完整验证为准。

## 重复执行和冲突

- 无新增提交时不创建空 PR。
- 同一阶段使用固定同步分支，已有开放 PR 时追加合并提交并更新原 PR，保留人工修复，不强推。
- 远程同步分支在运行期间被修改时，普通 push 拒绝覆盖；重新运行即可。
- 有冲突时中止合并并在 Actions 摘要列出冲突文件；已有 PR 的状态标为失败，不推送半成品。
- 手动解决冲突后提交到同步分支并重新验证；不得用整片 ours/theirs 覆盖 NAS 功能。
- 不再需要的同步分支可以在 PR 合并后删除；下次需要同步时会重新建立。

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
