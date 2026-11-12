# 卡 175 第一段标准答案

全部来自合成数据。产品基线 `4caedfb3c6ee74fb95d6f31b34beac354b9efcb5`，Room 2.8.4、sqlite-framework 2.6.2、Robolectric 4.16、SDK 29、native SQLite 3.32.2。不是手机数据，也不是 iOS 验收。

- `migrations/from-01.txt` ～ `from-32.txt`：按各版本 Room JSON 建库，每个实体插入三行，调用产品 `CaptureDatabase.open` 升级；包括所有表的数据与完整 sqlite_master。1～33 的结构文件齐全。
- `fresh-install.txt`：产品新装结构及空表数据；57 张物理/虚拟表、46 个索引、15 个触发器、0 个视图。
- `queries-seed.txt`、`queries.txt`：固定查询输入库、25 组产品入口的输入/返回值/实际 SQL/参数/类型化结果行。包含全部 10 个 SimpleSQLiteQuery 构造调用、唯一 @RawQuery、三个 FTS 表的四类检索。
- `synthetic-backup.zip`：旧实现 BackupArchive.create 输出的 format=1 完整备份；只含合成库、空白偏好设置及空热词。
- `backup-before.txt`、`backup-restored.txt`：备份前与 BackupArchive.verify → BackupRestore.stage/applyPending → CaptureDatabase.open 后的完整快照。
- `engine.txt`：录制环境诊断；本环境 PRAGMA compile_options 没有返回列/行，不假造编译参数。
- `manifest.json`：39 个产物的大小、SHA-256 及基线版本。

结构 SQL 只折叠引号外空白；保留名称、引号、SQL 常量、自动索引的 null SQL。每个表的全部行按类型化 JSON 字符串排序以去掉无 ORDER BY 的不确定顺序；查询结果保持实际顺序。NULL、INTEGER、REAL、TEXT、BLOB 分开，BLOB 全量十六进制，不截断文本。FTS rowid、影子表、sqlite_sequence、android_metadata、room_master_table 都保留。临时库不属于 sqlite_master。

历史 JSON 不包含手写触发器：本组严格按 JSON 创建来源库，没有猜测历史 onCreate 回调。最终触发器数为 v1～17：15、v18～26：5、v27～32：2。它们是本组输入的标准答案，不是“所有真实旧库只应有这么多触发器”；新装与原库备份夹具补充覆盖现有全部 15 个触发器。详见交付说明。

默认运行只比较，不生成或覆盖：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew -Dorg.gradle.daemon.registry.base=/tmp/taosheng-175-gradle -PskipSigningSecrets=true \
  :app:testDebugUnitTest --tests app.resound.data.Driver175FixturesTest
```

首次录制才设置 `TAOSHENG_175_RECORD=1`；即使设置，也只创建不存在的文件，已有文件仍逐字断言。不能通过删除/重录旧夹具让第二段“通过”。ZIP 有生成时间元数据，不比较重打包 ZIP 字节；永久保存旧 ZIP，验证其 SHA、解包内容和恢复结果。迁移全部走产品 open 中的 `.addMigrations(*MIGRATIONS)`，版本断言使用 `CaptureDatabase.VERSION`。

查询录制利用旧 Room 的同步 QueryCallback，在产品 SELECT 前对同一连接/SQL/绑定参数读取结果，再执行产品入口并记录返回值；合成场景无并发写入。这样不会在 IdeaMerger 写完后再查询而丢失合并前候选。第二段 Room 2.8.4 driver 模式不支持 setQueryCallback，需要把测试采集器改为 SQLiteDriver/SQLiteStatement 包装，继续读取本目录旧答案；不能改产品查询来适配测试。
