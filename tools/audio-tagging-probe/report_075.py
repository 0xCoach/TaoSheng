#!/usr/bin/env python3
"""从075单轮分数生成汇总、逐小时对照和私有原话核对清单，不再次解码/推理。"""
import argparse
import collections
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TITLES = dict(Music='音乐', Keyboard='键盘声', Rain='雨', Pet='宠物', Water='流水', Appliance='家电',
              Footsteps='脚步', AlarmClock='闹钟', Traffic='附近有车流', BabyCry='婴儿哭声', Knock='敲门', DogBark='狗叫', Siren='警笛')
NOTES = {
    'Music': '主要集中在10-29 19时，多语言、喜剧片段等文本与媒体配乐相容；无原话的高分段不能确认音乐来源。',
    'Keyboard': '文件夹、课件、办公设置等上下文与键盘操作相容；仍不能凭讨论电脑证明现场敲键盘。',
    'Rain': '无命中；以下列最高的5个未过门槛候选，不把它们当雨声。',
    'Pet': '抽查高分含无原话或“休息室”等，缺少宠物事件佐证；存在媒体动物声/误识风险，暂未确认。',
    'Water': '00:57—00:59集中高分，但文字没有洗漱/水龙头佐证；时间集中本身不能证明流水。',
    'Appliance': '办公交流与空调/风扇背景相容，但原话没有明确设备证据；家电0.005门槛较低，需留意误报。',
    'Footsteps': '新增较多，办公交流、夜间与无原话段均有命中；无法凭文字证明行走，低分命中作为待核对重点。',
    'AlarmClock': '唯一命中无原话，且处于19时音乐/疑似媒体密集时段；不能确认是现场闹钟，其余列最高未命中候选。',
    'Traffic': '07—08时交通/轨道分数集中，与通勤时间相容；19时也有疑似影视片段命中，无法区分现场与外放。',
    'BabyCry': '无命中；最高候选0.03仍不足门槛，不推断婴儿在场。',
    'Knock': '无命中；展示最高5个未过门槛候选。',
    'DogBark': '与宠物命中相同，缺少明确文字佐证；影视抑制规则仍由整段分数决定。',
    'Siren': '无命中；展示最高5个未过门槛候选，不能把普通交通声写成警笛。',
}


def max_score(scores, names):
    return max((scores.get(k, 0) for k in names), default=0)


def distribution(values):
    v = sorted(values)
    return dict(n=len(v), mean=statistics.mean(v), p50=statistics.median(v),
                p90=v[min(len(v)-1, int(len(v)*.9))], p95=v[min(len(v)-1, int(len(v)*.95))], max=v[-1])


def main(args):
    os.umask(0o077)
    out = args.out
    baseline = subprocess.check_output(['git', 'show', args.baseline+':app/src/main/java/app/resound/envsound/EnvSoundRules.kt'],
                                       cwd=ROOT, text=True)
    def rules(group):
        return {k: (float(t), re.findall(r'"([^"]+)"', names)) for k, t, names in re.findall(
            group+r'\.(\w+) to \(([.\d]+)f to listOf\(([^\n]+)\)\)', baseline)}
    tags, sounds = rules('EnvTag'), rules('SpecialSound')
    assert len(tags) == 9 and len(sounds) == 4
    def names(key):
        return re.findall(r'"([^"]+)"', re.search(r'val '+key+r' = listOf\(([^\n]+)\)', baseline)[1])
    speech, human = names('speech'), names('human')
    def classify(scores, group):
        return [k for k, (threshold, classes) in group.items() if max_score(scores, classes) >= threshold]
    def verdict(scores):
        if max_score(scores, speech) >= .05 and scores.get('Music', 0) >= .9:
            return 'Media'
        content = [n for _, ns in list(tags.values())+list(sounds.values()) for n in ns]
        if max_score(scores, speech) < .2 and max_score(scores, human) < .05 and max_score(scores, content) >= .2:
            return 'Skipped'
        return 'Keep'
    rows = [json.loads(x) for x in (out/'scores.jsonl').read_text().splitlines()]
    manifest = {r['id']: r for r in json.loads((out/'manifest.json').read_text())}
    expected = []
    for r in rows:
        v = verdict(r['whole'])
        d = dict(id=r['id'], verdict=v, old_tags=classify(r['whole'], tags), new_tags=classify(r['gaps'], tags),
                 old_sounds=[] if v == 'Media' else classify(r['whole'], sounds),
                 new_sounds=[] if v == 'Media' else classify(r['gaps'], sounds))
        r.update(d)
        expected.append(d)
    (out/'expected.jsonl').write_text(''.join(json.dumps(d)+'\n' for d in expected))
    summary = dict(baseline=args.baseline, baseline_rules_sha256=hashlib.sha256(baseline.encode()).hexdigest(),
        samples=len(rows), hours=len({r['hour'] for r in rows}), with_gaps=sum(bool(r['gaps']) for r in rows),
        insufficient=sum(not r['gaps'] for r in rows), capped=sum(r['available_samples']>480000 for r in rows),
        audio_seconds=sum(r['audio_samples']/16000 for r in rows), tags={}, sounds={}, rail={}, timing={},
        verdict_without_identity=dict(collections.Counter(r['verdict'] for r in rows)),
        scope='旧规则直接读基线源码；跳过开、影视不转写、本人保护取false仅作规则回放，未跑声纹；命中并非人工真值')
    for group, ruleset in [('tags', tags), ('sounds', sounds)]:
        for name, (threshold, classes) in ruleset.items():
            summary[group][name] = dict(title=TITLES[name], threshold=threshold,
                old=sum(name in r['old_'+group] for r in rows), new=sum(name in r['new_'+group] for r in rows),
                added=sum(name not in r['old_'+group] and name in r['new_'+group] for r in rows),
                removed=sum(name in r['old_'+group] and name not in r['new_'+group] for r in rows),
                old_hours=dict(sorted(collections.Counter(r['hour'] for r in rows if name in r['old_'+group]).items())),
                new_hours=dict(sorted(collections.Counter(r['hour'] for r in rows if name in r['new_'+group]).items())),
                max_old=max(max_score(r['whole'], classes) for r in rows),
                max_new=max(max_score(r['gaps'], classes) for r in rows))
    for key in ['old_tags', 'new_tags', 'old_sounds', 'new_sounds']:
        summary[key+'_segments'] = sum(bool(r[key]) for r in rows)
    for name in ['Subway, metro, underground', 'Rail transport', 'Train']:
        values = [r['gaps'][name] for r in rows if r['gaps']]
        summary['rail'][name] = distribution(values) | {
            'at_001': sum(v >= .01 for v in values), 'at_01': sum(v >= .1 for v in values),
            'at_03': sum(v >= .3 for v in values), 'at_05': sum(v >= .5 for v in values)}
    for name in ['decode_s', 'old_s', 'gap_extract_s', 'gap_infer_s', 'extra_s']:
        summary['timing'][name] = distribution([r[name] for r in rows])
    summary['speech'] = {method: distribution([r[method]['Speech'] for r in rows if r['gaps']]) for method in ['whole', 'gaps']}
    summary['speech']['gap_at_09'] = sum(r['gaps'].get('Speech',0) >= .9 for r in rows)
    summary['new_hit_missing_text'] = sum(bool(r['new_tags']) and not manifest[r['id']]['lines'] for r in rows)
    summary['manifest_sha256'] = hashlib.sha256((out/'manifest.json').read_bytes()).hexdigest()
    summary['scores_sha256'] = hashlib.sha256((out/'scores.jsonl').read_bytes()).hexdigest()
    (out/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    with (out/'小时分布.csv').open('w', newline='') as f:
        fields = ['小时', '总体段数', '抽样段数', '足够间隙段数'] + [f'{TITLES[k]}_{v}' for k in tags for v in ['旧', '新']]
        w = csv.DictWriter(f, fieldnames=fields, lineterminator='\n'); w.writeheader()
        population = json.loads((out/'population.json').read_text())['hours']
        for hour in sorted(population):
            selected = [r for r in rows if r['hour']==hour]
            d = dict(zip(fields[:4], [hour, population[hour]['population'], len(selected), sum(bool(r['gaps']) for r in selected)]))
            for k in tags:
                for title, method in [('旧','old_tags'),('新','new_tags')]:
                    d[f'{TITLES[k]}_{title}'] = sum(k in r[method] for r in selected)
            w.writerow(d)
    report = ['# 075 私有原话核对清单', '', '只读快照，未播放/上传音频；原话为快照已有转写，可能有识别错误。无原话明确留空。',
              '每类取5段：命中超过5段时列最高3段与最低2段；不足5段时补最高未命中候选。不能据此计算准确率。',
              '特别声音沿用整段影视抑制；0命中候选与命中分开标记。原文完整列表和时间在 manifest.json。', '']
    checklist = []
    for group, ruleset in [('tags', tags), ('sounds', sounds)]:
        for name, (threshold, classes) in ruleset.items():
            ranked = sorted(rows, key=lambda r: max_score(r['gaps'], classes), reverse=True)
            hits = [r for r in ranked if name in r['new_'+group]]
            chosen = hits[:3]+hits[-2:] if len(hits)>5 else (hits+[r for r in ranked if r not in hits])[:5]
            report += [f'## {TITLES[name]}（门槛 {threshold:g}，旧 {summary[group][name]["old"]} → 新 {len(hits)}）', '', NOTES[name], '',
                       '|段号|北京时间|旧分数|间隙分数|命中|原话片段|', '|---|---|---:|---:|---|---|']
            for r in chosen:
                text = ' / '.join(v['text'] for v in manifest[r['id']]['lines'])
                excerpt = text[:180]+('…' if len(text)>180 else '') if text else '（本段无原话，无法文字确认）'
                a, b = max_score(r['whole'], classes), max_score(r['gaps'], classes)
                hit = '是' if name in r['new_'+group] else '否（候选）'
                report.append(f'|{r["id"]}|{r["time"][5:19].replace("T"," ")}|{a:.6f}|{b:.6f}|{hit}|{excerpt.replace("|", "／").replace(chr(10), " ")}|')
                checklist.append(dict(category=name, id=r['id'], hit=name in r['new_'+group]))
            report.append('')
    for name in summary['rail']:
        report += [f'## {name}：分数最高5段（不新增标签）', '', '|段号|北京时间|整段|间隙|原话片段|', '|---|---|---:|---:|---|']
        for r in sorted(rows, key=lambda r:r['gaps'].get(name,0), reverse=True)[:5]:
            text = ' / '.join(v['text'] for v in manifest[r['id']]['lines'])[:180] or '（本段无原话）'
            report.append(f'|{r["id"]}|{r["time"][5:19].replace("T"," ")}|{r["whole"][name]:.6f}|{r["gaps"].get(name,0):.6f}|{text.replace("|", "／").replace(chr(10), " ")}|')
        report.append('')
    (out/'核对清单.md').write_text('\n'.join(report)+'\n')
    (out/'checklist.json').write_text(json.dumps(checklist, indent=2))
    print(json.dumps({k:summary[k] for k in ['samples','with_gaps','old_tags_segments','new_tags_segments','verdict_without_identity','speech']}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--baseline', default='9577d1c')
    main(p.parse_args())
