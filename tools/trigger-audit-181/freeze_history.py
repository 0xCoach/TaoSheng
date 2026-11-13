#!/usr/bin/env python3
"""从指定历史源码冻结回调；只读取 git 中的源码和 schema，不读取用户数据库。

根目录运行：python3 tools/trigger-audit-181/freeze_history.py [--check]
测试运行不依赖 git；--check 核对冻结内容和每个来源版本的回调/结构。
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "app/src/main/java/app/resound/"
SNAPSHOTS = {
    10: "a577273^", 11: "a577273", 17: "b1e22d5", 18: "32df98c",
    25: "6b01421", 26: "52b9103", 27: "e67d9ee", 28: "858c1bb",
    29: "774263c", 32: "6ee57a6",
}
CALLBACKS = {
    "SearchIndex": (11, "a577273", "search/SearchIndex.kt", "object SearchIndex"),
    "ImportRecordSchema": (26, "52b9103", "importer/ImportRecordDao.kt", "object ImportRecordSchema"),
    "ThingSchema": (27, "0bbc36e", "things/ThingData.kt", "internal object ThingSchema"),
    "PendingDigestSchema": (29, "774263c", "data/PendingDigest.kt", "internal object PendingDigestSchema"),
}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode()


def source(ref, path):
    return git("show", f"{ref}:{SOURCE}{path}")


def block(ref, path, start):
    # 这四个对象均在各自文件末尾；保留原文，不解释/重写 Kotlin 或 SQL。
    return start + source(ref, path).split(start, 1)[1]


def tokens(ref):
    text = source(ref, "search/SearchIndex.kt")
    start = "    fun sql(value: String): String {"
    return "object SearchTokens {\n" + start + text.split(start, 1)[1].split("\nobject SearchIndex", 1)[0]


def adapt_callback(text):
    # 卡 175：历史库直接由系统 SQLite 建立。仅机械替换类型/回调基类，
    # 全文比较仍覆盖每个 SQL 字符、注释与其余逻辑，不忽略或规范化 SQL。
    return text.replace("db: SupportSQLiteDatabase", "db: SQLiteDatabase").replace(
        "object : RoomDatabase.Callback()", "object : HistoricalCallback()")


def write_or_check(path, value):
    target = ROOT / path
    if "--check" in sys.argv:
        assert target.read_text() == value, f"冻结内容不一致：{path}"
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)


def main():
    frozen = {name: block(ref, path, start) for name, (_, ref, path, start) in CALLBACKS.items()}
    header = """// 卡 181 历史夹具，由 tools/trigger-audit-181/freeze_history.py 从 git 原文提取。
// 仅改 package / imports 和历史建库类型/回调基类；SQL 原文不变，不跟随产品代码更新。
package app.resound.data.history181

import android.database.sqlite.SQLiteDatabase

"""
    kotlin = header + "// a577273:search/SearchIndex.kt\n" + tokens("a577273")
    for name, (_, ref, path, _) in CALLBACKS.items():
        kotlin += f"\n// {ref}:{path}\n" + adapt_callback(frozen[name])

    manifest = []
    for version, ref in SNAPSHOTS.items():
        revision = git("rev-parse", ref).strip()
        database = source(ref, "data/CaptureDatabase.kt")
        assert f"VERSION = {version}" in database or f"version = {version}," in database
        schema_path = f"app/schemas/app.resound.data.CaptureDatabase/{version}.json"
        schema = git("show", f"{revision}:{schema_path}")
        assert schema == (ROOT / schema_path.replace("app/schemas/", "shared/schemas/", 1)).read_text(), f"v{version} 的 schema 与历史不一致"
        names = []
        for name, (introduced, _, path, start) in CALLBACKS.items():
            if version >= introduced:
                assert f"{name}.callback" in database, f"v{version} 未注册 {name}"
                assert block(ref, path, start) == frozen[name], f"v{version} 的 {name} 有变化"
                names.append(name)
            else:
                assert f"{name}.callback" not in database
        if version >= 11:
            assert tokens(ref) == tokens("a577273"), f"v{version} 的分词 SQL 有变化"
        manifest.append({"version": version, "commit": revision, "callbacks": names,
                         "schemaSha256": hashlib.sha256(schema.encode()).hexdigest()})
    write_or_check("app/src/test/java/app/resound/data/history181/FrozenCallbacks.kt", kotlin)
    write_or_check("app/src/test/resources/trigger-audit-181/history.json",
                   json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("历史回调与 10 个来源版本核对通过")


if __name__ == "__main__":
    main()
