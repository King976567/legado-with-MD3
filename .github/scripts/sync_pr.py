"""Prepare review-only sync PRs, using fast-forward pushes and explicit CI."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*args, check=True):
    return subprocess.run(args, text=True, encoding="utf-8", capture_output=True, check=check)


def git(*args):
    return run("git", *args).stdout.strip()


def gh(*args):
    return run("gh", *args).stdout.strip()


def ancestor(source, target):
    result = run("git", "merge-base", "--is-ancestor", source, target, check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(result.stderr)
    return result.returncode == 0


def prepare_branch(target, source, branch, existing=None):
    """Keep prior manual fixes; abort conflicts without publishing partial merges."""
    if ancestor(source, target):
        return None
    git("switch", "-c", branch, existing or target)
    for ref in (target, source):
        if ancestor(ref, "HEAD"):
            continue
        result = run("git", "merge", "--no-ff", "--no-edit", ref, check=False)
        if result.returncode:
            conflicts = git("diff", "--name-only", "--diff-filter=U")
            run("git", "merge", "--abort", check=False)
            raise RuntimeError(f"同步冲突，未推送改动，请人工处理：\n{conflicts or result.stderr}")
    return git("rev-parse", "HEAD")


def describe(message):
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as file:
            file.write(message + "\n\n")


def status(repo, sha, stage, state, description):
    url = f"{os.environ['GITHUB_SERVER_URL']}/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    gh("api", "--method", "POST", f"repos/{repo}/statuses/{sha}",
       "-f", f"state={state}", "-f", f"context=Sync verification ({stage})",
       "-f", f"description={description}", "-f", f"target_url={url}")


def sync():
    repo = os.environ["GITHUB_REPOSITORY"]
    if repo != "King976567/legado-with-MD3":
        raise RuntimeError("仅允许在指定 Fork 中运行")
    stage = os.environ["SYNC_STAGE"]
    if stage not in ("upstream", "nas"):
        raise ValueError("未知同步阶段")
    target = "main" if stage == "upstream" else "nas-md3"
    upstream_ref = os.environ.get("UPSTREAM_REF", "main").removeprefix("refs/heads/")
    git("check-ref-format", f"refs/heads/{upstream_ref}")
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("fetch", "--no-tags", "origin", f"refs/heads/{target}:refs/remotes/origin/{target}")
    base = git("rev-parse", f"origin/{target}")
    if stage == "upstream":
        git("fetch", "--no-tags", "https://github.com/HapeLee/legado-with-MD3.git", f"refs/heads/{upstream_ref}")
        source = git("rev-parse", "FETCH_HEAD")
        suffix = "main" if upstream_ref == "main" else hashlib.sha256(upstream_ref.encode()).hexdigest()[:12]
        branch = f"automation/sync-upstream-{suffix}"
        source_name = f"HapeLee/{upstream_ref}"
    else:
        git("fetch", "--no-tags", "origin", "refs/heads/main:refs/remotes/origin/main")
        source = git("rev-parse", "origin/main")
        branch = "automation/sync-nas-main"
        source_name = "main"
    if ancestor(source, base):
        describe(f"{target} 已包含 {source_name} 的全部提交，无需创建 PR。")
        return
    prs = json.loads(gh("pr", "list", "--repo", repo, "--state", "open", "--base", target,
                        "--head", branch, "--json", "number,url,headRefOid"))
    existing = None
    if git("ls-remote", "--heads", "origin", f"refs/heads/{branch}"):
        git("fetch", "--no-tags", "origin", f"refs/heads/{branch}")
        existing = git("rev-parse", "FETCH_HEAD")
    try:
        sha = prepare_branch(base, source, branch, existing)
    except RuntimeError:
        if prs:
            status(repo, prs[0]["headRefOid"], stage, "failure", "同步冲突，需要人工处理后重新运行")
        raise
    git("push", "origin", f"HEAD:refs/heads/{branch}")
    title = f"chore(sync): {source_name} → {target}"
    body = (
        f"将 `{source_name}` 的更新合入 `{target}`，保留目标分支和现有 PR 的修改。\n\n"
        f"- 来源：`{source}`\n- 目标基线：`{base}`\n- 待验证提交：`{sha}`\n\n"
        "同步工作流直接执行 JVM 测试、lint、架构检查和 arm64 Debug APK 打包，"
        "不依赖机器人创建 PR 触发其他工作流。请查看 Sync verification 状态的运行链接，"
        "在 Artifacts 下载 APK，测试并确认后再手动合并。\n\n"
        "不会自动合并或自动解决冲突；后续更新会追加到此 PR。"
    )
    with tempfile.TemporaryDirectory() as folder:
        body_file = Path(folder) / "body.md"
        body_file.write_text(body, encoding="utf-8")
        if prs:
            gh("pr", "edit", str(prs[0]["number"]), "--repo", repo, "--title", title, "--body-file", str(body_file))
            url = prs[0]["url"]
        else:
            url = gh("pr", "create", "--repo", repo, "--base", target, "--head", branch,
                     "--title", title, "--body-file", str(body_file))
    status(repo, sha, stage, "pending", "等待测试、lint、架构检查及 arm64 APK")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as file:
        file.write(f"{stage}_sha={sha}\n{stage}_base={base}\n")
    describe(f"[{title}]({url}) 已创建或更新，等待验证及人工确认合并。")


if __name__ == "__main__":
    try:
        sync()
    except Exception as error:
        describe(f"同步失败：{error}")
        raise
