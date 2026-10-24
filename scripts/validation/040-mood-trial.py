"""只读本机授权库副本，仅发最多 16 句/2400 字本人原话到 DeepSeek。
密钥由脚本读；仅输出统计，不保存原话、请求、回复或密钥。
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import sqlite3
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

parser = argparse.ArgumentParser()
parser.add_argument('snapshot', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
assets = root / 'app/src/main/assets/skills/mood_diary'
skill = json.loads((assets / 'skill.json').read_text())
prompt = (assets / 'prompt.md').read_text()
source = (root / 'app/src/main/java/app/resound/skills/SkillInput.kt').read_text()
rules = source.split('const val RULES = """', 1)[1].split('"""', 1)[0]
with sqlite3.connect(args.snapshot.resolve().as_uri() + '?mode=ro&immutable=1', uri=True) as db:
    rows = db.execute("""SELECT l.id,l.startAt,l.text FROM transcript_lines l
        JOIN segments s ON s.id=l.segmentId LEFT JOIN memory_events e ON e.id=l.eventId
        WHERE l.speakerName='我' AND s.status='Done' AND (e.id IS NULL OR e.deleted=0)
        ORDER BY l.startAt,l.id""").fetchall()
zone = ZoneInfo('Asia/Shanghai')
date = datetime.fromtimestamp(rows[-1][1] / 1000, zone).date().isoformat()
rows = [r for r in rows if datetime.fromtimestamp(r[1] / 1000, zone).date().isoformat() == date]
# 优先情绪关键词附近的完整原话；不将关键词匹配视为明确情绪表达结论。
pattern = re.compile('好烦|开心|累死|焦虑|紧张|生气|难过|烦躁|高兴|害怕|担心|疲惫|心情|压力')
centers = [i for i, r in enumerate(rows) if pattern.search(r[2])]
indices = sorted({j for i in centers for j in range(max(0, i-3), min(len(rows), i+4))}) or list(range(min(12, len(rows))))
selected = []
chars = 0
for i in indices:
    row = rows[i]
    if len(selected) >= 16 or chars + len(row[2]) > 2400:
        break
    # 授权有限原话；额外跳过可能的号码、邮件、网址。
    if re.search(r'\d{7,}|https?://|\S+@\S+', row[2]):
        continue
    selected.append(row)
    chars += len(row[2])
assert selected
system = rules + '\n归属日期：' + date + '\n技能要求：\n' + prompt + '\n卡片字段：\n'
for f in skill['fields']:
    system += f"{f['key']} | {f['label']} | {f['type']} | {f['hint']} | {'必填' if f['required'] else '可选'}\n"
system += '''只输出 JSON：{"cards":[{"entity":"日期","title":"标题","summary":"一句摘要","at":"HH:mm","end":null,"values":{},"evidence":["L1"]}],"nothing_reason":"不出卡的原因"}。
Text 用字符串，Tags/Bullets 用字符串数组，Quotes 仅用 L 行号数组，最多3条。evidence 仅用本次原话的 L 行号；一天最多1张；entity 固定为日期。'''
text = f'归属日期：{date}；以下是当天有限原话样本，不代表完整一天。\n原话：\n'
for i, (_, at, line) in enumerate(selected, 1):
    text += f'[L{i}] {datetime.fromtimestamp(at / 1000, zone):%Y-%m-%d %H:%M:%S} 我：{line}\n'
user = '<untrusted_data>\n' + text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') + '\n</untrusted_data>'
key = (Path.home() / '.config/resound/deepseek.key').read_text().strip()
body = {'model': 'deepseek-chat', 'messages': [{'role':'system','content':system},{'role':'user','content':user}],
        'response_format': {'type':'json_object'}, 'temperature':.2, 'max_tokens':1200}
request = urllib.request.Request('https://api.deepseek.com/chat/completions',
    data=json.dumps(body).encode(), headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
try:
    with urllib.request.urlopen(request, timeout=180) as response:
        result = json.load(response)
except urllib.error.HTTPError as error:
    raise SystemExit(f'DeepSeek HTTP {error.code}，未输出请求或响应正文')
except Exception:
    raise SystemExit('DeepSeek 请求失败，未输出凭据或原话')
output = json.loads(result['choices'][0]['message']['content'])
valid = {f'L{i}' for i in range(1, len(selected)+1)}
cards = output['cards']
assert len(cards) <= 1
for card in cards:
    assert isinstance(card['values']['one_line'], str) and card['values']['one_line'].strip()
    assert card['evidence'] and set(card['evidence']) <= valid
    assert len(card['values'].get('quotes', [])) <= 3
    assert set(card['values'].get('quotes', [])) <= valid
if not cards:
    assert output['nothing_reason'] == '今天没有明显的情绪表达'
print(json.dumps({'date':date, 'source_own_lines':len(rows), 'sent_lines':len(selected), 'sent_characters':chars,
    'cards':len(cards), 'reason':output.get('nothing_reason') if not cards else None,
    'evidence_valid':True, 'quote_count':sum(len(c['values'].get('quotes',[])) for c in cards),
    'usage':result.get('usage',{})}, ensure_ascii=False, indent=2))
