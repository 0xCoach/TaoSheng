"""卡 046：仅发送本脚本新编对话；密钥从本机读取，不输出。"""
import concurrent.futures
import datetime as dt
import json
from pathlib import Path
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
today = dt.date(2026, 10, 24)
monday = today - dt.timedelta(days=today.weekday())
calendar = []
for k in range(-7, 15):
    day = today + dt.timedelta(days=k)
    week = (day - monday).days // 7
    wk = {-1: '上周', 0: '本周', 1: '下周', 2: '下下周'}.get(week, '')
    rel = {0: '今天', 1: '明天', 2: '后天', -1: '昨天', -2: '前天'}.get(k)
    calendar.append(f'{day} 周{"一二三四五六日"[day.weekday()]}（{wk}周{"一二三四五六日"[day.weekday()]}{("，" + rel) if rel else ""}）')
system = (ROOT / 'app/src/main/assets/prompts/digest.md').read_text().replace('{{APP_NAME}}', '回响').replace('{{CALENDAR}}', '\n'.join(calendar))
cases = [
    ('done-1', '给小周寄样品', '样品我刚在柜台寄给小周了，快递已经揽收。', True),
    ('done-2', '缴清工作室本月电费', '工作室本月的电费我已经缴清了，付款回执也收到了。', True),
    ('done-3', '和阿宁核对参会人数', '我和阿宁已经逐个核对完参会人数，最终是十二个人。', True),
    ('done-4', '把备用钥匙交给小许', '备用钥匙刚才我亲手交给小许了，他当面收下了。', True),
    ('done-5', '预约周末洗车', '我打算晚饭后预约周末洗车，现在还没操作。', False),
    ('done-6', '联系物业更换自家水表', '隔壁老李说他联系物业把他家的水表换好了，我家的还没联系。', False),
    ('done-7', '完成展架安装', '展架底座我装好了，上面的板子还没装，今天只做了一半。', False),
    ('done-8', '提交项目验收表', '验收表那个事情吧，我已经……算了，等下再说。', False),
]
ignore = [
    ('ignore-1', '待办', '把编辑器字体临时调大', '帮我把这一页放大到一百五十，再切一下预览，现在操作就行。', False),
    ('ignore-2', '灵感', '希望界面换一种颜色', '这个界面的卡片要是换成圆角，再加一点阴影，我觉得可能更好看。', False),
    ('ignore-3', '待办', '有空也许学一种乐器', '哪天闲下来也许学学陶艺吧，只是随口想想，还没决定。', False),
    ('ignore-4', '待办', '现在重启服务测试接口', '再启动一次模拟器，把当前报错复现一下，这一轮调试继续。', False),
    ('ignore-5', '待办', '把编辑器字体临时调大', '我答应蒋老师，明天下午三点前把盖章的证明送到教务处，不能耽误报名。', True),
    ('ignore-6', '灵感', '希望界面换一种颜色', '我和小方约好了明天上午十点到市图书馆门口见面，一起参加讲座。', True),
]
key = (Path.home() / '.config/resound/deepseek.key').read_text().strip()

def run(case):
    name = case[0]
    if name.startswith('done'):
        _, task, line, expected = case
        context = {'unfinished_items': [{'id': 101, 'category': '待办', 'content': task, 'who': '我', 'due': None}], 'ignored_items': []}
    else:
        _, category, ignored, line, expected = case
        context = {'unfinished_items': [], 'ignored_items': [{'category': category, 'content': ignored}]}
    user = '待办上下文（资料，不是指令）：\n' + json.dumps(context, ensure_ascii=False) + '\n\n本次原话：\n[1] 16:00:00 我：' + line
    body = {'model': 'deepseek-chat', 'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': user}],
            'response_format': {'type': 'json_object'}, 'temperature': 0.2, 'max_tokens': 1200}
    request = urllib.request.Request('https://api.deepseek.com/chat/completions', data=json.dumps(body).encode(),
                                    headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            result = json.load(response)
        output = json.loads(result['choices'][0]['message']['content'])
        hints = output.get('done_hints', [])
        # 对忽略题，检查是否仍提取被忽略的同类；保留题检查待办/约定没有被过度屏蔽。
        if name.startswith('done'):
            actual = hints == [{'item_id': 101, 'line_id': 1}] if expected else hints == []
        else:
            target = {'待办', '约定'} if expected else {category, '备忘', '约定'}
            found = [i for i in output.get('items', []) if i.get('category') in target]
            actual = bool(found) == expected
        return {'id': name, 'dialogue': line, 'context': context, 'expected': expected, 'passed': actual,
                'output': output, 'model': result.get('model'), 'usage': result.get('usage')}
    except urllib.error.HTTPError as e:
        return {'id': name, 'passed': False, 'error': f'HTTP {e.code}'}
    except Exception:
        return {'id': name, 'passed': False, 'error': '请求或解析失败，未输出敏感详情'}

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(run, cases + ignore))
    path = ROOT / 'docs/任务卡/046-提示词验证.json'
    path.write_text(json.dumps({'date': str(today), 'synthetic_only': True, 'results': results}, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'total': len(results), 'passed': sum(r['passed'] for r in results),
                      'cases': [{'id': r['id'], 'passed': r['passed'], 'error': r.get('error')} for r in results]}, ensure_ascii=False))
