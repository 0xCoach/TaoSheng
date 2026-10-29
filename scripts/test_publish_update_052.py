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



class PrunePlanTest(unittest.TestCase):
    """魔搭清理：正式版留 3 个整包，预览版留 1 个，只删不指向当前版本的差异包，不碰说明文件。"""
    def test_formal_keeps_three_and_current_patches(self):
        paths = ["update.json", "README.md", "preview/update-preview.json", "preview/TaoSheng-v0.2.13-preview.1-arm64.apk",
                 *[f"TaoSheng-v0.2.{v}-arm64.apk" for v in (8, 9, 11, 12, 13)],
                 "TaoSheng-0.2.12-to-0.2.13.patch", "TaoSheng-0.2.11-to-0.2.12.patch"]
        doomed = publish.prune_plan(paths, "0.2.13", preview=False)
        self.assertEqual(doomed, ["TaoSheng-0.2.11-to-0.2.12.patch", "TaoSheng-v0.2.8-arm64.apk", "TaoSheng-v0.2.9-arm64.apk"])

    def test_preview_keeps_only_latest_inside_preview_dir(self):
        paths = ["TaoSheng-v0.2.12-arm64.apk", "preview/update-preview.json",
                 "preview/TaoSheng-v0.2.13-preview.1-arm64.apk", "preview/TaoSheng-v0.2.13-preview.2-arm64.apk",
                 "preview/TaoSheng-0.2.12-to-0.2.13-preview.1.patch", "preview/TaoSheng-0.2.12-to-0.2.13-preview.2.patch"]
        doomed = publish.prune_plan(paths, "0.2.13-preview.2", preview=True)
        self.assertEqual(doomed, ["preview/TaoSheng-0.2.12-to-0.2.13-preview.1.patch", "preview/TaoSheng-v0.2.13-preview.1-arm64.apk"])

if __name__ == "__main__":
    unittest.main()
