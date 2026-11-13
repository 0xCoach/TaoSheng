#!/usr/bin/env python3
"""卡 183 的只读前置核查；仅用 schema 创建内存库，不是性能或产品迁移测试。"""
import difflib
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = "603620df99969ad8aa7c63db6c0dba49a3da58af"
OUT = ROOT / "docs/任务卡/183-证据"
FIXTURES = ROOT / "app/src/test/resources/driver175"
SCHEMAS = ROOT / "app/schemas/app.resound.data.CaptureDatabase"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    checked = []
    for name, expected in manifest["files"].items():
        data = (FIXTURES / name).read_bytes()
        assert len(data) == expected["bytes"] and sha(data) == expected["sha256"], name
        checked.append({"name": name, "bytes": len(data), "sha256": sha(data)})
    schemas = []
    for version in range(1, 34):
        path = SCHEMAS / f"{version}.json"
        data = path.read_bytes()
        baseline = subprocess.check_output(["git", "show", f"{BASE}:{path.relative_to(ROOT)}"], cwd=ROOT)
        assert data == baseline, path
        schemas.append({"version": version, "sha256": sha(data)})

    # 与 Driver175FixturesTest.snapshot 一样保留 user_version。
    # 此探针故意保持旧 identity_hash 不动，单凭版本行即可证明“只删索引行”不成立。
    definition = json.loads((SCHEMAS / "33.json").read_text())["database"]
    db = sqlite3.connect(":memory:")
    try:
        for entity in definition["entities"]:
            db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for sql in definition["setupQueries"]:
            db.execute(sql)
        db.execute("PRAGMA user_version=33")

        def snapshot():
            version = db.execute("PRAGMA user_version").fetchone()[0]
            rows = db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").fetchall()
            return [f"user_version={version}\n"] + [json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows]

        before = snapshot()
        # 仅演示候选 A，并不代表已评估、决定或实际向产品添加此索引。
        db.execute("CREATE INDEX IF NOT EXISTS index_segments_status_voiceprintProcessedAt ON segments(status, voiceprintProcessedAt)")
        db.execute("PRAGMA user_version=34")
        after = snapshot()
        extra = [line for line in after[1:] if line not in before[1:]]
        assert len(extra) == 1 and json.loads(extra[0])[1] == "index_segments_status_voiceprintProcessedAt"
        stripped = [line for line in after if line not in extra]
        assert before[1:] == stripped[1:]
        assert before != stripped
        (OUT / "仅移除索引行后的差异.diff").write_text("".join(difflib.unified_diff(before, stripped, fromfile="synthetic-v33", tofile="synthetic-v34-minus-index")))
    finally:
        db.close()

    versioned = [item["name"] for item in checked if item["name"].endswith(".txt") and (FIXTURES / item["name"]).read_text().startswith("user_version=33\n")]
    report = {
        "baseline": BASE,
        "scope": "历史前置规则复现；此脚本不执行产品迁移或导出schema",
        "fixture_count": len(checked),
        "version_33_snapshots": versioned,
        "fixture_verification": checked,
        "unchanged_schemas": schemas,
        "schema33_identity_hash": definition["identityHash"],
        "probe": "只删除新增索引行，仍留下 user_version=33 与 user_version=34 的差异；identity_hash 尚未变更",
    }
    (OUT / "前置核查.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"旧夹具 {len(checked)} 份 SHA-256/大小一致；schema 1～33 与基线一致；{len(versioned)} 份快照固定 user_version=33。")
    print("历史结论：只移除索引行不足以跨版本比对；183审核意见已批准精确版本元数据差异，当前验收用verify.py。")


if __name__ == "__main__":
    main()
