#!/usr/bin/env python3
"""仅用已提交 schema 创建内存库，检查本卡新查询的索引计划；不读取手机/真实数据库。"""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[3]
schema = json.loads((root / 'shared/schemas/app.resound.data.CaptureDatabase/33.json').read_text())['database']
connection = sqlite3.connect(':memory:')
for entity in schema['entities']:
    name = entity['tableName']
    connection.execute(entity['createSql'].replace('${TABLE_NAME}', name))
    for index in entity.get('indices', []):
        connection.execute(index['createSql'].replace('${TABLE_NAME}', name))
methods = {
    'data/TranscribeDao.kt': {'observeQueueCounts','observeVoiceprintCounts','observeFailedSegments','recentDone','observeOverlapping','observeUsageTotals'},
    'data/MemoryDao.kt': {'observeUsageChanges','observeTokens'},
    'topic/TopicData.kt': {'observeCurrent','observePendingIds','observeProgress','observeLateTranscriptIds'},
    'importer/ImportRecordDao.kt': {'observeSummaryReady'},
    'envsound/EnvSoundDao.kt': {'observeBetween','presentationRows','observeSince','since','eventsBetween','presentationEvents','presentationEvent'},
}
print(f'SQLite {sqlite3.sqlite_version}; committed schema 33; synthetic in-memory database; not Android/device EXPLAIN')
pattern = r'@Query\(("""[\s\S]*?"""|"[^"\n]*")\)\s*(?:abstract\s+)?(?:suspend\s+)?fun\s+(\w+)'
for path, selected in methods.items():
    source = (root / 'app/src/main/java/app/resound' / path).read_text()
    found = set()
    for literal, name in re.findall(pattern, source):
        if name not in selected:
            continue
        found.add(name)
        sql = literal[3:-3] if literal.startswith('"""') else literal[1:-1]
        params = {p: 1 for p in re.findall(r':(\w+)', sql)}
        print(f'\n{path}#{name}\n' + ' '.join(sql.split()))
        for row in connection.execute('EXPLAIN QUERY PLAN ' + sql, params):
            print('  ' + row[3])
    assert found == selected, (path, selected - found)
