#!/usr/bin/env python3
"""离线诊断；所有逐条结果与向量只写 private，不读取转写正文、不使用网络。"""
from pathlib import Path
import hashlib, json, sqlite3, shutil, tempfile, struct, subprocess
from collections import Counter
import numpy as np
import onnxruntime as ort
ROOT=Path(__file__).resolve().parents[2]
PRIVATE=Path(__file__).resolve().parent/'private'
def unit(x): return x/np.linalg.norm(x,axis=-1,keepdims=True)
def summary(x):
    x=list(x)
    return dict(n=len(x),q=[round(float(v),4) for v in np.quantile(x,[0,.1,.5,.9,1])]) if x else dict(n=0,q=[])
def trim(x):
    frames=[x[i:i+320] for i in range(0,len(x),320)]
    frames=[a for a in frames if np.sqrt(np.mean((a.astype(float)/32768)**2))>=.002]
    return np.concatenate(frames) if frames else np.empty(0,dtype=np.int16)
# 与 KaldiFbank.kt 相同的反射补边、Povey、去 DC、预加重、mel 和全段 CMN。
def fbank(x,normalize=True):
    n=(len(x)+80)//160
    ix=np.arange(n)[:,None]*160-120+np.arange(400)[None,:]
    ix=np.where(ix<0,-ix-1,ix);ix=np.where(ix>=len(x),2*len(x)-1-ix,ix)
    a=x[ix].astype(float)/32768;a-=a.mean(axis=1,keepdims=True)
    a[:,1:]-=.97*a[:,:-1].copy();a[:,0]*=.03
    a*=(.5-.5*np.cos(2*np.pi*np.arange(400)/399))**.85
    power=np.abs(np.fft.rfft(a,n=512))**2
    mel=lambda hz:1127*np.log(1+hz/700)
    edges=np.linspace(mel(20),mel(7600),82);freq=mel(np.arange(257)*16000/512)
    filters=np.maximum(0,np.minimum((freq[None,:]-edges[:-2,None])/(edges[1:-1]-edges[:-2])[:,None],(edges[2:,None]-freq[None,:])/(edges[2:]-edges[1:-1])[:,None]))
    f=np.log(np.maximum(power@filters.T,1.1920928955078125e-7)).astype(np.float32)
    if normalize: f=(f-f.mean(axis=0,dtype=np.float64)).astype(np.float32)
    return f

def load():
    # WAL 一并复制后只读，避免修改交付的 DB/SHM。
    with tempfile.TemporaryDirectory(dir=PRIVATE) as td:
        for suffix in ['', '-wal']:
            p=PRIVATE/('capture.db'+suffix)
            if p.exists(): shutil.copyfile(p,Path(td)/p.name)
        c=sqlite3.connect('file:'+str(Path(td)/'capture.db')+'?mode=ro',uri=True);c.row_factory=sqlite3.Row;c.execute('pragma query_only=on')
        seg=[dict(r) for r in c.execute('select * from segments')]
        lines=[dict(r) for r in c.execute('select id,segmentId,startAt,endAt,speakerTag,speakerName,speakerManual,speakerPersonId,speakerScore from transcript_lines order by startAt,id')]
        samples=[dict(r) for r in c.execute('select * from voiceprint_samples')]
        exclusions=[dict(r) for r in c.execute('select * from voiceprint_exclusions')];c.close()
    for s in samples:s['v']=unit(np.frombuffer(s.pop('vector'),'<f4').copy())
    groups=[]
    for s in seg:
        by={}
        for l in lines:
            if l['segmentId']==s['id']:
                key='tag:'+l['speakerTag'] if l['speakerTag'] is not None else 'line:'+str(l['id'])
                by.setdefault(key,[]).append(l)
        for key,ls in by.items():
            identity=f"campplus-v1|{s['id']}|{s['path']}|{s['startAt']}|{s['endAt']}"+''.join(f"|{l['id']}:{l['startAt']}:{l['endAt']}:{key}" for l in ls)
            h=hashlib.sha256(identity.encode()).hexdigest();p=PRIVATE/'features'/h
            g=dict(s=s,key=key,lines=ls,hash=h,v=None,duration=0)
            if p.exists():
                b=p.read_bytes();ver,duration,has=struct.unpack('>iq?',b[:13]);assert ver==1
                assert len(b)==13+(768 if has else 0)
                g.update(v=unit(np.frombuffer(b[13:],'>f4').astype(np.float32)) if has else None,duration=duration)
            g['label']=next((int(a['personId']==0) for a in samples if a['source']!='auto' and a['segmentId']==s['id'] and a['groupKey']==key),None)
            if any(e['segmentId']==s['id'] and e['groupKey']==key for e in exclusions):g['label']=0
            groups.append(g)
    return seg,lines,samples,groups,exclusions

def main():
    originals=[PRIVATE/'capture.db',PRIVATE/'capture.db-wal']+list((PRIVATE/'features').glob('*'))+list((PRIVATE/'segments').rglob('*.ogg'))
    digest=lambda:{str(p.relative_to(PRIVATE)):hashlib.sha256(p.read_bytes()).hexdigest() for p in originals if p.is_file()}
    before=digest()
    seg,lines,samples,groups,exclusions=load()
    opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(ROOT/'app/src/main/assets/voiceprint/campplus.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
    def embed(x):return unit(session.run(None,{'x':fbank(x)[None]})[0][0])
    parity=[]
    for p in (ROOT/'tools/voiceprint-probe/fixtures').glob('*.pcm'):
        a=fbank(np.fromfile(p,'<i2'),False);b=np.fromfile(p.with_suffix('.fbank'),'<f4').reshape(-1,80)
        parity.append(float(np.max(np.abs(a-b))))
    assert max(parity)<.002,parity
    out=PRIVATE/'windows';out.mkdir(exist_ok=True)
    paths={p.name:p for p in (PRIVATE/'segments').rglob('*.ogg')}
    computed=0
    for idx,s in enumerate(seg):
        gs=[g for g in groups if g['s']['id']==s['id']]
        p=paths.get(Path(s['path']).name)
        if not p or not gs:continue
        pcm=None
        for g in gs:
            dest=out/(g['hash']+'.npz')
            if not dest.exists():
                if pcm is None:pcm=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-ar','16000','-ac','1','-f','s16le','-'],stderr=subprocess.DEVNULL),'<i2')
                ranges=sorted((max(0,min(len(pcm),(l['startAt']-s['startAt'])*16)),max(0,min(len(pcm),(l['endAt']-s['startAt'])*16))) for l in g['lines'])
                merged=[]
                for a,b in ranges:
                    if b<=a:continue
                    if merged and a<=merged[-1][1]:merged[-1]=(merged[-1][0],max(b,merged[-1][1]))
                    else:merged.append((a,b))
                vectors=[];bounds=[];duration=[];rms=[];parts=[]
                for a,b in merged:
                    parts.append(trim(pcm[a:b]))
                    # 连续 3s 原始窗口；不足 1.5s 的尾窗不合并跨说话人。
                    for start in range(a,b,48000):
                        end=min(start+48000,b);raw=pcm[start:end];speech=trim(raw)
                        if len(speech)<24000:continue
                        vectors.append(embed(speech));bounds.append([s['startAt']+start//16,s['startAt']+end//16]);duration.append(len(speech)/16);rms.append(float(np.sqrt(np.mean((raw.astype(float)/32768)**2))))
                whole=np.concatenate(parts) if parts else np.array([],dtype=np.int16)
                np.savez_compressed(dest,v=np.array(vectors).reshape(-1,192),bounds=np.array(bounds).reshape(-1,2),duration=duration,rms=rms,whole=embed(whole) if len(whole)>=24000 else np.array([]),wholeDuration=len(whole)/16)
                computed+=1
            d=np.load(dest);g['windows']=d['v'];g['bounds']=d['bounds'];g['wd']=d['duration'];g['rms']=d['rms'];g['whole']=d['whole'];g['wholeDuration']=float(d['wholeDuration'])
        if idx%40==0:print(json.dumps(dict(progress_segments=idx,new_groups=computed)),flush=True)
    report=dict(counts=dict(segments=len(seg),lines=len(lines),groups=len(groups),samples=len(samples),manual_me=sum(a['source']=='line' and a['personId']==0 for a in samples),auto=sum(a['source']=='auto' for a in samples),manual_other=sum(a['personId']!=0 for a in samples),enroll=sum(a['source']=='enroll' for a in samples),exclusions=len(exclusions),null_tag_lines=sum(l['speakerTag'] is None for l in lines),cache_vectors=sum(g['v'] is not None for g in groups),audio_groups=sum('windows'in g for g in groups),windows=sum(len(g.get('windows',[])) for g in groups)),fbank_max_error=max(parity))
    me=[s for s in samples if s['personId']==0];manual=[s for s in me if s['source']!='auto']
    center=unit(np.mean([s['v'] for s in manual],axis=0))
    for source in ['line','auto']:
        own=[s for s in me if s['source']==source];vs=np.array([s['v'] for s in own]);pairs=vs@vs.T
        report[source]=dict(center=summary(vs@center),pairs=summary(pairs[np.triu_indices(len(vs),1)]),below_045=int(np.sum(vs@center<.45)),below_065=int(np.sum(vs@center<.65)),seconds=summary(s['durationMillis']/1000 for s in own))
    # 时间分批仅检验关联，不把 line 的时间当朗读音源切换时间。
    ordered=sorted(manual,key=lambda s:s['createdAt']);half=len(ordered)//2
    report['manual_halves']=[summary(s['v']@center for s in batch) for batch in [ordered[:half],ordered[half:]]]
    valid=[g for g in groups if len(g.get('windows',[]))>=2]
    dispersion=[float(np.median((g['windows']@g['windows'].T)[np.triu_indices(len(g['windows']),1)])) for g in valid]
    report['dispersion']=dict(groups=len(valid),pair_median=summary(dispersion),below_045=sum(v<.45 for v in dispersion),below_065=sum(v<.65 for v in dispersion),null_tag_groups=sum(g['lines'][0]['speakerTag'] is None for g in valid))
    report['cache_recompute_cosine']=summary(g['v']@g['whole'] for g in groups if g['v'] is not None and len(g.get('whole',[])))
    # leave-one-segment-out；保留手动正例/负例，自动结果不能充当地面真值。
    rows=[]
    for g in groups:
        if g['label'] is None or g['v'] is None:continue
        train=[s for s in manual if s['segmentId']!=g['s']['id']];alltrain=[s for s in me if s['segmentId']!=g['s']['id']]
        v=g['v'];mat=np.array([s['v'] for s in train]);c=unit(mat.mean(axis=0));sim=np.maximum(0,mat@v)
        robust=mat[mat@c>=.45];rc=unit(robust.mean(axis=0))
        scores=dict(old=float(v@unit(np.mean([s['v'] for s in alltrain],axis=0))),manual_center=float(v@c),robust_center=float(v@rc),top3=float(np.sort(sim)[-3:].mean()),maximum=float(sim.max()),robust_top3=float(np.sort(np.maximum(0,robust@v))[-3:].mean()))
        w=g.get('windows',[])
        if len(w):
            ws=np.maximum(0,w@rc)
            # 按句重叠加权投票，在评估中按组内有效窗时长投票。
            scores['window_center']=float(np.average(ws,weights=g['wd']))
            scores['window_vote']=float(np.sum(g['wd'][ws>=.65])/np.sum(g['wd']))
            wt=np.sort(np.maximum(0,w@mat.T),axis=1)[:,-3:].mean(axis=1)
            scores['window_top3']=float(np.average(wt,weights=g['wd']))
            for threshold in [.45,.5,.55,.6,.65]:
                scores['vote_top3_'+str(threshold)]=float(np.sum(g['wd'][wt>=threshold])/np.sum(g['wd']))
        rows.append(dict(label=g['label'],scores=scores,segment=g['s']['id'],duration=g['duration']/1000,playback=g['s']['playbackMillis']>0))
    def metrics(rs,method,t):
        pos=[r['scores'][method]>=t for r in rs if r['label']==1 and method in r['scores']];neg=[r['scores'][method]>=t for r in rs if r['label']==0 and method in r['scores']]
        return dict(positive=len(pos),negative=len(neg),tp=int(sum(pos)),fp=int(sum(neg)),recall=round(sum(pos)/len(pos),4) if pos else None,fpr=round(sum(neg)/len(neg),4) if neg else None)
    report['methods']={k:dict(positive=summary(r['scores'][k] for r in rows if r['label']==1 and k in r['scores']),negative=summary(r['scores'][k] for r in rows if r['label']==0 and k in r['scores']),at065=metrics(rows,k,.75 if 'vote' in k else .65)) for k in sorted(set(k for r in rows for k in r['scores']))}
    report['scan']={k:[dict(threshold=round(float(t),2),**metrics(rows,k,t)) for t in np.arange(.4,.851,.05)] for k in report['methods'] if 'vote' not in k}
    retained=[r for r in rows if 'window_center' in r['scores']]
    report['retained_methods']={k:metrics(retained,k,.75 if 'vote' in k else .65) for k in report['methods']}
    # 分段确定性划分：每类约 2/3 调参，1/3 留出；每次仍排除待测段。
    ids=sorted(set(r['segment'] for r in rows if r['label']==1));negids=sorted(set(r['segment'] for r in rows if r['label']==0))
    held=set(ids[::3]+negids[::3]);dev=[r for r in rows if r['segment'] not in held];test=[r for r in rows if r['segment'] in held]
    report['split']={k:dict(dev=metrics(dev,k,.5),heldout=metrics(test,k,.5)) for k in ['old','top3','robust_top3']}
    report['short']=[dict(range=[a,b],**metrics([r for r in rows if a<=r['duration']<b],'old',.65)) for a,b in [(0,1.5),(1.5,3),(3,6),(6,10000)]]
    report['playback']=dict(segments=sum(s['playbackMillis']>0 for s in seg),groups=sum(g['s']['playbackMillis']>0 for g in groups),labelled=sum(r['playback'] for r in rows),scores=summary(g['v']@center for g in groups if g['s']['playbackMillis']>0 and g['v'] is not None))
    report['rms']=summary(v for g in groups for v in g.get('rms',[]))
    assert before==digest(),'输入发生变化，停止'
    (PRIVATE/'input-hashes.json').write_text(json.dumps(before,indent=2))
    (PRIVATE/'evaluation.json').write_text(json.dumps(rows,indent=2))
    (PRIVATE/'summary.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
