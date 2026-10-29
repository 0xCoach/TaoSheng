#!/usr/bin/env python3
"""环境音识别离线测试：对一批 wav 逐个做音频标签，输出前几类和耗时。只在电脑上跑，不改 App。
用法：probe.py <模型目录> <素材目录>...（素材 16kHz 单声道 wav）"""
import sys, time, glob, os
import numpy as np, soundfile as sf, sherpa_onnx

model_dir, *dirs = sys.argv[1:]
cfg = sherpa_onnx.AudioTaggingConfig(
    model=sherpa_onnx.AudioTaggingModelConfig(
        **({"ced": os.path.join(model_dir, "model.int8.onnx")} if "ced" in model_dir else
           {"zipformer": sherpa_onnx.OfflineZipformerAudioTaggingModelConfig(model=os.path.join(model_dir, "model.int8.onnx"))}),
        num_threads=2),
    labels=os.path.join(model_dir, "class_labels_indices.csv"), top_k=5)
tagger = sherpa_onnx.AudioTagging(cfg)
WIN = 10
for d in dirs:
    print(f"\n== {d}")
    for f in sorted(glob.glob(os.path.join(d, "*.wav"))):
        x, sr = sf.read(f, dtype="float32")
        if x.ndim > 1: x = x.mean(axis=1)
        # 切成 10 秒窗口分别判断，每类取各窗口里的最大值（CED 一次最多约 10 秒；长录音也能控制耗时）
        best = {}; t = time.time()
        for i in range(0, max(len(x), 1), WIN * sr):
            chunk = x[i:i + WIN * sr]
            if len(chunk) < sr: continue
            s = tagger.create_stream(); s.accept_waveform(sr, chunk)
            for e in tagger.compute(s): best[e.name] = max(best.get(e.name, 0), e.prob)
        ms = (time.time() - t) * 1000
        tags = " | ".join(f"{k} {v:.2f}" for k, v in sorted(best.items(), key=lambda kv: -kv[1])[:5])
        print(f"{os.path.basename(f)[:34]:34s} {len(x)/sr:5.1f}s {ms:6.0f}ms  {tags}")
