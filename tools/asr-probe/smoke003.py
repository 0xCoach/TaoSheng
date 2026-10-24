# /// script
# requires-python = ">=3.11"
# dependencies = ["requests>=2.32,<3", "websockets>=15,<17"]
# ///
"""003 的一次性 3 秒接入核验；独立 60 秒预算，先记账，不自动重试。"""
import asyncio
import fcntl
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from probe import ROOT, load_key, ws_probe, safe


def main():
    sample = ROOT.parents[1] / 'app/src/main/assets/asr-test.ogg'
    # ffprobe 已验证容器为 3.0065 秒，向上保守记为 3.007 秒。
    duration = 3.007
    budget = ROOT / 'results/budget003.json'
    label = '003_asset_ogg_fast'
    with budget.open('a+') as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        f.seek(0)
        entries = json.loads(f.read() or '[]')
        if any(x['label'] == label for x in entries):
            raise SystemExit('该核验已保留预算，拒绝重复发送')
        if sum(x['reserved_seconds'] for x in entries) + duration > 60:
            raise SystemExit('超过003累计60秒上限')
        entries.append(dict(label=label, reserved_seconds=duration, time=datetime.now(timezone.utc).isoformat()))
        f.seek(0); f.truncate(); json.dump(entries, f, indent=2); f.flush(); os.fsync(f.fileno())
    key = load_key()  # 仅此脚本运行时读取既定文件；不输出、不复制到其他文件。
    out = {}
    began = time.monotonic()
    summary = dict(label=label, reserved_seconds=duration, success=False)
    try:
        asyncio.run(ws_probe(key, sample, 'ogg', 'nostream', 'fast', duration, out))
        messages = out.get('messages', [])
        last = messages[-1] if messages else {}
        summary['success'] = last.get('kind') == 9 and bool(last.get('flags', 0) & 2)
        summary['final_packet'] = bool(last.get('flags', 0) & 2)
        summary['packets'] = len(messages)
        summary['sent_bytes'] = out.get('sent_bytes', 0)
        summary['send_seconds'] = out.get('send_seconds')
        summary['logid'] = out.get('headers', {}).get('X-Tt-Logid')
        payload = last.get('payload', {})
        summary['server_audio_millis'] = payload.get('audio_info', {}).get('duration')
        summary['utterances'] = len(payload.get('result', {}).get('utterances', []))
        summary['error_code'] = last.get('code')
    except Exception as e:
        summary['exception_type'] = type(e).__name__
    summary['total_seconds'] = round(time.monotonic() - began, 3)
    result = safe(summary, key)
    (ROOT / 'results/003_asset_ogg_fast.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    if not result['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
