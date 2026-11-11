#!/usr/bin/env python3
"""最小自测；只用临时目录，不改仓库。"""
import tempfile
import unittest
from pathlib import Path

from check_devices import COMMON, KNOWN_BRANDS_140, check


class CheckDevicesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("devices/build.gradle.kts", '''kotlin { sourceSets {
            commonMain.dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        } }''')

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")

    def test_clean(self):
        self.write(COMMON + "kotlin/core.kt", '// java.io.File 只是说明\nval label = "java.time.Instant"')
        self.write(COMMON + "kotlin/app/resound/devices/plugins/omi/Driver.kt", 'package app.resound.devices.plugins.omi')
        self.write("app/src/test/java/Example.kt", 'val brand = "Omi"')
        for path, _ in KNOWN_BRANDS_140:
            self.write(path, "\n".join(line for p, line in KNOWN_BRANDS_140 if p == path))
        self.assertEqual([], check(self.root))

    def test_java_import_and_qualified_reference(self):
        path = COMMON + "kotlin/Bad.kt"
        self.write(path, 'import java.io.File\nval clock = java.time.Instant.now()\nval template = "${java.time.Instant.now()}"')
        errors = check(self.root)
        self.assertEqual(3, len(errors))
        self.assertTrue(errors[0].startswith(path + ":1:"))
        self.assertTrue(errors[1].startswith(path + ":2:"))
        self.assertTrue(errors[2].startswith(path + ":3:"))

    def test_brands_from_assets_and_new_violation_in_known_file(self):
        self.write("app/src/main/assets/devices/new.json", '{"id":"new-brand"}')
        path = "app/src/main/java/app/resound/devices/AndroidDevices.kt"
        self.write(path, 'import app.resound.devices.plugins.sonicloud.*\nval brand = "NEW-BRAND OMI"\nval ordinary = "beeper"')
        errors = check(self.root)
        self.assertEqual(1, len(errors))
        self.assertTrue(errors[0].startswith(path + ":2:"))

    def test_cross_plugin(self):
        path = COMMON + "kotlin/app/resound/devices/plugins/omi/Driver.kt"
        self.write(path, 'import app.resound.devices.plugins.sonicloud.Files\nval own = app.resound.devices.plugins.omi.Local')
        errors = check(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("插件 omi 不得引用插件 sonicloud", errors[0])

    def test_dependency_allowlist(self):
        for dependency in ['implementation("com.example:bad:1.0")', 'api(libs.hidden)',
                           'compileOnly("com.example:bad:1.0")']:
            with self.subTest(dependency=dependency):
                self.write("devices/build.gradle.kts", "kotlin { sourceSets { commonMain.dependencies { " + dependency + " } } }")
                self.assertTrue(check(self.root))


if __name__ == "__main__":
    unittest.main()
