#!/usr/bin/env python3
"""只调用本机 say/ffmpeg，生成可复现的合成语音和逐句时间标注。"""
import json
import subprocess
import tempfile
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DIALOGUE = [
    ('Eddy', '早上好，我们确认一下今天的工作安排。上午十点和产品经理开会，讨论回响的录音功能。'),
    ('Flo', '好的，我会带上测试手机。我们先检查后台录音，再看语音识别能不能区分不同的说话人。'),
    ('Eddy', '下午三点请提醒我整理会议纪要，把客户的建议和下一步任务分别记录下来。'),
    ('Flo', '没问题。测试结束以后，我会把结果发给你，明天一起决定采用哪一种转写方案。'),
]
MONO = [('Eddy', '这是一段个人工作记录。今天完成了录音服务测试，接下来要比较转写速度和文字准确度。请记住，周五下午三点联系张经理，确认下周的项目安排，并准备会议材料。')]

def run(args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

def generate(name, lines, target, tmp):
    pcm = bytearray()
    turns = []
    for i, (voice, text) in enumerate(lines):
        aiff, wav = tmp / f'{name}{i}.aiff', tmp / f'{name}{i}.wav'
        run(['say', '-v', f'{voice} (中文（中国大陆）)', '-r', '155' if name == 'dialogue' else '140', '-o', str(aiff), text])
        run(['ffmpeg', '-y', '-i', str(aiff), '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(wav)])
        with wave.open(str(wav)) as f:
            data = f.readframes(f.getnframes())
        start = len(pcm) / 32000
        pcm.extend(data)
        turns.append(dict(voice=voice, text=text, start=start, end=len(pcm)/32000))
        pcm.extend(bytes(9600))
    if len(pcm) > target * 32000:
        raise ValueError('语音超出目标长度，请提高语速，不能截断原文')
    pcm.extend(bytes(target * 32000 - len(pcm)))
    out = ROOT / 'samples' / name
    with wave.open(str(out.with_suffix('.wav')), 'wb') as f:
        f.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        f.writeframes(pcm)
    run(['ffmpeg', '-y', '-i', str(out.with_suffix('.wav')), '-c:a', 'libopus', '-b:a', '24k', '-ar', '16000', '-ac', '1', str(out.with_suffix('.ogg'))])
    out.with_suffix('.json').write_text(json.dumps(dict(duration=target, turns=turns), ensure_ascii=False, indent=2)+'\n')
    print(name, target, '秒', '语音结束', round(turns[-1]['end'], 2))

if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as t:
        generate('dialogue', DIALOGUE, 40, Path(t))
        generate('monologue', MONO, 20, Path(t))
