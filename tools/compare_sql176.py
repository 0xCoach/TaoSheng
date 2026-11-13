#!/usr/bin/env python3
"""卡176：按 Kotlin 字面量原文比较 SQL 多重集合，不折叠空白/不改标准答案。
注释和字符字面量跳过；字符串模板递归扫描，同时保留外层完整字面量。
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = '1355f5f9ac43f0df1c8e28d2368bc8ac93a89e97'
ORIGINAL = '''data/AskDao.kt data/PendingDigest.kt data/PrivacyDao.kt data/ReviewDao.kt data/VoiceInputDao.kt data/VoiceprintDao.kt envsound/EnvSoundDao.kt importer/ImportRecordDao.kt things/ThingData.kt topic/TopicData.kt data/MemoryDao.kt data/TranscribeDao.kt devices/DeviceSyncStore.kt data/CaptureDatabase.kt push/PushRecords.kt search/SearchIndex.kt redo/RedoData.kt search/EmbeddingStore.kt skills/SkillDao.kt importer/ImportFingerprint.kt location/Location.kt location/Places.kt data/DatabaseSql.kt'''.split()
SPLIT = '''location/LocationData.kt location/PlaceData.kt importer/FolderImportData.kt devices/DeviceFileDetails.kt redo/RedoTables.kt search/EmbeddingEntity.kt search/Vectors.kt skills/SkillCardPresentation.kt skills/SkillRowMappings.kt data/AndroidMemoryActions.kt data/AndroidTranscribeActions.kt push/PushDao.kt data/AndroidDatabaseSql.kt data/AndroidCaptureDatabase.kt'''.split()
SQL = re.compile(r'\b(?:CREATE|ALTER|INSERT|UPDATE|DELETE|DROP|SELECT)\b', re.I)

def literals(text):
    result = []
    def code(i, nested=False):
        while i < len(text):
            if text.startswith('//', i):
                j=text.find('\n',i); i=len(text) if j<0 else j
            elif text.startswith('/*',i):
                i+=2; depth=1
                while i<len(text) and depth:
                    if text.startswith('/*',i): depth+=1;i+=2
                    elif text.startswith('*/',i):depth-=1;i+=2
                    else:i+=1
            elif text[i]=='\'':
                i+=1
                while i<len(text) and text[i]!='\'':i+=2 if text[i]=='\\' else 1
                i+=1
            elif text[i]=='"':
                start=i; quote='"""' if text.startswith('"""',i) else '"'; i+=len(quote)
                while i<len(text):
                    if text.startswith(quote,i):i+=len(quote);break
                    if text.startswith('${',i):i=code(i+2,True)
                    elif quote=='"' and text[i]=='\\':i+=2
                    else:i+=1
                literal=text[start:i]
                if SQL.search(literal):result.append(literal)
            elif text[i]=='{':i=code(i+1,True)
            elif text[i]=='}' and nested:return i+1
            else:i+=1
        return i
    code(0)
    return result

def compare():
    old=Counter();new=Counter();oldfiles=[];newfiles=[]
    for relative in ORIGINAL:
        path='app/src/main/java/app/resound/'+relative
        source=subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT,text=True)
        old.update(literals(source));oldfiles.append(path)
    for relative in ORIGINAL+SPLIT:
        for prefix in ['app/src/main/java/app/resound/', 'shared/src/commonMain/kotlin/app/resound/']:
            path=ROOT/(prefix+relative)
            if path.exists():new.update(literals(path.read_text()));newfiles.append(str(path.relative_to(ROOT)))
    added=new-old;removed=old-new
    return {'baseline':BASE,'before_files':oldfiles,'after_files':newfiles,'before_count':sum(old.values()),'after_count':sum(new.values()),'added_count':sum(added.values()),'removed_count':sum(removed.values()),'added':dict(added),'removed':dict(removed)}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path);a=p.parse_args()
    report=compare();data=json.dumps(report,ensure_ascii=False,indent=2)+'\n'
    if a.output:a.output.write_text(data)
    print(f"SQL 字面量：{report['before_count']} → {report['after_count']}；增加 {report['added_count']}，减少 {report['removed_count']}")
    if report['added_count'] or report['removed_count']:print(data)
    raise SystemExit(bool(report['added_count'] or report['removed_count']))
