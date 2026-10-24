#!/usr/bin/env python3
"""用 App 同一份提示词离线试大模型整理效果。密钥只在运行时从环境变量读取，不打印。"""
import json, os, sys, time, urllib.request
import datetime
W = '一二三四五六日'
def calendar(today):
    """与 App 里 DigestCalendar 相同：今天、前 7 天、后 14 天，带周几和相对说法。"""
    t = datetime.date.fromisoformat(today)
    monday = t - datetime.timedelta(days=t.weekday())
    rows = []
    for k in range(-7, 15):
        d = t + datetime.timedelta(days=k)
        week = (d - monday).days // 7
        rel = {0: '今天', 1: '明天', 2: '后天', -1: '昨天', -2: '前天'}.get(k, '')
        wk = {0: '本周', 1: '下周', 2: '下下周', -1: '上周'}.get(week, '')
        rows.append(f"{d.isoformat()} 周{W[d.weekday()]}（{wk}{'周'+W[d.weekday()] if wk else ''}{'，'+rel if rel else ''}）")
    return '\n'.join(rows)
ROOT = os.path.dirname(os.path.abspath(__file__))
prompt = open(os.path.join(ROOT, '../../app/src/main/assets/prompts/digest.md'), encoding='utf-8').read()
today, transcript, model = sys.argv[1], open(sys.argv[2], encoding='utf-8').read(), sys.argv[3]
body = {"model": model, "messages": [
    {"role": "system", "content": prompt.replace('{{CALENDAR}}', calendar(today)).replace('{{APP_NAME}}', '回响')},
    {"role": "user", "content": transcript}],
    "response_format": {"type": "json_object"}, "thinking": {"type": "disabled"}, "temperature": 0.2}
if model.startswith('deepseek'):
    base, key = 'https://api.deepseek.com', open(os.path.expanduser('~/.config/resound/deepseek.key')).read().strip()
    body.pop('thinking')
else:
    base, key = os.environ['ARK_BASE_URL'], os.environ['ARK_API_KEY']
req = urllib.request.Request(base + '/chat/completions', json.dumps(body).encode(),
    {"Authorization": "Bearer " + key, "Content-Type": "application/json"})
t = time.time()
d = json.load(urllib.request.urlopen(req, timeout=180))
print(f"耗时 {time.time()-t:.1f}s  用量 {d['usage']['prompt_tokens']}+{d['usage']['completion_tokens']} tokens")
print(json.dumps(json.loads(d['choices'][0]['message']['content']), ensure_ascii=False, indent=1))
