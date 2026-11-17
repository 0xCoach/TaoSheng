"""删除两卡独占/tmp评测树；先清点哈希，拒绝软链接；不操作原研究目录。"""
from pathlib import Path
import hashlib,json,shutil
root=Path('/tmp/taosheng-202-night-eval')
out=Path(__file__).resolve().parents[2]/'docs/任务卡/203-证据/download-cleanup.json'
if root.exists():
    assert not root.is_symlink()
    rows=[]
    for p in sorted(root.rglob('*')):
        assert not p.is_symlink(),str(p)
        if p.is_file():rows.append({'path':str(p.relative_to(root)),'bytes':p.stat().st_size,'sha256':hashlib.file_digest(p.open('rb'),'sha256').hexdigest()})
    shutil.rmtree(root)
    assert not root.exists()
    out.write_text(json.dumps({'root':str(root),'removed':True,'files':rows,'bytes':sum(v['bytes'] for v in rows),'original_research_directory_modified':False},indent=2))
    print('REMOVED',len(rows),'files')
else:
    print('Already absent')
