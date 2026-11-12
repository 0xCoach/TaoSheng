#!/usr/bin/env python3
"""在仓库根目录运行 python3 tools/ios_progress.py，仅统计手写 Kotlin/Java 源码。"""
from collections import Counter
from pathlib import Path


def source_lines(directory):
    for file in sorted(directory.rglob('*')):
        if file.suffix in {'.kt', '.java'} and file.is_file():
            yield file, len(file.read_text(encoding='utf-8').splitlines())


def main():
    root = Path(__file__).resolve().parents[1]
    packages = Counter()
    totals = {}
    for module in ('shared', 'devices'):
        total = 0
        for file, lines in source_lines(root / module / 'src/commonMain'):
            total += lines
            relative = file.relative_to(root / module / 'src/commonMain').as_posix()
            package = relative.split('/app/resound/', 1)[-1].split('/')[0]
            packages[package] += lines
        totals[module] = total
    app = sum(lines for _, lines in source_lines(root / 'app/src/main'))
    common = sum(totals.values())
    denominator = app + common
    print('口径：shared/devices 的 src/commonMain 与 app/src/main 下 .kt/.java 物理行数，含注释和空行；不含测试、资源、生成代码、vendor 和原生源码。')
    print(f"shared 共用：{totals['shared']:,} 行；devices 共用：{totals['devices']:,} 行；共用合计：{common:,} 行")
    print(f'app/src/main：{app:,} 行；总计：{denominator:,} 行；共用占比：{common / denominator:.2%}' if denominator else '总计：0 行；共用占比：0.00%')
    print('按 app.resound 一级包汇总共用行数（两模块合计，含子包）：')
    for package, lines in sorted(packages.items()):
        print(f'  {package}：{lines:,} 行')


if __name__ == '__main__':
    main()
