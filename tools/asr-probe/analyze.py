#!/usr/bin/env python3
"""离线汇总：最终包完整性、归一化字错率、按合成时间标注核对说话人。"""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def normalized(s):
    # 样本只有这两项数字归一化，明确固定映射，不掩盖其他错字。
    s = s.replace('十点', '10点').replace('三点', '3点')
    return re.sub(r'[^\w]', '', s)


def distance(a, b):
    row = list(range(len(b)+1))
    for i, x in enumerate(a, 1):
        new = [i]
        for j, y in enumerate(b, 1):
            new.append(min(new[-1]+1, row[j]+1, row[j-1]+(x != y)))
        row = new
    return row[-1]


def analyze():
    rows = []
    for p in sorted((ROOT/'results').glob('*.json')):
        if p.stem in ('budget', 'summary'):
            continue
        x = json.loads(p.read_text())
        if 'scheme' not in x:
            continue
        row = {k:x.get(k) for k in ['label', 'scheme', 'sample', 'format', 'mode', 'pace', 'reserved_seconds', 'total_seconds', 'send_seconds', 'sent_bytes', 'http_status', 'exception_type', 'headers']}
        messages = x.get('messages', [])
        row['message_count'] = len(messages)
        row['server_errors'] = [m for m in messages if m['kind'] == 15]
        final = next((m for m in reversed(messages) if m['flags'] & 2 and m['kind'] == 9), None)
        row['has_final'] = final is not None
        if final:
            payload = final['payload']
            result = payload.get('result', {})
            row['recognized_duration_ms'] = payload.get('audio_info', {}).get('duration')
            row['text'] = result.get('text', '')
            sample = json.loads((ROOT/'samples'/(x['sample']+'.json')).read_text())
            reference = normalized(''.join(t['text'] for t in sample['turns']))
            hypothesis = normalized(row['text'])
            row['reference_characters'] = len(reference)
            row['edit_distance'] = distance(reference, hypothesis)
            row['normalized_cer'] = row['edit_distance']/len(reference)
            row['utterances'] = []
            for u in result.get('utterances', []):
                start = u.get('start_time', 0)
                end = u.get('end_time', start)
                voices = sorted({t['voice'] for t in sample['turns'] if min(end/1000, t['end'])-max(start/1000, t['start']) > 0.2})
                row['utterances'].append(dict(start_ms=u.get('start_time'), diagnostic_start_ms=start, end_ms=u.get('end_time'), text=u['text'], speaker_id=u.get('additions', {}).get('speaker_id'), expected_voices=voices, definite=u.get('definite')))
        rows.append(row)
    budget = json.loads((ROOT/'results/budget.json').read_text())
    hashes = {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'samples').iterdir())}
    summary = dict(reserved_total_seconds=sum(x['reserved_seconds'] for x in budget), samples_sha256=hashes, probes=rows)
    (ROOT/'results/summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
    for row in rows:
        print(json.dumps({k:v for k,v in row.items() if k not in ('utterances', 'headers', 'server_errors', 'text')}, ensure_ascii=False))
    print('保守累计预算秒数', summary['reserved_total_seconds'])

if __name__ == '__main__':
    analyze()
