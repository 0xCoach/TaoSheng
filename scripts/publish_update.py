#!/usr/bin/env python3
"""涛声双通道发布：先传安装包和差异包，最后更新通道说明。

正式发布延续已有 v<版本> 发布；预览发布用 --preview，自动建立预发布。
--dry-run 完全离线、不读取凭证；可重复传 --base-apk 旧包路径验证差异包。
真实发布从 GitHub 选最近 5 个正式/预览版本（排除 preview-channel）作差异包基线。
预览版只传魔搭（快）；正式版魔搭 + GitHub 双平台。App 两边都查、取版本号大的，所以 GitHub 上旧的预览通道不影响更新。
差异包基线整包缓存在 ~/.cache/taosheng-release-bases，下过一次就不再重复下载。
"""
import argparse, hashlib, json, os, re, subprocess, sys, tempfile
from pathlib import Path

REPO_GH = "0xCoach/TaoSheng"
REPO_MS = "OpenRecode/TaoSheng-releases"
TOKEN_FILE = Path.home() / "Downloads/UU/ModelScope.txt"
KEEP = 5
CACHE = Path.home() / ".cache/taosheng-release-bases"
MS_BASE = "https://modelscope.cn/models/OpenRecode/TaoSheng-releases/resolve/master/"


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
    """最近 KEEP 个正式/预览版（不含新版本身和固定通道）的整包"""
    tags = [r["tagName"] for r in json.loads(gh("release", "list", "--limit", "100", "--exclude-drafts", "--json", "tagName"))]
    out = []
    for tag in tags:
        if tag in (f"v{new_name}", "preview-channel") or not re.fullmatch(r"v[0-9]+(?:\.[0-9]+)+(?:-preview\.[0-9]+)?", tag) or len(out) >= KEEP:
            continue
        d = CACHE / tag
        if not list(d.glob("*.apk")):
            d.mkdir(parents=True, exist_ok=True)
            gh("release", "download", tag, "-p", "*.apk", "-D", str(d))
        apks = list(d.glob("*.apk"))
        if apks:
            out.append((tag.removeprefix("v"), apks[0]))
    return out


def modelscope_previews(known: set[str]) -> list[tuple[str, Path]]:
    """只传了魔搭的预览版：本地缓存里有的直接用；当前魔搭预览通道指向的那个，缓存没有就下载"""
    out = []
    for d in sorted(CACHE.glob("v*-preview.*")):
        name = d.name.removeprefix("v")
        apks = list(d.glob("*.apk"))
        if apks and name not in known:
            out.append((name, apks[0]))
    try:
        import urllib.request
        m = json.loads(urllib.request.urlopen(MS_BASE + "preview/update-preview.json", timeout=20).read())
        name = m["versionName"]
        d = CACHE / f"v{name}"
        if name not in known and not list(d.glob("*.apk")):
            d.mkdir(parents=True, exist_ok=True)
            urllib.request.urlretrieve(MS_BASE + "preview/" + m["apk"]["file"], d / m["apk"]["file"])
            if sha256(d / m["apk"]["file"]) != m["apk"]["sha256"]:
                (d / m["apk"]["file"]).unlink(); raise ValueError("魔搭预览包校验不符")
            out.append((name, d / m["apk"]["file"]))
    except Exception as error:
        print(f"（跳过魔搭预览基线：{error}）")
    return out


def validate_channel(name: str, preview: bool):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+(?:-preview\.[0-9]+)?", name):
        raise ValueError("安装包版本名格式不对")
    if ("-preview." in name) != preview:
        raise ValueError("通道与安装包版本名不一致：预览包必须加 --preview，正式包不能加")


def channel_paths(preview: bool) -> tuple[str, str]:
    manifest = "update-preview.json" if preview else "update.json"
    prefix = "preview/" if preview else ""
    assert not preview or (prefix + manifest == "preview/update-preview.json" and manifest != "update.json")
    return prefix, manifest


MS_KEEP_FORMAL = 3   # 魔搭只是国内下载镜像：正式版留最近 3 个，预览版只留最新 1 个；GitHub 全部保留
MS_KEEP_PREVIEW = 1
APK_RE = re.compile(r"TaoSheng-v(.+)-arm64\.apk")
PATCH_RE = re.compile(r"TaoSheng-(.+)-to-(.+)\.patch")


def version_key(name: str) -> tuple:
    """0.2.13-preview.2 排在 0.2.13 之前；正式版的预览序号视为无穷大。"""
    base, _, preview = name.partition("-preview.")
    return tuple(int(x) for x in base.split(".")) + (int(preview) if preview else float("inf"),)


def prune_plan(paths: list[str], current: str, preview: bool) -> list[str]:
    """本通道目录里要删的旧文件：超出保留数的整包，以及不指向当前版本的差异包。只动 .apk/.patch。"""
    prefix = "preview/" if preview else ""
    own = [p[len(prefix):] for p in paths if p.startswith(prefix) and "/" not in p[len(prefix):]]
    apks = {m.group(1): f for f in own if (m := APK_RE.fullmatch(f))}
    keep = set(sorted(apks, key=version_key)[-(MS_KEEP_PREVIEW if preview else MS_KEEP_FORMAL):]) | {current}
    doomed = [f for v, f in apks.items() if v not in keep]
    doomed += [f for f in own if (m := PATCH_RE.fullmatch(f)) and m.group(2) != current]
    assert all(APK_RE.fullmatch(f) or PATCH_RE.fullmatch(f) for f in doomed)
    return sorted(prefix + f for f in doomed)


def prune_modelscope(api, current: str, preview: bool):
    paths = [f["Path"] for f in api.get_model_files(REPO_MS, recursive=True) if f.get("Type") != "tree"]
    doomed = prune_plan(paths, current, preview)
    if not doomed:
        return print("魔搭：没有要清理的旧文件")
    try:
        api.delete_files(REPO_MS, file_paths=doomed, commit_message=f"涛声 {current}：清理旧版本")
        print(f"魔搭：已清理 {len(doomed)} 个旧文件")
    except Exception as error:  # 清理失败不影响已完成的发布
        print(f"魔搭：清理旧文件失败（{type(error).__name__}），下次发布会再试")


def ensure_prerelease(tag: str, title: str):
    try:
        gh("release", "view", tag, "--json", "tagName")
    except subprocess.CalledProcessError:
        gh("release", "create", tag, "--prerelease", "--latest=false", "--title", title, "--notes", "涛声预览版")
    else:
        gh("release", "edit", tag, "--prerelease", "--latest=false")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", type=Path)
    ap.add_argument("--notes", type=Path, required=True)
    ap.add_argument("--dry-run", action="store_true", help="只在本地生成，不上传")
    ap.add_argument("--preview", action="store_true", help="发布到预览通道")
    ap.add_argument("--base-apk", type=Path, action="append", default=[], help="离线 dry-run 的旧包，可重复，最多 5 个")
    a = ap.parse_args()
    if a.base_apk and (not a.dry_run or len(a.base_apk) > KEEP):
        ap.error("--base-apk 仅用于 --dry-run，最多 5 个")
    code, name = badging(a.apk)
    try:
        validate_channel(name, a.preview)
    except ValueError as error:
        ap.error(str(error))
    prefix, manifest_name = channel_paths(a.preview)
    apk_name = f"TaoSheng-v{name}-arm64.apk"
    work = Path(tempfile.mkdtemp(prefix="taosheng-update-"))
    # 统一整包文件名，GitHub 附件名必须与说明文件一致。
    import shutil
    staged_apk = work / apk_name
    shutil.copyfile(a.apk, staged_apk)
    files = {apk_name: staged_apk}
    patches = []
    bases = [(badging(path)[1], path) for path in a.base_apk] if a.dry_run else older_releases(name, work)
    if not a.dry_run:
        # 只传了魔搭的预览版也要作基线：装着最新预览版的人更新正式版时也只下差异包
        bases += modelscope_previews({n for n, _ in bases} | {name})
        # 只取版本号最近的 KEEP 个；上一个正式版无论多旧都保留（正式通道的人从它升级）
        ranked = sorted(bases, key=lambda b: version_key(b[0]), reverse=True)
        formal = next((b for b in ranked if "-preview." not in b[0]), None)
        bases = ranked[:KEEP] + ([formal] if formal and formal not in ranked[:KEEP] else [])
    seen = set()
    for old_name, old in bases:
        old_code, _ = badging(old)
        validate_channel(old_name, "-preview." in old_name)
        digest = sha256(old)
        if old_code >= code or digest in seen:
            continue
        seen.add(digest)
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
    update = work / manifest_name
    update.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(f"{manifest_name}：{name}（{code}），差异包 {len(patches)} 个，目录 {work}")
    if a.dry_run:
        return

    from modelscope.hub.api import HubApi
    api = HubApi()
    api.login(TOKEN_FILE.read_text().strip())
    if not api.repo_exists(REPO_MS):
        api.create_repo(REPO_MS, visibility="public", license="mit")
    # 预览发布的所有魔搭写入都限定在 preview/；通道文件必须最后上传。
    assert not a.preview or (prefix == "preview/" and update.name == "update-preview.json")
    for file_name, path in files.items():
        api.upload_file(path_or_fileobj=str(path), path_in_repo=prefix + file_name, repo_id=REPO_MS, commit_message=f"涛声 {name}：{file_name}")
    if not a.preview:
        gh("release", "upload", f"v{name}", *[str(p) for p in files.values()], "--clobber")
    api.upload_file(path_or_fileobj=str(update), path_in_repo=prefix + manifest_name, repo_id=REPO_MS, commit_message=f"涛声 {name}：版本说明")
    if not a.preview:
        gh("release", "upload", f"v{name}", str(update), "--clobber")
    # 这次的整包留作下次的差异包基线
    (CACHE / f"v{name}").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(staged_apk, CACHE / f"v{name}" / apk_name)
    print(f"已发布：{'魔搭' if a.preview else '魔搭和 GitHub'} 的 {manifest_name} 指向", name)
    # 版本说明已指向新版后再清理，正在更新的人不会下载到被删的文件
    prune_modelscope(api, name, a.preview)


if __name__ == "__main__":
    main()
