"""卡 052：仅本地测试，不调用发布服务，也不读取凭证。"""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import publish_update as publish


class PublishPreviewTest(unittest.TestCase):
    def test_channel_mismatch_exits_before_any_external_access(self):
        for name, flags in [("0.2.12", ["--preview"]), ("0.2.12-preview.1", [])]:
            with self.subTest(name=name), patch.object(publish, "badging", return_value=(28, name)), \
                 patch.object(publish, "gh", side_effect=AssertionError("不应联网")), \
                 patch("sys.argv", ["publish", "fake.apk", "--notes", "fake.txt", "--dry-run", *flags]), \
                 contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    publish.main()
                self.assertEqual(error.exception.code, 2)

    def test_offline_dry_run_writes_only_correct_channel_manifest(self):
        for preview in (True, False):
            with self.subTest(preview=preview), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                apk = root / "input.apk"
                apk.write_bytes(b"local-test-fixture")
                notes = root / "notes.txt"
                notes.write_text("本地验证")
                output = root / "output"
                output.mkdir()
                name = "0.2.12-preview.1" if preview else "0.2.12"
                args = ["publish", str(apk), "--notes", str(notes), "--dry-run"] + (["--preview"] if preview else [])
                with patch.object(publish, "badging", return_value=(28, name)), \
                     patch.object(publish, "gh", side_effect=AssertionError("不应联网")), \
                     patch.object(publish.tempfile, "mkdtemp", return_value=str(output)), \
                     patch("sys.argv", args), contextlib.redirect_stdout(io.StringIO()):
                    publish.main()
                prefix, manifest_name = publish.channel_paths(preview)
                self.assertEqual(prefix, "preview/" if preview else "")
                manifest = json.loads((output / manifest_name).read_text())
                self.assertEqual(manifest["versionName"], name)
                self.assertEqual(manifest["apk"]["sha256"], publish.sha256(apk))
                self.assertEqual(len(list(output.glob("*.json"))), 1)
                if preview:
                    self.assertFalse((output / "update.json").exists())


if __name__ == "__main__":
    unittest.main()
