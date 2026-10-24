#!/usr/bin/env python3
"""仅通过 adb cat 只读拉库；只向 DeepSeek 发送指定日期已整理事件和提取项。"""
import argparse
import datetime as dt
import importlib.util
import json
import pathlib
import sqlite3
import urllib.request
from zoneinfo import ZoneInfo

ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
PRIVATE = HERE / 'private'
ZONE = ZoneInfo('Asia/Shanghai')
LIMITS = {'一句话': 1, '要紧的': 3, '发生了什么': 8, '待办': 6, '值得留意': 3, '灵感': 5, '心力': 1}


def pull():
    # 复用已有双读主库/WAL和本地 integrity_check；没有任何手机写入命令。
    spec = importlib.util.spec_from_file_location('ask_probe', ROOT / 'tools/ask-probe/probe.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.PRIVATE = PRIVATE
    module.pull()


def run(date):
    PRIVATE.mkdir(exist_ok=True, mode=0o700)
    day = dt.date.fromisoformat(date)
    def time(ms):
        return dt.datetime.fromtimestamp(ms / 1000, ZONE).isoformat()
    start = int(dt.datetime.combine(day, dt.time(), ZONE).timestamp() * 1000)
    end = int(dt.datetime.combine(day + dt.timedelta(days=1), dt.time(), ZONE).timestamp() * 1000)
    db = sqlite3.connect(f'file:{PRIVATE / "snapshot.db"}?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    events = []
    for row in db.execute("SELECT * FROM memory_events WHERE deleted=0 AND scene NOT IN ('媒体','旁人') AND startAt>=? AND startAt<? ORDER BY startAt,id", (start, end)):
        e = dict(row)
        event = {k: e[k] for k in ['id', 'scene', 'title', 'summary', 'emotion', 'intensity']}
        event.update(startAt=time(e['startAt']), endAt=time(e['endAt']), points=json.loads(e['pointsJson']), important=bool(e['important']))
        event['items'] = []
        for item in db.execute('SELECT * FROM memory_items WHERE eventId=? ORDER BY createdAt,id', (e['id'],)):
            i = dict(item)
            event['items'].append({**{k: i[k] for k in ['category', 'kind', 'content', 'status', 'dueText']},
                'createdAt': time(i['createdAt']), 'dueAt': time(i['dueAt']) if i['dueAt'] is not None else None})
        events.append(event)
    db.close()
    if not events:
        raise RuntimeError('当天无事件，不调用模型')
    data = {'date': date, 'events': events}
    (PRIVATE / 'input.json').write_text(json.dumps(data, ensure_ascii=False, indent=2))
    system = (ROOT / 'app/src/main/assets/prompts/review.md').read_text().replace('{{APP_NAME}}', '回响')
    key = (pathlib.Path.home() / '.config/resound/deepseek.key').read_text().strip()
    body = {'model': 'deepseek-flash', 'temperature': 0.2, 'response_format': {'type': 'json_object'},
        'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': json.dumps(data, ensure_ascii=False)}]}
    req = urllib.request.Request('https://api.deepseek.com/chat/completions', json.dumps(body).encode(),
        {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=200) as response:
        reply = json.load(response)
    (PRIVATE / 'response.json').write_text(json.dumps(reply, ensure_ascii=False, indent=2))
    result = json.loads(reply['choices'][0]['message']['content'].strip().removeprefix('```json').removeprefix('```').removesuffix('```'))
    ids = {e['id'] for e in events}
    invalid = []
    sections = []
    for title, limit in LIMITS.items():
        lines = []
        for section in result['sections']:
            if section.get('title') != title:
                continue
            for line in section.get('lines', []):
                ref = line.get('eventId')
                if type(ref) is not int or ref not in ids:
                    invalid.append(ref)
                elif isinstance(line.get('text'), str) and line['text'].strip():
                    value = {'text': line['text'].strip(), 'eventId': ref}
                    if value not in lines:
                        lines.append(value)
        if lines:
            sections.append({'title': title, 'lines': lines[:limit]})
    assert {'一句话', '发生了什么'} <= {s['title'] for s in sections}
    assert isinstance(result['brief'], str) and result['brief'].strip()
    normalized = {'brief': result['brief'].strip()[:60], 'sections': sections}
    (PRIVATE / 'normalized.json').write_text(json.dumps(normalized, ensure_ascii=False, indent=2))
    usage = reply.get('usage', {})
    out = ['# 每日回顾离线验证', '', f'日期：{date}（Asia/Shanghai）；模型：deepseek-flash。', '',
        f'只读快照：{len(events)} 个有效事件、{sum(len(e["items"]) for e in events)} 个提取项。仅发送这些已整理内容，未发送整库或原话。',
        '提示词：`app/src/main/assets/prompts/review.md`，与 App 共用。数据库、输入及原始响应仅放在被忽略的 `private/`。', '',
        f'用量：输入 {usage.get("prompt_tokens", 0)} tokens，输出 {usage.get("completion_tokens", 0)} tokens；1 次调用（离线调用不写入手机用量）。', '',
        f'brief（{len(normalized["brief"])} 字）：{normalized["brief"]}', '']
    for section in sections:
        out += ['## ' + section['title'], '']
        out += [f'- {line["text"]}（事件 #{line["eventId"]}）' for line in section['lines']]
        out += ['']
    out += [f'自动核验：越界 id {len(invalid)} 条；保留条目 id 全部来自当天有效事件；栏目按固定顺序、上限裁剪。', '',
        '人工核对：待本次执行后逐条对照本地输入补充。', '']
    (HERE / 'results.md').write_text('\n'.join(out))
    print(f'已生成：事件 {len(events)}，提取项 {sum(len(e["items"]) for e in events)}，栏目 {len(sections)}，越界编号 {len(invalid)}；密钥未输出。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--pull', action='store_true')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--date', default='2026-10-23')
    args = parser.parse_args()
    try:
        if args.pull:
            pull()
        if args.run:
            run(args.date)
    except Exception as error:
        print('验证未完成：' + type(error).__name__)
        raise SystemExit(1)
