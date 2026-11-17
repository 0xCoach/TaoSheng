"""只追加夜里表/索引白名单，既有业务快照不改。依据 Room 40 与实际迁移 SQL。"""
import json,re,sqlite3
from pathlib import Path
root=Path(__file__).resolve().parents[2]
schema=json.loads((root/'shared/schemas/app.resound.data.CaptureDatabase/40.json').read_text())['database']
p=root/'app/src/test/resources/index183/expected-delta.json';spec=json.loads(p.read_text())
old=sqlite3.connect(':memory:');fresh=sqlite3.connect(':memory:')
for table in schema['entities']:
    if not(table['tableName']=='nights' or table['tableName'].startswith('night_')):continue
    fresh.execute(table['createSql'].replace('${TABLE_NAME}',table['tableName']))
    for index in table.get('indices',[]):fresh.execute(index['createSql'].replace('${TABLE_NAME}',table['tableName']))
text=(root/'shared/src/commonMain/kotlin/app/resound/night/NightStorage.kt').read_text().split('object NightSchema')[1]
for sql in re.findall(r'db.execSQL\("([^"\n]+)"\)',text):
    if '$' not in sql and ' INDEX ' not in sql:old.execute(sql)
for name,columns in re.findall(r'child\("([^"\n]+)", "([^"\n]+)"\)',text):
    old.execute(f'CREATE TABLE `{name}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nightId` INTEGER NOT NULL, {columns}, FOREIGN KEY(`nightId`) REFERENCES `nights`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)')
    old.execute(f'CREATE INDEX `index_{name}_nightId` ON `{name}` (`nightId`)')
for sql in re.findall(r'db.execSQL\("([^"\n]+)"\)',text):
    if '$' not in sql and ' INDEX ' in sql:old.execute(sql)
compact=lambda x:json.dumps(x,ensure_ascii=False,separators=(',',':'))
for typ,name,tbl,sql in fresh.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"):
    if typ=='table':
        rows=[compact([typ,name,tbl,sql])]
        actual=old.execute('SELECT sql FROM sqlite_master WHERE name=?',(name,)).fetchone()[0]
        if actual!=sql:rows.append(compact([typ,name,tbl,actual]))
        columns=[r[1] for r in fresh.execute(f'PRAGMA table_info(`{name}`)')]
        spec['tables']=[s for s in spec['tables'] if s['name']!=name]+[dict(name=name,masterRows=rows,block=['TABLE '+name,'columns='+compact(columns),''],occurrences=1)]
    elif typ=='index':
        assert old.execute('SELECT sql FROM sqlite_master WHERE name=?',(name,)).fetchone()[0]==sql
        spec['indices']=[s for s in spec['indices'] if s['name']!=name]+[dict(name=name,table=tbl,row=compact([typ,name,tbl,sql]),occurrences=1)]
spec['newIdentityHash']=schema['identityHash'];spec['newSetupQueries']=schema['setupQueries'];spec['version']['newLine']='user_version=40';spec['identity']['newRow']=compact([{'integer':42},{'text':schema['identityHash']}])
p.write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n')
