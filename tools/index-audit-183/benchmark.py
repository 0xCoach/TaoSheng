#!/usr/bin/env python3
"""卡183：确定性合成文件库；SQL取自产品/旧查询夹具，不读取真实数据。
查询计划用本机SQLite，最终Room/迁移另测。--phase final读取当前产品SQL。
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/任务卡/183-证据'
WORK = ROOT / 'app/build/index183'
BASE = 'df43e70'
AT = 1_700_000_000_000
N = 50_000
SCHEMA = json.loads((ROOT/'app/schemas/app.resound.data.CaptureDatabase/33.json').read_text())['database']
INDEXES = {
 'A': 'CREATE INDEX IF NOT EXISTS index_segments_status_voiceprintProcessedAt ON segments(status, voiceprintProcessedAt)',
 'B': 'CREATE INDEX IF NOT EXISTS index_transcript_lines_startAt_segmentId ON transcript_lines(startAt, segmentId)',
 'B1': 'CREATE INDEX IF NOT EXISTS index_transcript_lines_startAt ON transcript_lines(startAt)',
 'C': 'CREATE INDEX IF NOT EXISTS index_import_records_ingestComplete_summaryRequested_startAt ON import_records(ingestComplete, summaryRequested, startAt)',
}
Q = {
 'queue': 'data/TranscribeDao.kt#observeQueueCounts',
 'voiceprint': 'data/TranscribeDao.kt#observeVoiceprintCounts',
 'failed': 'data/TranscribeDao.kt#observeFailedSegments',
 'late': 'topic/TopicData.kt#observeLateTranscriptIds',
 'imports': 'importer/ImportRecordDao.kt#observeSummaryReady',
}

def source(path, baseline=False):
 return subprocess.check_output(['git','show',f'{BASE}:{path}'],cwd=ROOT).decode() if baseline else (ROOT/path).read_text()

def catalogue(baseline=False):
 paths=sorted((ROOT/'app/src/main/java/app/resound').rglob('*.kt'))
 texts={p:source(str(p.relative_to(ROOT)),baseline) for p in paths if '/ui/' not in str(p)}
 constants={name:val for text in texts.values() for name,val in re.findall(r'const val (\w+) = """(.*?)"""',text,re.S)}
 queries={}
 for path,text in texts.items():
  for match in re.finditer(r'@Query\((.*?)\)\s*(?:(?:protected|abstract|suspend|override)\s+)*fun\s+(\w+)',text,re.S):
   expr,name=match.groups()
   tokens=re.findall(r'""".*?"""|"(?:\\.|[^"\\])*"|\b[A-Z_]{3,}\b',expr,re.S)
   sql=''.join(t[3:-3] if t.startswith('"""') else json.loads(t) if t.startswith('"') else constants.get(t,'$'+t) for t in tokens)
   for key,value in constants.items(): sql=sql.replace('$'+key,value)
   if not (re.search(r'\bsegments\b',sql) and re.search(r'\bstatus\b',sql) or re.search(r'\btranscript_lines\b',sql) and re.search(r'\bstartAt\b',sql) or name in Q.values() or (path.name=='ImportRecordDao.kt' and name in ('observeSummaryReady','pending','day','observe'))): continue
   assert '$' not in sql,(path,name,sql)
   key=str(path.relative_to(ROOT/'app/src/main/java/app/resound'))+'#'+name
   queries[key]={'sql':sql,'source':str(path.relative_to(ROOT)), 'line':text[:match.start()].count('\n')+1}
 # 已录制动态问问SQL的所有相关形态（含FTS/向量回读）。
 fixture=(ROOT/'app/src/test/resources/driver175/queries.txt').read_text()
 case=''; number=0
 for line in fixture.splitlines():
  if line.startswith('CASE '): case=line[5:];number=0
  if line.startswith('SQL '):
   sql=line[4:];number+=1
   if 'transcript_lines' in sql and 'startAt' in sql:
    if not baseline and case.startswith('fts.search_lines.'):
     table=json.loads((ROOT/'app/src/main/assets/prompts/ask-sql.json').read_text())['search_lines']['table']
     sql=sql.replace('FROM transcript_lines l WHERE',f'FROM {table} WHERE')
    queries[f'ask/dynamic175#{case}/{number}']={'sql':sql,'source':'app/src/test/resources/driver175/queries.txt','line':None}
 return queries

def seed_sql(n=N, lines=500_000):
 entities={e['tableName']:e for e in SCHEMA['entities']}
 statements=[]
 def insert(table,count,values,extra=''):
  fields=entities[table]['fields']
  cols=[f['columnName'] for f in fields]
  expr=[values.get(c,'NULL' if not f.get('notNull',False) else "''" if f['affinity']=='TEXT' else "X''" if f['affinity']=='BLOB' else '0') for c,f in zip(cols,fields)]
  statements.append(f"WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<{count}) {extra} INSERT INTO {table} ({','.join(cols)}) SELECT {','.join(expr)} FROM n")
 insert('memory_events',200,{'id':'x','startAt':f'{AT}+(x-1)*250*60000','endAt':f'{AT}+(x-1)*250*60000+1200000','title':"'合成专题'||x",'summary':"'合成摘要'",'pointsJson':"'[]'",'peopleJson':"'[]'"})
 insert('topics',200,{'id':'x','kind':"'Conversation'",'status':"'Done'",'createdAt':f'{AT}+(x-1)*250*60000','eventId':'x','retainedItemIds':"'[]'"})
 insert('topic_spans',200,{'id':'x','topicId':'x','startAt':f'{AT}+((x-1)*250+30)*60000','endAt':f'{AT}+((x-1)*250+50)*60000'})
 insert('import_records',2000,{'id':'x','pendingId':"'synthetic-'||x",'fileName':"'import-'||x",'kind':"'Audio'",'startAt':f'{AT}+x*60000','endAt':f'{AT}+x*60000+30000','status':"CASE WHEN x%100=0 THEN 'Transcribing' ELSE 'Done' END",'ingestComplete':'1','summaryRequested':'CASE WHEN x%100=0 THEN 1 ELSE 0 END','speakersReady':'1'})
 insert('segments',n,{'id':'x','startAt':f'{AT}+x*60000','endAt':f'{AT}+x*60000+30000','sourceId':"CASE WHEN x<=2000 THEN 'import' ELSE 'mic' END",'path':"'synthetic-'||x",'sizeBytes':'100','status':"CASE WHEN (x-1)%1000<10 THEN 'Pending' WHEN (x-1)%1000<15 THEN 'Failed' WHEN (x-1)%1000<18 THEN 'Running' WHEN (x-1)%1000<20 THEN 'Empty' ELSE 'Done' END",'voiceprintProcessedAt':f'CASE WHEN (x-1)%1000>=30 THEN {AT}+x*60000 END','importRecordId':'CASE WHEN x<=2000 THEN x END','completedAt':f'CASE WHEN (x-1)%1000>=20 THEN {AT}+x*60000+30000 END','nextRetryAt':str(AT),'attempts':'1','separateSpeakers':'1'})
 # 50k录音中49k Done；500k原话只挂Done段，语音输入标记约0.1%。
 seg=f'(21+((x-1)%{n//1000*980})/980*1000+(x-1)%980)'
 insert('transcript_lines',lines,{'id':'x','segmentId':seg,'startAt':f'{AT}+{seg}*60000+(x-1)/{n//1000*980}*1000','endAt':f'{AT}+{seg}*60000+(x-1)/{n//1000*980}*1000+500','text':"'合成原话：用于索引测量，非用户记录'",'speakerTag':"'speaker0'",'digestedAt':str(AT+10_000_000_000),'eventId':f'CASE WHEN {seg}%250>=30 AND {seg}%250<50 AND {seg}<=50000 THEN {seg}/250+1 END','voiceInputJson':"CASE WHEN x%1000=0 THEN '{}' END"})
 statements.append("INSERT INTO asr_usage(day,sentMillis) VALUES('synthetic',0)")
 return statements

def setup(path):
 if path.exists(): path.unlink()
 db=sqlite3.connect(path)
 db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=NORMAL');db.execute('PRAGMA foreign_keys=ON')
 for e in SCHEMA['entities']:
  db.execute(e['createSql'].replace('${TABLE_NAME}',e['tableName']))
  for idx in e.get('indices',[]): db.execute(idx['createSql'].replace('${TABLE_NAME}',e['tableName']))
 # 压力初值不填FTS，避免把无关的50万行分词计入建库/索引时间；50轮写入启用真实15触发器。
 for sql in seed_sql():db.execute(sql)
 for line in (ROOT/'app/src/test/resources/driver175/fresh-install.txt').read_text().splitlines():
  if line.startswith('["trigger",'):db.execute(json.loads(line)[3])
 assert not db.execute('PRAGMA foreign_key_check').fetchall()
 db.commit();db.execute('PRAGMA wal_checkpoint(TRUNCATE)');db.close()

def params(sql,wide=False):
 values={'id':100,'eventId':100,'ids':100,'start':AT+N*60000-86400000,'end':AT+N*60000+60000,
 'since':AT+N*60000-3600000,'from':AT+N*60000-86400000,'to':AT+N*60000+60000,
 'until':AT+N*60000+60000,'now':AT+N*60000,'at':AT+N*60000,'before':AT+N*60000-3600000,
 'after':499000,'includeLines':1,'includeVoiceInput':1,'name':'合成','text':'合成','status':'Done','on':1,'pattern':'%合成%','today':'synthetic'}
 if wide:
  for name in ('start','since','from'):values[name]=AT
 return {name:values.get(name,1) for name in re.findall(r'(?<!:):(\w+)',sql)} if '?' not in sql else [1]*sql.count('?')

def plans(db,sql): return [r[3] for r in db.execute('EXPLAIN QUERY PLAN '+sql,params(sql))]

def elapsed(db,sql,args=None):
 start=time.perf_counter_ns()
 result=db.execute(sql,params(sql) if args is None else args).fetchall()
 return (time.perf_counter_ns()-start)/1e6,result

def variant(queries,mode):
 result={key:dict(v) for key,v in queries.items()}
 if 'A' in mode:
  key=Q['queue'];result[key]['sql'] += " WHERE status IN ('Pending', 'Failed', 'Running')"
 # 实验阶段允许测试等价SQL；正式产品修改必须等测量结论。
 # 固定等价连接顺序以隔离索引收益；原SQL单次成本另存日志。
 if True:
  key=Q['late'];result[key]['sql']=result[key]['sql'].replace('topic_spans ts JOIN transcript_lines l','topic_spans ts CROSS JOIN transcript_lines l').replace('JOIN segments s ON','CROSS JOIN segments s ON')
 return result

def run(mode,baseline,queries,phase):
 path=WORK/f'{phase}-{mode}-{os.getpid()}.db';shutil.copyfile(baseline,path)
 db=sqlite3.connect(path);db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=NORMAL');db.execute('PRAGMA foreign_keys=ON')
 indexes=[] if mode=='none' else mode.split('+')
 for key in indexes:db.execute(INDEXES[key])
 db.commit();db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
 report={'mode':mode,'timing_scope':'same CROSS JOIN late-query on both sides; original JOIN single cost separately recorded','bytes':path.stat().st_size,'plans':{},'single':{},'audit':{},'resident_ms':dict.fromkeys(Q,0.0),'write_ms':dict.fromkeys(('insert','env','complete'),0.0)}
 sqls=queries if phase=='final' else variant(queries,mode)
 for key,q in sqls.items():
  sql=q['sql'];report['plans'][key]={'sql':sql,'details':plans(db,sql)}
 relevant = set(Q) if mode=='none' or '+' in mode or phase!='measure' else {'queue','voiceprint','failed'} if mode=='A' else {'late'} if mode in ('B','B1') else {'imports'}
 for label,key in Q.items():
  if label not in relevant: continue
  ms,rows=elapsed(db,sqls[key]['sql']);report['single'][label]={'ms':ms,'rows':len(rows)}
  print(phase,mode,label,round(ms,3),len(rows),flush=True)
 # 单次审计：全部相关SELECT与UPDATE/DELETE（写查询在savepoint内回滚），记录3个样本及返回行摘要。
 for key,q in sqls.items():
  timings=[]; sql=q['sql']; result=[]
  if key==Q['late'] and 'B' not in mode:
   continue  # 全表扫描已有单次/累计实测，避免额外重复。
  for _ in range(3):
   db.execute('SAVEPOINT audit')
   ms,result=elapsed(db,sql)
   timings.append(ms)
   db.execute('ROLLBACK TO audit');db.execute('RELEASE audit')
  # 没有ORDER BY的结果按集合比较，保留重复行；有ORDER BY保留真实顺序。
  canonical=repr(result if 'ORDER BY' in sql.upper() else sorted(result,key=repr)).encode()
  report['audit'][key]={'ms':timings,'median_ms':statistics.median(timings),'rows':len(result),'sha256':hashlib.sha256(canonical).hexdigest()}
 # 与182同样50轮：新增段、写环境音、完成转写/attempt/usage；每次段变化都重执行5条订阅，无失效合并。
 def read():
  for label,key in Q.items():
   if label not in relevant: continue
   ms,_=elapsed(db,sqls[key]['sql']);report['resident_ms'][label]+=ms
 read()
 for i in range(50):
  if i%5==0: print('cycle',phase,mode,i,flush=True)
  sid=N+i+1;at=AT+sid*60000
  start=time.perf_counter_ns()
  db.execute("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status,attempts,sentMillis,playbackMillis,redoPending) VALUES(?,?,?,'mic',?,100,'Pending',0,0,0,0)",(sid,at,at+30000,f'new-{sid}'));db.commit()
  report['write_ms']['insert']+=(time.perf_counter_ns()-start)/1e6
  read()
  start=time.perf_counter_ns()
  db.execute("INSERT INTO env_sounds VALUES(?,?,?,?,'Keep',NULL,'Rain','','{}',0,0,0,0,1)",(sid,at,at+30000,f'new-{sid}'));db.commit()
  report['write_ms']['env']+=(time.perf_counter_ns()-start)/1e6
  start=time.perf_counter_ns()
  db.execute("DELETE FROM transcript_lines WHERE segmentId=?",(sid,))
  db.execute("INSERT INTO transcript_lines(segmentId,startAt,endAt,text,speakerManual) VALUES(?,?,?,'合成新原话',0)",(sid,at,at+30000))
  db.execute("UPDATE segments SET status='Done',completedAt=? WHERE id=?",(at+30000,sid))
  db.execute("INSERT INTO asr_attempts(segmentId,at,result,elapsedMillis,sentMillis) VALUES(?,?,'Done',1,30000)",(sid,at+30000))
  db.execute("UPDATE asr_usage SET sentMillis=sentMillis+30000 WHERE day='synthetic'")
  db.commit();report['write_ms']['complete']+=(time.perf_counter_ns()-start)/1e6
  read()
 db.execute('PRAGMA wal_checkpoint(TRUNCATE)');report['bytes_after']=path.stat().st_size
 db.close();path.unlink()
 (OUT/f'{phase}-{mode}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print('FINISH',mode,report['resident_ms'],report['write_ms'],report['bytes'],flush=True)
 return report

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--phase',choices=('measure','measure-all','final'),default='measure');ap.add_argument('--modes',default='none,A,B1,B,C');ap.add_argument('--catalogue-only',action='store_true');args=ap.parse_args()
 OUT.mkdir(parents=True,exist_ok=True);WORK.mkdir(parents=True,exist_ok=True)
 queries=catalogue(baseline=args.phase!='final')
 (OUT/f'{args.phase}-queries.json').write_text(json.dumps(queries,ensure_ascii=False,indent=2)+'\n')
 print('SQLite',sqlite3.sqlite_version,'queries',len(queries),flush=True)
 if args.catalogue_only:return
 baseline=WORK/'baseline.db'
 if not baseline.exists():setup(baseline)
 for mode in args.modes.split(','):run(mode,baseline,queries,args.phase)

if __name__=='__main__':main()
