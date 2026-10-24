#!/usr/bin/env python3
"""卡 035：只使用本地副本；含原话、中间结果及输入指纹仅写入 private/。"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
import hashlib
import heapq
import json
from pathlib import Path
import shutil
import sqlite3
import struct
import tempfile
from zoneinfo import ZoneInfo

import numpy as np

ROOT = Path(__file__).resolve().parent
THRESHOLDS = [v / 100 for v in range(45, 76, 5)]
METHODS = ("incremental", "agglomerative", "periodic")
METHOD_NAMES = {"incremental": "增量单遍", "agglomerative": "中心凝聚", "periodic": "增量+定期合并"}


def unit(vector):
    vector = np.asarray(vector, dtype=np.float64)
    norm = np.linalg.norm(vector)
    if vector.shape != (192,) or not np.isfinite(vector).all() or norm <= 1e-10:
        raise ValueError("无效向量")
    return vector / norm


def read_feature(path):
    # Java DataOutputStream：big endian int + long + boolean + 192 float。
    data = path.read_bytes()
    if len(data) < 13:
        raise ValueError("缓存头截断")
    version, duration, present = struct.unpack(">iqB", data[:13])
    if version != 1 or duration < 0 or present not in (0, 1):
        raise ValueError("缓存头无效")
    if len(data) != 13 + 768 * present:
        raise ValueError("缓存长度无效")
    return duration, unit(struct.unpack(">192f", data[13:])) if present else None


def group_key(line):
    return "tag:" + line["speakerTag"] if line["speakerTag"] is not None else f"line:{line['id']}"


def cache_key(segment, lines):
    # 顺序与 VoiceprintDao.lines 的 ORDER BY startAt, id 相同。
    identity = f"campplus-v1|{segment['id']}|{segment['path']}|{segment['startAt']}|{segment['endAt']}"
    for line in lines:
        identity += f"|{line['id']}:{line['startAt']}:{line['endAt']}:{group_key(line)}"
    return hashlib.sha256(identity.encode()).hexdigest()


def union_millis(ranges):
    end = None
    total = 0
    for lo, hi in sorted(ranges):
        if hi <= lo:
            continue
        total += max(0, hi - max(lo, end if end is not None else lo))
        end = max(end if end is not None else hi, hi)
    return total


@dataclass
class Item:
    segment: dict
    key: str
    lines: list
    duration: int
    span: int
    vector: np.ndarray
    label: str | None
    manual: bool
    conflict: bool
    uncertain: bool


def input_hashes(private):
    paths = [private / "capture.db", private / "capture.db-wal"]
    paths += sorted((private / "features").iterdir())
    return {str(p.relative_to(private)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in paths if p.is_file()}


def load_data(private):
    stats = Counter()
    # 原始副本不打开 SQLite。连同 WAL 复制至 private 内临时目录后只读查询，
    # 让 SQLite 在临时目录重建 shm；不能用 immutable=1，否则可能漏掉 WAL。
    with tempfile.TemporaryDirectory(prefix="snapshot-", dir=private) as tmp:
        for name in ("capture.db", "capture.db-wal"):
            if (private / name).exists():
                shutil.copyfile(private / name, Path(tmp) / name)
        connection = sqlite3.connect((Path(tmp) / "capture.db").as_uri() + "?mode=ro", uri=True)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA query_only=ON")
            if connection.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                raise ValueError("数据库快速校验失败")
            segments = [dict(r) for r in connection.execute("SELECT * FROM segments ORDER BY startAt, id")]
            lines = [dict(r) for r in connection.execute("SELECT * FROM transcript_lines ORDER BY startAt, id")]
        finally:
            connection.close()
    stats.update(segments=len(segments), lines=len(lines))
    stats["playback_segments"] = sum(s["playbackMillis"] > 0 for s in segments)
    by_segment = defaultdict(lambda: defaultdict(list))
    for line in lines:
        by_segment[line["segmentId"]][group_key(line)].append(line)
    feature_paths = {p.name: p for p in (private / "features").iterdir() if p.is_file()}
    stats["feature_files"] = len(feature_paths)
    features = {}
    for key, path in feature_paths.items():
        try:
            features[key] = read_feature(path)
            stats["file_null_vectors" if features[key][1] is None else "file_valid_vectors"] += 1
        except ValueError:
            stats["file_invalid"] += 1
    items, used = [], set()
    for segment in segments:
        for key, group in by_segment[segment["id"]].items():
            stats["segment_speaker_groups"] += 1
            digest = cache_key(segment, group)
            if digest not in feature_paths:
                stats["cache_missing"] += 1
                continue
            used.add(digest)
            stats["cache_matched"] += 1
            if digest not in features:
                stats["matched_invalid"] += 1
                continue
            duration, vector = features[digest]
            if vector is None:
                stats["matched_null"] += 1
                continue
            labels = {line["speakerName"] for line in group if line["speakerName"]}
            label = next(iter(labels)) if len(labels) == 1 else None
            # 混合身份、部分有名部分无名都不当成纯净真值或陌生声音。
            conflict = len(labels) > 1 or bool(labels) and any(not line["speakerName"] for line in group)
            span = union_millis((max(line["startAt"], segment["startAt"]),
                                 min(line["endAt"], segment["endAt"])) for line in group)
            manual = bool(label) and all(line["speakerManual"] for line in group)
            items.append(Item(segment, key, group, duration, span, vector, label, manual,
                              conflict, any((line["speakerTag"] or "").startswith("uncertain:") for line in group)))
    stats["orphan_files"] = len(feature_paths.keys() - used)
    stats["usable_vectors"] = len(items)
    stats["labels"] = dict(Counter("conflict" if i.conflict else "unknown" if not i.label else
                                   ("self_manual" if i.manual else "self_auto") if i.label == "我" else
                                   "named_manual" if i.manual else "named_auto" for i in items))
    stats["sources"] = dict(Counter(i.segment["sourceId"] for i in items))
    return items, dict(stats)


def reject_reason(item, minimum):
    if item.segment["playbackMillis"] > 0:
        return "playback"
    if item.conflict:
        return "conflicting_labels"
    if item.uncertain:
        return "uncertain_speaker"
    if item.span <= 0 or item.duration > item.span + 100:
        return "invalid_duration"
    if item.duration < minimum * 1000:
        return "short_speech"
    return None


def center(items, members):
    # 等权单位向量之和；合并按成员数保留权重，不能把两个组中心等权平均。
    return unit(np.sum([items[i].vector for i in members], axis=0))


def merge_groups(items, groups, threshold):
    """每次合并最高余弦的一对中心，更新后重算；确定性平局顺序。"""
    members = {i: list(g) for i, g in enumerate(groups)}
    sums = {i: np.sum([items[j].vector for j in g], axis=0) for i, g in members.items()}
    centers = {i: unit(v) for i, v in sums.items()}
    heap = []
    for a in members:
        for b in range(a):
            score = float(centers[a] @ centers[b])
            if score >= threshold:
                heapq.heappush(heap, (-score, b, a))
    next_id = len(groups)
    while heap:
        _, a, b = heapq.heappop(heap)
        if a not in members or b not in members:
            continue
        members[next_id] = members.pop(a) + members.pop(b)
        sums[next_id] = sums.pop(a) + sums.pop(b)
        centers.pop(a)
        centers.pop(b)
        centers[next_id] = unit(sums[next_id])
        for other in members:
            if other == next_id:
                continue
            score = float(centers[other] @ centers[next_id])
            if score >= threshold:
                heapq.heappush(heap, (-score, other, next_id))
        next_id += 1
    return sorted((sorted(g) for g in members.values()), key=lambda g: min(g))


def cluster(items, method, threshold, merge_every=20):
    if method == "agglomerative":
        return merge_groups(items, [[i] for i in range(len(items))], threshold)
    groups, centers = [], []
    for idx, item in enumerate(items):
        scores = [float(item.vector @ c) for c in centers]
        best = int(np.argmax(scores)) if scores else -1
        if best >= 0 and scores[best] >= threshold:
            groups[best].append(idx)
            centers[best] = center(items, groups[best])
        else:
            groups.append([idx])
            centers.append(item.vector)
        if method == "periodic" and (idx + 1) % merge_every == 0:
            groups = merge_groups(items, groups, threshold)
            centers = [center(items, g) for g in groups]
    if method == "periodic":
        groups = merge_groups(items, groups, threshold)
    return groups


def occurrences(items, group):
    # 同录音被拆出多个 speakerTag 后又合并，最多算一次出现。
    return len({items[i].segment["id"] for i in group})


def group_duration(items, group):
    # 每个特征是去静音后的拼接人声。重复/重叠 tag 不能使一个录音累计超过句子并集。
    by_segment = defaultdict(list)
    for i in group:
        by_segment[items[i].segment["id"]].append(items[i])
    return sum(min(sum(i.duration for i in batch), union_millis(
        (max(line["startAt"], i.segment["startAt"]), min(line["endAt"], i.segment["endAt"]))
        for i in batch for line in i.lines)) for batch in by_segment.values())


def triggered(items, group):
    return occurrences(items, group) >= 3 and group_duration(items, group) >= 300_000


def distribution(values):
    return [round(float(x), 2) for x in np.percentile(values, [0, 50, 90, 100])] if values else [0] * 4


def probes(items, groups, queries, threshold):
    centers = [center(items, g) for g in groups]
    return {"hits": sum(any(float(q.vector @ c) >= threshold for c in centers) for q in queries),
            "total": len(queries)}


def named_metrics(items, method, threshold):
    # 人名只用于事后计分；聚类时隐藏，加入未知候选中检查拆分及跨姓名合并。
    groups = cluster(items, method, threshold)
    names = sorted({i.label for i in items if i.label})
    metrics = []
    for name in names:
        counts = [sum(items[i].label == name for i in g) for g in groups]
        counts = [n for n in counts if n]
        metrics.append({"samples": sum(counts), "groups": len(counts), "largest": max(counts),
                        "manual_samples": sum(i.label == name and i.manual for i in items)})
    mixed = sum(len({items[i].label for i in g if items[i].label}) > 1 for g in groups)
    return {"people": metrics, "mixed_named_groups": mixed}


def evaluate(items, method, threshold, minimum=3):
    eligible = [i for i in items if reject_reason(i, minimum) is None]
    unknown = [i for i in eligible if not i.label]
    groups = cluster(unknown, method, threshold)
    self_items = [i for i in eligible if i.label == "我"]
    named = [i for i in eligible if i.label and i.label != "我"]
    trigger_details = []
    for g in groups:
        if triggered(unknown, g):
            centroid = center(unknown, g)
            trigger_details.append({"units": len(g), "occurrences": occurrences(unknown, g),
                                    "seconds": group_duration(unknown, g) / 1000,
                                    "manual_self": probes(unknown, [g], [i for i in self_items if i.manual], threshold),
                                    "cohesion": distribution([float(unknown[i].vector @ centroid) for i in g])})
    return {"method": method, "threshold": threshold, "minimum_seconds": minimum,
            "unknown": len(unknown), "groups": len(groups),
            "triggered": sum(triggered(unknown, g) for g in groups),
            "occurrences": distribution([occurrences(unknown, g) for g in groups]),
            "seconds": distribution([group_duration(unknown, g) / 1000 for g in groups]),
            "singletons": sum(occurrences(unknown, g) == 1 for g in groups),
            "group_stats": [{"units": len(g), "occurrences": occurrences(unknown, g),
                             "seconds": round(group_duration(unknown, g) / 1000, 3)} for g in groups],
            "self": probes(unknown, groups, self_items, threshold),
            "manual_self": probes(unknown, groups, [i for i in self_items if i.manual], threshold),
            "named": named_metrics([i for i in eligible if i.label != "我"], method, threshold),
            "trigger_details": trigger_details,
            "named_samples": len(named)}, unknown, groups


def ratio(value):
    n, d = value["hits"], value["total"]
    return f"{n}/{d} ({n / d:.1%})" if d else "0/0（不可评估）"


def table(headers, rows):
    return "\n".join(["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)] +
                     ["| " + " | ".join(map(str, row)) + " |" for row in rows])


def stamp(ms):
    return datetime.fromtimestamp(ms / 1000, ZoneInfo("Asia/Shanghai")).isoformat(timespec="seconds")


def build_public(stats, results, sensitivity, chosen, excluded, robustness, exploratory):
    r70 = next(r for r in results if r['method'] == 'periodic' and r['threshold'] == .70)
    r75 = next(r for r in results if r['method'] == 'periodic' and r['threshold'] == .75)
    single70 = next(r for r in results if r['method'] == 'incremental' and r['threshold'] == .70)
    agg70 = next(r for r in results if r['method'] == 'agglomerative' and r['threshold'] == .70)
    examples = "\n".join(f"对照触发组 {n}：{g['units']} 个单元、{g['occurrences']} 个独立录音段、{g['seconds']:.2f} 秒；"
                           f"人工本人对该中心命中 {ratio(g['manual_self'])}；成员到中心余弦最小/中位/P90/最大 {g['cohesion']}。"
                           for n, g in enumerate(exploratory['trigger_details'], 1))
    text = ["# 卡 035 · 陌生声音归组离线实验", "",
            "## 结论", "",
            "当前数据不能定出满足“本人误归接近 0”的上线阈值。建议后续使用增量+定期合并做可撤销的候选归组，"
            "以 0.70、有效人声 ≥3 秒作为观察起点，先补本人门禁和人工确认样本，再决定是否自动提示。"
            f"0.70 下人工本人命中 {ratio(r70['manual_self'])}；0.75 下为 {ratio(r75['manual_self'])}，已知姓名的拆分情况见下表。"
            f"这些数字是相似命中风险，不是真实误归率。已知姓名仅 {len(r70['named']['people'])} 人、{r70['named_samples']} 个合格单元，不能充分验证多人之间的区分能力。", "",
            "## 数据与口径", "",
            f"本地数据库含 {stats['segments']} 段、{stats['lines']} 句、{stats['segment_speaker_groups']} 个段×说话人单元。"
            f"{stats['feature_files']} 个特征文件中，有效向量 {stats.get('file_valid_vectors', 0)} 个、空向量 {stats.get('file_null_vectors', 0)} 个、格式错误 {stats.get('file_invalid', 0)} 个。"
            f"精确缓存键匹配 {stats['cache_matched']} 个单元；缺失 {stats.get('cache_missing', 0)} 个；孤立文件 {stats.get('orphan_files', 0)} 个。",
            f"有效匹配向量 {stats['usable_vectors']} 个；标签分布：{json.dumps(stats['labels'], ensure_ascii=False)}。"
            f"含播放标记的录音段共 {stats['playback_segments']} 个。",
            "按生产缓存格式读取 192 维大端浮点向量，按生产哈希规则关联转写；按录音起始时间、段 ID、句子顺序增量处理。只读临时副本加载 WAL，前后逐文件 SHA-256 验证输入未变。",
            "阈值扫描主过滤为有效人声 ≥3 秒，排除 playbackMillis>0、uncertain 说话人、混合/部分身份标签、异常时长。"
            f"有效向量的互斥排除计数：{json.dumps(excluded, ensure_ascii=False)}。",
            "实际陌生池只含无名单元。已标“我”按标签被挡住，观测误入为 0，但这不构成声纹算法准确率。"
            "下表把所有合格“我”单元查询陌生池最终中心，统计达到阈值的比例（冻结中心，不更新；回顾性评估，含未来片段）；这是离线误吸收风险代理，不能判定未知声音真的不是我。人工子集单独列出。"
            "自动标签依赖现有模型，不能当独立真值；未知池也可能混有漏识别的本人。没有原音、未做听辨，不报告真实误归率或纯度。",
            "已知姓名检查将姓名单元加入未知池，隐藏标签再聚类；按姓名列出“样本数/占据组数/最大组覆盖数”，不把无名成员当作不同人。",
            "每次出现按独立录音段去重；累计时长用缓存去静音人声时长，同一段以对应句子时间并集作上限。录音分段仍不等于独立社交场次。", "",
            "## 三种方法与阈值", "",
            "中心均为等权单位向量的均值再归一化。中心凝聚每次合并最相近的一对；增量+定期合并每 20 个输入及末尾执行同样的中心凝聚，合并阈值与加入阈值一致。不是 single/complete/average linkage。", "",
            table(["方法", "阈值", "陌生组数", "单次组", "触发组", "本人命中", "人工本人命中", "已知姓名 样本/组/最大覆盖", "跨姓名组"],
                  [[METHOD_NAMES[r['method']], f"{r['threshold']:.2f}", r['groups'], r['singletons'], r['triggered'], ratio(r['self']), ratio(r['manual_self']),
                    "；".join(f"{p['samples']}/{p['groups']}/{p['largest']}" for p in r['named']['people']) or "无样本", r['named']['mixed_named_groups']] for r in results]), "",
            f"0.70 下单遍为 {single70['groups']} 组、合并后 {r70['groups']} 组、中心凝聚 {agg70['groups']} 组；合并减少碎组，但没有证据证明纯度更好。"
            "低阈值下中心变化可使本人命中非单调，不能靠继续降低阈值追求更少组。只有一个已知姓名时，跨姓名组为 0 不能证明通过跨人误合并测试。", "",
            "### 各阈值组分布", "",
            "数组顺序均为最小/中位/P90/最大。每一组的精确出现次数与秒数保存在 private/results.json。", "",
            table(["方法", "阈值", "出现次数分布", "累计秒数分布"],
                  [[METHOD_NAMES[r['method']], f"{r['threshold']:.2f}", r['occurrences'], r['seconds']] for r in results]), "",
            "## 时长过滤敏感性", "",
            f"固定增量+定期合并、阈值 {chosen['threshold']:.2f}。时长指去静音后的有效人声，不是录音文件长度。", "",
            table(["最短秒数", "陌生单元", "组数", "触发组", "本人命中", "人工本人命中", "已知姓名样本"],
                  [[r['minimum_seconds'], r['unknown'], r['groups'], r['triggered'], ratio(r['self']), ratio(r['manual_self']), r['named_samples']] for r in sensitivity]), "",
            "## 观察配置下的组分布与门槛", "",
            f"观察候选（未获上线验证）：增量+定期合并，阈值 {chosen['threshold']:.2f}，有效人声 ≥3 秒。"
            f"{chosen['unknown']} 个陌生单元归成 {chosen['groups']} 组，其中单次组 {chosen['singletons']} 个。"
            f"按“≥3 次且 ≥300 秒”触发 {chosen['triggered']} 组；完整组明细、出现时段与代表原话仅在 private/report.md。",
            f"每组出现次数的最小/中位/P90/最大值：{chosen['occurrences']}；累计秒数同口径：{chosen['seconds']}。", "",
            f"较宽松的 0.65 对照归成 {exploratory['groups']} 组，触发 {exploratory['triggered']} 组。"
            "中心准入及后续合并没有保证全部成员两两相似，触发门槛不能证明组内是同一陌生人。"
            "不要为了触发提示而降阈值；时间段和代表原话仅保存在私有报告。", "", examples, "",
            table(["对照", "陌生单元", "组数", "触发组", "人工本人命中"],
                  [[name, r['unknown'], r['groups'], r['triggered'], ratio(r['manual_self'])] for name, r in robustness.items()]), "",
            "倒序和固定随机顺序只用于诊断顺序敏感性；排除导入素材用于检查来源偏差。门槛按录音段计数可能把同一次长谈拆成多次，正式开发建议同时显示去重场次，先人工确认后命名。", "",
            "## 过滤与试听建议", "",
            "3 秒是待验证的保守起点，不是已定标的最优值；1.5 秒是现有特征提取下限。无有效向量、播放标记、说话人不确定、身份冲突直接排除；短片段暂存等待更多证据。"
            "仅凭向量不能测出噪声、音量、削波或 SNR；有效人声/句子时长比只能描述静音占比，不能冒充质量评分。",
            "试听候选先按与组中心余弦相似度排序，再优先具有连续 8～12 秒单人转写区间的录音，选 3 个不同录音段并尽量覆盖不同场次。"
            "当前没有原音，无法断言最清楚，也不能把拼接的 10 秒有效人声等同于可播放的连续 10 秒。"
            "未来持有原音时，再在候选区间上测 RMS、削波率、SNR 和重叠说话；以近中心、足够人声、低噪声/低削波综合排序，并允许试听拒绝。", "",
            "## 结论边界", "",
            "这批样本只能支持候选参数比较，不能证明自动归组可以上线。合并会减少碎组，也可能不可逆地混入不同人；首次产品试验建议把合并结果作为可撤销候选。"
            "先用本人/已知人的识别结果作前置门禁；对本人相似但未达身份确认阈值的片段单独待确认，不能直接当陌生人。"
            "需要独立人工确认的多人正负样本与跨场次数据后，才能定最终阈值；本卡不联网、不操作手机、不修改 App。", ""]
    return "\n".join(text)


def build_private(public, items, groups, stats):
    text = [public, "## 私有附录：数据来源", "", json.dumps(stats['sources'], ensure_ascii=False), "",
            "以下全部时间、内部标识和原话仅留本机。每组至多 3 句原话；近中心候选不代表已通过音质听辨。", ""]
    for number, group in enumerate(groups, 1):
        centroid = center(items, group)
        ranked = sorted(group, key=lambda i: (-float(items[i].vector @ centroid), -items[i].duration, i))
        text += [f"### 组 {number}（{'触发' if triggered(items, group) else '未触发'}）", "",
                 f"单元 {len(group)}，独立录音 {occurrences(items, group)}，累计 {group_duration(items, group)/1000:.2f} 秒。", "",
                 table(["段", "说话人键", "录音起止", "有效人声秒", "中心相似度"],
                       [[items[i].segment['id'], items[i].key,
                         stamp(items[i].segment['startAt']) + " ～ " + stamp(items[i].segment['endAt']),
                         f"{items[i].duration/1000:.2f}", f"{items[i].vector @ centroid:.4f}"] for i in group]), ""]
        seen, examples = set(), []
        for i in ranked:
            item = items[i]
            if item.segment['id'] in seen:
                continue
            seen.add(item.segment['id'])
            line = max(item.lines, key=lambda l: min(l['endAt'] - l['startAt'], 10000))
            examples.append(f"- 段 {item.segment['id']}，{stamp(line['startAt'])} ～ {stamp(line['endAt'])}：" +
                            json.dumps(line['text'], ensure_ascii=False))
            if len(examples) == 3:
                break
        # 单次组可补同段其他句，最多 3 句；不足 2 句明确说明。
        if len(examples) < 2:
            for i in ranked:
                for line in items[i].lines:
                    value = f"- 段 {items[i].segment['id']}，{stamp(line['startAt'])} ～ {stamp(line['endAt'])}：" + json.dumps(line['text'], ensure_ascii=False)
                    if value not in examples:
                        examples.append(value)
                    if len(examples) >= 3:
                        break
                if len(examples) >= 3:
                    break
        text += ["代表原话/试听定位候选：", "", *examples, ""]
        if len(examples) < 2:
            text += ["该组仅有 1 句可用原话。", ""]
    return "\n".join(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threshold", type=float, default=0.70, choices=THRESHOLDS)
    args = parser.parse_args()
    private = ROOT / "private"
    before = input_hashes(private)
    items, stats = load_data(private)
    results = [evaluate(items, method, threshold)[0] for method in METHODS for threshold in THRESHOLDS]
    sensitivity = [evaluate(items, "periodic", args.threshold, minimum)[0] for minimum in (1.5, 3, 5, 10)]
    chosen, unknown, groups = evaluate(items, "periodic", args.threshold)
    excluded = dict(Counter(reason for i in items if (reason := reject_reason(i, 3))))
    random_order = list(items)
    np.random.default_rng(35).shuffle(random_order)
    robustness = {"时间倒序": evaluate(list(reversed(items)), "periodic", args.threshold)[0],
                  "固定随机顺序": evaluate(random_order, "periodic", args.threshold)[0],
                  "排除导入素材": evaluate([i for i in items if i.segment['sourceId'] != 'import'], "periodic", args.threshold)[0]}
    after = input_hashes(private)
    if before != after:
        raise RuntimeError("输入文件发生变化，停止报告")
    exploratory, ex_unknown, ex_groups = evaluate(items, "periodic", 0.65)
    public = build_public(stats, results, sensitivity, chosen, excluded, robustness, exploratory)
    private_report = build_private(public, unknown, groups, stats)
    private_report += "\n## 私有附录：0.65 对照中达到提示门槛的组\n\n"
    private_report += build_private("", ex_unknown, [g for g in ex_groups if triggered(ex_unknown, g)], stats)
    (private / "report.md").write_text(private_report, encoding="utf-8")
    # 白名单构建公开报告，绝不从私有报告做正则脱敏。
    public_path = ROOT.parents[1] / "docs" / "任务卡" / "035-实验报告.md"
    public_path.write_text(public, encoding="utf-8")
    (private / "results.json").write_text(json.dumps({"input_sha256": before, "stats": stats,
        "results": results, "sensitivity": sensitivity, "chosen": chosen, "excluded": excluded,
        "robustness": robustness, "exploratory": exploratory}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"vectors": stats['usable_vectors'], "unknown": chosen['unknown'],
                      "groups": chosen['groups'], "triggered": chosen['triggered'],
                      "manual_self": chosen['manual_self'], "input_unchanged": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()
