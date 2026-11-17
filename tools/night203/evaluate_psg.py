"""只读公开EDF的一段（60–360秒）；原始医学文件不提交，输出Mic信号模型分数和匿名事件时间。"""
import urllib.request,urllib.parse,json,hashlib,sys,csv
from pathlib import Path
import numpy as np
from scipy.signal import resample_poly
import sherpa_onnx
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'night202'))
import reference_evaluate as r
root=Path('/tmp/taosheng-202-night-eval');p=root/'psg';out=Path('docs/任务卡/203-证据')
h=(p/'header.edf').read_bytes();n=int(h[252:256]);header=int(h[184:192]);pos=256+n*(16+80+8+8+8+8+8+80)
counts=[int(h[pos+i*8:pos+(i+1)*8]) for i in range(n)];stride=sum(counts)*2
start=header+60*stride;end=header+360*stride-1
path='V3/APNEA_EDF/00000999-100507/00000999-100507%5B001%5D.edf'
u='https://huggingface.co/datasets/dust-systems/psg-audio/resolve/main/'+urllib.parse.quote(path,safe='/')+f'?download=true&slice={start}-{end}'
req=urllib.request.Request(u,headers={'Range':f'bytes={start}-{end}'})
with urllib.request.urlopen(req,timeout=60) as response:
 assert response.status==206,response.status
 assert response.headers.get('Content-Range','').startswith(f'bytes {start}-{end}/'),response.headers.get('Content-Range')
 b=response.read(end-start+2);assert len(b)==end-start+1
(p/'slice.edf.bin').write_bytes(b)
records=np.frombuffer(b,dtype='<i2').reshape(300,sum(counts));offset=sum(counts[:18]);audio=records[:,offset:offset+counts[18]].reshape(-1).astype(np.float32)/32768
x=resample_poly(audio,1,3);print('read',len(b),'audio seconds',len(x)/16000,flush=True)
model=sherpa_onnx.AudioTagging(sherpa_onnx.AudioTaggingConfig(model=sherpa_onnx.AudioTaggingModelConfig(zipformer=sherpa_onnx.OfflineZipformerAudioTaggingModelConfig(model=str(root/'model/model.int8.onnx')),num_threads=2,provider='cpu'),labels=str(root/'model/class_labels_indices.csv'),top_k=527))
rows=[];history=[];env=[]
for off in range(0,len(x)-31999,32000):
 w=x[off:off+32000];db=int(np.clip(np.floor(r.db(w)+.5),-120,0));bg=int(np.median(history)) if history else db
 rms=[r.rms(w[i:i+8000]) for i in range(0,32000,8000)];env=(env+rms)[-60:];z=np.array(env);c=z-z.mean();cv=z.std()/(z.mean()+1e-12);cor=0.
 if len(z)>=12:
  for lag in range(4,min(12,len(z)//2)+1):
   den=np.linalg.norm(c[:-lag])*np.linalg.norm(c[lag:]);cor=max(cor,float(c[:-lag]@c[lag:])/den if den>1e-15 else 0)
 stream=model.create_stream();stream.accept_waveform(16000,w.astype(np.float32));scores={v.name:float(v.prob) for v in model.compute(stream,527)}
 rows.append(dict(case='psg_real_60_360',at=off//16+60000,snore=scores['Snoring'],rhythm=cor,modulation=cv,above=db-bg,levels=';'.join(str(int(np.clip(np.floor(20*np.log10(max(v,1e-6))+.5),-120,0))-bg) for v in rms)))
 history=(history+[db])[-300:]
with (out/'psg-windows.csv').open('w') as f:
 writer=csv.DictWriter(f,rows[0],lineterminator="\n");writer.writeheader();writer.writerows(rows)
import xml.etree.ElementTree as E
annotation=[]
for e in E.parse(p/'annotations.rml').getroot().iter():
 if e.tag.split('}')[-1]=='Event' and e.attrib.get('Family')=='Respiratory':
  a=e.attrib
  if 60<=float(a['Start'])<360:annotation.append({k:a[k] for k in ['Type','Start','Duration']})
(out/'psg-access.json').write_text(json.dumps({'source':'https://huggingface.co/datasets/dust-systems/psg-audio','original':'https://doi.org/10.11922/sciencedb.00345','license':'CC-BY-4.0 (mirror README)','path':path,'slice_seconds':[60,360],'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),'channel':'Mic','rate_original':48000,'rate_model':16000,'annotation_sha256':hashlib.sha256((p/'annotations.rml').read_bytes()).hexdigest(),'respiratory_annotations':annotation,'limits':'Single 5-minute real excerpt; clinical respiratory annotations are not equivalent to the product SnoreBreak label. No full-night false-positive claim.'},ensure_ascii=False,indent=2))
print('DONE',len(annotation),'respiratory annotations',flush=True)
