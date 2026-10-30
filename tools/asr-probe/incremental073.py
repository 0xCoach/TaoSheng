"""073-7 对照探针。私有 manifest/输出目录必须显式传入；不改变 App 默认值。

python incremental073.py --manifest ... --out ... --seconds 210
仅 S1～M3，新旧各一次、最多两路，超时不重试。原始回复只落私有输出目录。
"""
import argparse
import asyncio
import json
import os
import struct
import time
import uuid
from pathlib import Path

import websockets
from probe import frame, load_key, parse_frame, safe


class Incremental:
    """确定分句保留；同起点的修订替换，不把中间假设直接追加为正文。"""
    def __init__(self):
        self.stable = {}
        self.pending = []

    def accept(self, payload, final=False):
        utterances = payload.get('result', {}).get('utterances', [])
        if not utterances:
            return
        pending = []
        for u in utterances:
            key = u['start_time']
            if u.get('definite') or final:
                self.stable[key] = u
            else:
                pending.append(u)
        self.pending = pending

    def result(self):
        return sorted(self.stable.values(), key=lambda u: (u['start_time'], u['end_time']))


def canonical(utterances):
    return [(u['text'].strip(), u['start_time'], u['end_time'],
             str(u.get('additions', {}).get('speaker_id'))) for u in utterances if u['text'].strip()]


def packets(data):
    offset, previous, pending = 0, 0, bytearray()
    while offset < len(data):
        assert data[offset:offset + 4] == b'OggS'
        n = data[offset + 26]
        end = offset + 27 + n + sum(data[offset + 27:offset + 27 + n])
        page = data[offset:end]
        pending.extend(page)
        granule = struct.unpack_from('<Q', page, 6)[0]
        if granule != 0xffffffffffffffff and granule - previous >= 9600:
            yield bytes(pending)
            pending.clear()
            previous = granule
        offset = end
    if pending:
        yield bytes(pending)


def config(result_type, fmt='ogg'):
    hotwords = ['涛声', '回响', '豆包', '声纹', '转写', '智能体', '大模型', '中国电信']
    return dict(user={'uid': 'resound'},
                audio={'format': fmt, 'codec': 'opus' if fmt == 'ogg' else 'raw', 'rate': 16000, 'bits': 16, 'channel': 1},
                request={'model_name': 'bigmodel', 'enable_itn': True, 'enable_punc': True,
                         'enable_speaker_info': True, 'show_utterances': True, 'ssd_version': '200',
                         'result_type': result_type,
                         'corpus': {'context': json.dumps({'hotwords': [{'word': w} for w in hotwords]}, ensure_ascii=False)}})


async def request(item, mode, out, key, deadline, fmt='ogg'):
    label = item['id'] + '_' + mode
    target = out / (label + '.json')
    assert not target.exists(), '不覆盖既有证据'
    ledger = out / 'budget.json'
    budget = json.loads(ledger.read_text()) if ledger.exists() else []
    assert label not in [x['label'] for x in budget]
    assert sum(x['seconds'] for x in budget) + item['seconds'] < 12000
    budget.append(dict(label=label, seconds=item['seconds']))
    ledger.write_text(json.dumps(budget, indent=2))
    start = time.perf_counter()
    evidence = dict(sample=item['id'], mode=mode, success=False, replies=[], wire_bytes=0)
    aggregate = Incremental()
    try:
        data = Path(item[fmt]).read_bytes()
        chunks = list(packets(data)) if fmt == 'ogg' else [data[i:i+6400] for i in range(0, len(data), 6400)]
        async with asyncio.timeout(max(1, min(105, deadline - time.time()))):
            async with websockets.connect(
                    'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream',
                    additional_headers={'X-Api-Key': key, 'X-Api-Resource-Id': 'volc.seedasr.sauc.duration',
                                        'X-Api-Connect-Id': str(uuid.uuid4())},
                    max_size=8*1024*1024, open_timeout=15, close_timeout=1,
                    compression=None, ping_interval=None, write_limit=256*1024) as sock:
                await sock.send(frame(1, 0, json.dumps(config(mode, fmt)).encode(), True))

                async def receive():
                    async for msg in sock:
                        p = parse_frame(msg)
                        evidence['wire_bytes'] += len(msg)
                        evidence['replies'].append(p)
                        if p['kind'] == 15 or p['payload'].get('code', 20000000) not in (0, 20000000):
                            return
                        aggregate.accept(p['payload'], bool(p['flags'] & 2))
                        if p['flags'] & 2:
                            evidence['success'] = True
                            evidence['utterances'] = (aggregate.result() if mode == 'single' else
                                                      p['payload'].get('result', {}).get('utterances', []))
                            return

                receiver = asyncio.create_task(receive())
                try:
                    for i, chunk in enumerate(chunks):
                        if receiver.done():
                            break
                        await sock.send(frame(2, 2 if i == len(chunks)-1 else 0, chunk))
                        await asyncio.sleep(0)
                    await receiver
                finally:
                    receiver.cancel()
                    await asyncio.gather(receiver, return_exceptions=True)
    except Exception as e:
        evidence['error'] = type(e).__name__
    evidence['wall_s'] = time.perf_counter() - start
    target.write_text(json.dumps(safe(evidence, key), ensure_ascii=False, indent=2))
    return evidence


async def main(args):
    os.umask(0o077)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    items = json.loads(Path(args.manifest).read_text())[:6]
    key, deadline, start = load_key(), time.time() + args.seconds, time.monotonic()
    summary = []
    for item in items:
        if time.time() >= deadline:
            break
        old, new = await asyncio.gather(*(request(item, mode, out, key, deadline) for mode in ('full', 'single')))
        row = dict(sample=item['id'], success=old['success'] and new['success'],
                   full_s=old['wall_s'], single_s=new['wall_s'],
                   full_bytes=old['wire_bytes'], single_bytes=new['wire_bytes'])
        if row['success']:
            a, b = canonical(old['utterances']), canonical(new['utterances'])
            row.update(exact=a == b, full_sentences=len(a), single_sentences=len(b),
                       full_chars=sum(len(u[0]) for u in a), single_chars=sum(len(u[0]) for u in b))
        summary.append(row)
        print(json.dumps(row), flush=True)
    (out / 'summary.json').write_text(json.dumps(dict(rows=summary, wall_s=time.monotonic()-start), indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--manifest', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--seconds', type=int, default=210)
    asyncio.run(main(p.parse_args()))
