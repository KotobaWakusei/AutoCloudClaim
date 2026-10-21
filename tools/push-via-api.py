#!/usr/bin/env python3
"""
通过 GitHub Git Data API 推送本地 commit（绕过 git smart-HTTP 的 github.com 通道）。

为什么需要它：某些网络下 api.github.com 通、github.com 不通，git push 会卡在
HTTP2 framing error。本脚本用 gh api（走 api.github.com）直接构造 git 对象。

commit 对象是内容寻址的：只要 tree / parent / author / committer / message
与本地完全一致，API 生成的对象 SHA 与本地逐字节相同 —— 所以推完之后本地与远端
不会出现分叉，后续普通 git push 照常可用。

用法： tools/push-via-api.py [要推的 commit ...]   # 默认推 origin/main..HEAD
"""
import base64
import json
import subprocess
import sys
import time

OWNER = "KotobaWakusei"
REPO = "AutoCloudClaim"
BRANCH = "main"
API = f"/repos/{OWNER}/{REPO}"


def sh(*args, check=True, binary=False):
    p = subprocess.run(args, capture_output=True)
    if check and p.returncode != 0:
        raise SystemExit(f"命令失败: {' '.join(args)}\n{p.stderr.decode(errors='replace')}")
    return p.stdout if binary else p.stdout.decode()


def api(method, path, body=None, allow_404=False, retries=6):
    """调用 gh api（走 api.github.com）。返回 (status, parsed_json_or_None)。"""
    cmd = ["gh", "api", "--method", method, path]
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        cmd += ["--input", "-"]
    last_err = ""
    for attempt in range(retries):
        p = subprocess.run(cmd, input=data, capture_output=True)
        out = p.stdout.decode(errors="replace")
        err = p.stderr.decode(errors="replace")
        if p.returncode == 0:
            try:
                return 200, json.loads(out) if out.strip() else None
            except json.JSONDecodeError:
                return 200, None
        # 404：调用方可能是在探测存在性
        if allow_404 and "404" in err:
            return 404, None
        last_err = err.strip().splitlines()[-1] if err.strip() else "未知错误"
        # 网络瞬断（EOF / timeout / 5xx）值得重试
        if attempt + 1 < retries:
            wait = 2 * (attempt + 1)
            print(f"    … API {method} 失败（{last_err}），{wait}s 后重试 {attempt+2}/{retries}")
            time.sleep(wait)
    raise SystemExit(f"API {method} {path} 失败:\n{last_err}")


def ensure_blob(sha, path):
    """确保远端有该 blob；没有就上传。"""
    status, _ = api("GET", f"{API}/git/blobs/{sha}", allow_404=True)
    if status == 200:
        return False
    content = sh("git", "cat-file", "blob", sha, binary=True)
    api("POST", f"{API}/git/blobs",
        {"content": base64.b64encode(content).decode(), "encoding": "base64"})
    print(f"    + blob {sha[:10]} {path}")
    return True


def ensure_tree(commit):
    """把 commit 的 tree 完整同步到远端，返回 tree SHA。"""
    entries = []
    for line in sh("git", "ls-tree", "-r", commit).splitlines():
        meta, path = line.split("\t", 1)
        mode, otype, sha = meta.split()
        if otype != "blob":
            # 子模块（commit 类型）：直接沿用远端已有对象
            entries.append({"mode": mode, "type": otype, "sha": sha, "path": path})
            continue
        ensure_blob(sha, path)
        entries.append({"mode": mode, "type": "blob", "sha": sha, "path": path})
    want_tree = sh("git", "rev-parse", f"{commit}^{{tree}}").strip()
    status, res = api("POST", f"{API}/git/trees", {"base_tree": None, "tree": entries})
    got_tree = res.get("sha") if res else None
    if got_tree != want_tree:
        # base_tree=None 时 API 可能是按目录结构生成，tree SHA 应完全一致；不一致就报出来
        print(f"    ! tree SHA 不一致：远端 {got_tree} / 本地 {want_tree}")
        raise SystemExit("tree 构造不一致，已中止（避免推上去的内容与本地不一致）")
    print(f"    = tree {want_tree[:10]} ({len(entries)} 个文件)")
    return want_tree


def commit_meta(commit):
    """从本地 commit 对象里取出 author/committer/message，格式与 API 一致。"""
    raw = sh("git", "cat-file", "commit", commit)
    head, _, message = raw.partition("\n\n")
    meta = {"message": message}
    for line in head.splitlines():
        if line.startswith("author "):
            name, email, ts, tz = parse_ident(line[len("author "):])
            meta["author"] = {"name": name, "email": email, "date": iso(ts, tz)}
        elif line.startswith("committer "):
            name, email, ts, tz = parse_ident(line[len("committer "):])
            meta["committer"] = {"name": name, "email": email, "date": iso(ts, tz)}
    return meta


def parse_ident(s):
    # "Name <email> 1792593067 +0800"
    name, rest = s.split(" <", 1)
    email, rest = rest.split("> ", 1)
    ts, tz = rest.split(" ", 1)
    return name, email, ts, tz


def iso(ts, tz):
    sign = tz[0]
    hh, mm = tz[1:3], tz[3:5]
    offset = f"{sign}{hh}:{mm}"
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(int(ts) + int(hh) * 3600 + int(mm) * 60)) + offset


def main():
    commits = sys.argv[1:]
    if not commits:
        out = sh("git", "rev-list", "--reverse", f"origin/{BRANCH}..HEAD").split()
        commits = out
    if not commits:
        print("没有要推的 commit")
        return

    parents = sh("git", "rev-parse", f"origin/{BRANCH}").strip()
    print(f"推 {len(commits)} 个 commit，base={parents[:10]}")

    for c in commits:
        want = sh("git", "rev-parse", c).strip()
        print(f"  commit {want[:10]} {sh('git', 'log', '-1', '--format=%s', c).strip()}")
        tree = ensure_tree(c)
        meta = commit_meta(c)
        body = dict(meta)
        body["tree"] = tree
        body["parents"] = [parents]
        status, res = api("POST", f"{API}/git/commits", body)
        got = res.get("sha") if res else None
        if got != want:
            print(f"    ! commit SHA 不一致：远端 {got} / 本地 {want}")
            raise SystemExit("commit 构造不一致，已中止")
        print(f"    = commit {got[:10]} (SHA 与本地一致)")
        parents = got

    # 更新分支引用
    api("PATCH", f"{API}/git/refs/heads/{BRANCH}", {"sha": parents, "force": False})
    print(f"已更新 {BRANCH} -> {parents}")


if __name__ == "__main__":
    main()
