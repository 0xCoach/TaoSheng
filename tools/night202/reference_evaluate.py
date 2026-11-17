#!/usr/bin/env python3
"""Deterministic bounded evaluation. No phone, no app changes, no external writes."""
import os,time,json,csv,hashlib,platform,subprocess
from pathlib import Path
from collections import Counter
import numpy as np
import soundfile as sf
from scipy import signal
import sherpa_onnx
ROOT=Path(__file__).resolve().parents[1];SR=16000;N=6*SR;SEED=20261117
SCORES=['Snoring','Breathing','Cough','Speech','Throat clearing','Music','Television','Whispering','Sneeze','Rustle','Mechanical fan','Air conditioning','Rain','Vehicle']
def writecsv(path,rows):
    if not rows:return
    with (ROOT/path).open('w') as f:
        w=csv.DictWriter(f,list(rows[0]));w.writeheader();w.writerows(rows)
def rms(x):return float(np.sqrt(np.mean(np.asarray(x,dtype=np.float64)**2)+1e-15))
def db(x):return 20*np.log10(rms(x))
def norm(x,level):return (x*(10**(level/20)/rms(x))).astype(np.float32)
def load(r):
    x,s=sf.read(ROOT/r['path'],dtype='float32',always_2d=True);x=x.mean(axis=1)
    if s!=SR:
        from math import gcd
        g=gcd(s,SR);x=signal.resample_poly(x,SR//g,s//g)
    return x.astype(np.float32)
def fit(x,n=N,offset=0):
    x=x[offset:offset+n];return np.pad(x,(0,max(0,n-len(x))))[:n].astype(np.float32)
def room(x,tv=False):
    sos=signal.butter(3,[300,5000] if tv else [80,4000],btype='bandpass',fs=SR,output='sos')
    x=signal.sosfilt(sos,x).astype(np.float32);y=x.copy()
    for delay,gain in [(0.037,.3),(.083,.18),(.149,.1),(.223,.05)]:
        k=int(delay*SR);y[k:]+=gain*x[:-k]
    return y

def features(x,context):
    # Cheap framing / FFT only; no model is called here.
    v=np.fft.rfft(x);p=np.abs(v)**2;f=np.fft.rfftfreq(len(x),1/SR)
    low=float(p[(f>=80)&(f<800)].sum()/(p[(f>=80)&(f<6000)].sum()+1e-15))
    e=np.sqrt(np.mean(context[:len(context)//320*320].reshape(-1,320)**2,axis=1)+1e-15)
    cv=float(e.std()/(e.mean()+1e-12));e=e-e.mean();ac=[]
    for lag in range(100,min(301,len(e)-20),5):
        a,b=e[:-lag],e[lag:];ac.append(float(np.dot(a,b)/(np.linalg.norm(a)*np.linalg.norm(b)+1e-12)))
    return dict(dbfs=db(x),low_fraction=low,envelope_cv=cv,periodicity=max(ac,default=0))
def gate(f,baseline):
    excess=f['dbfs']-baseline
    return excess>=6 or (excess>=3 and f['low_fraction']>=.45 and f['envelope_cv']>=.2 and f['periodicity']>=.25)

def main():
    started=time.time();rng=np.random.default_rng(SEED)
    manifest=list(csv.DictReader((ROOT/'results/manifest.csv').open()));assert all(r['status']=='ok' for r in manifest)
    source={r['id']:load(r) for r in manifest};bycat={}
    for r in manifest:bycat.setdefault(r['category'],[]).append(r)
    labels=list(csv.DictReader((ROOT/'model/class_labels_indices.csv').open()));names=[r['display_name'] for r in labels]
    cfg=sherpa_onnx.AudioTaggingConfig(model=sherpa_onnx.AudioTaggingModelConfig(zipformer=sherpa_onnx.OfflineZipformerAudioTaggingModelConfig(model=str(ROOT/'model/model.int8.onnx')),num_threads=2,provider='cpu'),labels=str(ROOT/'model/class_labels_indices.csv'),top_k=527)
    t=time.perf_counter();tagger=sherpa_onnx.AudioTagging(cfg);loadsecs=time.perf_counter()-t
    allscores=[];rows=[];calls=0
    def infer(x,meta,ctx,base=-42,rolling=None):
        nonlocal calls
        f=features(x,ctx);t=time.perf_counter();s=tagger.create_stream();s.accept_waveform(SR,x);events=tagger.compute(s,527);elapsed=time.perf_counter()-t
        probs={e.name:float(e.prob) for e in events};assert len(probs)==527 and all(np.isfinite(v) and 0<=v<=1 for v in probs.values())
        a=np.array([probs[n] for n in names],dtype=np.float32);allscores.append(a)
        top=sorted(probs,key=probs.get,reverse=True)[:5]
        row=dict(index=len(rows),**meta,**f,baseline_db=base,gate_fixed=int(gate(f,-48)),gate_calibrated=int(gate(f,base)),gate_rolling=int(gate(f,base if rolling is None else rolling)),inference_sec=elapsed,top5=' | '.join(f'{n}:{probs[n]:.4f}' for n in top))
        row.update({k:probs.get(k,0.) for k in SCORES});rows.append(row);calls+=1
        if calls%250==0:print('inferences',calls,'elapsed',round(time.time()-started,1),flush=True)
    # Same background identity and crop for every SNR copy of an utterance.
    recipe=[]
    bgcats=['fan','air_conditioner','traffic','rain']
    for r in manifest:
        category=r['category'];positive=category in ['snoring','coughing','speech']
        if positive or category in ['breathing','sneezing']:
            variants=['clean',20,10,5,0] if positive else ['clean']
            x=fit(source[r['id']]);x=norm(room(x),-42 if category=='speech' else -36) # includes -6 dB distance attenuation
            options=[a for a in bycat[bgcats[len(recipe)%4]] if a['split']==r['split']] or bycat[bgcats[len(recipe)%4]]
            b=options[int(rng.integers(len(options)))];raw=source[b['id']];off=int(rng.integers(max(1,len(raw)-N)));noise=fit(np.tile(raw,1+N//len(raw)),offset=min(off,max(0,len(raw)-N)))
            for snr in variants:
                mix=x if snr=='clean' else x+noise*(rms(x)/(rms(noise)*10**(snr/20)))
                # Uniform gain only if overload; realized SNR is preserved.
                peak=float(np.max(np.abs(mix)));mix=(mix/max(1.,peak/.98)).astype(np.float32)
                cid=f"{r['id']}:{snr}";recipe.append(dict(clip_id=cid,category=category,snr=snr,background=b['id'],background_offset=off,foreground_dbfs=db(x),split=r['split']))
                ctx=np.concatenate([norm(fit(np.tile(raw,1+N//len(raw))),-42),mix])
                for w in [1,2,3]:
                    for pos in range(0,N,w*SR):
                        infer(mix[pos:pos+w*SR],dict(kind='clip',clip_id=cid,source_id=r['id'],category=category,split=r['split'],snr=str(snr),window=w,start=pos/SR,background=b['id']),ctx)
            if category=='speech':
                tv=norm(room(fit(source[r['id']]),tv=True),-42);ctx=np.concatenate([np.zeros(N,dtype=np.float32),tv])
                for w in [1,2,3]:
                    for pos in range(0,N,w*SR):infer(tv[pos:pos+w*SR],dict(kind='clip',clip_id=r['id']+':tv',source_id=r['id'],category='tv_speech',split=r['split'],snr='clean',window=w,start=pos/SR,background='none'),ctx)
        else:
            raw=source[r['id']];count=min(5,max(1,len(raw)//N));offsets=np.linspace(0,max(0,len(raw)-N),count).astype(int)
            for j,off in enumerate(offsets):
                x=norm(fit(raw,offset=off),-38);ctx=np.concatenate([norm(fit(raw),-42),x])
                for w in [1,2,3]:
                    for pos in range(0,N,w*SR):infer(x[pos:pos+w*SR],dict(kind='clip',clip_id=f"{r['id']}:{j}",source_id=r['id'],category=category,split=r['split'],snr='clean',window=w,start=pos/SR,background='none'),ctx)
    writecsv('results/mixing_manifest.csv',recipe)
    print('POSITIVE/CONTROL DONE',calls,flush=True)
    # A one-hour background program. Crossfaded real recordings repeated to a fixed schedule.
    # 0-10min fan, 10-20 HVAC, 20-30 traffic, 30-40 rain, 40-50 sporadic bed rustle, 50-60 mixture.
    hour=np.zeros(3600*SR,dtype=np.float32);nightrecipe=[]
    for block,cat in enumerate(['fan','air_conditioner','traffic','rain','bed_rustle','mixed']):
        dest=hour[block*600*SR:(block+1)*600*SR];cats=[cat] if cat!='mixed' else ['fan','air_conditioner','traffic','rain']
        for ci,c in enumerate(cats):
            pool=[r for r in bycat[c] if r['split']=='validation'] or bycat[c]
            pos=0;iteration=0
            if c=='bed_rustle':
                fan=source[bycat['fan'][0]['id']];dest[:]=norm(np.resize(fan,len(dest)),-44)
            while pos<len(dest):
                r=pool[iteration%len(pool)];raw=source[r['id']];chunk=raw.copy();fade=min(320,len(chunk)//2)
                chunk[:fade]*=np.linspace(0,1,fade);chunk[-fade:]*=np.linspace(1,0,fade)
                length=min(len(chunk),len(dest)-pos);level={'fan':-42,'air_conditioner':-38,'traffic':-36,'rain':-33,'bed_rustle':-34}.get(c,-40)
                if cat=='mixed':level-=6
                dest[pos:pos+length]+=norm(chunk,level)[:length]
                nightrecipe.append(dict(block=cat,source_id=r['id'],start=block*600+pos/SR,duration=length/SR,level_dbfs=level))
                pos+=len(chunk)+(40*SR if c=='bed_rustle' else 0);iteration+=1
    # First ten minutes calibrate baseline. At each step rolling uses only preceding samples.
    frame_db=np.array([db(hour[i:i+SR]) for i in range(0,len(hour),SR)]);baseline=float(np.percentile(frame_db[:600],20))
    for w in [1,2,3]:
        for pos in range(0,len(hour),w*SR):
            sec=pos//SR;cat=['fan','air_conditioner','traffic','rain','bed_rustle','mixed'][min(sec//600,5)]
            ctx=fit(hour[max(0,pos-12*SR):pos+w*SR],12*SR) if pos<12*SR else hour[pos-12*SR:pos+w*SR]
            rolling=baseline if sec<600 else float(np.percentile(frame_db[max(0,sec-120):sec],20))
            infer(hour[pos:pos+w*SR],dict(kind='night',clip_id='night',source_id=cat,category=cat,split='background',snr='none',window=w,start=sec,background=cat),ctx,base=baseline,rolling=rolling)
    writecsv('results/night_manifest.csv',nightrecipe)
    writecsv('results/windows.csv',rows);np.savez_compressed(ROOT/'results/all_scores.npz',scores=np.stack(allscores),labels=np.array(names))
    metadata=dict(seed=SEED,sample_rate=SR,threads=2,top_k=527,sherpa_onnx=sherpa_onnx.__version__,numpy=np.__version__,platform=platform.platform(),machine=platform.machine(),cpu=subprocess.run(['sysctl','-n','machdep.cpu.brand_string'],capture_output=True,text=True).stdout.strip(),model_load_sec=loadsecs,wall_sec=time.time()-started,total_calls=calls,baseline_dbfs=baseline,score_labels=SCORES,missing_labels=[s for s in SCORES if s not in names],started_unix=started)
    (ROOT/'results/run.json').write_text(json.dumps(metadata,indent=2));print(json.dumps(metadata,indent=2),flush=True)
if __name__=='__main__':main()
