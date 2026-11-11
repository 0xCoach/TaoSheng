"""独立 Python cryptography 向量。仅公开测试私钥，不能用于真实设备。"""
import json
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization as s

own = ec.derive_private_key(0x123456789ABCDEF112233445566778899, ec.SECP256R1())
peer = ec.derive_private_key(0x998877665544332211FEDCBA987654321, ec.SECP256R1())
message = b"device-hub-card-140-vector"
def spki(key):
    return key.public_key().public_bytes(s.Encoding.DER, s.PublicFormat.SubjectPublicKeyInfo).hex()
def point(key):
    return key.public_key().public_bytes(s.Encoding.X962, s.PublicFormat.UncompressedPoint).hex()
result = dict(ownSpki=spki(own), ownPoint=point(own),
    ownPkcs8=own.private_bytes(s.Encoding.DER, s.PrivateFormat.PKCS8, s.NoEncryption()).hex(),
    peerSpki=spki(peer), peerPoint=point(peer), shared=own.exchange(ec.ECDH(), peer.public_key()).hex(),
    message=message.hex(), signature=peer.sign(message, ec.ECDSA(hashes.SHA256(), deterministic_signing=True)).hex())
Path(__file__).with_name("hub-p256-python.json").write_text(json.dumps(result, indent=2) + "\n")
