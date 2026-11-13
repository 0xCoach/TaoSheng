#!/usr/bin/env python3
"""只读硬门槛：旧字节、已固定差异与KSP结构；不生成/重录答案。"""
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
ROOT=Path(__file__).resolve().parents[2]
BASE='603620df99969ad8aa7c63db6c0dba49a3da58af'
OUT=ROOT/'docs/任务卡/183-证据'
S=ROOT/'app/schemas/app.resound.data.CaptureDatabase'
F=ROOT/'app/src/test/resources/driver175'
def sha(b):return hashlib.sha256(b).hexdigest()
def original(p):return subprocess.check_output(['git','show',f'{BASE}:{p.relative_to(ROOT)}'],cwd=ROOT)
old_hashes={}
for p in sorted(S.glob('*.json')):
 if int(p.stem)>33:continue
 assert p.read_bytes()==original(p),p
 old_hashes[p.name]=sha(p.read_bytes())
assert len(old_hashes)==33
for p in (F/'manifest.json',F/'README.md'):assert p.read_bytes()==original(p),p
manifest=json.loads((F/'manifest.json').read_text())
for name,info in manifest['files'].items():
 data=(F/name).read_bytes();assert len(data)==info['bytes'] and sha(data)==info['sha256'],name
 assert data==original(F/name),name
spec=json.loads((ROOT/'app/src/test/resources/index183/expected-delta.json').read_text())
a=json.loads((S/'33.json').read_text());b=json.loads((S/'34.json').read_text());normalized=copy.deepcopy(b)
x=a['database'];y=normalized['database']
assert x['version']==33 and y['version']==34
assert x['identityHash']==spec['oldIdentityHash'] and y['identityHash']==spec['newIdentityHash']
assert x['setupQueries']==spec['oldSetupQueries'] and y['setupQueries']==spec['newSetupQueries']
y['version']=x['version'];y['identityHash']=x['identityHash'];y['setupQueries']=x['setupQueries']
for item in spec['indices']:
 entity=next(e for e in y['entities'] if e['tableName']==item['table'])
 assert entity['indices'].count(item['schemaIndex'])==1
 entity['indices'].remove(item['schemaIndex'])
assert a==normalized,'schema含白名单以外差异'
assert len(spec['indices'])==2 and len(spec['snapshots'])==36
assert len(spec['querySql'])==4
for rule in spec['querySql']:
 assert rule['snapshot']=='queries.txt' and rule['case'].startswith('fts.search_lines.') and rule['occurrences']==1
 assert rule['newLine']==rule['oldLine'].replace('FROM transcript_lines l WHERE','FROM transcript_lines l NOT INDEXED WHERE',1)
 section=(F/'queries.txt').read_text().split('CASE '+rule['case']+'\n',1)[1].split('CASE ',1)[0]
 assert section.splitlines().count(rule['oldLine'])==1
source=(ROOT/'app/src/main/java/app/resound/data/CaptureDatabase.kt').read_text()
body=re.search(r'val MIGRATION_33_34 = object : Migration\(33, 34\) \{\s*override fun migrate\([^\n]+\) \{(.*?)\n            \}',source,re.S).group(1)
statements=re.findall(r'db.execSQL\("([^"]+)"\)',body)
assert len(statements)==2 and all(s.startswith('CREATE INDEX IF NOT EXISTS ') for s in statements)
assert not re.sub(r'db.execSQL\("[^"]+"\)','',body).strip()
assert set(statements)=={i['schemaIndex']['createSql'].replace('${TABLE_NAME}',i['table']) for i in spec['indices']}
from benchmark import catalogue
old_queries=json.loads((ROOT/'app/src/test/resources/index183/old-queries.json').read_text())
baseline_queries=catalogue(True)
assert len(old_queries)==18
for key,sql in old_queries.items():assert sql==baseline_queries[key]['sql'],key
report={'old_schema_sha256':old_hashes,'old_fixture_manifest_sha256':sha((F/'manifest.json').read_bytes()),'old_fixture_count':len(manifest['files']),'allowed_schema_differences':{'version':[33,34],'identityHash':[spec['oldIdentityHash'],spec['newIdentityHash']],'setupQueries':[spec['oldSetupQueries'],spec['newSetupQueries']],'indexes':spec['indices']},'everything_else_equal':True,'migration_sql':statements}
(OUT/'schema与旧夹具核验.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('schema 1～33、39旧夹具、manifest原字节一致；34仅有批准元数据和两个索引差异；迁移只有两条幂等CREATE INDEX。')
