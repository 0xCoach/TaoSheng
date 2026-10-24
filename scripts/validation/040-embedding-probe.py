"""本机同版本 BGE 模型的合成句对试验，不读取用户原话。"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import onnxruntime as ort
from tokenizers import BertWordPieceTokenizer

parser = argparse.ArgumentParser()
parser.add_argument('model_dir', type=Path)
args = parser.parse_args()
for name, digest in [('model_quantized.onnx', '15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc'),
                     ('vocab.txt', '45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c')]:
    assert hashlib.sha256((args.model_dir / name).read_bytes()).hexdigest() == digest
session = ort.InferenceSession(str(args.model_dir / 'model_quantized.onnx'), providers=['CPUExecutionProvider'])
tokens = BertWordPieceTokenizer(str(args.model_dir / 'vocab.txt'), lowercase=False, strip_accents=False)
tokens.enable_truncation(max_length=512)
def embed(text):
    ids = np.array([tokens.encode('灵感\n' + text).ids], dtype=np.int64)
    inputs = {'input_ids': ids, 'attention_mask': np.ones_like(ids), 'token_type_ids': np.zeros_like(ids)}
    values = session.run(['last_hidden_state'], {i.name: inputs[i.name] for i in session.get_inputs()})[0][0, 0]
    return values / np.linalg.norm(values)
pairs = [
    ('给回响加一个每天自动总结情绪的功能', '回响可以每天自动整理一份情绪总结'),
    ('把相似的灵感自动合并，保留每次说话的出处', '相近的想法合到一起，每次提到的原话来源都保留'),
    ('给回响加一个每天自动总结情绪的功能', '给回响加一个每周总结工作进展的功能'),
    ('用语音记录每天的心情', '用语音提醒每天的待办'),
]
print(json.dumps([{'a': a, 'b': b, 'cosine': round(float(embed(a) @ embed(b)), 6),
                   'merge_at_086': bool(embed(a) @ embed(b) >= .86),
                   'merge_at_088': bool(embed(a) @ embed(b) >= .88)} for a, b in pairs], ensure_ascii=False, indent=2))
