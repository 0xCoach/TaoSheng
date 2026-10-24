#!/usr/bin/env python3
"""012：固定本机合成样本，按 App OGG 页发送；独立 600 秒硬预算，不自动重试。"""
import argparse
import asyncio
import fcntl
import hashlib
import json
import os
import subprocess
import tempfile
import time
import uuid
import wave
from pathlib import Path

import websockets
from probe import ROOT, load_key, safe, frame, parse_frame, audio_meta, request_meta

SAMPLES = ROOT / 'samples/long012'
RESULTS = ROOT / 'results/long012'
TEXT = '这是回响的合成语音测试，不包含任何真实录音。今天上午讨论录音功能，下午整理会议纪要。我们需要确认长时间的语音能够完整识别，最后一句话也不能遗漏。'


def command(args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def generate():
    SAMPLES.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        aiff, wav = Path(directory) / 'speech.aiff', Path(directory) / 'speech.wav'
        command(['say', '-v', 'Eddy (中文（中国大陆）)', '-r', '155', '-o', str(aiff), TEXT])
        command(['ffmpeg', '-y', '-i', str(aiff), '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(wav)])
        with wave.open(str(wav)) as source:
            speech = source.readframes(source.getnframes()) + bytes(6400)
        for seconds in (70, 150, 290):
            target = SAMPLES / f'{seconds}.ogg'
            if target.exists():
                raise ValueError('样本已存在，不覆盖证据')
            pcm = Path(directory) / 'speech.pcm'
            pcm.write_bytes((speech * (seconds * 32000 // len(speech) + 1))[:seconds * 32000])
            command(['ffmpeg', '-y', '-f', 's16le', '-ar', '16000', '-ac', '1', '-i', str(pcm),
                     '-c:a', 'libopus', '-b:a', '24k', '-ar', '16000', '-ac', '1', str(target)])
        (SAMPLES / 'source.json').write_text(json.dumps(dict(text=TEXT, voice='Eddy (中文（中国大陆）)',
            rate=155, durations=[70, 150, 290], encoding='16k mono Opus 24kbps',
            construction='循环 say 语音加 200ms 间隔，按目标时长截取 PCM'), ensure_ascii=False, indent=2) + '\n')


def pages(data):
    offset = 0
    while offset < len(data):
        assert data[offset:offset+4] == b'OggS'
        count = data[offset+26]
        end = offset + 27 + count + sum(data[offset+27:offset+27+count])
        assert end <= len(data)
        yield data[offset:end]
        offset = end


def reserve(label, seconds):
    RESULTS.mkdir(parents=True, exist_ok=True)
    with (RESULTS / 'budget.json').open('a+') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        stream.seek(0)
        entries = json.loads(stream.read() or '[]')
        if any(item['label'] == label for item in entries) or sum(item['seconds'] for item in entries) + seconds > 600:
            raise ValueError('重复调用或超过 600 秒预算，拒绝上传')
        entries.append(dict(label=label, seconds=seconds))
        stream.seek(0); stream.truncate()
        json.dump(entries, stream, indent=2)
        stream.flush(); os.fsync(stream.fileno())


async def probe(seconds, retry=False):
    path = SAMPLES / f'{seconds}.ogg'
    data = path.read_bytes()
    duration = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
                                             '-of', 'default=nw=1:nk=1', str(path)]))
    label = f'{seconds}_fast' + ('_retry1' if retry else '')
    key = load_key()  # 唯一凭证来源；不输出、不保存。
    reserve(label, duration)
    timeout = 30 + 2 * seconds
    out = dict(label=label, audio_seconds=duration, sha256=hashlib.sha256(data).hexdigest(),
               timeout_seconds=timeout, success=False, timed_out=False, packets=[])
    began = time.monotonic()
    try:
        async with asyncio.timeout(timeout):
            async with websockets.connect('wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream',
                additional_headers={'X-Api-Key': key, 'X-Api-Resource-Id': 'volc.seedasr.sauc.duration',
                                    'X-Api-Connect-Id': str(uuid.uuid4())},
                max_size=8*1024*1024, open_timeout=15, close_timeout=3) as socket:
                out['logid'] = socket.response.headers.get('X-Tt-Logid')
                config = dict(user={'uid': 'resound'}, audio=audio_meta('ogg'), request=request_meta('nostream'))
                await socket.send(frame(1, 0, json.dumps(config).encode(), True))
                chunks = list(pages(data))
                for i, chunk in enumerate(chunks):
                    await socket.send(frame(2, 2 if i == len(chunks)-1 else 0, chunk))
                out['send_seconds'] = round(time.monotonic()-began, 3)
                out['sent_bytes'] = len(data)
                async for message in socket:
                    packet = parse_frame(message)
                    elapsed = round(time.monotonic()-began, 3)
                    payload = packet['payload']
                    out['packets'].append(dict(kind=packet['kind'], flags=packet['flags'], code=packet['code'], elapsed_seconds=elapsed))
                    if packet['kind'] == 15:
                        break
                    if packet['flags'] & 2:
                        out['final_seconds'] = elapsed
                        out['success'] = payload.get('code', 20000000) in (0, 20000000)
                        out['server_audio_millis'] = payload.get('audio_info', {}).get('duration')
                        utterances = payload.get('result', {}).get('utterances', [])
                        out['utterances'] = len(utterances)
                        out['last_end_millis'] = max((u.get('end_time', 0) for u in utterances), default=0)
                        out['text_chars'] = len(payload.get('result', {}).get('text', ''))
                        break
    except Exception as error:
        out['exception_type'] = type(error).__name__
        out['timed_out'] = isinstance(error, TimeoutError)
    out['total_seconds'] = round(time.monotonic()-began, 3)
    out['would_timeout_at_90s'] = out.get('final_seconds', out['total_seconds']) >= 90
    out = safe(out, key)
    (RESULTS / f'{label}.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(out, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['generate', '70', '150', '290'])
    parser.add_argument('--retry', action='store_true', help='保留首次账本，另记一次预算；仅在明确检查失败原因后使用')
    args = parser.parse_args()
    if args.action == 'generate':
        generate()
    else:
        asyncio.run(probe(int(args.action), args.retry))
