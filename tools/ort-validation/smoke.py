#!/usr/bin/env python3
"""CPU 模型兼容性冒烟测试；仅合成夹具，不访问网络或手机数据。

分别在安装 ORT 1.23.2 和 1.25.1 的 Python 环境运行；桌面结果不能替代 Android 真机测试。
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--search-model-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    fixtures = root / 'tools/voiceprint-probe/fixtures'
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1

    def session(path):
        return ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])

    def unit(vector):
        assert np.isfinite(vector).all() and np.linalg.norm(vector) > 0
        return vector / np.linalg.norm(vector)

    vad = session(root / 'app/src/main/assets/silero_vad.onnx')
    probabilities = []
    for samples in [np.zeros(16000, dtype=np.float32),
                    np.fromfile(fixtures / 'speech.pcm', '<i2').astype(np.float32) / 32768]:
        state = np.zeros((2, 1, 128), dtype=np.float32)
        history = np.zeros(64, dtype=np.float32)
        for offset in range(0, len(samples), 512):
            frame = samples[offset:offset + 512]
            frame = np.pad(frame, (0, 512 - len(frame)))
            data = np.concatenate([history, frame])[None, :]
            result = dict(zip([v.name for v in vad.get_outputs()], vad.run(None, {
                'input': data, 'state': state, 'sr': np.array(16000, dtype=np.int64),
            })))
            state = result['stateN']
            probability = float(result['output'].flat[0])
            assert np.isfinite(state).all() and 0 <= probability <= 1
            probabilities.append(probability)
            history = data[0, -64:]

    camp = session(root / 'app/src/main/assets/voiceprint/campplus.onnx')
    features = np.fromfile(fixtures / 'speech.fbank', '<f4').reshape(-1, 80)
    features -= features.mean(axis=0, keepdims=True)
    speaker = unit(camp.run(None, {'x': features[None]})[0].reshape(-1))
    assert speaker.shape == (192,)

    for name, sha in {
        'model_quantized.onnx': '15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc',
        'vocab.txt': '45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c',
    }.items():
        assert hashlib.sha256((args.search_model_dir / name).read_bytes()).hexdigest() == sha
    vocab = {word: i for i, word in enumerate((args.search_model_dir / 'vocab.txt').read_text().splitlines())}
    bge = session(args.search_model_dir / 'model_quantized.onnx')

    def embed(text):
        # 本测试仅用汉字和中文标点，逐字分词与应用中文分支一致。
        ids = np.array([[vocab['[CLS]']] + [vocab.get(c, vocab['[UNK]']) for c in text] +
                        [vocab['[SEP]']]], dtype=np.int64)
        inputs = {'input_ids': ids, 'attention_mask': np.ones_like(ids), 'token_type_ids': np.zeros_like(ids)}
        result = dict(zip([o.name for o in bge.get_outputs()], bge.run(None, {
            i.name: inputs[i.name] for i in bge.get_inputs()
        })))
        vector = unit(result['last_hidden_state'][0, 0])
        assert vector.shape == (512,)
        return vector

    search = np.stack([embed(text) for text in [
        '为这个句子生成表示以用于检索相关文章：报销',
        '明天把车费发票交给财务', '今晚去楼下面馆吃面条', '星期六去公园跑步',
    ]])
    scores = search[1:] @ search[0]
    assert int(np.argmax(scores)) == 0, scores
    values = {'vad': np.array(probabilities), 'speaker': speaker, 'search': search}
    report = {'runtime': ort.__version__, 'scope': 'macOS CPU，合成人声和文本；不是 Android 真机验收',
              'vad_frames': len(probabilities), 'speaker_dimensions': 192,
              'search_dimensions': 512, 'search_scores': scores.tolist()}
    if args.baseline:
        with np.load(args.baseline) as baseline:
            difference = float(np.max(np.abs(values['vad'] - baseline['vad'])))
            speaker_cosine = float(speaker @ baseline['speaker'])
            search_cosine = np.sum(search * baseline['search'], axis=1)
            assert difference < 1e-4, difference
            assert speaker_cosine > .999, speaker_cosine
            assert np.all(search_cosine > .999), search_cosine
            report['comparison'] = {'vad_max_abs': difference, 'speaker_cosine': speaker_cosine,
                                    'search_min_cosine': float(search_cosine.min())}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    np.savez(args.output.with_suffix('.npz'), **values)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
