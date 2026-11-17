"""复用既有清单，只下载夜里节奏评测所需源音频到 /tmp；校验原 SHA-256。"""
import csv, hashlib, urllib.request, concurrent.futures
from pathlib import Path
ROOT = Path.home() / 'Developer/TaoSheng-research/snore-eval'
TARGET = Path('/tmp/taosheng-202-night-eval')
CATEGORIES = {'snoring', 'breathing', 'fan', 'air_conditioner', 'traffic', 'rain', 'bed_rustle'}
def download(row):
    path = TARGET / row['path']
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with urllib.request.urlopen(row['url'], timeout=30) as response:
            path.write_bytes(response.read())
    assert hashlib.sha256(path.read_bytes()).hexdigest() == row['sha256'], row['id']
    return row['id']
if __name__ == '__main__':
    rows = [r for r in csv.DictReader((ROOT/'results/manifest.csv').open()) if r['category'] in CATEGORIES]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for i, name in enumerate(pool.map(download, rows)):
            if i % 20 == 0: print(i + 1, len(rows), name, flush=True)
    print('VERIFIED', len(rows), flush=True)
