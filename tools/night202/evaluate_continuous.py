"""连续来源分组后调参；留出组只汇总一次。与 NightFrames/NightRhythm/NightDetector 同定义。"""
import csv,json,time,sys
from pathlib import Path
import numpy as np
import sherpa_onnx
sys.path.insert(0,str(Path(__file__).parent))
import reference_evaluate as r
r.ROOT=Path('/tmp/taosheng-202-night-eval')
ROOT=Path.home()/'Developer/TaoSheng-research/snore-eval';OUT=Path('docs/任务卡/202-证据')
sources=json.loads((OUT/'continuous-sources.json').read_text())
manifest=list(csv.DictReader((ROOT/'results/manifest.csv').open()))
raw={a['id']:r.load(a) for a in manifest if (r.ROOT/a['path']).exists()}
bycat={}
for a in manifest:
    if a['id'] in raw:bycat.setdefault(a['category'],[]).append(a)
config=sherpa_onnx.AudioTaggingConfig(model=sherpa_onnx.AudioTaggingModelConfig(zipformer=sherpa_onnx.OfflineZipformerAudioTaggingModelConfig(model=str(r.ROOT/'model/model.int8.onnx')),num_threads=2,provider='cpu'),labels=str(r.ROOT/'model/class_labels_indices.csv'),top_k=527)
model=sherpa_onnx.AudioTagging(config)
def feature(e):
    a=np.asarray(e[-60:]);m=float(a.mean());cv=float(a.std()/(m+1e-12));best=0.
    if len(a)>=12:
        c=a-m
        for lag in range(4,min(12,len(c)//2)+1):
            aa=float(c[:-lag]@c[:-lag]);bb=float(c[lag:]@c[lag:])
            if aa>1e-15 and bb>1e-15:best=max(best,float(c[:-lag]@c[lag:])/(aa*bb)**.5)
    return best,cv
rows=[]
def analyze(x,key,split,snr,category,baseline=None):
    history=[] if baseline is None else [baseline]*300;env=[];start=time.monotonic();n=0
    for off in range(0,len(x)-31999,32000):
        w=x[off:off+32000];db=int(np.clip(np.floor(r.db(w)+.5),-120,0));bg=int(np.median(history)) if history else db
        env.extend(r.rms(w[j:j+8000]) for j in range(0,32000,8000));env=env[-60:];cor,mod=feature(env)
        stream=model.create_stream();stream.accept_waveform(16000,w.astype(np.float32));scores={v.name:float(v.prob) for v in model.compute(stream,527)}
        rows.append(dict(key=key,split=split,snr=snr,category=category,at=off/16000,snore=scores['Snoring'],cough=scores['Cough'],rhythm=cor,modulation=mod,above=db-bg,db=db,bg=bg))
        history.append(db);history=history[-300:];n+=1
    print(key,snr,n,round(time.monotonic()-start,2),flush=True)
# 独立来源分组，最多前 120 秒；不重复或循环正样本。
source_durations=[]
for src in sources:
    if 'error' in src:continue
    x=r.load(dict(path=src['path']))[:120*16000]
    source_durations.append(dict(id=src['id'],seconds=len(x)/16000,split=src['split']))
    x=r.norm(r.room(x),-36)
    for i,snr in enumerate([20,10,5,0]):
        category=['fan','air_conditioner','traffic','rain'][i]
        pool=bycat[category];noise=raw[pool[0]['id']];noise=np.resize(noise,len(x));scaled=noise*(r.rms(x)/(r.rms(noise)*10**(snr/20)))
        mix=x+scaled;gain=max(1.,float(np.max(np.abs(mix)))/.98);mix/=gain
        analyze(mix,src['id'],src['split'],snr,'snore',int(np.floor(r.db(scaled/gain)+.5)))
# 与原报告完全同序列/响度/渐变，实际推理 1 小时背景。
hour=np.zeros(3600*16000,dtype=np.float32)
for block,cat in enumerate(['fan','air_conditioner','traffic','rain','bed_rustle','mixed']):
    dest=hour[block*600*16000:(block+1)*600*16000]
    for c in ([cat] if cat!='mixed' else ['fan','air_conditioner','traffic','rain']):
        pool=[v for v in bycat[c] if v['split']=='validation'] or bycat[c]
        if c=='bed_rustle':dest[:]=r.norm(np.resize(raw[bycat['fan'][0]['id']],len(dest)),-44)
        pos=0;j=0
        while pos<len(dest):
            chunk=raw[pool[j%len(pool)]['id']].copy();fade=min(320,len(chunk)//2);chunk[:fade]*=np.linspace(0,1,fade);chunk[-fade:]*=np.linspace(1,0,fade)
            level={'fan':-42,'air_conditioner':-38,'traffic':-36,'rain':-33,'bed_rustle':-34}[c]-(6 if cat=='mixed' else 0)
            n=min(len(chunk),len(dest)-pos);dest[pos:pos+n]+=r.norm(chunk,level)[:n];pos+=len(chunk)+(40*16000 if c=='bed_rustle' else 0);j+=1
analyze(hour,'background','stress',None,'background')
# 呼吸按原报告独立源分组，仍是 5 秒短素材，不宣称是连续粗重呼吸实录。
for a in bycat['breathing']:
    analyze(r.norm(r.room(r.fit(raw[a['id']])),-36),a['id'],a['split'],None,'breathing')
with (OUT/'continuous-windows.csv').open('w') as f:
    w=csv.DictWriter(f,rows[0],lineterminator='\n');w.writeheader();w.writerows(rows)
def events(ws,cor,mod,deferred=False):
    # 粗筛保存前后 2 秒；上下文特征在夜间计算，不把候选片段拼成连续时间轴。
    selected=set(range(len(ws)))
    if deferred:
        selected=set()
        for i,w in enumerate(ws):
            if w['above']>=6:selected.update(j for j in [i-1,i,i+1] if 0<=j<len(ws))
    positive=[];active=None;result=[]
    for i,w in enumerate(ws):
        if i not in selected:continue
        at=w['at'];positive=[p for p in positive if p>=at-28]
        hit=w['snore']>=.1
        if hit:positive.append(at)
        if active is not None and at-active[1]>=30:result.append(active);active=None
        if hit and (active is not None or len(positive)>=3 and w['rhythm']>=cor and w['modulation']>=mod):active=(active[0] if active else positive[0],at+2)
    if active:result.append(active)
    return result,len(selected)*2
keys={}
for v in rows:keys.setdefault((v['key'],v['snr']),[]).append(v)
background=keys[('background',None)]
grid=[]
for cor in [.1,.2,.3,.5,.7]:
    for mod in [.15,.3,.5,.8]:
        positive=[v for v in keys.values() if v[0]['category']=='snore' and v[0]['split']=='tune']
        hits=sum(bool(events(v,cor,mod)[0]) for v in positive);neg=events(background,cor,mod)[0]
        fan=sum(a<600 for a,b in neg);objective=hits/len(positive)-min(len(neg),10)*.15-fan*.3
        grid.append(dict(correlation=cor,modulation=mod,hits=hits,n=len(positive),false_events=len(neg),fan=fan,objective=objective))
best=max(grid,key=lambda p:(p['objective'],p['correlation'],p['modulation']))
(OUT/'continuous-selection.json').write_text(json.dumps(dict(best=best,grid=grid,selection='tune source positives + reused background stress; validation positives not used',sources=source_durations),indent=2))
cor=best['correlation'];mod=best['modulation'];summary=[]
for split in ['tune','validation']:
    for snr in [20,10,5,0]:
        seq=[v for v in keys.values() if v[0]['category']=='snore' and v[0]['split']==split and v[0]['snr']==snr]
        summary.append(dict(split=split,snr=snr,n=len(seq),full_hits=sum(bool(events(v,cor,mod)[0]) for v in seq),deferred_hits=sum(bool(events(v,cor,mod,True)[0]) for v in seq)))
neg,seconds=events(background,cor,mod);deferred,stored=events(background,cor,mod,True)
report=dict(positive=summary,background=[dict(category=c,minutes=10,events=sum(a>=i*600 and a<(i+1)*600 for a,b in neg)) for i,c in enumerate(['fan','air_conditioner','traffic','rain','bed_rustle','mixed'])],background_total=len(neg),deferred_background=len(deferred),candidate_seconds_per_hour=stored,estimated_8h_opus_bytes=stored*8*24000/8,breathing=[dict(split=s,n=sum(v[0]['category']=='breathing' and v[0]['split']==s for v in keys.values()),hits=sum(bool(events(v,cor,mod)[0]) for v in keys.values() if v[0]['category']=='breathing' and v[0]['split']==s)) for s in ['tune','validation']])
(OUT/'continuous-results.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
