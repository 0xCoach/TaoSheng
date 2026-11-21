"""匿名范围请求，严格限制每次响应，避免下载整夜数据；只保存核查需要的头和标注。"""
import urllib.request,urllib.parse,json
from pathlib import Path
root=Path('/tmp/taosheng-202-night-eval/psg');root.mkdir(exist_ok=True)
base='https://huggingface.co/datasets/dust-systems/psg-audio/resolve/main/'
path='V3/APNEA_EDF/00000999-100507/00000999-100507%5B001%5D.edf'
u=base+urllib.parse.quote(path,safe='/')+'?download=true'
def fetch(url,limit,byte_range=None):
 req=urllib.request.Request(url,headers={'Range':byte_range} if byte_range else {})
 with urllib.request.urlopen(req,timeout=30) as r:
  if byte_range:assert r.status==206,(r.status,dict(r.headers))
  b=r.read(limit+1);assert len(b)<=limit,len(b);return b
try:
 h=fetch(u,8192,'bytes=0-8191');(root/'header.edf').write_bytes(h)
 print('header',h[:8], 'headerbytes',h[184:192], 'records',h[236:244], 'seconds',h[244:252], 'signals',h[252:256],flush=True)
 n=int(h[252:256]);print([h[256+i*16:272+i*16].decode().strip() for i in range(n)],flush=True)
 r=fetch(base+'V3/APNEA_RML/00000999-100507.rml',2000000);(root/'annotations.rml').write_bytes(r);print(r[:200],len(r),flush=True)
except Exception as e:
 p=Path('docs/任务卡/203-证据/psg-access.json');p.write_text(json.dumps({'url':u,'error':str(e)},indent=2));raise
