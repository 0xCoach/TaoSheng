#!/usr/bin/env python3
"""卡 051 发布：给已打好的正式包做差异包和 update.json，传到魔搭和 GitHub 发布页。

用法（在 GitHub 上已建好 v<版本> 发布、整包已传上去之后）：
  ~/.venvs/modelscope/bin/python scripts/publish_update.py <新版整包.apk> --notes 更新内容.txt [--dry-run]

做的事：
1. 从 GitHub 发布页取最近 5 个正式版整包，各做一个直达新版的 bsdiff 差异包；
2. 写 update.json（版本号从安装包里读）；
3. 先传整包和差异包，最后才传 update.json：魔搭 OpenRecode/TaoSheng-releases，GitHub 同名发布。
密钥只从 ~/Downloads/UU/ModelScope.txt 读，不打印、不写进任何文件。
"""
import argparse, hashlib, json, os, re, subprocess, sys, tempfile
from pathlib import Path

REPO_GH = "0xCoach/TaoSheng"
REPO_MS = "OpenRecode/TaoSheng-releases"
TOKEN_FILE = Path.home() / "Downloads/UU/ModelScope.txt"
KEEP = 5


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def badging(apk: Path) -> tuple[int, str]:
    sdk = next(l.split("=", 1)[1].strip() for l in Path("local.properties").read_text().splitlines() if l.startswith("sdk.dir"))
    aapt = sorted(Path(sdk, "build-tools").glob("*/aapt2"))[-1]
    line = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout.splitlines()[0]
    code = int(re.search(r"versionCode='(\d+)'", line).group(1))
    name = re.search(r"versionName='([^']+)'", line).group(1)
    return code, name


def gh(*args: str, capture=True) -> str:
    return subprocess.run(["gh", *args, "-R", REPO_GH], capture_output=capture, text=True, check=True).stdout


def older_releases(new_name: str, work: Path) -> list[tuple[str, Path]]:
    """最近 KEEP 个正式版（不含新版本身）的整包"""
    tags = [l.split("\t")[2] for l in gh("release", "list", "--limit", "20", "--exclude-drafts", "--exclude-pre-releases").splitlines() if l.strip()]
    out = []
    for tag in tags:
        if tag == f"v{new_name}" or len(out) >= KEEP:
            continue
        d = work / tag
        d.mkdir()
        gh("release", "download", tag, "-p", "*.apk", "-D", str(d))
        apks = list(d.glob("*.apk"))
        if apks:
            out.append((tag.removeprefix("v"), apks[0]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", type=Path)
    ap.add_argument("--notes", type=Path, required=True)
    ap.add_argument("--dry-run", action="store_true", help="只在本地生成，不上传")
    a = ap.parse_args()
    code, name = badging(a.apk)
    apk_name = f"TaoSheng-v{name}-arm64.apk"
    work = Path(tempfile.mkdtemp(prefix="taosheng-update-"))
    files = {apk_name: a.apk}
    patches = []
    for old_name, old in older_releases(name, work):
        patch = work / f"TaoSheng-{old_name}-to-{name}.patch"
        subprocess.run(["bsdiff", str(old), str(a.apk), str(patch)], check=True)
        # 自检：用 bspatch 还原，必须和新包一模一样
        back = work / f"check-{old_name}.apk"
        subprocess.run(["bspatch", str(old), str(back), str(patch)], check=True)
        if sha256(back) != sha256(a.apk):
            sys.exit(f"{old_name} 的差异包还原后对不上，停止发布")
        back.unlink()
        files[patch.name] = patch
        patches.append({"fromSha256": sha256(old), "file": patch.name, "sha256": sha256(patch), "size": patch.stat().st_size})
        print(f"差异包 {old_name} → {name}：{patch.stat().st_size / 1e6:.1f} MB")
    manifest = {"versionCode": code, "versionName": name, "notes": a.notes.read_text().strip(),
                "apk": {"file": apk_name, "sha256": sha256(a.apk), "size": a.apk.stat().st_size}, "patches": patches}
    update = work / "update.json"
    update.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(f"update.json：{name}（{code}），差异包 {len(patches)} 个，目录 {work}")
    if a.dry_run:
        return

    from modelscope.hub.api import HubApi
    api = HubApi()
    api.login(TOKEN_FILE.read_text().strip())
    if not api.repo_exists(REPO_MS):
        api.create_repo(REPO_MS, visibility="public", license="mit")
    # 先传安装包和差异包，最后才传 update.json，手机不会查到没传完的版本
    for file_name, path in files.items():
        api.upload_file(path_or_fileobj=str(path), path_in_repo=file_name, repo_id=REPO_MS, commit_message=f"涛声 {name}：{file_name}")
    gh("release", "upload", f"v{name}", *[str(p) for n, p in files.items() if n != apk_name], "--clobber")
    api.upload_file(path_or_fileobj=str(update), path_in_repo="update.json", repo_id=REPO_MS, commit_message=f"涛声 {name}：版本说明")
    gh("release", "upload", f"v{name}", str(update), "--clobber")
    print("已发布：魔搭和 GitHub 的 update.json 都指向", name)


if __name__ == "__main__":
    main()
