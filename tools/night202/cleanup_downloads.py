"""只删除本次预检清单中的音频；哈希不符停止，不改研究目录。"""
import csv, hashlib
from pathlib import Path
root=Path('/tmp/taosheng-202-night-eval')
manifest=Path(__file__).resolve().parents[2]/'docs/任务卡/202-证据/download-manifest.csv'
if root.exists():
    for row in csv.DictReader(manifest.open()):
        p=root/row['path']
        assert p.resolve().is_relative_to(root.resolve())
        if p.exists():
            assert hashlib.sha256(p.read_bytes()).hexdigest()==row['sha256'], str(p)
            p.unlink()
    for p in sorted(root.rglob('*'),key=lambda p:len(p.parts),reverse=True):
        if p.is_dir() and not any(p.iterdir()):p.rmdir()
    if not any(root.iterdir()):root.rmdir()
