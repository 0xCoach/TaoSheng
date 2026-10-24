#!/usr/bin/env python3
"""只读拉库；在本机副本查询，用共享提示词/SQL/工具定义验证真实问答。密钥不输出。"""
import argparse, datetime as dt, json, pathlib, re, sqlite3, subprocess, urllib.request
from zoneinfo import ZoneInfo
ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
PRIVATE = HERE / 'private'
ASSETS = ROOT / 'app/src/main/assets/prompts'
ZONE = ZoneInfo('Asia/Shanghai')

def pull():
    PRIVATE.mkdir(exist_ok=True, mode=0o700)
    # 在手机保持运行时双读主库和 WAL，稳定且 integrity_check 通过才采用。
    for attempt in range(4):
        batches=[]
        for _ in range(2):
            files={}
            for name in ['capture.db','capture.db-wal','capture.db-shm']:
                p=subprocess.run(['adb','exec-out','run-as','app.resound','cat','databases/'+name],capture_output=True)
                if p.returncode:
                    if name=='capture.db' or b'No such file' not in p.stderr: raise RuntimeError('只读拉库失败')
                else: files[name]=p.stdout
            batches.append(files)
        if all(batches[0].get(n)==batches[1].get(n) for n in ['capture.db','capture.db-wal']):
            for name in ['capture.db','capture.db-wal','capture.db-shm']:
                path=PRIVATE/name
                path.unlink(missing_ok=True)
                # SHM 是进程锁状态，在本地由 SQLite 重建，不复用手机的锁。
                if name in batches[1] and not name.endswith('-shm'): path.write_bytes(batches[1][name])
            src=sqlite3.connect(PRIVATE/'capture.db')
            if src.execute('PRAGMA integrity_check').fetchone()[0]!='ok': src.close(); continue
            dest=sqlite3.connect(PRIVATE/'snapshot.db'); src.backup(dest); dest.close(); src.close()
            print('只读快照完成，完整性检查通过',flush=True); return
    raise RuntimeError('数据库正在变化，未取得稳定快照；未操作手机写入')

def calendar(today):
    monday=today-dt.timedelta(days=today.weekday()); rows=[]
    for k in range(-7,15):
        d=today+dt.timedelta(days=k); week=(d-monday).days//7; w='一二三四五六日'[d.weekday()]
        wk={0:'本周',1:'下周',2:'下下周',-1:'上周'}.get(week)
        rel={0:'今天',1:'明天',2:'后天',-1:'昨天',-2:'前天'}.get(k)
        rows.append(f'{d} 周{w}（'+(wk+'周'+w if wk else '')+('，'+rel if rel else '')+'）')
    return '\n'.join(rows)

def timestamp(value,end=False):
    if not isinstance(value,str) or len(value) not in [10,16]: raise ValueError('date')
    fmt='%Y-%m-%d' if len(value)==10 else '%Y-%m-%d %H:%M'
    d=dt.datetime.strptime(value,fmt).replace(tzinfo=ZONE)
    if end: d+=dt.timedelta(days=1) if len(value)==10 else dt.timedelta(minutes=1)
    return int(d.timestamp()*1000)

def query(name,args):
    spec=json.loads((ASSETS/'ask-sql.json').read_text())[name]
    allowed={'search_lines':{'keywords','from','to','speaker'},'search_events':{'keywords','from','to','starred_only'},'list_items':{'category','status','from','to'},'get_day':{'date'}}[name]
    if not isinstance(args,dict) or set(args)-allowed: raise ValueError('arguments')
    if name=='search_lines' and 'keywords' not in args: raise ValueError('keywords')
    if name=='get_day' and len(args['date'])!=10: raise ValueError('date')
    where=[spec['where']]; params=[]
    if 'starred_only' in args and not isinstance(args['starred_only'],bool): raise ValueError('starred_only')
    if args.get('starred_only'): where.append('e.starred=1')
    if name=='get_day': args={'from':args['date'],'to':args['date']}
    for field,op in [('from','>='),('to','<')]:
        if args.get(field): where.append(spec['time']+op+'?'); params.append(timestamp(args[field],field=='to'))
    if args.get('from') and args.get('to') and timestamp(args['from'])>=timestamp(args['to'],True): raise ValueError('range')
    kws=args.get('keywords',[])
    if not isinstance(kws,list) or len(kws)>8: raise ValueError('keywords')
    ors=[]
    for kw in kws:
        if not isinstance(kw,str) or not kw.strip() or len(kw)>80: raise ValueError('keyword')
        for col in spec['keywords']:
            ors.append(col+" LIKE ? ESCAPE '\\'"); params.append('%'+kw.strip().replace('\\','\\\\').replace('%','\\%').replace('_','\\_')+'%')
    if ors: where.append('('+' OR '.join(ors)+')')
    for field in ['speaker','category','status']:
        if args.get(field) and field in spec:
            if not isinstance(args[field],str) or not 1<=len(args[field])<=80: raise ValueError(field)
            if field=='category' and args[field] not in ['待办','约定','备忘','灵感','关键信息','决定','知识']: raise ValueError(field)
            if field=='status' and args[field] not in ['Pending','Accepted','Ignored','Done']: raise ValueError(field)
            cols=spec[field] if isinstance(spec[field],list) else [spec[field]]
            where.append('('+' OR '.join(c+'=?' for c in cols)+')'); params.extend([args[field]]*len(cols))
    prefix='l' if name=='search_lines' else 'i' if name=='list_items' else 'e'
    order='e.starredAt' if args.get('starred_only') and not kws else spec['time']
    sql='SELECT '+spec['select']+' FROM '+spec['table']+' WHERE '+' AND '.join(where)+' ORDER BY '+order+' DESC, '+prefix+'.id DESC LIMIT 41'
    return sql,params,spec['kind']

class Search:
    def __init__(self,db): self.db=db; self.sources=[]
    def call(self,name,args):
        sql,params,kind=query(name,args); rows=self.db.execute(sql,params).fetchall(); result=[]; truncated=len(rows)>40
        for row in rows[:40]:
            r=dict(row)
            if "starred" in r: r["starred"]=bool(r["starred"])
            for k,v in r.items():
                if isinstance(v,str): r[k]=v[:900]
            source={'kind':kind,'id':r['id'],'at':r['at'],'title':r['title'],'quote':r['quote']}
            try: ref=self.sources.index(source)+1
            except ValueError: ref=len(self.sources)+1
            r.update(ref=ref,kind=kind,time=dt.datetime.fromtimestamp(r['at']/1000,ZONE).strftime('%Y-%m-%d %H:%M'))
            if len(json.dumps({'results':result+[r],'truncated':False},ensure_ascii=False,separators=(',',':')))>6000: truncated=True; break
            if ref>len(self.sources): self.sources.append(source)
            result.append(r)
        return {'results':result,'truncated':truncated}

def stream(messages,tools,key,allow_tools=True):
    body={'model':'deepseek-flash','messages':messages,'tools':tools,'tool_choice':'auto','stream':True,'stream_options':{'include_usage':True},'temperature':0.2,'max_tokens':1800}
    if not allow_tools: body['tool_choice']='none'
    req=urllib.request.Request('https://api.deepseek.com/chat/completions',json.dumps(body).encode(),{'Authorization':'Bearer '+key,'Content-Type':'application/json'})
    text=''; calls={}; usage={}; done=False
    with urllib.request.urlopen(req,timeout=100) as response:
        for line in response:
            line=line.decode().strip()
            if not line.startswith('data:'): continue
            payload=line[5:].strip()
            if payload=='[DONE]': done=True; break
            j=json.loads(payload)
            if j.get('usage'): usage=j['usage']
            for ch in j.get('choices',[]):
                delta=ch.get('delta',{}); text+=delta.get('content') or ''
                for c in delta.get('tool_calls',[]):
                    a=calls.setdefault(c['index'],{'id':'','type':'function','function':{'name':'','arguments':''}})
                    a['id']+=c.get('id',''); f=c.get('function',{})
                    for k in ['name','arguments']: a['function'][k]+=f.get(k,'')
    if not done: raise RuntimeError('SSE 未完整结束')
    msg={'role':'assistant','content':text or None}
    if calls: msg['tool_calls']=[calls[i] for i in sorted(calls)]
    return msg,usage

def run(selected=None):
    db=sqlite3.connect(f'file:{PRIVATE / "snapshot.db"}?mode=ro',uri=True); db.row_factory=sqlite3.Row
    tools=json.loads((ASSETS/'ask-tools.json').read_text()); today=dt.datetime.now(ZONE).date()
    prompt=(ASSETS/'ask.md').read_text().replace('{{CALENDAR}}',calendar(today)).replace('{{APP_NAME}}','回响').replace('{{TODAY}}',str(today))
    key=(pathlib.Path.home()/'.config/resound/deepseek.key').read_text().strip()
    questions=['今天我都做了什么','客户要什么会议室','我说过哪些产品想法','手机日志的事后来怎么样了','下午三点的会是什么','我给火星基地订了多少吨氦三燃料']
    results=[]
    if selected:
        previous=json.loads((PRIVATE/'results.json').read_text())
        results=[r for r in previous if r['question'] not in selected]
    for q in questions:
        if selected and q not in selected: continue
        search=Search(db); messages=[{'role':'system','content':prompt},{'role':'user','content':q}]; trace=[]; total=0; usages=[]
        for _ in range(7):
            if total>=6:
                messages.append({'role':'system','content':'本轮工具额度已用完。现在只根据已有原话出处给最终回答；没查到就说没找到。不再调用工具，不输出工具标记。'})
            reply,usage=stream(messages,tools,key,total<6); usages.append(usage); messages.append(reply)
            calls=reply.get('tool_calls',[])
            if not calls: break
            if total>=6: raise RuntimeError('服务未遵守工具调用上限')
            for c in calls:
                f=c['function']; total+=1
                if total>6: result={'error':'工具次数已用完，请根据已有出处回答；没有就说没找到'}
                else:
                    try: result=search.call(f['name'],json.loads(f['arguments']))
                    except (ValueError,KeyError): result={'error':'参数无效，请检查日期与工具参数'}
                trace.append({'name':f['name'],'arguments':f['arguments'],'result':result})
                messages.append({'role':'tool','tool_call_id':c['id'],'content':json.dumps(result,ensure_ascii=False)})
        answer=reply.get('content') or ''
        refs=[int(n) for n in re.findall(r'\[\^(\d+)\]',answer)]
        valid=all(1<=n<=len(search.sources) for n in refs)
        if not answer or 'DSML' in answer or (not refs and '没找到' not in answer): raise RuntimeError('回答未通过出处或正文检查')
        results.append({'question':q,'calls':trace,'answer':answer,'sources':search.sources,'refs_valid':valid,'usage':usages})
        (PRIVATE/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
        print(f'已完成 {len(results)}/6，工具 {min(total,6)} 次，引用编号有效={valid}',flush=True)
    results.sort(key=lambda r: questions.index(r['question']))
    (PRIVATE/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    lines=['# 问问离线实测','',f'日期：{today}；模型：deepseek-flash；手机只读快照；共享 ask.md / ask-tools.json / ask-sql.json。','', '以下为真实问答与被引用摘录；完整工具结果及原始数据库仅保存在已忽略的 private/。','']
    for r in results:
        lines+=['## '+r['question'],'','工具调用：']
        for c in r['calls']: lines.append('- `'+c['name']+'('+c['arguments']+')` → '+str(len(c['result'].get('results',[])))+' 条；截断='+str(c['result'].get('truncated',False)))
        lines+=['',r['answer'],'','出处核验：']
        for n in sorted(set(int(n) for n in re.findall(r'\[\^(\d+)\]',r['answer']))):
            if not 1<=n<=len(r['sources']): lines.append(f'- [^{n}] 无效'); continue
            s=r['sources'][n-1]; lines.append(f'- [^{n}] {s["kind"]} #{s["id"]}，{dt.datetime.fromtimestamp(s["at"]/1000,ZONE):%m-%d %H:%M}，{s["title"]}：{s["quote"]}')
        lines+=['',f'编号检查：{r["refs_valid"]}。出处直接取自本地 SQL 结果；语义复核见文末。','']
    (HERE/'results.md').write_text('\n'.join(lines)); db.close()

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--pull',action='store_true'); p.add_argument('--run',action='store_true'); p.add_argument('--question',action='append'); a=p.parse_args()
    try:
        if a.pull: pull()
        if a.run: run(a.question)
    except Exception as e:
        # 不打印网络异常全文、请求体或密钥。
        print('验证未完成：'+type(e).__name__); raise SystemExit(1)
