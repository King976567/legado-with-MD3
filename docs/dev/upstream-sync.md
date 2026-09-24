# MD3 上游同步流程

本仓库把 `HapeLee/legado-with-MD3` 作为 `upstream`，把用户自己的 GitHub Fork 作为 `origin`。`main` 只跟踪上游基线，`nas-md3` 保存 NAS 集成。上游同步不会直接写入 `nas-md3`，也不会把 NAS 改动推回上游。

## 首次配置

在 GitHub 网页创建自己的 Fork 后，在本地仓库配置远程。不要把令牌、NAS 地址或其他凭据写入 URL、脚本或仓库文件。

```powershell
git remote add origin https://github.com/<your-account>/legado-with-MD3.git
git remote set-url upstream https://github.com/HapeLee/legado-with-MD3.git
git fetch --prune upstream
git fetch --prune origin
git branch --set-upstream-to=upstream/main main
```

如果 `origin` 已存在但指向其他仓库，先核对 `git remote -v`，再使用 `git remote set-url origin ...`。当前工作树没有用户 Fork 地址时，不要猜测或自动创建远程。

建议在 GitHub Fork 中保护 `main` 和 `nas-md3`，要求 Pull Request 通过 `Verify` 后才能合并；打开自动删除已合并分支可以清理同步分支。

## 自动/手动同步

`.github/workflows/sync-upstream.yml` 只在 Fork 中运行，默认每周执行一次，也可以在 Actions 页面手动运行并填写上游分支（默认 `main`）。它会：

1. 从 Fork 的 `main` 创建一次性的 `automation/sync-upstream-*` 分支。
2. 添加 `upstream` 并把指定分支合并到该同步分支。
3. 合并无冲突时推送同步分支，并创建一个指向 `main` 的 PR。
4. 如果已有同步 PR，则跳过本次运行；如果没有变化，也不会创建空 PR。

工作流不会自动合并 PR、不会直接推送 `main`、不会修改 `nas-md3`，也不会自动填写或解决冲突。发生冲突时它会失败并保留工作树不变；请在本地手动处理后重新发起 PR。

## 合并前后操作

同步 PR 合并前至少运行：

```powershell
.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache
```

PR 合并后更新本地基线，并单独把基线变更带到 NAS 分支。NAS 分支若有冲突，必须人工检查每一处 NAS 代码和上游改动；不要用 `ours`/`theirs` 批量覆盖：

```powershell
git fetch --prune origin upstream
git switch main
git pull --ff-only origin main
git switch nas-md3
git merge main
# 手动解决、测试后再提交并推送 nas-md3
git push origin nas-md3
```

如果 `main` 本地出现了不属于上游的提交，先停止自动同步并人工决定是回滚、拆分到 `nas-md3`，还是在同步 PR 中保留；不要强推覆盖远程历史。

## 凭据与回滚边界

- 工作流只使用 GitHub Actions 的临时 `GITHUB_TOKEN` 创建分支和 PR，不需要把个人 PAT 写入仓库。
- NAS Bearer Token 不属于 Git 配置，也不得出现在 PR、日志、备份文件或 GitHub Secrets 以外的提交内容中。
- 同步 PR 仅改变 `main` 的上游提交；拒绝或关闭 PR 即可回滚，不能用它替代 NAS 分支的代码审查。
