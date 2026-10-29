#!/usr/bin/env python3
"""只读 061 数据；070 整段/三窗真实推理，输出仅文件名与结论，详细计时留 /tmp。"""
import argparse,csv,json,pathlib,re,time,subprocess,math
import numpy as np,soundfile as sf,sherpa_onnx
from scipy.signal import resample_poly
p=argparse.ArgumentParser();p.add_argument('--model',type=pathlib.Path,required=True);p.add_argument('--data',type=pathlib.Path,default=pathlib.Path.home()/'Datasets/taosheng-audio');p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('/tmp/070-phone.json'));a=p.parse_args()
rules=(pathlib.Path(__file__).resolve().parents[2]/'app/src/main/java/app/resound/envsound/EnvSoundRules.kt').read_text()
def names(key): return re.findall(r'"([^"]+)"',re.search(r'val '+key+r' = listOf\(([^\n]+)\)',rules)[1])
speech=names('speech');human=names('human')
content=[]
for line in rules.splitlines():
 if ' to (' in line: content+=re.findall(r'"([^"]+)"',line)
labels=list(csv.DictReader((a.model/'class_labels_indices.csv').open())); allnames={r['display_name'] for r in labels}
assert set(speech+human+content)<=allnames, set(speech+human+content)-allnames
model=sherpa_onnx.AudioTagging(sherpa_onnx.AudioTaggingConfig(model=sherpa_onnx.AudioTaggingModelConfig(zipformer=sherpa_onnx.OfflineZipformerAudioTaggingModelConfig(model=str(a.model/'model.int8.onnx')),num_threads=2),labels=str(a.model/'class_labels_indices.csv'),top_k=527))
rows=[r for r in json.loads((a.data/'manifest.json').read_text()) if r['split']=='phone'];assert len(rows)==18
out=[]
for row in rows:
 source=pathlib.Path(row['path'])
 try: x,rate=sf.read(source,dtype='float32',always_2d=True);x=x.mean(axis=1)
 except Exception: x=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(source),'-f','f32le','-ac','1','-ar','16000','-']),dtype=np.float32);rate=16000
 if rate!=16000: g=math.gcd(rate,16000);x=resample_poly(x,16000//g,rate//g)
 n=len(x);w=160000;windows=[(0,n)] if n<=480000 else [(s,s+w) for s in sorted(set([0,(n-w)//2,n-w]))]
 scores={};t=time.perf_counter()
 for start,end in windows:
  stream=model.create_stream();chunk=x[start:end];chunk=np.pad(chunk,(0,max(0,16000-len(chunk))));stream.accept_waveform(16000,chunk);events=model.compute(stream)
  assert len(events)==527
  for e in events: scores[e.name]=max(scores.get(e.name,0),e.prob)
 elapsed=time.perf_counter()-t
 S=max(scores[v] for v in speech);H=max(scores[v] for v in human);M=scores['Music']
 verdict='Media' if S>=.05 and M>=.9 else 'Skippable' if S<.2 and H<.05 and max(scores[v] for v in content)>=.2 else 'Keep'
 # manifest path 可能指向准备后的 WAV；保留原始文件名用于交付。
 out.append(dict(file=source.name,verdict=verdict,seconds=n/16000,inference_seconds=elapsed,calls=len(windows)))
a.output.write_text(json.dumps(dict(sherpa=sherpa_onnx.__version__,rows=out),ensure_ascii=False,indent=2))
for r in out: print(r['file'],r['verdict'])
