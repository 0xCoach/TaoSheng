import importlib.util
import json
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519

spec = importlib.util.spec_from_file_location("sign_cards", Path(__file__).with_name("sign_device_cards.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def temporary_key(path, key=None):
    key = key or ec.generate_private_key(ec.SECP256R1())
    path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                     serialization.NoEncryption()))
    path.chmod(0o600)
    return key


class SigningTest(unittest.TestCase):
    def test_cli_der_spki_and_tampering(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "cards-signing.key"
            key = temporary_key(path)
            original = path.read_bytes()
            table = Path(folder) / "cards.json"
            table.write_text(json.dumps({"version": 1, "issuedAt": "2026-11-09T00:00:00Z", "cards": []}))
            public = Path(folder) / "public.hex"
            command = [sys.executable, str(Path(__file__).with_name("sign_device_cards.py")),
                       str(table), "--key", str(path), "--public-key-out", str(public)]
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.read_bytes(), original)
            signature = table.with_suffix(".json.sig").read_bytes()
            loaded = serialization.load_der_public_key(bytes.fromhex(public.read_text()))
            self.assertIsInstance(loaded.curve, ec.SECP256R1)
            loaded.verify(signature, table.read_bytes(), ec.ECDSA(hashes.SHA256()))
            with self.assertRaises(InvalidSignature):
                loaded.verify(signature, table.read_bytes() + b' ', ec.ECDSA(hashes.SHA256()))
            public.write_text("different")
            result = subprocess.run(command, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("different", public.read_text())

    def test_missing_key_is_not_created(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "absent" / "key"
            with self.assertRaises(FileNotFoundError):
                module.load_key(path)
            self.assertFalse(path.parent.exists())

    def test_unsafe_permissions_symlink_and_wrong_algorithm_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "key"
            temporary_key(path)
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                module.load_key(path)
            path.chmod(0o600)
            link = Path(folder) / "link"
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                module.load_key(link)
            for wrong in [ec.generate_private_key(ec.SECP384R1()), ed25519.Ed25519PrivateKey.generate()]:
                temporary_key(path, wrong)
                with self.assertRaises(ValueError):
                    module.load_key(path)


if __name__ == "__main__":
    unittest.main()
