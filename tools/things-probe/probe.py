#!/usr/bin/env python3
"""卡079只读离线验证。手机只执行 exec-out run-as cat；原话/响应仅保存 private。"""
import argparse, datetime as dt, hashlib, importlib.util, json, pathlib, re, shutil, sqlite3, subprocess, time, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from zoneinfo import ZoneInfo
HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PRIVATE = HERE / 'private'
ASSET = ROOT / 'app/src/main/assets/prompts/things.md'
ZONE = ZoneInfo('Asia/Shanghai')
# 复用与 DigestPrompt.calendar 等价的日历实现；拉库在本文件明确限制命令范围。
spec = importlib.util.spec_from_file_location('ask_probe', ROOT / 'tools/ask-probe/probe.py')
shared = importlib.util.module_from_spec(spec); spec.loader.exec_module(shared)
shared.PRIVATE = PRIVATE

def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')

def pull():
    """手机仅执行 run-as cat；先校验读取结果，避免把run-as错误当成数据库。"""
    PRIVATE.mkdir(exist_ok=True, mode=0o700)
    for attempt in range(4):
        reads=[]
        for _ in range(2):
            files={}
            for name in ('capture.db','capture.db-wal','capture.db-shm'):
                result=subprocess.run(['adb','exec-out','run-as','app.resound','cat','databases/'+name],capture_output=True,timeout=30)
                error=result.stdout + result.stderr
                if b'package not debuggable' in error:
                    raise RuntimeError('run-as: package not debuggable: app.resound；未改手机，也未覆盖现有快照')
                if name=='capture.db':
                    if result.returncode or not result.stdout.startswith(b'SQLite format 3\x00'):
                        raise RuntimeError('未读到SQLite主库；未改手机，也未覆盖现有快照')
                elif result.returncode or b'No such file' in error:
                    if b'No such file' in error: continue
                    raise RuntimeError('只读拉取数据库附属文件失败')
                files[name]=result.stdout
            reads.append(files)
        if any(reads[0].get(name)!=reads[1].get(name) for name in ('capture.db','capture.db-wal')):
            continue
        stage=PRIVATE/'pull-stage'; stage.mkdir(exist_ok=True)
        for suffix in ('','-wal','-shm','-journal'):
            (stage/('capture.db'+suffix)).unlink(missing_ok=True)
        for name,data in reads[1].items():
            if not name.endswith('-shm'): (stage/name).write_bytes(data)
        with sqlite3.connect(stage/'capture.db') as source:
            if source.execute('PRAGMA integrity_check').fetchone()[0]!='ok': continue
            pending=stage/'snapshot.db'; pending.unlink(missing_ok=True)
            with sqlite3.connect(pending) as destination: source.backup(destination)
        pending.replace(PRIVATE/'snapshot.db')
        dump(PRIVATE/'provenance.json',{'kind':'adb-read-only','at':dt.datetime.now(ZONE).isoformat(),
            'sha256':hashlib.sha256((PRIVATE/'snapshot.db').read_bytes()).hexdigest()})
        print('只读快照完成，完整性检查通过',flush=True)
        return
    raise RuntimeError('手机数据库持续变化，未取得稳定快照；未改手机')

def inputs(lines, context=(), known=()):
    return {'mode':'live','contextNote':'已处理过，只作参考，不得输出旧事实','context':list(context),'lines':list(lines),'known':list(known)}

def real_batches():
    db = sqlite3.connect(f'file:{PRIVATE / "snapshot.db"}?mode=ro', uri=True); db.row_factory=sqlite3.Row
    end=dt.datetime.now(ZONE); start=end-dt.timedelta(days=7)
    tables={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    media_sql="EXISTS(SELECT 1 FROM env_sounds e WHERE e.segmentId=l.segmentId AND e.reportedWrong=0 AND (e.markMedia=1 OR e.verdict='Media'))" if 'env_sounds' in tables else '0'
    all_rows=db.execute(f'''SELECT l.*, s.sourceId,
      {media_sql} AS media,
      EXISTS(SELECT 1 FROM memory_events e WHERE e.id=l.eventId AND e.deleted=1) AS hidden
      FROM transcript_lines l JOIN segments s ON l.segmentId=s.id WHERE l.startAt>=? AND l.startAt<=? ORDER BY l.id''', (int(start.timestamp()*1000),int(end.timestamp()*1000))).fetchall()
    def eligible(r): return r['sourceId']!='import' and not r['media'] and not r['hidden']
    def line(r): return {'id':r['id'],'segmentId':r['segmentId'],'at':dt.datetime.fromtimestamp(r['startAt']/1000,ZONE).isoformat(),
                        'speaker':r['speakerName'] or ('我' if r['speakerPersonId']==0 else '未知'),'text':r['text']}
    previous=db.execute(f'''SELECT l.*,s.sourceId, {media_sql} AS media,
      EXISTS(SELECT 1 FROM memory_events e WHERE e.id=l.eventId AND e.deleted=1) AS hidden FROM transcript_lines l JOIN segments s ON l.segmentId=s.id WHERE l.id<? ORDER BY l.id DESC''', (all_rows[0]['id'] if all_rows else 0,)).fetchall()
    history=[line(r) for r in reversed(previous) if eligible(r)]
    known=[]
    if 'things' in tables and 'thing_records' in tables:
        for thing in db.execute('SELECT * FROM things WHERE deleted=0 ORDER BY updatedAt DESC,id DESC'):
            records=db.execute("SELECT * FROM thing_records WHERE thingId=? AND status='Accepted' ORDER BY at DESC,id DESC",(thing['id'],)).fetchall()
            if not records: continue
            latest=records[0]; last=next((r['place'] for r in records if r['action']=='Placed' and r['place']),None)
            known.append({'id':thing['id'],'name':thing['name'],'aliases':json.loads(thing['aliasesJson']),
                          'action':latest['action'],'place':latest['place'],'lastPlace':last,'who':latest['who'],'owner':thing['owner']})
            if len(known)==200: break
    # 离线输出始终是未确认结果，不能自动加入后续请求的已确认清单。
    batches=[]; valid=[]
    for i in range(0,len(all_rows),80):
        fresh=[line(r) for r in all_rows[i:i+80] if eligible(r)]
        if fresh: batches.append(inputs(fresh,history[-20:],known))
        history.extend(fresh); valid.extend(fresh)
    dump(PRIVATE/'lines.json',valid); dump(PRIVATE/'batches.json',batches)
    dump(PRIVATE/'audit-candidates.json',[r for r in valid if re.search('放|搁|塞|借|拿走|在哪|收|带走|还回|抽屉|柜|钥匙|伞',r['text'])])
    data_range=db.execute('SELECT MIN(startAt),MAX(startAt) FROM transcript_lines').fetchone()
    summary={'from':start.isoformat(),'to':end.isoformat(),'raw_lines':len(all_rows),'eligible_lines':len(valid),'excluded':len(all_rows)-len(valid),'batches':len(batches),'known':len(known),'schema':db.execute('PRAGMA user_version').fetchone()[0],'media_available':'env_sounds' in tables,
        'data_from':dt.datetime.fromtimestamp(data_range[0]/1000,ZONE).isoformat() if data_range[0] else None,
        'data_to':dt.datetime.fromtimestamp(data_range[1]/1000,ZONE).isoformat() if data_range[1] else None,
        'snapshot_sha256':hashlib.sha256((PRIVATE/'snapshot.db').read_bytes()).hexdigest()}
    dump(PRIVATE/'summary.json',summary); print(json.dumps(summary,ensure_ascii=False),flush=True)
    return batches

CASES=[
('钥匙我搁鞋柜第二个抽屉了。','钥匙/Placed/鞋柜第二个抽屉'),
('你充电器我放餐桌上了。','充电器/Placed/餐桌/别人的'),
('伞在门后。','伞/Placed/门后'),
('待会儿我把相机放车里。','无：将来计划'),
('你把剪刀放厨房抽屉里。','无：命令别人'),
('要是有保险柜，我就把金条放里面。','无：假设'),
('电视里那个人说：我把钻石塞在床垫下面了。','无：电视台词'),
('项目文件在服务器上，钱还在银行卡里。','无：非实物'),
('东西总是乱放，真让人着急。','无：泛泛而谈'),
('放好了。','无：对象位置均不明'),
('我那副墨镜搁门口矮凳底下咧。','墨镜/Placed/门口矮凳底下'),
('护照塞行李箱夹层了，耳机收在床头柜。','护照/Placed/行李箱夹层；耳机/Placed/床头柜'),
('电钻借给老张了。','电钻/Lent/老张'),
('老张把电钻还回来了。','电钻/Returned/位置空'),
('老婆把蓝色保温杯带出门了。','蓝色保温杯/TakenAway/家人的'),
('帮我记住，备用门卡在玄关小盒子里。','备用门卡/Placed/玄关小盒子/explicit=true'),
('这个寻物功能应该能识别“我把手表放书柜了”，提示词怎么写？','无：设计讨论中的例句'),
('我的扳手放哪儿了？','无：仅疑问'),
('你问的那本字典就在客厅书柜最上层。','字典/Placed/客厅书柜最上层'),
('收妥了，就按刚说的那个地方。','红围巾/Placed/衣柜上层；位置来自已处理上下文'),
]

def case_batches():
    batches=[]
    for i,(text,expected) in enumerate(CASES,1):
        line={'id':i,'segmentId':i,'at':dt.datetime.now(ZONE).replace(hour=12,minute=0,second=0,microsecond=0).isoformat(),'speaker':'我','text':text}
        context=[]
        if i==20: context=[{'id':100,'segmentId':20,'at':line['at'],'speaker':'我','text':'红围巾就收衣柜上层吧。'}]
        batches.append(inputs([line],context))
    return batches

def run(mode, jobs, selected=None, reuse=False, force=False):
    PRIVATE.mkdir(exist_ok=True,mode=0o700)
    if mode=='real' and reuse:
        batches=json.loads((PRIVATE/'batches.json').read_text())
        summary=json.loads((PRIVATE/'summary.json').read_text())
        if 'snapshot_sha256' in summary:
            assert summary['snapshot_sha256']==hashlib.sha256((PRIVATE/'snapshot.db').read_bytes()).hexdigest(), '快照已变化，请重新准备批次'
    else: batches=real_batches() if mode=='real' else case_batches()
    template=ASSET.read_text(); fingerprint=hashlib.sha256(template.encode()).hexdigest()
    today=dt.datetime.now(ZONE).date(); system=template.replace('{{CALENDAR}}',shared.calendar(today))
    key=(pathlib.Path.home()/'.config/resound/deepseek.key').read_text().strip()
    out=PRIVATE/mode; out.mkdir(exist_ok=True)
    def one(i,payload):
        path=out/f'{i+1:04}.json'
        if path.exists() and not force:
            cached=json.loads(path.read_text())
            if cached.get('prompt_sha256')==fingerprint and cached.get('input')==payload: return i,len(cached['output']['items']),'cached'
        body={'model':'deepseek-chat','temperature':0.2,'response_format':{'type':'json_object'},'messages':[{'role':'system','content':system},{'role':'user','content':json.dumps(payload,ensure_ascii=False)}]}
        for attempt in range(3):
            try:
                request=urllib.request.Request('https://api.deepseek.com/chat/completions',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+key})
                with urllib.request.urlopen(request,timeout=200) as response: reply=json.load(response)
                parsed=json.loads(reply['choices'][0]['message']['content'].strip().removeprefix('```json').removeprefix('```').removesuffix('```'))
                assert isinstance(parsed['items'],list)
                ids={r['id'] for r in payload['lines']}
                for item in parsed['items']:
                    assert item['lineId'] in ids and item['name'].strip()
                    assert item['action'] in ['Placed','Lent','Returned','TakenAway']
                    assert item['action']!='Placed' or item.get('place')
                    assert item['action']!='Lent' or item.get('who')
                    assert item['owner'] in ['Mine','Family','Others']
                    assert isinstance(item['explicit'],bool) and isinstance(item['confident'],bool)
                    assert item['knownId'] is None or item['knownId'] in {v['id'] for v in payload['known']}
                if path.exists():
                    prior=json.loads(path.read_text()); archive=PRIVATE/'history'/mode/prior['prompt_sha256']; archive.mkdir(parents=True,exist_ok=True)
                    shutil.copyfile(path,archive/path.name)
                dump(path,{'prompt_sha256':fingerprint,'input':payload,'output':parsed,'usage':reply.get('usage'), 'model':reply.get('model'),
                    'evaluated_at':dt.datetime.now(ZONE).isoformat()})
                return i,len(parsed['items']),'ok'
            except Exception as e:
                if attempt==2: raise RuntimeError(f'batch {i+1} failed ({type(e).__name__})') from None
                time.sleep(2)
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        if selected is not None: assert all(1<=n<=len(batches) for n in selected), '批次越界'
        futures=[pool.submit(one,i,p) for i,p in enumerate(batches) if selected is None or i+1 in selected]
        for f in as_completed(futures):
            i,count,status=f.result(); print(f'{mode} {i+1}/{len(batches)}: {count} {status}',flush=True)
    if mode=='real':
        real_report(batches,out,fingerprint)
    if mode=='cases':
        rows=['# 寻物自编20句离线验证','',f'模型 deepseek-chat；提示词 SHA-256 `{fingerprint}`。与 App 使用同一 things.md；独立场景逐句请求。','','|#|原话|预期|模型结果|人工判定|','|---|---|---|---|---|']
        for i,(text,expected) in enumerate(CASES,1):
            items=json.loads((out/f'{i:04}.json').read_text())['output']['items']
            output='；'.join('/'.join(str(x.get(k,'')) for k in ['name','action','place','who','owner','explicit']) for x in items) or '无'
            rows.append(f'|{i}|{text}|{expected}|{output}|待审核|')
        rows+=['','第20句上文（已处理，仅参考）：“红围巾就收衣柜上层吧。”']
        (HERE/'cases.md').write_text('\n'.join(rows)+'\n')


def local_snapshot(source):
    """只复制用户指定本机副本；不修改原数据库，不把旧副本称作本轮手机拉库。"""
    source=pathlib.Path(source).resolve(); PRIVATE.mkdir(exist_ok=True,mode=0o700)
    target=PRIVATE/'capture.db'
    assert source!=target.resolve()
    for suffix in ('','-wal','-shm'):
        dest=pathlib.Path(str(target)+suffix); dest.unlink(missing_ok=True)
        original=pathlib.Path(str(source)+suffix)
        if suffix!='-shm' and original.exists(): shutil.copyfile(original,dest)
    with sqlite3.connect(target) as src:
        assert src.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        with sqlite3.connect(PRIVATE/'snapshot.db') as dst: src.backup(dst)
    dump(PRIVATE/'provenance.json',{'kind':'existing-local-copy','path':str(source),
        'source_mtime':dt.datetime.fromtimestamp(source.stat().st_mtime,ZONE).isoformat(),
        'sha256':hashlib.sha256(source.read_bytes()).hexdigest()})

def real_report(batches,out,fingerprint):
    summary=json.loads((PRIVATE/'summary.json').read_text())
    def escape(v): return str(v).replace('|','\\|').replace('\n',' ')
    revisions={}
    for i,batch in enumerate(batches,1):
        result=json.loads((out/f'{i:04}.json').read_text())
        assert result['input']==batch, '结果与当前快照批次不符'
        revisions.setdefault(result['prompt_sha256'],[]).append(i)
    rows=['# 寻物真实原话离线验证','',f'提示词 SHA-256 `{fingerprint}`。',
          f"窗口 {summary['from']} 至 {summary['to']}；原始 {summary['raw_lines']} 句，合格 {summary['eligible_lines']} 句，排除 {summary['excluded']} 句，共 {summary['batches']} 批。",
          '每批至多80句新原话，前20句为上文。真实数据完整性及来源见本机 private/provenance.json；该原始文件不提交。',
          '下表合并首轮与按根因复验的受影响批次，不代表所有批次都用最终提示词重复跑过。各批次实际提示词版本：'+ '; '.join(f"{sha}: {','.join(map(str,ids))}" for sha,ids in revisions.items()),
          '', '|批次|原话id|原话|名字|动作|位置/借用人|把握|判定及理由|','|---|---|---|---|---|---|---|---|']
    count=0
    for i,batch in enumerate(batches,1):
        result=json.loads((out/f'{i:04}.json').read_text())
        assert result['input']==batch, '结果与当前快照批次不符'
        byid={r['id']:r for r in batch['lines']}
        for item in result['output']['items']:
            count+=1; line=byid[item['lineId']]
            values=[i,item['lineId'],line['text'],item['name'],item['action'],item.get('place') or item.get('who') or '空',item['confident'],'待人工审核']
            rows.append('|'+ '|'.join(escape(v) for v in values)+'|')
    rows+=['',f'抓到 {count} 条；错几条、准确率、明显漏了几条：待人工逐条审核后填写。',
           '漏检审阅候选为 private/audit-candidates.json，关键词只用于人工复核，不用于模型预过滤。']
    (HERE/'results.md').write_text('\n'.join(rows)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--pull',action='store_true');p.add_argument('--stats',action='store_true');p.add_argument('--local-snapshot',type=pathlib.Path);p.add_argument('--run',choices=['real','cases']);p.add_argument('--jobs',type=int,default=3)
    p.add_argument('--batches',help='只请求指定批次，如 3,8,12；其余使用原结果')
    p.add_argument('--reuse-batches',action='store_true',help='复用本轮固定窗口/批次，不随时间重新分批')
    p.add_argument('--force',action='store_true',help='即使有同提示词缓存也实际复验')
    a=p.parse_args()
    if a.local_snapshot: local_snapshot(a.local_snapshot)
    if a.pull:
        pull()
    if a.stats: real_batches()
    if a.run: run(a.run,min(max(a.jobs,1),4),set(map(int,a.batches.split(','))) if a.batches else None,a.reuse_batches,a.force)
