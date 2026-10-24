"""对齐生产 Top3/多人差距/逐句重叠投票；只写聚合数字。"""
import json
import numpy as np
from diag import load,PRIVATE,ROOT,unit,summary,trim,fbank
import onnxruntime as ort
seg,lines,samples,groups,_=load()
def bank(person,excluded=None):
    ss=[s for s in samples if s['personId']==person and s['source']!='auto' and s['segmentId']!=excluded]
    if len(ss)<3 or sum(s['durationMillis'] for s in ss)<30000:return None
    return np.array([s['v'] for s in ss])
def score(v,b):return float(np.sort(np.maximum(0,b@v))[-3:].mean())
def match(v,banks,t):
    ranked=sorted([(score(v,b),p) for p,b in banks.items() if b is not None],reverse=True)
    if not ranked or ranked[0][0]<t:return None
    if len(ranked)>1 and ranked[0][0]-ranked[1][0]<.08:return None
    return ranked[0][1]
def metrics(rows,key):
    p=[r for r in rows if r['label']==1];n=[r for r in rows if r['label']==0]
    return dict(positive=len(p),negative=len(n),tp=sum(r[key] for r in p),fp=sum(r[key] for r in n))
rows=[];all_disp=[];auto_disp=[];sample_disp=[];null_disp=[];short={'under15':0,'under3':0,'total':0};label_windows=[]
for g in groups:
    p=PRIVATE/'windows'/(g['hash']+'.npz')
    if not p.exists():continue
    d=np.load(p);w=d['v'];bounds=d['bounds']
    short['total']+=1;short['under15']+=int(d['wholeDuration']<1500);short['under3']+=int(d['wholeDuration']<3000)
    if len(w)>=2:
        pairs=w@w.T;disp=float(np.median(pairs[np.triu_indices(len(w),1)]));all_disp.append(disp)
        own=[s for s in samples if s['segmentId']==g['s']['id'] and s['groupKey']==g['key']]
        if own:
            (auto_disp if own[0]['source']=='auto' else sample_disp).append(disp)
        if g['lines'][0]['speakerTag'] is None:null_disp.append(disp)
    if g['label'] is None:continue
    s=next(s for s in samples if s['source']!='auto' and s['segmentId']==g['s']['id'] and s['groupKey']==g['key'])
    line=next(l for l in g['lines'] if l['id']==s['sourceLineId'])
    banks={person:bank(person,g['s']['id']) for person in set(s['personId'] for s in samples)}
    allme=[s['v'] for s in samples if s['personId']==0 and s['segmentId']!=g['s']['id']]
    oldrank=[]
    if g['v'] is not None:
        for person in set(s['personId'] for s in samples):
            ref=[s for s in samples if s['personId']==person and s['segmentId']!=g['s']['id']]
            if len(ref)>=3 and sum(s['durationMillis'] for s in ref)>=30000:
                oldrank.append((max(0,float(g['v']@unit(np.mean([s['v'] for s in ref],axis=0)))),person))
    oldrank.sort(reverse=True)
    old=bool(oldrank and oldrank[0][1]==0 and oldrank[0][0]>=.65 and (len(oldrank)==1 or oldrank[0][0]-oldrank[1][0]>=.08))
    row=dict(label=g['label'],old=bool(old),seconds=(line['endAt']-line['startAt'])/1000,segment=g['s']['id'])
    for t in [.45,.5,.55,.6,.65]:
        yes=0
        for v,(a,b) in zip(w,bounds):
            overlap=max(0,min(b,line['endAt'])-max(a,line['startAt']))
            if match(v,banks,t)==0:yes+=overlap
        row[str(t)]=bool(yes/max(1,line['endAt']-line['startAt'])>=.75)
    rows.append(row)
    for v,dur in zip(w,d['duration']):label_windows.append(dict(label=g['label'],score=score(v,banks[0]),duration=float(dur)/1000))
report=dict(retained_source_lines={k:metrics(rows,k) for k in ['old']},window_distributions=dict(all=summary(all_disp),auto=summary(auto_disp),manual=summary(sample_disp),null_tag=summary(null_disp)),short=short)
report['retained_source_lines'].update({k:metrics(rows,k) for k in ['0.45','0.5','0.55','0.6','0.65']})
# 按录音段划分调参/留出，留出身份库仍是交叉验证库，不是外部独立测试集。
held=set(sorted(set(r['segment'] for r in rows if r['label']==1))[::3]+sorted(set(r['segment'] for r in rows if r['label']==0))[::3])
report['split']={k:{part:metrics([r for r in rows if (r['segment'] in held)==isheld],k) for part,isheld in [('development',False),('heldout',True)]} for k in ['old','0.45','0.5','0.55']}
report['windows_by_label']={str(label):summary(r['score'] for r in label_windows if r['label']==label) for label in [0,1]}
report['short_window']=[dict(range=[a,b],positive=sum(r['label']==1 and a<=r['duration']<b for r in label_windows),recognized=sum(r['label']==1 and a<=r['duration']<b and r['score']>=.5 for r in label_windows)) for a,b in [(1.5,2),(2,3),(3,4)]]
# 已提交的本地 say 合成夹具，非真实人声；只做一个补充负例。
opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
session=ort.InferenceSession(str(ROOT/'app/src/main/assets/voiceprint/campplus.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
x=trim(np.fromfile(ROOT/'tools/voiceprint-probe/fixtures/speech.pcm','<i2'));v=unit(session.run(None,{'x':fbank(x)[None]})[0][0])
report['synthetic']=dict(count=1,seconds=len(x)/16000,top3=score(v,bank(0)))
play=[g for g in groups if g['s']['playbackMillis']>0 and g['v'] is not None]
report['playback_proxy']=dict(count=len(play),old_positive=sum(float(g['v']@unit(np.mean([s['v'] for s in samples if s['personId']==0],axis=0)))>=.65 for g in play),top3_positive=sum(score(g['v'],bank(0))>=.5 for g in play),scores=summary(score(g['v'],bank(0)) for g in play))
# 原样本按本地日界前后分组，不公开时间或逐条标识。
cutoff=1792771200000 # 2026-10-24 00:00 Asia/Shanghai
manual=[s for s in samples if s['source']=='line' and s['personId']==0];center=unit(np.mean([s['v'] for s in manual],axis=0))
report['before_after']= [summary(s['v']@center for s in manual if (s['createdAt']>=cutoff)==after) for after in [False,True]]
(PRIVATE/'production-evaluation.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
