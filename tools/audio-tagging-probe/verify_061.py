#!/usr/bin/env python3
"""结果完整性验收；不重新推理。"""
import argparse,csv,json,pathlib,hashlib,math,collections
P=argparse.ArgumentParser();P.add_argument('--root',type=pathlib.Path,default=pathlib.Path.home()/'Datasets/taosheng-audio');a=P.parse_args();R=a.root;O=R/'results';m=json.loads((R/'manifest.json').read_text());ids={r['id'] for r in m};assert len(ids)==len(m)
parents={}
for r in m:
 assert pathlib.Path(r['path']).is_file()
 if r['source']=='private':assert not pathlib.Path(r['path']).is_relative_to(R)
 for p in r['parents']:
  assert p not in parents or parents[p]==r['split'];parents[p]=r['split']
public=[r for r in m if r['split']!='phone'];assert sum(r['split']=='validation' for r in public)/len(public)>=.3
counts={}
for model,strategies in [('zipformer',{'whole','sliding','three'}),('ced-small',{'sliding','three'}),('ced-mini',{'sliding','three'})]:
 seen=set()
 with open(O/(model+'.csv')) as fp:
  rd=csv.DictReader(fp);assert len(rd.fieldnames)==532
  for r in rd:
   key=(r['id'],r['strategy']);assert key not in seen;seen.add(key);assert r['id'] in ids and r['strategy'] in strategies
   assert float(r['seconds'])>0 and float(r['inference_seconds'])>0
   for k in rd.fieldnames[5:]:assert math.isfinite(float(r[k])) and 0<=float(r[k])<=1
 assert seen=={(i,s) for i in ids for s in strategies};counts[model]=len(seen)
f=json.loads((O/'frozen-config.json').read_text());v=json.loads((O/'validation-summary.json').read_text());assert v['frozen_config_sha256']==hashlib.sha256((O/'frozen-config.json').read_bytes()).hexdigest();assert f['manifest_sha256']==hashlib.sha256((R/'manifest.json').read_bytes()).hexdigest()
assert sum(1 for _ in csv.DictReader(open(O/'label-map.csv')))==527
assert json.loads((R/'download-ledger.json').read_text())['bytes']<8_000_000_000
for group in ['validation','phone']:
 for key,z in v[group].items():
  for name in ['skip','media']:
   c=z[name];assert c['n']==sum(c[x] for x in ['tp','fp','fn','tn'])
print(json.dumps({'samples':len(m),'rows':counts,'total_score_rows':sum(counts.values()),'source_leakage':False,'all_527_scores_valid':True,'frozen_config_verified':True},indent=2))
