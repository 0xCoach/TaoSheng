#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["requests>=2.32,<3", "websockets>=15,<17"]
# ///
"""只发 ASR 数据面请求。凭证仅在运行时读取；预算先落盘，失败不返还，不自动重试。"""
import argparse
import asyncio
import base64
import fcntl
import gzip
import json
import re
import struct
import time
import uuid
import wave
from datetime import datetime, timezone
from pathlib import Path

import requests
import websockets

ROOT = Path(__file__).resolve().parent
KEY_FILE = Path('/Users/coach/Developer/anti-scam-granny/API Key.md')
HOST = 'https://openspeech.bytedance.com/api/v3'
RESOURCES = {'A': 'volc.seedasr.auc', 'B': 'volc.seedasr.sauc.duration', 'C': 'volc.bigasr.auc_turbo'}


def load_key():
    key = KEY_FILE.read_text().strip()
    if not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', key):
        raise ValueError('凭证文件格式不符，内容未输出')
    return key


def safe(value, key):
    # 先去掉完整密钥及其 UUID 各组成部分，防止异常/远端回显泄露。
    text = json.dumps(value, ensure_ascii=False)
    for part in [key, *key.split('-')]:
        text = re.sub(re.escape(part), '[REDACTED]', text, flags=re.I)
    return json.loads(text)


def reserve(label, duration):
    path = ROOT / 'results' / 'budget.json'
    path.parent.mkdir(exist_ok=True)
    with path.open('a+') as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        f.seek(0)
        entries = json.loads(f.read() or '[]')
        if any(x['label'] == label for x in entries):
            raise ValueError('该测试已保留预算，拒绝重复发送')
        if sum(x['reserved_seconds'] for x in entries) + duration > 300:
            raise ValueError('超过累计 300 秒发送上限')
        entries.append(dict(label=label, reserved_seconds=duration, time=datetime.now(timezone.utc).isoformat()))
        f.seek(0)
        f.truncate()
        json.dump(entries, f, ensure_ascii=False, indent=2)
        f.flush()
        import os
        os.fsync(f.fileno())


def frame(kind, flags, payload, is_json=False):
    return bytes([0x11, kind << 4 | flags, 0x10 if is_json else 0, 0]) + struct.pack('>I', len(payload)) + payload


def parse_frame(data):
    kind, flags = data[1] >> 4, data[1] & 15
    pos = (data[0] & 15) * 4
    seq = None
    if flags & 1:
        seq = struct.unpack_from('>i', data, pos)[0]
        pos += 4
    code = None
    if kind == 15:
        code = struct.unpack_from('>I', data, pos)[0]
        pos += 4
    size = struct.unpack_from('>I', data, pos)[0]
    payload = data[pos+4:pos+4+size]
    if data[2] & 15 == 1:
        payload = gzip.decompress(payload)
    if data[2] >> 4 == 1:
        payload = json.loads(payload)
    else:
        payload = payload.decode(errors='replace')
    return dict(kind=kind, flags=flags, sequence=seq, code=code, payload=payload)


def audio_meta(fmt):
    return dict(format=fmt, codec='opus' if fmt == 'ogg' else 'raw', rate=16000, bits=16, channel=1)


def request_meta(mode):
    req = dict(model_name='bigmodel', enable_itn=True, enable_punc=True,
               enable_speaker_info=True, show_utterances=True)
    if mode in ('async', 'nostream'):
        req.update(ssd_version='200', result_type='full')
    if mode == 'async':
        req['enable_nonstream'] = True
    return req


def selected_headers(headers):
    return {k: headers[k] for k in ('X-Api-Status-Code', 'X-Api-Message', 'X-Tt-Logid') if k in headers}


def http_probe(key, scheme, path, fmt, out):
    headers = {'X-Api-Key': key, 'X-Api-Resource-Id': RESOURCES[scheme],
               'X-Api-Request-Id': str(uuid.uuid4()), 'X-Api-Sequence': '-1'}
    body = dict(user={'uid': 'resound-probe'}, audio={**audio_meta(fmt), 'data': base64.b64encode(path.read_bytes()).decode()}, request=request_meta('http'))
    endpoint = '/auc/bigmodel/submit' if scheme == 'A' else '/auc/bigmodel/recognize/flash'
    out['endpoint'] = HOST + endpoint
    out['request'] = {'audio': audio_meta(fmt), 'transport': 'audio.data base64 (no URL)', 'request': body['request']}
    # requests 默认不重试；禁止跳转，防止携带凭证流向其他域名。
    response = requests.post(HOST+endpoint, headers=headers, json=body, timeout=(15, 90), allow_redirects=False)
    out['http_status'] = response.status_code
    out['headers'] = selected_headers(response.headers)
    try:
        out['response'] = response.json()
    except ValueError:
        out['response'] = response.text
    if scheme == 'A' and response.headers.get('X-Api-Status-Code') == '20000000':
        out['queries'] = []
        for _ in range(12):
            time.sleep(2)
            r = requests.post(HOST+'/auc/bigmodel/query', headers=headers, json={}, timeout=(15, 30), allow_redirects=False)
            item = dict(http_status=r.status_code, headers=selected_headers(r.headers), response=r.json())
            out['queries'].append(item)
            if r.headers.get('X-Api-Status-Code') not in ('20000001', '20000002'):
                break


async def ws_probe(key, path, fmt, mode, pace, duration, out):
    url = 'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_'+mode
    out['endpoint'] = url
    if fmt == 'pcm':
        with wave.open(str(path)) as f:
            audio = f.readframes(f.getnframes())
    else:
        audio = path.read_bytes()
    config = dict(user={'uid': 'resound-probe'}, audio=audio_meta(fmt), request=request_meta(mode))
    out['request'] = config
    headers = {'X-Api-Key': key, 'X-Api-Resource-Id': RESOURCES['B'], 'X-Api-Connect-Id': str(uuid.uuid4())}
    out['messages'] = []
    async with websockets.connect(url, additional_headers=headers, max_size=8*1024*1024, open_timeout=15, close_timeout=3) as ws:
        out['headers'] = selected_headers(ws.response.headers)
        await ws.send(frame(1, 0, json.dumps(config).encode(), True))
        sent_start = time.monotonic()
        finished = asyncio.Event()

        async def receive():
            async for msg in ws:
                record = parse_frame(msg)
                record['elapsed_seconds'] = round(time.monotonic()-sent_start, 3)
                out['messages'].append(record)
                if record['kind'] == 15 or record['flags'] & 2:
                    finished.set()
                    return
        receiver = asyncio.create_task(receive())
        try:
            # PCM 每包 200ms；OGG 按容器总字节等分为约 200ms 的传输块，不转码。
            chunk = 6400 if fmt == 'pcm' else max(1, int(len(audio)/duration*0.2))
            count = (len(audio)+chunk-1)//chunk
            out['sent_bytes'] = 0
            for i, pos in enumerate(range(0, len(audio), chunk)):
                if finished.is_set():
                    break
                if pace == 'realtime':
                    await asyncio.sleep(max(0, sent_start + min((i+1)*0.2, duration) - time.monotonic()))
                payload = audio[pos:pos+chunk]
                await ws.send(frame(2, 2 if i == count-1 else 0, payload))
                out['sent_bytes'] += len(payload)
                await asyncio.sleep(0)  # 接收协程仍需及时读取错误和结果
            out['send_seconds'] = round(time.monotonic()-sent_start, 3)
            await asyncio.wait_for(receiver, 60)
        finally:
            receiver.cancel()
            await asyncio.gather(receiver, return_exceptions=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('label')
    p.add_argument('--scheme', choices=['A', 'B', 'C'], required=True)
    p.add_argument('--sample', choices=['dialogue', 'monologue'], default='dialogue')
    p.add_argument('--format', choices=['pcm', 'wav', 'ogg'], default='pcm')
    p.add_argument('--mode', choices=['async', 'nostream'], default='nostream')
    p.add_argument('--pace', choices=['fast', 'realtime'], default='fast')
    args = p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        p.error('label 只允许字母数字下划线短横线')
    if args.scheme != 'B' and args.format == 'pcm':
        p.error('HTTP 请使用 wav/ogg')
    key = load_key()
    print(f'已读取，长度 {len(key)}', flush=True)
    path = ROOT / 'samples' / (args.sample + ('.wav' if args.format == 'pcm' else '.'+args.format))
    # 以真实 WAV 帧数核算时长；OGG 样本来自相同 WAV，另加容器尾部时长以保守计数。
    with wave.open(str(path.with_suffix('.wav'))) as f:
        duration = f.getnframes()/f.getframerate()
    if args.format == 'ogg':
        import subprocess
        duration = max(duration, float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', str(path)])))
    reserve(args.label, duration)
    out = dict(label=args.label, scheme=args.scheme, resource_id=RESOURCES[args.scheme], sample=args.sample,
               format=args.format, mode=args.mode if args.scheme == 'B' else None, pace=args.pace,
               reserved_seconds=duration, started=datetime.now(timezone.utc).isoformat())
    start = time.monotonic()
    try:
        if args.scheme == 'B':
            asyncio.run(ws_probe(key, path, args.format, args.mode, args.pace, duration, out))
        else:
            http_probe(key, args.scheme, path, args.format, out)
    except Exception as e:
        # 不保存 traceback、请求对象或异常 repr。握手只提取允许的响应字段。
        out['exception_type'] = type(e).__name__
        response = getattr(e, 'response', None)
        if response is not None:
            out['http_status'] = response.status_code
            out['headers'] = selected_headers(response.headers)
            out['response_body'] = bytes(response.body).decode(errors='replace')
        elif isinstance(e, (TimeoutError, requests.exceptions.RequestException, OSError)):
            out['error'] = '网络/超时异常；为保护凭证不输出异常详情'
        else:
            out['error'] = '探测未完成；为保护凭证不输出异常详情'
    finally:
        out['total_seconds'] = round(time.monotonic()-start, 3)
        sanitized = safe(out, key)
        (ROOT / 'results' / (args.label+'.json')).write_text(json.dumps(sanitized, ensure_ascii=False, separators=(',', ':'))+'\n')
        print(json.dumps({k:sanitized[k] for k in ('label', 'reserved_seconds', 'total_seconds')}, ensure_ascii=False), flush=True)

if __name__ == '__main__':
    main()
