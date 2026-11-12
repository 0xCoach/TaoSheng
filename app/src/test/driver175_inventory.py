#!/usr/bin/env python3
"""卡 175：只读扫描代码，输出需人工复核的 API 位置及计数（不修改产品）。"""
from collections import Counter
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
PATTERNS = {
    "类型": r"\b(?:SupportSQLite\w+|SimpleSQLiteQuery|SQLiteDatabase|Cursor)\b",
    "底层": r"\b(?:openHelper|writableDatabase|readableDatabase|compileStatement|execSQL|rawQuery|beginTransaction\w*|endTransaction|setTransactionSuccessful)\b",
    "事务": r"\b(?:withTransaction|runInTransaction)\s*\{",
    "通知": r"\b(?:invalidationTracker|InvalidationTracker)\b",
    "查询": r"@RawQuery|\b(?:db|database|sql|source|target|old|copy)\.query\(",
}


def scan(directory):
    result = {}
    for file in sorted(directory.rglob("*.kt")):
        # 此文件的 execSQL 只是 SQL 防注入关键字字符串，不是数据库调用。
        if file.name == "OpenAiEndpoint.kt":
            continue
        hits = {category: [] for category in PATTERNS}
        for line_no, line in enumerate(file.read_text().splitlines(), 1):
            if line.lstrip().startswith(("import ", "//", "*")):
                continue
            for category, pattern in PATTERNS.items():
                hits[category] += [line_no] * len(re.findall(pattern, line))
        if any(hits.values()):
            result[file.relative_to(ROOT).as_posix()] = hits
    return result


def report():
    lines = ["# 卡 175：数据层 API 改动清单（第一段基线）", "",
             "由 `python3 app/src/test/driver175_inventory.py` 生成。基线提交见交付说明。",
             "处数是源码 token 次数，排除 import、整行注释；同一行可有多个 token、多个类别，不能相加当工作项数。事务只计调用，排除 return 标签。",
             "类型→SQLiteConnection/SQLiteStatement/RoomRawQuery；底层→prepare/bind/step/use；查询→读连接及类型化取列；事务→writer + immediateTransaction；通知→保留并核验，裸写入检查 refreshAsync。",
             "手动复核：ContentResolver.query、core.query、SQL 防注入关键字均不计。所有位置是切换时的核查点，通知 API 不代表都要删除。", ""]
    for heading, directory in [("产品代码", ROOT / "app/src/main/java"), ("测试适配候选", ROOT / "app/src/test/java")]:
        found = scan(directory)
        totals = Counter({c: sum(len(h[c]) for h in found.values()) for c in PATTERNS})
        lines += [f"## {heading}", "", f"{len(found)} 个文件；" + "，".join(f"{c} {n}" for c, n in totals.items()) + "。", "",
                  "| 文件 | 类型 | 底层 | 事务 | 通知 | 查询 |", "|---|---:|---:|---:|---:|---:|"]
        for file, hits in found.items():
            def cell(category):
                loc = hits[category]
                return f"{len(loc)}（{','.join(map(str, sorted(set(loc))))}）" if loc else "—"
            lines.append("| `" + file + "` | " + " | ".join(cell(c) for c in PATTERNS) + " |")
        lines.append("")
    lines += ["## 无需逐个手改的生成代码", "",
              "19 个 DAO 的 @Query/@Insert/@Update/@Delete 实现由 Room 2.8.4 KSP 生成；不要编辑 build/generated。`CaptureDatabase` 中 32 段 Migration 全部只在 MIGRATIONS 维护。",
              "额外装配点：`app/build.gradle.kts`（驱动依赖选择）、`CaptureDatabase.open`（setDriver）、测试 builder/回调/QueryCallback。若未来新增业务层规则，放 :shared；本卡不搬家。", ""]
    return "\n".join(lines).rstrip()


if __name__ == "__main__":
    print(report())
