import urllib.request,re,json,hashlib
from pathlib import Path
root=Path('/tmp/taosheng-202-night-eval');out=Path('docs/任务卡/202-证据')
items=[('VlatkoBlazek','191297','tune'),('dobroide','40967','tune'),('tim.kahn','841772','validation'),('smidoid','63349','validation')]
rows=[]
for author,sid,split in items:
    url=f'https://freesound.org/people/{author}/sounds/{sid}/'
    try:
        html=urllib.request.urlopen(url,timeout=30).read().decode()
        (out/f'license-{sid}.html').write_text(html)
        license=re.search(r'https?://creativecommons.org/licenses/by/[^"<> ]+',html)
        assert license, 'CC BY link missing'
        previews=re.findall(r'https://[^\s"<>]+(?:preview-hq\.mp3|hq\.mp3)',html)
        assert previews
        data=urllib.request.urlopen(previews[0],timeout=60).read()
        p=root/f'continuous/{sid}.mp3';p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
        rows.append(dict(id=sid,author=author,split=split,page=url,license=license.group(0),preview=previews[0],path=str(p),sha256=hashlib.sha256(data).hexdigest(),bytes=len(data)))
        print(sid,len(data),flush=True)
    except Exception as e:rows.append(dict(id=sid,page=url,error=str(e)));print(sid,type(e).__name__,str(e),flush=True)
(out/'continuous-sources.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
# 原 App 同一 int8 模型，严格校验。
base='https://modelscope.cn/models/OpenRecode/TaoSheng-models/resolve/master/audio-tagging/zipformer-small-2024-04-15/'
for name,sha in [('model.int8.onnx','69304b8a1b96bbe6b7d16c24079f0732c65faf3568a14cb82a8238c709afe76c'),('class_labels_indices.csv','cdd1049833c4b86127c2773ac0d14a2754b6a6d0d1798002ed5c66e699708429')]:
    p=root/'model'/name;p.parent.mkdir(parents=True,exist_ok=True)
    if not p.exists():p.write_bytes(urllib.request.urlopen(base+name,timeout=60).read())
    assert hashlib.sha256(p.read_bytes()).hexdigest()==sha
