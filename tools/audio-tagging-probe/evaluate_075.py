#!/usr/bin/env python3
"""075：只读快照、本机 CPU 单轮评测；私人原话与完整分数仅写 --out。"""
import argparse
import collections
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import sqlite3
import subprocess
import time
from zoneinfo import ZoneInfo

import numpy as np
import onnxruntime as ort
import sherpa_onnx as sherpa

RATE = 16000
ROOT = Path(__file__).resolve().parents[2]
ZONE = ZoneInfo('Asia/Shanghai')


def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2))


def select(snapshot, count):
    """每个有录音的小时至少 6 段；余量按小时总体分配，小时内按时长分位取样。"""
    db = sqlite3.connect((snapshot / 'visibility-capture.db').as_uri() + '?mode=ro&immutable=1', uri=True)
    db.row_factory = sqlite3.Row
    files = {p.name: p for day in ('2026-10-28', '2026-10-29')
             for p in (snapshot / 'extracted/files/segments' / day).glob('*.ogg')}
    groups = collections.defaultdict(list)
    for r in db.execute('select id,startAt,endAt,path from segments order by startAt,id'):
        path = files.get(Path(r['path']).name)
        stamp = dt.datetime.fromtimestamp(r['startAt'] / 1000, ZONE)
        if path is None or stamp.date().isoformat() not in ('2026-10-28', '2026-10-29'):
            continue
        lines = [dict(v) for v in db.execute(
            'select id,startAt,endAt,text from transcript_lines where segmentId=? order by startAt,id', (r['id'],))]
        item = dict(r)
        item.update(path=str(path), time=stamp.isoformat(), hour=stamp.strftime('%m-%d %H'),
                    seconds=(r['endAt']-r['startAt'])/1000, lines=lines, sha256=digest(path))
        groups[item['hour']].append(item)
    db.close()
    total = sum(map(len, groups.values()))
    assert count <= total
    quota = {k: min(6, len(v)) for k, v in groups.items()}
    assert sum(quota.values()) <= count
    while sum(quota.values()) < count:
        k = max((k for k in groups if quota[k] < len(groups[k])),
                key=lambda k: (len(groups[k])*count/total-quota[k], k))
        quota[k] += 1
    selected = []
    for hour, items in sorted(groups.items()):
        ordered = sorted(items, key=lambda v: (v['seconds'], v['startAt'], v['id']))
        selected.extend(ordered[min(len(ordered)-1, int((i+.5)*len(ordered)/quota[hour]))]
                        for i in range(quota[hour]))
    return sorted(selected, key=lambda v: v['startAt']), dict(
        ogg_files=len(files), matched_segments=total, selected=len(selected),
        hours={k: dict(population=len(groups[k]), selected=quota[k]) for k in sorted(groups)},
        strategy='每小时至少6段（不足全取），余量按小时总量分配；小时内按时长分位取样；不按文本或标签选样')


def gap_spans(pcm, vad):
    """逐帧复现 Android SileroVad：512+64、RMS gate、尾帧补零、每段状态清零。"""
    state = np.zeros((2, 1, 128), dtype=np.float32)
    history = np.zeros(64, dtype=np.float32)
    start = None
    raw = []
    for at in range(0, len(pcm), 512):
        frame = np.zeros(512, dtype=np.float32)
        size = min(512, len(pcm)-at)
        frame[:size] = pcm[at:at+size]
        inp = np.concatenate((history, frame))[None]
        rms = np.float32(np.sqrt(np.mean(frame.astype(np.float64)**2)))
        if rms < np.float32(.002):
            state.fill(0)
            probability = 0
        else:
            prob, state = vad.run(['output', 'stateN'],
                                  {'input': inp, 'state': state, 'sr': np.array(16000, dtype=np.int64)})
            probability = float(prob.flat[0])
        history = inp[0, 512:576].copy()
        if probability >= .5:
            if start is not None:
                raw.append((start, at))
                start = None
        elif start is None:
            start = at
    if start is not None:
        raw.append((start, len(pcm)))
    trimmed = [(a+1600, b-1600) for a, b in raw if b-a >= 8000]
    total = sum(b-a for a, b in trimmed)
    if total < 32000:
        return raw, trimmed, [], np.empty(0, np.float32), total
    # 不降采样；在拼接时间轴上均匀取30个连续1秒窗，保留原来的音高和顺序。
    logical = [(0, total)] if total <= 480000 else [
        (i*(total-16000)//29, i*(total-16000)//29+16000) for i in range(30)]
    selected = []
    for a, b in logical:
        offset = 0
        for left, right in trimmed:
            n = right-left
            begin, end = max(a, offset), min(b, offset+n)
            if end > begin:
                selected.append((left+begin-offset, left+end-offset))
            offset += n
            if offset >= b:
                break
    return raw, trimmed, selected, np.concatenate([pcm[a:b] for a, b in selected]), total


def infer(tagger, pcm):
    stream = tagger.create_stream()
    stream.accept_waveform(RATE, pcm if len(pcm) >= RATE else np.pad(pcm, (0, RATE-len(pcm))))
    events = tagger.compute(stream, 527)
    scores = {v.name: float(v.prob) for v in events}
    assert len(scores) == 527 and all(np.isfinite(v) and 0 <= v <= 1 for v in scores.values())
    return scores


def main(args):
    os.umask(0o077)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    assert not (out / 'started.json').exists(), '已有评测证据，不重复推理或覆盖；另选输出目录'
    manifest, population = select(args.snapshot.resolve(), args.count)
    save(out / 'manifest.json', manifest)
    save(out / 'population.json', population)
    model = args.models / 'model.int8.onnx'
    labels = args.models / 'class_labels_indices.csv'
    vad_path = ROOT / 'app/src/main/assets/silero_vad.onnx'
    assert digest(model) == '69304b8a1b96bbe6b7d16c24079f0732c65faf3568a14cb82a8238c709afe76c'
    assert digest(labels) == 'cdd1049833c4b86127c2773ac0d14a2754b6a6d0d1798002ed5c66e699708429'
    db_path = args.snapshot / 'visibility-capture.db'
    db_hash = digest(db_path)
    started = time.monotonic()
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('30分钟评测预算截止')))
    signal.alarm(args.seconds)
    save(out / 'started.json', dict(at=dt.datetime.now(ZONE).isoformat(), seconds=args.seconds,
         platform=platform.platform(), python=platform.python_version(), sherpa=sherpa.__version__,
         ort=ort.__version__, tagger_threads=2, vad_threads=1, model_sha256=digest(model),
         labels_sha256=digest(labels), vad_sha256=digest(vad_path), db_sha256=db_hash,
         manifest_sha256=digest(out/'manifest.json'), script_sha256=digest(Path(__file__))))
    t = time.perf_counter()
    tagger = sherpa.AudioTagging(sherpa.AudioTaggingConfig(model=sherpa.AudioTaggingModelConfig(
        zipformer=sherpa.OfflineZipformerAudioTaggingModelConfig(model=str(model)), num_threads=2),
        labels=str(labels), top_k=527))
    tagger_load = time.perf_counter()-t
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = opts.inter_op_num_threads = 1
    t = time.perf_counter()
    vad = ort.InferenceSession(str(vad_path), sess_options=opts, providers=['CPUExecutionProvider'])
    vad_load = time.perf_counter()-t
    completed = 0
    try:
        with (out/'scores.jsonl').open('x') as fp:
            for item in manifest:
                tick = time.perf_counter()
                raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', item['path'],
                      '-f', 's16le', '-ar', '16000', '-ac', '1', 'pipe:1'], timeout=30)
                pcm = np.frombuffer(raw, dtype='<i2').astype(np.float32)/32768
                decode = time.perf_counter()-tick
                n = len(pcm)
                wins = [(0, n)] if n <= 480000 else [(a, a+160000) for a in (0, (n-160000)//2, n-160000)]
                t = time.perf_counter()
                whole = {}
                for a, b in wins:
                    for k, v in infer(tagger, pcm[a:b]).items():
                        whole[k] = max(whole.get(k, 0), v)
                old_s = time.perf_counter()-t
                t = time.perf_counter()
                raw_gaps, trimmed, spans, background, total = gap_spans(pcm, vad)
                extraction_s = time.perf_counter()-t
                t = time.perf_counter()
                gap_scores = infer(tagger, background) if len(background) else {}
                infer_s = time.perf_counter()-t if len(background) else 0
                row = dict(id=item['id'], time=item['time'], hour=item['hour'], audio_samples=n,
                           decode_s=decode, old_s=old_s, gap_extract_s=extraction_s, gap_infer_s=infer_s,
                           extra_s=extraction_s+infer_s, raw_gaps=raw_gaps, trimmed_gaps=trimmed,
                           selected_spans=spans, available_samples=total, used_samples=len(background),
                           whole=whole, gaps=gap_scores, wall_s=time.perf_counter()-tick)
                fp.write(json.dumps(row, ensure_ascii=False)+'\n')
                fp.flush()
                completed += 1
                if completed % 20 == 0:
                    print(f'{completed}/{len(manifest)}，累计 {time.monotonic()-started:.1f} 秒', flush=True)
    finally:
        signal.alarm(0)
        save(out/'finished.json', dict(completed=completed, expected=len(manifest),
             wall_s=time.monotonic()-started, tagger_load_s=tagger_load, vad_load_s=vad_load,
             inputs_unchanged=digest(db_path)==db_hash and all(digest(Path(v['path']))==v['sha256'] for v in manifest)))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--snapshot', type=Path, required=True)
    p.add_argument('--models', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--count', type=int, default=360)
    p.add_argument('--seconds', type=int, default=1800)
    main(p.parse_args())
