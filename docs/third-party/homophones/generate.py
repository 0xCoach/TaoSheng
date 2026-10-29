"""python3 generate.py /path/to/jieba/dict.txt /path/to/pinyin-data/pinyin.txt OUTPUT_DIR
输入 SHA-256 见 ../066-本地同音纠正.md；只处理本地文件，不联网。
"""
from pathlib import Path
import sys
import unicodedata

jieba, pinyin, output = map(Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)
words = []
for line in jieba.read_text().splitlines():
    word, frequency, *_ = line.split()
    if len(word) >= 2 and all('\u4e00' <= char <= '\u9fff' for char in word):
        words.append((int(frequency), word))
words = sorted(words, key=lambda item: (-item[0], item[1]))[:15000]
(output / 'common-words.txt').write_text('\n'.join(word for _, word in words) + '\n')
rows = []
for line in pinyin.read_text().splitlines():
    if not line.startswith('U+'):
        continue
    codepoint, raw = line.split(':', 1)
    codepoint = int(codepoint[2:], 16)
    syllables = []
    for syllable in raw.split('#')[0].strip().split(','):
        syllable = unicodedata.normalize('NFD', syllable).replace('u\u0308', 'v')
        syllable = ''.join(c for c in syllable if not unicodedata.combining(c)).upper()
        if syllable not in syllables:
            syllables.append(syllable)
    if len(syllables) > 1 or codepoint > 0xffff:
        rows.append(chr(codepoint) + ' ' + ','.join(syllables))
(output / 'readings.txt').write_text('\n'.join(rows) + '\n')
