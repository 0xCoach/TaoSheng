#!/usr/bin/env python3
"""只读 tune 分数选配置并冻结；--validate 才读取验证分数生成统计。"""
import argparse,csv,json,pathlib,hashlib,datetime
import numpy as np
P=argparse.ArgumentParser();P.add_argument('--root',type=pathlib.Path,default=pathlib.Path.home()/'Datasets/taosheng-audio');P.add_argument('--validate',action='store_true');a=P.parse_args();R=a.root;O=R/'results'
for model in ['zipformer','ced-small','ced-mini']:
 assert (O/(model+'.csv')).is_file() and (O/(model+'-provenance.json')).is_file(), 'complete all three models before tuning'
M={r['id']:r for r in json.loads((R/'manifest.json').read_text())};F=json.loads((O/'features.json').read_text())
EVENTS={'baby':['Baby cry, infant cry'],'smoke':['Smoke detector, smoke alarm','Fire alarm'],'doorbell':['Doorbell'],'knock':['Knock'],'siren':['Siren','Police car (siren)','Ambulance (siren)','Fire engine, fire truck (siren)'],'glass':['Shatter','Glass'],'dog':['Dog','Bark']}
TAGS={'室内':['Inside, small room','Inside, large room or hall','Inside, public space'],'户外':['Outside, urban or manmade','Outside, rural or natural'],'车上':['Car','Bus','Truck'],'交通':['Vehicle','Traffic noise, roadway noise','Car','Train','Aircraft','Helicopter','Vehicle horn, car horn, honking','Siren'],'嘈杂人声':['Crowd','Chatter','Hubbub, speech noise, speech babble'],'音乐':['Music'],'键盘':['Computer keyboard','Typing'],'厨房':['Dishes, pots, and pans','Frying (food)','Chopping (food)','Cutlery, silverware'],'雨':['Rain','Rain on surface','Raindrop'],'宠物':['Dog','Bark','Cat','Meow','Purr'],'流水':['Water','Water tap, faucet','Pour','Drip'],'家电':['Vacuum cleaner','Air conditioning','Mechanical fan'],'发动机':['Engine','Idling','Engine starting'],'脚步':['Walk, footsteps'],'闹钟':['Alarm clock'],'人声活动':['Speech','Conversation','Narration, monologue','Cough','Laughter','Breathing']}
SPEECH=['Speech','Male speech, man speaking','Female speech, woman speaking','Child speech, kid speaking','Conversation','Narration, monologue','Whispering','Speech synthesizer']
HUMAN=['Baby cry, infant cry','Crying, sobbing','Laughter','Cough','Breathing','Screaming','Shout']
TH=[.005,.01,.02,.05,.1,.2,.3,.4,.5,.6,.7,.8,.9,1.1]
def load(split):
 data={}
 for path in sorted(O.glob('*.csv')):
  for r in csv.DictReader(open(path)):
   if r.get('id') not in M or M[r['id']]['split']!=split or M[r['id']]['category']=='unknown':continue
   key=path.stem+'/'+r['strategy'];data.setdefault(key,[]).append(r)
 return data
def score(rows,names):return np.array([max([float(r.get(k,0)) for k in names] or [0]) for r in rows])
def metrics(y,p):
 y=np.array(y,dtype=bool);p=np.array(p,dtype=bool);tp=int(sum(y&p));fp=int(sum(~y&p));fn=int(sum(y&~p));tn=int(sum(~y&~p));pr=tp/(tp+fp) if tp+fp else None;rc=tp/(tp+fn) if tp+fn else None
 return dict(n=len(y),positive=int(sum(y)),tp=tp,fp=fp,fn=fn,tn=tn,precision=pr,recall=rc,f1=2*tp/(2*tp+fp+fn) if 2*tp+fp+fn else 0)
def masks(rows):
 meta=[M[r['id']] for r in rows];cats=np.array([r['category'] for r in meta]);live=np.isin(cats,['speech','speech_room','speech_noise','speech_music']);pure=np.isin(cats,['music','environment']);media=np.isin(cats,['media_dry','media_music']);return meta,cats,live,pure,media
ENV=list(set(sum(TAGS.values(),[])+sum(EVENTS.values(),[])))
def predict(rows,c):
 s=score(rows,SPEECH);music=score(rows,['Music']);human=score(rows,HUMAN);env=score(rows,ENV);tv=score(rows,['Television','Radio'])
 skip=(s<c['skip_speech'])&(human<.05)&((music>=c['skip_content'])|(env>=c['skip_content']))
 media=(s>=.05)&((music>=c['media_music'])|(tv>=c['media_tv']))
 return skip,media

def tune(rows):
 meta,cats,live,pure,media=masks(rows);s=score(rows,SPEECH);human=score(rows,HUMAN);content=score(rows,ENV);music=score(rows,['Music']);tv=score(rows,['Television','Radio'])
 best=(-1,None)
 for st in [.001,.005,.01,.02,.05,.1,.2]:
  for ct in [.2,.4,.6,.8]:
   p=(s<st)&(human<.05)&(np.maximum(content,music)>=ct)
   if sum(p&live)/max(1,sum(live))>.01:continue
   v=metrics(pure,p)['f1']
   if v>best[0]:best=(v,{'skip_speech':st,'skip_content':ct})
 c=best[1] or {'skip_speech':0,'skip_content':1.1};bestmedia=(-1,None)
 eligible=live|media
 for mt in [.2,.4,.6,.8,.9,.95,.99,1.1]:
  for tt in [.05,.1,.2,.4,.6,.8,1.1]:
   p=(s>=.05)&((music>=mt)|(tv>=tt))
   # Every live subgroup must satisfy <=5% on tuning; prioritise safe abstention.
   if any(sum(p&(cats==g))/max(1,sum(cats==g))>.05 for g in ['speech','speech_room','speech_noise','speech_music']):continue
   v=metrics(media[eligible],p[eligible])['f1']
   if v>bestmedia[0]:bestmedia=(v,{'media_music':mt,'media_tv':tt})
 c.update(bestmedia[1] or {'media_music':1.1,'media_tv':1.1});c['events']={};c['tags']={}
 raw=np.array([r['kind']=='original' for r in meta])
 for name,names in EVENTS.items():
  y=np.array([r['event']==name for r in meta]);sc=score(rows,names)
  if not sum(y&raw):c['events'][name]=1.1;continue
  opts=[]
  for t in TH:
   met=metrics(y[raw],sc[raw]>=t)
   if (met['precision'] or 0)>=.9:opts.append((met['f1'],t))
  c['events'][name]=max(opts)[1] if opts else 1.1
 for name,names in TAGS.items():
  y=np.array([name in r['tags'] for r in meta]);sc=score(rows,names)
  if not sum(y&raw):c['tags'][name]=1.1;continue
  c['tags'][name]=max((metrics(y[raw],sc[raw]>=t)['f1'],t) for t in TH)[1]
 return c

def summarize(rows,c):
 meta,cats,live,pure,media=masks(rows);skip,mp=predict(rows,c);eligible=live|media
 result={'skip':metrics(pure,skip),'live_harm':metrics(live,skip),'live_count':int(sum(live)),'live_skipped':int(sum(live&skip)),'media':metrics(media[eligible],mp[eligible]),'groups':{},'events':{},'tags':{},'timing':{}}
 for category in sorted(set(cats)):
  mask=cats==category;result['groups'][category]={'n':int(sum(mask)),'skip':int(sum(skip&mask)),'media':int(sum(mp&mask))}
 raw=np.array([r['kind']=='original' for r in meta]);film=cats=='media_event'
 for name,names in EVENTS.items():
  sc=score(rows,names);p=sc>=c['events'][name];y=np.array([r['event']==name for r in meta]);daily=raw&~y
  result['events'][name]={'threshold':c['events'][name],'raw':metrics(y[raw],p[raw]),'film_same':{'n':int(sum(film&y)),'triggered':int(sum(p&film&y))},'daily_false_alarms':int(sum(p&daily)), 'all':metrics(y,p)}
 for name,names in TAGS.items():
  y=np.array([name in r['tags'] for r in meta]);result['tags'][name]={'threshold':c['tags'][name],'original':metrics(y[raw],score(rows,names)[raw]>=c['tags'][name])}
 for kind in sorted({r['kind'] for r in meta}):
  chosen=[r for r,m in zip(rows,meta) if m['kind']==kind];seconds=sum(float(r['seconds']) for r in chosen);elapsed=sum(float(r['inference_seconds']) for r in chosen)
  result['timing'][kind]={'audio_seconds':seconds,'cpu_wall_seconds':elapsed,'seconds_per_audio_minute':elapsed/seconds*60}
 result['event_examples']=[]
 for name,names in EVENTS.items():
  sc=score(rows,names)
  misses=[{'id':r['id'],'event':name,'score':float(v),'type':'漏报'} for r,m,v in zip(rows,meta,sc) if m['kind']=='original' and m['event']==name and v<c['events'][name]][:2]
  films=[{'id':r['id'],'event':name,'score':float(v),'type':'模拟影视触发'} for r,m,v in zip(rows,meta,sc) if m['category']=='media_event' and m['event']==name and v>=c['events'][name]][:1]
  result['event_examples']+=misses+films
 result['examples']=[{'id':r['id'],'category':m['category'],'skip':bool(sk),'media':bool(md),'speech':float(s),'music':float(mu)} for r,m,sk,md,s,mu in zip(rows,meta,skip,mp,score(rows,SPEECH),score(rows,['Music'])) if (sk and m['category'].startswith('speech')) or (md and m['category'].startswith('speech')) or (not sk and m['category']=='music')][:12]
 return result
lock=O/'frozen-config.json'
if not a.validate:
 if lock.exists():raise SystemExit('Frozen config exists; refusing retune. Use a new results directory for another study.')
 data=load('tune');configs={};summaries={};utility={}
 for key,rows in data.items():
  c=tune(rows);s=summarize(rows,c);configs[key]=c;summaries[key]=s
  ev=[v['raw']['f1'] for v in s['events'].values() if v['raw']['positive']];tags=[v['original']['f1'] for v in s['tags'].values() if v['original']['positive']]
  utility[key]=(s['skip']['f1']+sum(ev)/len(ev)+sum(tags)/len(tags))/3
 chosen=max(utility,key=utility.get)
 frozen={'created_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'selection':'tune mean(skip F1, supported event macro F1, supported tag macro F1), speech harm <=1% constraint','chosen':chosen,'utility':utility,'configs':configs,'events_mapping':EVENTS,'tags_mapping':TAGS,'speech_mapping':SPEECH,'human_guard':HUMAN,'manifest_sha256':hashlib.sha256((R/'manifest.json').read_bytes()).hexdigest()}
 lock.write_text(json.dumps(frozen,ensure_ascii=False,indent=2));(O/'tune-summary.json').write_text(json.dumps(summaries,ensure_ascii=False,indent=2));print('frozen',chosen,utility)
 # All 527 classes explicitly mapped or ignored.
 names=next(csv.DictReader(open(next(O.glob('*.csv'))))).keys()
 with open(O/'label-map.csv','w') as fp:
  w=csv.writer(fp);w.writerow(['audioset_label','user_tags'])
  for n in names:
   if n in ['id','strategy','seconds','inference_seconds','calls']:continue
   w.writerow([n,'|'.join(k for k,v in TAGS.items() if n in v) or '忽略'])
else:
 frozen=json.loads(lock.read_text());assert frozen['manifest_sha256']==hashlib.sha256((R/'manifest.json').read_bytes()).hexdigest()
 result={}
 for split in ['validation','phone']:
  result[split]={k:summarize(rows,frozen['configs'][k]) for k,rows in load(split).items()}
 result['frozen_config_sha256']=hashlib.sha256(lock.read_bytes()).hexdigest();(O/'validation-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('validated once',frozen['chosen'])
 # Simple no-music replay rule candidates: fit using tune only, then evaluate unchanged.
 exploratory={};td=load('tune');vd=load('validation');pd=load('phone')
 for key,tr in td.items():
  va=vd[key]
  def arrays(rows):
   rr=[r for r in rows if M[r['id']]['category'] in ['speech','speech_room','speech_noise','media_dry']];y=np.array([M[r['id']]['category']=='media_dry' for r in rr]);return rr,y
  tr,y=arrays(tr);va,yv=arrays(va);candidates=[]
  for low in [.001,.005,.01,.03,.1]:
   for cv in [.4,.8,1.2,2,10]:
    candidates.append(('spectrum',low,cv))
  for tv in [.005,.01,.05,.1,.2]:candidates.append(('tv',tv,0))
  for act in [.3,.5,.7,.9]:candidates.append(('active',act,0))
  def pred(rows,c):
   kind,t,v=c
   if kind=='tv':return score(rows,['Television','Radio'])>=t
   if kind=='active':return np.array([F[r['id']]['active_ratio']>=t for r in rows])
   return np.array([F[r['id']]['low_ratio']<t and F[r['id']]['rms_cv']<v for r in rows])
  choice=max(candidates,key=lambda c:metrics(y,pred(tr,c))['f1']);exploratory[key]={'rule':choice,'tune':metrics(y,pred(tr,choice)),'validation':metrics(yv,pred(va,choice)),'phone_negatives':metrics(*((lambda rr:(np.zeros(len(rr),dtype=bool),pred(rr,choice)))([r for r in pd[key] if M[r['id']]['category']=='speech']))),'note':'synthetic replay only; active_ratio is energy occupancy, not VAD or speaking rate'}
 (O/'dry-replay-exploration.json').write_text(json.dumps(exploratory,ensure_ascii=False,indent=2))
