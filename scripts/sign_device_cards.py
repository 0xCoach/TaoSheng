#!/usr/bin/env python3
"""ECDSA P-256 + SHA-256 签署原始文件字节；依赖 cryptography。签名为 DER 编码。"""
import argparse
import json
import os
import stat
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def load_key(path):
    # 正式私钥由负责人单独生成；签名命令绝不创建、覆盖或轮换密钥。
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError("私钥必须是权限 600 的普通文件，不能是符号链接")
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("必须使用 ECDSA P-256 私钥")
    return key


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("table", type=Path)
    parser.add_argument("--key", type=Path, default=Path.home() / ".config/taosheng/cards-signing.key")
    parser.add_argument("--public-key-out", type=Path, help="首次初始化时写入 App 公钥文件；已有不同公钥时拒绝覆盖")
    args = parser.parse_args()
    data = args.table.read_bytes()
    table = json.loads(data)
    if len(data) > 256 * 1024 or set(table) != {"version", "issuedAt", "cards"} or type(table["version"]) is not int or table["version"] <= 0 or not isinstance(table["cards"], list):
        raise ValueError("参数表格式或大小错误")
    key = load_key(args.key.expanduser())
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo).hex()
    print("P-256 公钥（X.509 SPKI DER hex）：" + public)
    if args.public_key_out:
        if args.public_key_out.exists() and args.public_key_out.read_text().strip() != public:
            raise ValueError("现有公钥不同，禁止自动更换信任根")
        args.public_key_out.write_text(public + "\n")
    signature = key.sign(data, ec.ECDSA(hashes.SHA256()))
    key.public_key().verify(signature, data, ec.ECDSA(hashes.SHA256()))
    target = args.table.with_name(args.table.name + ".sig")
    temp = target.with_name(target.name + ".tmp")
    with temp.open("wb") as output:
        output.write(signature)
        output.flush()
        os.fsync(output.fileno())
    temp.replace(target)
    print("已签名：" + str(target))


if __name__ == "__main__":
    main()
