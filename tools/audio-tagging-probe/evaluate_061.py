#!/usr/bin/env python3
"""离线推理，保存527类完整分数；同一窗口只推理一次，策略时间按逻辑调用求和。"""
import argparse,csv,hashlib,json,pathlib,time,math,subprocess
import numpy as np,soundfile as sf,sherpa_onnx
from scipy.signal import resample_poly
P=argparse.ArgumentParser();P.add_argument('--root',type=pathlib.Path,default=pathlib.Path.home()/'Datasets/taosheng-audio');P.add_argument('--models',type=pathlib.Path,default=pathlib.Path.home()/'.claude/jobs/3b8dcfaa/tmp/at');a=P.parse_args();R=a.root
manifest=json.loads((R/'manifest.json').read_text());out=R/'results';out.mkdir(exist_ok=True)
def read(p):
 try:x,s=sf.read(p,dtype='float32',always_2d=True);x=x.mean(axis=1)
 except Exception:x=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',p,'-f','f32le','-ac','1','-ar','16000','-']),dtype=np.float32);s=16000
 if s!=16000:g=math.gcd(s,16000);x=resample_poly(x,16000//g,s//g)
 return x.astype(np.float32)
features={}
for r in manifest:
 x=read(r['path']);frames=x[:len(x)//1600*1600].reshape(-1,1600);rms=np.sqrt(np.mean(frames*frames,axis=1)+1e-12);ps=np.abs(np.fft.rfft(x))**2;f=np.fft.rfftfreq(len(x),1/16000);den=ps.sum()+1e-12
 features[r['id']]={'low_ratio':float(ps[f<300].sum()/den),'high_ratio':float(ps[f>5000].sum()/den),'rms_cv':float(rms.std()/(rms.mean()+1e-12)),'active_ratio':float(np.mean(rms>max(.001,float(rms.max())*.1)))}
(out/'features.json').write_text(json.dumps(features))
for name,folder in [('zipformer','sherpa-onnx-zipformer-small-audio-tagging-2024-04-15'),('ced-small','sherpa-onnx-ced-small-audio-tagging-2024-04-19'),('ced-mini','sherpa-onnx-ced-mini-audio-tagging-2024-04-19')]:
 dest=out/(name+'.csv')
 if dest.exists():
  provenance=json.loads((out/(name+'-provenance.json')).read_text())
  assert provenance['manifest_sha256']==hashlib.sha256((R/'manifest.json').read_bytes()).hexdigest(), 'manifest changed; use a separate result directory'
  assert provenance['model_sha256']==hashlib.sha256((a.models/folder/'model.int8.onnx').read_bytes()).hexdigest(), 'model changed'
  print('already complete',name,flush=True);continue
 md=a.models/folder;labels=list(csv.DictReader(open(md/'class_labels_indices.csv')));names=[v['display_name'] for v in labels]
 model={'ced':str(md/'model.int8.onnx')} if name.startswith('ced') else {'zipformer':sherpa_onnx.OfflineZipformerAudioTaggingModelConfig(model=str(md/'model.int8.onnx'))}
 tagger=sherpa_onnx.AudioTagging(sherpa_onnx.AudioTaggingConfig(model=sherpa_onnx.AudioTaggingModelConfig(**model,num_threads=2),labels=str(md/'class_labels_indices.csv'),top_k=527))
 stream=tagger.create_stream();stream.accept_waveform(16000,np.zeros(160000,dtype=np.float32));tagger.compute(stream) # excluded warm-up
 t0=time.perf_counter();partial=dest.with_suffix('.csv.part')
 with open(partial,'w') as fp:
  writer=csv.DictWriter(fp,fieldnames=['id','strategy','seconds','inference_seconds','calls']+names);writer.writeheader()
  for i,r in enumerate(manifest):
   x=read(r['path']);n=len(x);w=160000
   starts=list(range(0,max(n-w,0)+1,80000));starts=sorted(set(starts+[max(0,n-w)]))
   windows={'sliding':[(s,min(n,s+w)) for s in starts],'three':[(s,min(n,s+w)) for s in sorted(set([0,max(0,(n-w)//2),max(0,n-w)]))]}
   if name=='zipformer':windows['whole']=[(0,n)]
   cache={}
   for strategy,bounds in windows.items():
    for b in bounds:
     if b in cache:continue
     chunk=x[b[0]:b[1]]
     if len(chunk)<16000:chunk=np.pad(chunk,(0,16000-len(chunk)))
     t=time.perf_counter();s=tagger.create_stream();s.accept_waveform(16000,chunk);events=tagger.compute(s);elapsed=time.perf_counter()-t
     scores=np.zeros(527);assert len(events)==527
     for e in events:scores[e.index]=e.prob
     assert np.isfinite(scores).all();cache[b]=(scores,elapsed)
    best=np.max([cache[b][0] for b in bounds],axis=0)
    writer.writerow(dict(id=r['id'],strategy=strategy,seconds=n/16000,inference_seconds=sum(cache[b][1] for b in bounds),calls=len(bounds),**{k:float(v) for k,v in zip(names,best)}))
   if (i+1)%100==0:fp.flush();print(name,i+1,'/',len(manifest),'elapsed',round(time.perf_counter()-t0),flush=True)
 partial.replace(dest)
 (out/(name+'-provenance.json')).write_text(json.dumps({'model':str(md),'model_sha256':hashlib.sha256((md/'model.int8.onnx').read_bytes()).hexdigest(),'sherpa_onnx':sherpa_onnx.__version__,'threads':2,'wall_seconds':time.perf_counter()-t0,'manifest_sha256':hashlib.sha256((R/'manifest.json').read_bytes()).hexdigest()},indent=2))
 print('completed',name,round(time.perf_counter()-t0),flush=True)
