import importlib.util
import json
import stat
import tempfile
import unittest
from pathlib import Path
from cryptography.exceptions import InvalidSignature

spec = importlib.util.spec_from_file_location("sign_cards", Path(__file__).with_name("sign_device_cards.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SigningTest(unittest.TestCase):
    def test_generation_reuse_and_tampering(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "cards-signing.key"
            key = module.load_key(path)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            original = path.read_bytes()
            loaded = module.load_key(path)
            self.assertEqual(path.read_bytes(), original)
            data = b'{"version":1}'
            signature = loaded.sign(data)
            key.public_key().verify(signature, data)
            with self.assertRaises(InvalidSignature):
                key.public_key().verify(signature, data + b' ')

    def test_unsafe_permissions_and_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "key"
            module.load_key(path)
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                module.load_key(path)
            path.chmod(0o600)
            link = Path(folder) / "link"
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                module.load_key(link)


if __name__ == "__main__":
    unittest.main()
