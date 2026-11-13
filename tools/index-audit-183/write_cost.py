#!/usr/bin/env python3
"""隔离查询/等待影响，配对测量索引维护成本；三轮500段，每轮全新同一合成库。"""
import json
import os
from pathlib import Path
import shutil
import sqlite3
import statistics
import time
from benchmark import WORK, OUT, INDEXES, AT, N

def sample(mode, imports=False):
 path=WORK/f'write-cost-{os.getpid()}.db';shutil.copyfile(WORK/'baseline.db',path)
 db=sqlite3.connect(path);db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=NORMAL');db.execute('PRAGMA foreign_keys=ON')
 for index in ([] if mode=='none' else mode.split('+')):db.execute(INDEXES[index])
 db.commit();db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
 # 统一暖身；不运行任何长查询，避免7秒间隔的操作系统回写改变写入时间。
 db.execute('SELECT SUM(startAt) FROM segments NOT INDEXED').fetchone()
 times=[0.0,0.0,0.0]
 for i in range(500):
  sid=N+1+i;at=AT+sid*60000
  if imports:
   start=time.perf_counter_ns()
   db.execute("INSERT INTO import_records(id,pendingId,fileName,kind,startAt,endAt,byChannel,status,ingestComplete,revision,summaryRequested,speakersReady) VALUES(?,?,?,'Meeting',?,?,0,'Transcribing',0,0,0,0)",(2001+i,f'cost-{i}',f'cost-{i}',at,at+30000));db.commit();times[0]+=(time.perf_counter_ns()-start)/1e6
   start=time.perf_counter_ns();db.execute('UPDATE import_records SET ingestComplete=1,summaryRequested=1 WHERE id=?',(2001+i,));db.commit();times[2]+=(time.perf_counter_ns()-start)/1e6
   continue
  start=time.perf_counter_ns()
  db.execute("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status,attempts,sentMillis,playbackMillis,redoPending) VALUES(?,?,?,'mic',?,100,'Pending',0,0,0,0)",(sid,at,at+30000,f'cost-{i}'));db.commit();times[0]+=(time.perf_counter_ns()-start)/1e6
  start=time.perf_counter_ns();db.execute("INSERT INTO env_sounds VALUES(?,?,?,?,'Keep',NULL,'Rain','','{}',0,0,0,0,1)",(sid,at,at+30000,f'cost-{i}'));db.commit();times[1]+=(time.perf_counter_ns()-start)/1e6
  start=time.perf_counter_ns()
  db.execute('DELETE FROM transcript_lines WHERE segmentId=?',(sid,))
  db.execute("INSERT INTO transcript_lines(segmentId,startAt,endAt,text,speakerManual) VALUES(?,?,?,'合成新原话',0)",(sid,at,at+30000))
  db.execute("UPDATE segments SET status='Done',completedAt=? WHERE id=?",(at+30000,sid))
  db.execute("INSERT INTO asr_attempts(segmentId,at,result,elapsedMillis,sentMillis) VALUES(?,?,'Done',1,30000)",(sid,at+30000))
  db.execute("UPDATE asr_usage SET sentMillis=sentMillis+30000 WHERE day='synthetic'");db.commit();times[2]+=(time.perf_counter_ns()-start)/1e6
 db.close();path.unlink()
 return {'insert_ms':times[0],'env_ms':times[1],'complete_ms':times[2],'total_ms':sum(times)}

results={key:[] for key in ('none','A','B1','B','A+B1','imports-none','imports-C')}
# 轮换顺序，控制首次IO/热缓存偏差；这是一组统计采样，不重复回归测试。
for round in range(3):
 keys=list(results);keys=keys[round:]+keys[:round]
 for key in keys:
  results[key].append(sample(key.removeprefix('imports-'),key.startswith('imports-')))
report={key:{'samples':values,'median_total_ms':statistics.median(v['total_ms'] for v in values)} for key,values in results.items()}
(OUT/'write-cost.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
for key,value in report.items():print(key,value['median_total_ms'])
