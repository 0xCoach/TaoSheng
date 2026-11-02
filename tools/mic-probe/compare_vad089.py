# Synthetic fixtures only; run with uv run --with onnxruntime --with numpy.
import json,numpy as np,onnxruntime as ort
from pathlib import Path
root=Path(__file__).resolve().parents[2]
opt=ort.SessionOptions();opt.intra_op_num_threads=1;opt.inter_op_num_threads=1
session=ort.InferenceSession(str(root/'app/src/main/assets/silero_vad.onnx'),sess_options=opt,providers=['CPUExecutionProvider'])
def run(x,gate,threshold,boost):
 state=np.zeros((2,1,128),np.float32);history=np.zeros(64,np.float32);voiced=0;ran=0
 for i in range(0,len(x)-511,512):
  frame=x[i:i+512].astype(np.float32)/32768
  rms=float(np.sqrt(np.mean(frame**2)))
  gain=min(8,max(1,.02/max(rms,1e-9))) if boost else 1
  feed=np.concatenate([history,np.clip(frame*gain,-1,1)]).reshape(1,576)
  if rms<gate:state.fill(0);prob=0
  else:
   prob,state=session.run(None,{'input':feed,'state':state,'sr':np.array(16000,np.int64)});prob=float(prob[0][0]);ran+=1
  history=feed[0,512:].copy()
  voiced+=int(prob>=threshold)
 return {'speech_seconds':round(voiced*512/16000,3),'inferred_frames':ran}
results=[]
for name in ['speech','noise','silence']:
 x=np.fromfile(root/f'tools/voiceprint-probe/fixtures/{name}.pcm',dtype='<i2')
 for scale in ([1,.1,.03,.01] if name=='speech' else [1,.01]):
  y=np.clip(x.astype(np.float64)*scale,-32768,32767).astype(np.int16)
  results.append({'sample':name,'scale':scale,'rms':round(float(np.sqrt(np.mean((y.astype(float)/32768)**2))),7),'seconds':len(x)/16000,'old':run(y,.002,.5,False),'lower_gate':run(y,.0005,.4,False),'sensitive':run(y,.0005,.4,True)})
quiet = next(r for r in results if r['sample']=='speech' and r['scale']==.01)
assert quiet['old']['speech_seconds'] == 0
assert quiet['sensitive']['speech_seconds'] >= .4
assert all(r['sensitive']['speech_seconds']==0 for r in results if r['sample'] in ['noise','silence'])
print(json.dumps(results,indent=2))
