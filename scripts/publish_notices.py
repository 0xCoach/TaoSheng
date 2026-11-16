#!/usr/bin/env python3
"""发布远程通知（不升级 App 也能触达用户）。

用法：
  ~/.venvs/modelscope/bin/python scripts/publish_notices.py 通知.json [--dry-run]

通知.json 示例（图片写本机路径，脚本会复制、改名并算校验值）：
  {"notices": [
    {"id": "group-2026-11", "title": "加入涛声用户群", "body": "交流使用心得、第一时间收到新版本",
     "image": {"path": "~/Desktop/群二维码.png", "caption": "保存到相册后，用微信扫一扫 → 相册识别"},
     "link": {"label": "了解更多", "url": "https://www.taosheng.app"},
     "endAt": "2026-11-23", "priority": 5}
  ]}
  - 撤下全部通知：发布 {"notices": []}
  - 时间可写 "2026-11-23" 或 "2026-11-23 20:00"（北京时间），也可写毫秒
  - 只给某些版本/品牌看：minVersionCode / maxVersionCode / brands: ["huawei"]
  - dismissible: false 表示不能点“知道了”（到 endAt 自动消失）

做的事：读线上当前版本号 +1 → 复制图片到 notices/ 并算 SHA-256 → 写 notices/notices.json →
用说明卡同一把私钥（~/.config/taosheng/cards-signing.key）签名 → 自验签 → 上传魔搭 notices/（先图片后通知）。
GitHub 备用源读仓库 main 分支的 notices/ 目录：发布后把 notices/ 提交并推送即可。
"""
import argparse, hashlib, json, re, shutil, sys, urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "notices"
KEY = Path.home() / ".config/taosheng/cards-signing.key"
REPO_MS = "OpenRecode/TaoSheng-releases"
TOKEN_FILE = Path.home() / "Downloads/UU/ModelScope.txt"
REMOTE = "https://modelscope.cn/models/OpenRecode/TaoSheng-releases/resolve/master/notices/notices.json"
CST = timezone(timedelta(hours=8))
SAFE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\.(png|jpg|jpeg|webp)")


def millis(v):
    if v is None or isinstance(v, int):
        return v
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return int(datetime.strptime(v, fmt).replace(tzinfo=CST).timestamp() * 1000)
        except ValueError:
            pass
    raise ValueError(f"时间格式不对：{v}")


def remote_version():
    try:
        with urllib.request.urlopen(REMOTE, timeout=20) as r:
            return int(json.load(r)["version"])
    except Exception:
        return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path)
    ap.add_argument("--dry-run", action="store_true", help="只生成并签名，不上传")
    a = ap.parse_args()
    src = json.loads(a.source.read_text(encoding="utf-8"))
    OUT.mkdir(exist_ok=True)
    local = json.loads((OUT / "notices.json").read_text()) ["version"] if (OUT / "notices.json").exists() else 0
    version = max(local, remote_version()) + 1
    notices, images = [], []
    for n in src.get("notices", []):
        n = dict(n)
        for k in ("startAt", "endAt"):
            if k in n:
                n[k] = millis(n[k])
        if "image" in n:
            img = dict(n["image"])
            path = Path(img.pop("path")).expanduser()
            ext = path.suffix.lower().lstrip(".")
            name = f"{n['id']}.{ext}"
            if not SAFE.fullmatch(name):
                raise ValueError(f"通知编号只能用字母数字和 ._-：{n['id']}")
            data = path.read_bytes()
            if len(data) > 1024 * 1024:
                raise ValueError(f"图片超过 1 MB：{path}")
            shutil.copyfile(path, OUT / name)
            img.update(file=name, sha256=hashlib.sha256(data).hexdigest())
            n["image"] = img
            images.append(OUT / name)
        notices.append(n)
    body = {"kind": "notices", "version": version, "issuedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "notices": notices}
    data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
    if len(data) > 128 * 1024:
        raise ValueError("通知文件超过 128 KB")
    key = serialization.load_pem_private_key(KEY.read_bytes(), password=None)
    sig = key.sign(data, ec.ECDSA(hashes.SHA256()))
    key.public_key().verify(sig, data, ec.ECDSA(hashes.SHA256()))
    pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo).hex()
    if pub != (ROOT / "app/src/main/assets/device-cards-public-key.hex").read_text().strip():
        raise ValueError("私钥和 App 内置公钥不匹配，停止")
    (OUT / "notices.json").write_bytes(data)
    (OUT / "notices.json.sig").write_bytes(sig)
    keep = {"notices.json", "notices.json.sig"} | {p.name for p in images}
    for old in OUT.iterdir():
        if old.name not in keep:
            old.unlink()
    print(f"已生成并签名：版本 {version}，{len(notices)} 条通知，{len(images)} 张图片 → {OUT}")
    if a.dry_run:
        return
    from modelscope.hub.api import HubApi
    api = HubApi()
    api.login(TOKEN_FILE.read_text().strip())
    for p in images:  # 先传图片，再传签名和通知文件
        api.upload_file(path_or_fileobj=str(p), path_in_repo="notices/" + p.name, repo_id=REPO_MS, commit_message=f"涛声通知 v{version}：{p.name}")
    for name in ("notices.json.sig", "notices.json"):
        api.upload_file(path_or_fileobj=str(OUT / name), path_in_repo="notices/" + name, repo_id=REPO_MS, commit_message=f"涛声通知 v{version}：{name}")
    print("已上传魔搭。GitHub 备用源：把 notices/ 提交并推送到 main。用户约 6 小时内（或下次打开 App 时）收到。")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        sys.exit(f"发布停止：{e}")
