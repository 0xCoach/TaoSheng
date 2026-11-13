#!/usr/bin/env python3
"""已发现计划退化后的配对核查：相同种子，交错先后顺序，区分时段负载与计划变化。"""
import json
import os
import shutil
import sqlite3
import statistics
import time
from benchmark import ROOT, OUT, WORK, INDEXES, catalogue, params, plans, elapsed

old=catalogue(True);new=catalogue(False)
# 全仓库动态SQL补充：AskToolsCore用ask-sql.json生成空关键词/时间/说话人/地点组合。
spec=json.loads((ROOT/'app/src/main/assets/prompts/ask-sql.json').read_text())['search_lines']
for name,extra in {'all':'','range':' AND l.startAt>=:start AND l.startAt<:end','speaker':' AND (l.speakerName=:speaker OR l.speakerTag=:speaker)','place':' AND EXISTS (SELECT 1 FROM (VALUES (:start,:end)) WHERE l.startAt<column2 AND l.endAt>column1)'}.items():
 sql=f"SELECT {spec['select']}, l.voiceInputJson FROM {spec['table']} WHERE {spec['where']}{extra} ORDER BY l.startAt DESC, l.id DESC LIMIT 41"
 old['ask/dynamic#'+name]={'sql':sql.replace('transcript_lines l NOT INDEXED','transcript_lines l')};new['ask/dynamic#'+name]={'sql':sql}
connections=[];paths=[]
for mode in ('none','A+B1'):
 path=WORK/f'paired-{mode}-{os.getpid()}.db';shutil.copyfile(WORK/'baseline.db',path);paths.append(path)
 db=sqlite3.connect(path);db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=NORMAL');db.execute('PRAGMA foreign_keys=ON')
 if mode!='none':
  for index in mode.split('+'):db.execute(INDEXES[index])
 db.commit();db.execute('PRAGMA wal_checkpoint(TRUNCATE)');connections.append(db)
report={}
try:
 keys=set(os.getenv('INDEX183_KEYS','').split(',')) if os.getenv('INDEX183_KEYS') else None
 for key in old:
  if keys is not None and key not in keys:continue
  if os.getenv('INDEX183_DYNAMIC_ONLY')=='1' and not key.startswith('ask/dynamic#'):continue
  if key=='topic/TopicData.kt#observeLateTranscriptIds':continue # 已有完整累计，避免重新执行25秒旧查询。
  if not old[key]['sql'].lstrip().startswith('SELECT'):continue # UPDATE/DELETE属于单独写入成本。
  values=[[],[]];rows=[None,None]
  if keys is not None:
   for index in (0,1):
    for _ in range(2):elapsed(connections[index],(old,new)[index][key]['sql'])
  for trial in range(17 if keys is not None else 5):
   for index in ((0,1) if trial%2==0 else (1,0)):
    sql=(old,new)[index][key]['sql']
    ms,result=elapsed(connections[index],sql)
    values[index].append(ms);rows[index]=result
  # 无明确外层排序的SQL保持多重集合比较；新增失败段ORDER BY只稳定旧扫描顺序。
  sorted_rows=lambda r:sorted(r,key=repr)
  assert sorted_rows(rows[0])==sorted_rows(rows[1]),key
  if 'ORDER BY' in old[key]['sql'].upper():assert rows[0]==rows[1],key
  before=plans(connections[0],old[key]['sql']);after=plans(connections[1],new[key]['sql'])
  b,a=map(statistics.median,values)
  # 报告全部原样本；阈值只用于提示复查，不将阈值以下的数据删除或改写。
  stable=a>b*1.10 and min(values[1])>max(values[0]) and a-b>0.2
  report[key]={'before_sql':old[key]['sql'],'after_sql':new[key]['sql'],'before_plan':before,'after_plan':after,'before_ms':values[0],'after_ms':values[1],'before_median_ms':b,'after_median_ms':a,'same_rows':True,'rows':len(rows[0]),'review':stable}
  if stable:print('REVIEW',key,round(b,3),round(a,3),'planChanged',before!=after,flush=True)
 (OUT/('paired-noise-check.json' if keys is not None else 'paired-dynamic-final.json' if os.getenv('INDEX183_DYNAMIC_ONLY')=='1' else 'paired-audit.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print('SQL',len(report),'all rows equal; review',sum(v['review'] for v in report.values()),flush=True)
finally:
 for db in connections:db.close()
 for path in paths:path.unlink()
