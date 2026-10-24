#!/usr/bin/env python3
"""仅本地 say/ffmpeg + CAM++；真实音频不上传、不写进报告或 Git。"""
import argparse, hashlib, json, subprocess, wave
from pathlib import Path
import numpy as np
import onnxruntime as ort
import kaldi_native_fbank as knf
import sherpa_onnx
ROOT = Path(__file__).resolve().parent
TEXTS = [
 '今天上午我们讨论了新的工作计划，先把录音功能测试清楚，再检查后台任务有没有正常运行。下午我会整理会议记录，明天继续核对结果。',
 '请帮我记住下周三下午要和同事开会，我们需要提前准备材料，确认每个人负责的事情，以及最后交付的时间。今天先完成手里的任务。',
 '周末我打算出去走走，如果天气不错就去公园，回来以后读一会书。晚饭之前给家里打个电话，问问最近的生活和身体情况。',
 '这次测试主要观察不同句子的声音特征，文字内容会发生变化，但是说话人的音色应该保持稳定。结果需要记录下来，方便以后比较。',
 '明天早上先去买一些水果，然后到办公室处理邮件，中午和朋友一起吃饭。下午完成报告以后检查一遍数据，晚上早点休息。',
]
VOICES = ['Tingting', 'Meijia', 'Sinji']
def run(args): subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
def pcm(path):
    data = subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-ar','16000','-ac','1','-f','s16le','-'])
    return np.frombuffer(data, '<i2').copy()
def speech(x):
    # 与 App 相同：20ms RMS >= 0.002，仅在句子范围内去掉静音。
    frames=[f for i in range(0,len(x),320) if len(f:=x[i:i+320]) and np.sqrt(np.mean((f.astype(np.float64)/32768)**2)) >= .002]
    return np.concatenate(frames) if frames else np.empty(0,dtype=np.int16)

def features(x):
    opts=knf.FbankOptions(); opts.frame_opts.dither=0; opts.frame_opts.snip_edges=False
    opts.frame_opts.samp_freq=16000; opts.mel_opts.num_bins=80; opts.mel_opts.high_freq=-400
    f=knf.OnlineFbank(opts); f.accept_waveform(16000,x.astype(np.float32)/32768); f.input_finished()
    return np.stack([f.get_frame(i) for i in range(f.num_frames_ready)])
def unit(x): return x / np.linalg.norm(x)
def summary(xs):
    x=np.asarray(xs); return dict(count=len(xs), min=float(x.min()), median=float(np.median(x)), max=float(x.max()), values=[round(float(v),6) for v in xs])
def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--real-dir',type=Path); args=parser.parse_args()
    samples=ROOT/'samples'; samples.mkdir(exist_ok=True)
    results=ROOT/'results'; results.mkdir(exist_ok=True)
    model=ROOT/'model.onnx'
    opts=ort.SessionOptions(); opts.intra_op_num_threads=1; opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(model),sess_options=opts,providers=['CPUExecutionProvider'])
    meta=session.get_modelmeta().custom_metadata_map
    assert meta['normalize_samples']=='1' and meta['feature_normalize_type']=='global-mean'
    def embed(x):
        f=features(x); f-=f.mean(axis=0,keepdims=True)
        return unit(session.run(None,{session.get_inputs()[0].name:f[None]})[0][0])
    vectors={}; durations={}
    for v,voice in enumerate(VOICES):
        for i,text in enumerate(TEXTS):
            path=samples/f'voice{v}-{i}.aiff'
            if not path.exists(): run(['say','-v',voice,'-r','155','-o',str(path),text])
            x=speech(pcm(path)); vectors[v,i]=embed(x); durations[f'{v}-{i}']=len(x)/16000
    enrollment=[vectors[0,i] for i in range(3)]; mean=unit(np.mean(enrollment,axis=0))
    same=[mean@vectors[0,i] for i in range(3,5)]
    other=[mean@vectors[v,i] for v in range(1,len(VOICES)) for i in range(5)]
    same_pairs=[vectors[v,i]@vectors[v,j] for v in range(len(VOICES)) for i in range(5) for j in range(i+1,5)]
    other_pairs=[vectors[v,i]@vectors[w,j] for v in range(len(VOICES)) for w in range(v+1,len(VOICES)) for i in range(5) for j in range(5)]
    # sherpa 原生特征+模型端到端对照，使用归一化 PCM 输入 API。
    extractor=sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(model),num_threads=1))
    x=speech(pcm(samples/'voice0-3.aiff')); stream=extractor.create_stream()
    stream.accept_waveform(16000,x.astype(np.float32)/32768); stream.input_finished()
    native_features=np.asarray(stream.get_frames(0,len(features(x))),dtype=np.float32).reshape(-1,80)
    native_feature_error=float(np.max(np.abs(native_features-features(x))))
    native=unit(np.asarray(extractor.compute(stream)))
    # 逐值测试夹具：静音、种子随机噪声、真实合成语音，包含非整帧尾部。
    fixtures=ROOT/'fixtures'; fixtures.mkdir(exist_ok=True)
    cases={'silence':np.zeros(803,dtype='<i2'), 'noise':np.random.default_rng(6).integers(-16000,16000,10001,dtype=np.int16), 'speech':x[:32017]}
    for name,a in cases.items():
        a.astype('<i2').tofile(fixtures/f'{name}.pcm'); features(a).astype('<f4').tofile(fixtures/f'{name}.fbank')
    report=dict(model_sha256=hashlib.sha256(model.read_bytes()).hexdigest(), model_bytes=model.stat().st_size, metadata=meta,
        versions=dict(numpy=np.__version__,onnxruntime=ort.__version__,kaldi_native_fbank=knf.__version__,sherpa_onnx=sherpa_onnx.__version__),
        voices=VOICES,enrollment_seconds=sum(durations[f'0-{i}'] for i in range(3)), durations=durations,
        heldout_same=summary(same),heldout_other=summary(other),all_same_pairs=summary(same_pairs),all_other_pairs=summary(other_pairs),
        max_matching_same=summary([max(e@vectors[0,i] for e in enrollment) for i in range(3,5)]),
        max_matching_other=summary([max(e@vectors[v,i] for e in enrollment) for v in range(1,len(VOICES)) for i in range(5)]),
        sherpa_reference_cosine=float(native@embed(x)), sherpa_fbank_max_abs=native_feature_error)
    if args.real_dir:
        real=[]; real_vectors=[]
        for i,path in enumerate(sorted(args.real_dir.glob('*.ogg'))):
            a=speech(pcm(path)); row=dict(index=i, speech_seconds=len(a)/16000)
            if len(a)>=48000:
                # 没有人工身份标注，不把前后半段假称同人。
                middle=len(a)//2; halves=[embed(a[:middle]),embed(a[middle:])]
                row['halves_similarity']=float(halves[0]@halves[1]); row['synthetic_me_similarity']=float(embed(a)@mean)
                real_vectors.append(embed(a))
            real.append(row)
        report['real_unlabelled']=real
        report['real_pair_scores']=[float(a@b) for i,a in enumerate(real_vectors) for b in real_vectors[i+1:]]
    (results/'offline.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ['all_same_pairs','all_other_pairs','durations']},ensure_ascii=False,indent=2))
if __name__=='__main__': main()
