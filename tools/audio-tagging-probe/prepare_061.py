#!/usr/bin/env python3
"""构建固定分组清单和模拟集；私人音频只引用原路径，不落地副本。"""
import argparse,csv,hashlib,io,json,math,pathlib,re,subprocess,zipfile
import numpy as np, soundfile as sf
from scipy.signal import resample_poly,butter,sosfilt,fftconvolve
import pyarrow.parquet as pq
P=argparse.ArgumentParser();P.add_argument('--root',type=pathlib.Path,default=pathlib.Path.home()/'Datasets/taosheng-audio');a=P.parse_args();R=a.root;SR=16000

def read(path):
 try: x,s=sf.read(path,dtype='float32',always_2d=True);x=x.mean(axis=1)
 except Exception:
  x=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-f','f32le','-ac','1','-ar',str(SR),'-']),dtype=np.float32);s=SR
 if s!=SR:x=resample_poly(x,SR//math.gcd(s,SR),s//math.gcd(s,SR))
 return x.astype(np.float32)
def norm(x):return x/(np.sqrt(np.mean(x*x))+1e-8)*.08
def fit(x,n):return np.resize(x,n)
def room(x,seed,speaker=False):
 rng=np.random.default_rng(seed);rt=.25+(seed%5)*.1;t=np.arange(int(SR*rt))/SR
 h=rng.normal(0,.015,len(t))*np.exp(-6.91*t/rt);h[0]=1
 for delay,amp in [(.017,.4),(.037,.23),(.071,.12)]:h[int(delay*SR)]+=amp
 y=fftconvolve(x,h)[:len(x)]
 if speaker:y=sosfilt(butter(4,[300,5000],btype='bandpass',fs=SR,output='sos'),y);y=np.tanh(norm(y)*1.3)/1.3
 return norm(y).astype(np.float32)
rows=[]
def add(uid,x,split,category,source,group,kind='original',parents=None,event='',tags=None,path=None):
 if path is None:
  path=R/'prepared'/f'{uid}.wav';path.parent.mkdir(exist_ok=True);sf.write(path,np.clip(x,-.99,.99),SR)
 rows.append(dict(id=uid,path=str(path),split=split,category=category,source=source,group=group,kind=kind,parents=parents or [group],event=event,tags=tags or [],seconds=len(x)/SR))
# ESC official folds are source-disjoint; keep folds 4+5 sealed (40%).
catmap={'dog':('dog',['宠物']),'cat':('', ['宠物']),'rain':('', ['雨']), 'pouring_water':('', ['流水']), 'water_drops':('', ['流水']), 'crying_baby':('baby',[]),'door_wood_knock':('knock',[]),'door_wood_creaks':('',[]),'glass_breaking':('glass',[]),'keyboard_typing':('', ['键盘']),'washing_machine':('', ['家电']),'vacuum_cleaner':('', ['家电']),'clock_alarm':('', ['闹钟']),'engine':('', ['发动机']),'car_horn':('', ['交通']),'train':('', ['交通']),'airplane':('', ['交通']),'siren':('siren',['交通']),'helicopter':('', ['交通']),'laughing':('', ['人声活动']),'coughing':('', ['人声活动']),'breathing':('', ['人声活动']),'footsteps':('', ['脚步'])}
z=zipfile.ZipFile(R/'sources/esc50.zip');prefix=z.namelist()[0];meta=list(csv.DictReader(io.StringIO(z.read(prefix+'meta/esc50.csv').decode())))
for m in meta:
 if m['category'] not in catmap:continue
 x=read(io.BytesIO(z.read(prefix+'audio/'+m['filename'])));event,tags=catmap[m['category']]
 add('esc-'+m['filename'][:-4],x,'validation' if int(m['fold'])>=4 else 'tune','environment','ESC-50','esc:'+m['src_file'],event=event,tags=tags)
# 每条源文件仅取中部45秒。分片范围固定；按文件哈希分组，非说话人保证。
for kind in ['music','speech']:
 entries=[]
 for f in sorted((R/'sources/musan'/kind).glob('*.parquet')):
  for batch in pq.ParquetFile(f).iter_batches(batch_size=1):
   m=batch.to_pylist()[0];path=m['path'];audio=m['audio'];x=read(io.BytesIO(audio['bytes']))
   if len(x)<SR*10:continue
   start=max(0,(len(x)-45*SR)//2);x=x[start:start+45*SR];
   license_text=(R/'sources/musan/licenses'/pathlib.Path(path).parent/'LICENSE').read_text()
   block=next((b for b in re.split(r'={5,}',license_text) if pathlib.Path(path).stem in b),license_text)
   if kind=='music' and re.search(r'NoDeriv|BY.ND|BY.NC.ND',block,re.I):continue
   group=('music-license:'+hashlib.sha256(block.encode()).hexdigest()[:16]) if kind=='music' else 'musan:'+path
   entries.append((path,x,group,block))
 # deterministic 40% holdout, source-file level
 entries=sorted(entries,key=lambda e:hashlib.sha256(e[0].encode()).hexdigest())[:60]
 hold=set();count=0
 for group in sorted({e[2] for e in entries},key=lambda g:hashlib.sha256(g.encode()).hexdigest()):
  if count>=math.ceil(len(entries)*.4):break
  hold.add(group);count+=sum(e[2]==group for e in entries)
 for i,(path,x,group,block) in enumerate(entries):
  split='validation' if group in hold else 'tune';uid='musan-'+pathlib.Path(path).stem
  add(uid,x,split,kind,'MUSAN',group,tags=['音乐'] if kind=='music' else ['人声活动'])
  rows[-1]['attribution']=block;rows[-1]['source_path']=path
# All mixture ingredients must belong to the same partition.
base=list(rows)
for split in ['tune','validation']:
 speech=[r for r in base if r['split']==split and r['category']=='speech'];music=[r for r in base if r['split']==split and r['category']=='music'];noise=[r for r in base if r['split']==split and r['category']=='environment' and not r['event'] and '人声活动' not in r['tags']];events=[r for r in base if r['split']==split and r['event']]
 for i,s in enumerate(speech):
  x=norm(read(s['path']));m=music[i%len(music)];n=noise[i%len(noise)];mx=norm(fit(read(m['path']),len(x)));nx=norm(fit(read(n['path']),len(x)));seed=int(hashlib.sha256(s['id'].encode()).hexdigest()[:8],16)
  for suffix,y,category,parents,tags in [
   ('room',room(x,seed),'speech_room',[s['group']],['人声活动']),
   ('noise',room(x+nx*.56,seed),'speech_noise',[s['group'],n['group']],['人声活动']+n['tags']),
   ('music',room(x+mx*.56,seed),'speech_music',[s['group'],m['group']],['人声活动','音乐']),
   ('replay',room(x,seed,True),'media_dry',[s['group']],['人声活动']),
   ('film',room(x+mx*.56,seed,True),'media_music',[s['group'],m['group']],['人声活动','音乐'])]:
   add(s['id']+'-'+suffix,y,split,category,'MUSAN mix',s['group'],kind='simulated',parents=parents,tags=tags)
 for i,m in enumerate(music):add(m['id']+'-replay',room(norm(read(m['path'])),i+100,True),split,'music','MUSAN mix',m['group'],kind='simulated',tags=['音乐'])
 for i,e in enumerate(events):
  s=speech[i%len(speech)];m=music[i%len(music)];x=np.zeros(35*SR,np.float32);ev=norm(read(e['path']));x[15*SR:15*SR+len(ev)]+=ev
  y=x+norm(fit(read(s['path']),len(x)))*.7+norm(fit(read(m['path']),len(x)))*.35
  add(e['id']+'-film',room(y,i+200,True),split,'media_event','ESC+MUSAN mix',e['group'],kind='simulated',parents=[e['group'],s['group'],m['group']],event=e['event'],tags=e['tags'])
# Deduplicate source segment IDs across private directories, prefer WAV.
files={}
for d in ['回响身份识别验证-20261023','回响本地转写独立验证-20261023']:
 for p in sorted((pathlib.Path.home()/'Downloads'/d).rglob('segment-*')):
  if p.suffix.lower() in ['.wav','.ogg'] and (p.stem not in files or p.suffix=='.wav'):files[p.stem]=p
for uid,p in sorted(files.items()):
 category='media_music' if uid in ['segment-132','segment-137','segment-138'] else 'unknown' if uid=='segment-142' else 'speech'
 add(uid,read(p),'phone',category,'private',uid,kind='phone',path=p)
# Prevent derivative leakage, and record all artifacts.
parts={}
for r in rows:
 for p in r['parents']:
  assert p not in parts or parts[p]==r['split'],('leakage',p)
  parts[p]=r['split']
(R/'manifest.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
print('samples',len(rows),'by partition', {s:sum(r['split']==s for r in rows) for s in ['tune','validation','phone']},flush=True)
