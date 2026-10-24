#!/usr/bin/env python3
"""026 合成数据对比：只在电脑运行，不接触手机/真实库；--cloud 才发一次 DeepSeek 请求。"""
import argparse, hashlib, json, pathlib, sqlite3, sys, urllib.request

REV = '75c43b069aac4d136ba6bc1122f995fedcfd2781'
BASE = f'https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/{REV}/'
FILES = {
    'onnx/model_quantized.onnx': ('15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc', 24010842),
    'vocab.txt': ('45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c', 109540),
}
ROWS = [
    {'id': 1, 'title': '交通票据', 'summary': '明天把车费发票交给财务', 'points': ['整理票据'], 'quote': '明天把打车的钱和车费发票整理一下，交给财务。'},
    {'id': 2, 'title': '晚饭安排', 'summary': '今晚吃面条', 'points': ['去楼下面馆'], 'quote': '今晚去楼下面馆吃面条。'},
    {'id': 3, 'title': '周末运动', 'summary': '星期六去公园跑步', 'points': ['带运动鞋'], 'quote': '星期六早上去公园跑步，记得带运动鞋。'},
]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--cloud', action='store_true')
    args = parser.parse_args()
    import numpy as np
    import onnxruntime as ort
    from tokenizers import BertWordPieceTokenizer
    args.cache.mkdir(parents=True, exist_ok=True)
    for remote, (sha, size) in FILES.items():
        target = args.cache / pathlib.Path(remote).name
        if not target.exists():
            with urllib.request.urlopen(BASE + remote, timeout=120) as response:
                target.write_bytes(response.read())
        assert target.stat().st_size == size and hashlib.sha256(target.read_bytes()).hexdigest() == sha
    tokenizer = BertWordPieceTokenizer(str(args.cache / 'vocab.txt'), lowercase=False, strip_accents=False)
    tokenizer.enable_truncation(max_length=512)
    session = ort.InferenceSession(str(args.cache / 'model_quantized.onnx'), providers=['CPUExecutionProvider'])
    def embed(text, query=False):
        ids = tokenizer.encode(('为这个句子生成表示以用于检索相关文章：' if query else '') + text).ids
        inputs = {'input_ids': np.array([ids],dtype=np.int64), 'attention_mask': np.ones((1,len(ids)),dtype=np.int64), 'token_type_ids':np.zeros((1,len(ids)),dtype=np.int64)}
        inputs = {i.name: inputs[i.name] for i in session.get_inputs()}
        outputs = dict(zip([o.name for o in session.get_outputs()], session.run(None,inputs)))
        v = outputs['last_hidden_state'][0,0,:]
        return v / np.linalg.norm(v)
    vectors = [embed(r['title']+'\n'+r['summary']+'\n'+json.dumps(r['points'],ensure_ascii=False,separators=(',',':'))) for r in ROWS]
    query = '报销'; question = '关于报销，我要准备什么？'
    q = embed(query,True)
    ranking = sorted([(row['id'],float(np.dot(q,v))) for row,v in zip(ROWS,vectors)],key=lambda p:-p[1])
    db = sqlite3.connect(':memory:')
    db.execute('CREATE TABLE memories(id INTEGER,title TEXT,summary TEXT,points TEXT)')
    db.executemany('INSERT INTO memories VALUES(?,?,?,?)',[(r['id'],r['title'],r['summary'],json.dumps(r['points'],ensure_ascii=False)) for r in ROWS])
    old = [r[0] for r in db.execute('SELECT id FROM memories WHERE title LIKE ? OR summary LIKE ? OR points LIKE ?', ['%'+query+'%']*3)]
    assert old == [] and ranking[0][0] == 1
    samples = ['你好，BGE 2026！', 'école ABC foo-bar', '报销']
    result = {'model_revision':REV,'model_bytes':24010842,'dim':len(q),'onnx_inputs':[i.name for i in session.get_inputs()],
              'question':question,'keyword':query,'before_like_ids':old,'after_semantic_ranking':ranking,
              'tokenizer_examples':{s:tokenizer.encode(s).ids for s in samples},
              'validation_scope':'电脑 CPU ONNX + 三条合成记录；不代表 Android 真机验收','cloud_requests':0}
    if args.cloud:
        # 读取后仅放 Authorization；不打印密钥、请求头、异常响应体或原始请求。
        key = (pathlib.Path.home()/'.config/resound/deepseek.key').read_text().strip()
        relevant = next(r for r in ROWS if r['id']==ranking[0][0])
        body = {'model':'deepseek-flash','temperature':0,'max_tokens':600,'messages':[
            {'role':'system','content':'这是合成数据检索对比。只根据给定记录回答，不补全经历。请分别用“改前：”和“改后：”简短回答同一个问题。空结果必须说没找到；有结果引用[^1]。'},
            {'role':'user','content':json.dumps({'question':question,'before_records':[], 'after_records':[dict(relevant,ref=1)]},ensure_ascii=False)}]}
        request = urllib.request.Request('https://api.deepseek.com/chat/completions',json.dumps(body).encode(),
                                        {'Authorization':'Bearer '+key,'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(request,timeout=90) as response:
                reply = json.load(response)
            result.update(cloud_requests=1, cloud_model=reply.get('model'), cloud_answer=reply['choices'][0]['message']['content'],usage=reply.get('usage'))
        except Exception:
            result.update(cloud_requests=1,cloud_error='真实问答请求未完成，未记录敏感请求或响应')
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__ == '__main__':
    main()
