"""073-10 离线反例：每个标签一个中心向量能否替代当前三秒窗投票。

这不是 native 分人 embedding 导出；用同一批窗口的中心作较有利的代理候选。
只读旧数据库，忽略自动/待审核样本，排除同段自匹配，保持短窗的未知票。
输出只含汇总与私有向量，无原话/姓名；不上传、不回写数据库。
"""
import argparse
import importlib.util
import json
import os
import sqlite3
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('voice_diag', ROOT / 'tools/voiceprint-diag/diag.py')
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)


def match(vector, banks):
    if vector is None:
        return None
    ranked = sorted(((float(np.sort(np.clip(bank @ vector, 0, 1))[-3:].mean()), person)
                     for person, bank in banks.items()), reverse=True)
    if not ranked or ranked[0][0] < .50 or (len(ranked) > 1 and ranked[0][0] - ranked[1][0] < .08):
        return None
    return ranked[0][1]


def vote(line, observations, banks):
    votes = defaultdict(float)
    for a, b, vector in observations:
        overlap = max(0, min(line['b'], b) - max(line['a'], a))
        person = match(vector, banks)
        if person is not None:
            votes[person] += overlap
    best = max(votes, key=votes.get) if votes else None
    return best if best is not None and votes[best] / max(1, line['b'] - line['a']) >= .75 else None


def main(args):
    os.umask(0o077)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    assert not (out / 'summary.json').exists(), '不得覆盖既有证据'
    db = sqlite3.connect(Path(args.database).resolve().as_uri() + '?mode=ro&immutable=1', uri=True)
    db.row_factory = sqlite3.Row
    samples = [dict(r) for r in db.execute("select * from voiceprint_samples where source not in ('auto','line-review')")]
    sources = dict(db.execute('select id,sourceId from segments'))
    for s in samples:
        s['v'] = ref.unit(np.frombuffer(s.pop('vector'), '<f4').copy())
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    opts.inter_op_num_threads = 1
    engine = ort.InferenceSession(str(ROOT / 'app/src/main/assets/voiceprint/campplus.onnx'),
                                  sess_options=opts, providers=['CPUExecutionProvider'])
    started, deadline = time.monotonic(), time.monotonic() + args.seconds
    rows = []
    try:
        for item in json.loads(Path(args.manifest).read_text()):
            tick = time.monotonic()
            pcm = np.fromfile(Path(args.pcm_dir) / (item['id'] + '.pcm'), '<i2')
            row = dict(sample=item['id'], groups=0, lines=0, windows=0, valid_windows=0,
                       changed=0, unknown_to_known=0, known_to_unknown=0, changed_person=0,
                       old_known=0, new_known=0, success=False)
            private = []
            try:
                for source in item['sources']:
                    sid = source['segment_id']
                    segment = db.execute('select startAt,endAt,sourceId from segments where id=?', (sid,)).fetchone()
                    assert segment is not None
                    offset = round(source['offset_s'] * 1000)
                    limit = round(source['used_s'] * 1000)
                    groups = defaultdict(list)
                    for r in db.execute('select id,startAt,endAt,speakerTag,speakerManual from transcript_lines where segmentId=? order by startAt,id', (sid,)):
                        a, b = max(0, r['startAt']-segment['startAt']), min(limit, r['endAt']-segment['startAt'])
                        if b > a:
                            tag = r['speakerTag']
                            key = 'tag:' + tag if tag is not None else 'line:' + str(r['id'])
                            groups[key].append(dict(a=a+offset, b=b+offset, manual=r['speakerManual'], uncertain=bool(tag and tag.startswith('uncertain:'))))
                    refs = defaultdict(list)
                    for s in samples:
                        if s['segmentId'] != sid:
                            refs[s['personId']].append(s)
                    banks = {}
                    for person, ss in refs.items():
                        if person == 0:
                            same = [s for s in ss if sources.get(s['segmentId']) == segment['sourceId']]
                            if len(same) >= 3 and sum(s['durationMillis'] for s in same) >= 30000:
                                ss = same
                        if len(ss) >= 3 and sum(s['durationMillis'] for s in ss) >= 30000:
                            banks[person] = np.array([s['v'] for s in ss])
                    excluded = {r[0] for r in db.execute('select groupKey from voiceprint_exclusions where segmentId=?', (sid,))}
                    for key, lines in groups.items():
                        allowed = {p: b for p, b in banks.items() if p != 0 or key not in excluded}
                        merged = []
                        for line in sorted(lines, key=lambda l: l['a']):
                            a, b = line['a'], line['b']
                            if merged and a <= merged[-1][1]:
                                merged[-1] = (merged[-1][0], max(b, merged[-1][1]))
                            else:
                                merged.append((a, b))
                        observations = []
                        for a, b in merged:
                            for start in range(a, b, 3000):
                                if time.monotonic() >= deadline:
                                    raise TimeoutError('组件试验预算截止')
                                end = min(start+3000, b)
                                speech = ref.trim(pcm[start*16:min(end*16, len(pcm))])
                                vector = None
                                if len(speech) >= 24000:
                                    vector = ref.unit(engine.run(None, {'x': ref.fbank(speech)[None]})[0][0])
                                observations.append((start, end, vector))
                        vectors = [v for _, _, v in observations if v is not None]
                        center = ref.unit(np.mean(vectors, axis=0)) if vectors else None
                        # 给候选保留短窗/未知窗，不能借填满未知票来伪造收益。
                        reused = [(a, b, center if v is not None else None) for a, b, v in observations]
                        row['groups'] += 1
                        row['windows'] += len(observations)
                        row['valid_windows'] += len(vectors)
                        for line in lines:
                            if line['manual'] or line['uncertain']:
                                continue
                            old, new = vote(line, observations, allowed), vote(line, reused, allowed)
                            row['lines'] += 1
                            row['old_known'] += int(old is not None)
                            row['new_known'] += int(new is not None)
                            row['changed'] += int(old != new)
                            row['unknown_to_known'] += int(old is None and new is not None)
                            row['known_to_unknown'] += int(old is not None and new is None)
                            row['changed_person'] += int(old is not None and new is not None and old != new)
                        private.append(dict(observations=[(a, b, v.tolist() if v is not None else None) for a, b, v in observations]))
                row['success'] = True
            except TimeoutError:
                row['timeout'] = True
            row['wall_s'] = time.monotonic() - tick
            (out / (item['id'] + '.json')).write_text(json.dumps(dict(summary=row, groups=private), indent=2))
            rows.append(row)
            print(json.dumps(row), flush=True)
            if not row['success']:
                break
    finally:
        db.close()
        (out / 'summary.json').write_text(json.dumps(dict(rows=rows, wall_s=time.monotonic()-started), indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('manifest', 'database', 'pcm-dir', 'out'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--seconds', type=int, default=120)
    main(parser.parse_args())
