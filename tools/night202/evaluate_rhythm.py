"""原评测清单/分组/混音复用；不调整留出集门槛。原样移植 NightRhythm。"""
import sys, csv, json
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).parent))
import reference_evaluate as ref
ROOT=Path.home() / 'Developer/TaoSheng-research/snore-eval'
ref.ROOT=Path('/tmp/taosheng-202-night-eval')
OUT=Path(__file__).resolve().parents[2]/'docs/任务卡/202-证据'
def rhythm(x):
    values=np.array([ref.rms(x[i:i+8000]) for i in range(0,len(x)-7999,8000)])[-60:]
    if len(values)<12:return 0.
    c=values-values.mean();best=0.
    for lag in range(4,min(12,len(c)//2)+1):
        a,b=c[:-lag],c[lag:];aa=float(a@a);bb=float(b@b)
        if aa>1e-15 and bb>1e-15:best=max(best,float(a@b)/(aa*bb)**.5)
    return best
manifest={r['id']:r for r in csv.DictReader((ROOT/'results/manifest.csv').open())}
source={k:ref.load(r) for k,r in manifest.items() if (ref.ROOT/r['path']).is_file()}
windows=list(csv.DictReader((ROOT/'results/windows.csv').open()))
clips={}
for r in windows:
    if r['window']=='2' and r['category'] in ['snoring','breathing']:
        clips.setdefault(r['clip_id'],[]).append(r)
recipes={r['clip_id']:r for r in csv.DictReader((ROOT/'results/mixing_manifest.csv').open())}
rows=[]
for cid,ws in clips.items():
    ws=sorted(ws,key=lambda r:float(r['start']));s=ws[0];r=recipes[cid]
    x=ref.norm(ref.room(ref.fit(source[s['source_id']])),-36)
    if s['snr']!='clean':
        raw=source[r['background']];noise=ref.fit(np.tile(raw,1+ref.N//len(raw)),offset=min(int(r['background_offset']),max(0,len(raw)-ref.N)))
        x=x+noise*(ref.rms(x)/(ref.rms(noise)*10**(float(s['snr'])/20)))
    x=(x/max(1.,float(np.max(np.abs(x)))/.98)).astype(np.float32)
    p=sum(float(w['Snoring'])>=.1 for w in ws);corr=rhythm(x)
    rows.append(dict(source=s['source_id'],split=s['split'],category=s['category'],snr=s['snr'],positive_windows=p,correlation=corr,accepted=p>=3 and corr>=.5,baseline=p>0))
OUT.mkdir(parents=True,exist_ok=True)
with (OUT/'rhythm-short-clips.csv').open('w') as f:
    w=csv.DictWriter(f,rows[0],lineterminator='\n');w.writeheader();w.writerows(rows)
summary=[]
for split in ['tune','validation']:
    for snr in ['clean','20','10','5','0']:
        a=[r for r in rows if r['split']==split and r['snr']==snr and r['category']=='snoring']
        summary.append(dict(split=split,snr=snr,n=len(a),baseline=sum(r['baseline'] for r in a),accepted=sum(r['accepted'] for r in a),three_windows=sum(r['positive_windows']>=3 for r in a)))
(OUT/'rhythm-short-summary.json').write_text(json.dumps(summary,indent=2))
print(json.dumps(summary,indent=2))
