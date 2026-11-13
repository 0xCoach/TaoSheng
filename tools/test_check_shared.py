"""卡 176：共用 Room API 允许，旧安卓数据库入口仍禁止。"""
import tempfile
import unittest
from pathlib import Path

from check_shared import check


class CheckShared176Test(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "shared/src/commonMain/kotlin/Example.kt"
        self.source.parent.mkdir(parents=True)
        (self.root / "shared/build.gradle.kts").write_text('''
            commonMain.dependencies {
                api("androidx.room:room-runtime:2.8.4")
                api("androidx.sqlite:sqlite:2.6.2")
            }
            dependencies { add("kspJvm", "androidx.room:room-compiler:2.8.4") }
        ''')

    def test_common_room_and_sqlite_allowed(self):
        self.source.write_text('''import androidx.room.RoomDatabase
            import androidx.room.migration.Migration
            import androidx.sqlite.SQLiteConnection
            val label = "SupportSQLiteDatabase openHelper" // java.io.File
        ''')
        self.assertEqual([], check(self.root))

    def test_android_legacy_apis_and_hidden_dependency_rejected(self):
        self.source.write_text('''import androidx.sqlite.db.SupportSQLiteDatabase
            val query = SimpleSQLiteQuery("SELECT 1")
            val old = db.openHelper
            import androidx.room.withTransaction
            import androidx.lifecycle.ViewModel
        ''')
        self.assertGreaterEqual(len(check(self.root)), 5)
        self.source.write_text("val ok = 1")
        build = self.root / "shared/build.gradle.kts"
        build.write_text(build.read_text() + '\ndependencies { add("commonMainImplementation", "bad:bad:1") }')
        self.assertTrue(check(self.root))


if __name__ == "__main__":
    unittest.main()
