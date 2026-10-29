#!/usr/bin/env python3
"""只下载公开数据；8 GB 累计传输上限；断点用已完成文件 SHA256 验证。"""
import argparse, hashlib, json, pathlib, urllib.request, time
P=argparse.ArgumentParser(); P.add_argument('--root',type=pathlib.Path,default=pathlib.Path.home()/'Datasets/taosheng-audio'); a=P.parse_args()
r=a.root; r.mkdir(parents=True,exist_ok=True); ledger=r/'download-ledger.json'
log=json.loads(ledger.read_text()) if ledger.exists() else {'bytes':0,'files':{}}
def save(): ledger.write_text(json.dumps(log,indent=2))
def get(url,name):
 p=r/name; p.parent.mkdir(parents=True,exist_ok=True)
 if p.exists() and name in log['files'] and hashlib.sha256(p.read_bytes()).hexdigest()==log['files'][name]['sha256']: return p
 tmp=p.with_suffix(p.suffix+'.part'); h=hashlib.sha256()
 try:
  with urllib.request.urlopen(url,timeout=90) as resp,open(tmp,'wb') as out:
   while b:=resp.read(1024*1024):
    log['bytes']+=len(b)
    if log['bytes']>8_000_000_000: raise RuntimeError('8 GB download budget exceeded')
    out.write(b); h.update(b)
  tmp.replace(p); log['files'][name]={'url':url,'bytes':p.stat().st_size,'sha256':h.hexdigest()}; print(name,p.stat().st_size,flush=True)
 finally: save()
 return p
# 固定 revision，首次解析后保存，复现沿用同一版本。
lock=r/'sources/revisions.json'; lock.parent.mkdir(exist_ok=True)
if lock.exists(): rev=json.loads(lock.read_text())
else:
 rev={}
 for key,url in [('esc','https://api.github.com/repos/karolpiczak/ESC-50/commits/master'),('musan','https://huggingface.co/api/datasets/corypaik/musan')]:
  p=get(url,f'sources/{key}-revision.json'); rev[key]=json.loads(p.read_text())['sha']
 lock.write_text(json.dumps(rev,indent=2))
get(f'https://raw.githubusercontent.com/karolpiczak/ESC-50/{rev["esc"]}/LICENSE','sources/ESC50-LICENSE')
get(f'https://codeload.github.com/karolpiczak/ESC-50/zip/{rev["esc"]}','sources/esc50.zip')
base=f'https://huggingface.co/datasets/corypaik/musan/resolve/{rev["musan"]}/'
for sub in ['music/fma','music/fma-western-art','music/hd-classical','music/jamendo','music/rfm','speech/librivox','speech/us-gov']:
 get(base+'licenses/'+sub+'/LICENSE','sources/musan/licenses/'+sub+'/LICENSE')
get(base+'README.md','sources/musan/README.md')
for kind,count,n in [('music',10,1),('speech',14,2)]:
 for i in range(n):
  name=f'{kind}/train-{i:05d}-of-{count:05d}.parquet'; get(base+name,'sources/musan/'+name)
print('total transferred bytes',log['bytes'])
