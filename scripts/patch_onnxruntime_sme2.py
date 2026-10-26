#!/usr/bin/env python3
"""
Patch arm64-v8a/libonnxruntime.so (v1.23.2) to disable MLAS_CPUIDINFO.has_arm_sme_.

On Qualcomm Snapdragon 8 Elite / 8 Gen 5 (Oryon) devices (e.g., OPPO Find N6),
Linux AT_HWCAP2 sets HWCAP2_SME (bit 23), causing ONNX Runtime 1.23.2 MLAS to
dispatch into KleidiAI SME2 kernels (`_sme2_mopa` using `ptrue pn9.b`), which
triggers `Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC)` when CaptureService
initializes SileroVad and CamPlusPlus.

This script rewrites the two `has_arm_sme_ = 1` byte stores in MLAS_CPUIDINFO:
  - 0x0075ddac: 68 16 00 39 (`strb w8, [x19, #5]`)    -> 7f 16 00 39 (`strb wzr, [x19, #5]`)
  - 0x011e4a0c: 68 16 01 39 (`strb w8, [x19, #0x45]`) -> 7f 16 01 39 (`strb wzr, [x19, #0x45]`)
forcing ONNX Runtime to fall back to standard ARM64 NEON / FP16 / DotProd kernels.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import sys

EXPECTED_SIZE = 19_347_616
ORIG_SHA256 = "e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302"
PATCHED_SHA256 = "6b635a1d1df0d70f942329ef3ab5a3f3bd387bc5e119681c9d0b69ce8c744081"

PATCH_SITES = (
    {
        "name": "MLAS_CPUIDINFO::MLAS_CPUIDINFO (#1)",
        "offset": 0x0075DDAC,
        "pre": bytes.fromhex("c1fbbf3628008052"),
        "orig": bytes.fromhex("68160039"),
        "patched": bytes.fromhex("7f160039"),
        "post": bytes.fromhex("81fb2fb6"),
    },
    {
        "name": "MLAS_CPUIDINFO::MLAS_CPUIDINFO (#2)",
        "offset": 0x011E4A0C,
        "pre": bytes.fromhex("690e01396a120139"),
        "orig": bytes.fromhex("68160139"),
        "patched": bytes.fromhex("7f160139"),
        "post": bytes.fromhex("33c8d597"),
    },
)


def patch_so(path: pathlib.Path, check_only: bool = False) -> int:
    data = bytearray(path.read_bytes())
    if len(data) != EXPECTED_SIZE:
        print(
            f"[ERROR] Unexpected file size {len(data)} (expected {EXPECTED_SIZE}) for {path}",
            file=sys.stderr,
        )
        return 1

    modified = False
    for site in PATCH_SITES:
        off = site["offset"]
        pre = site["pre"]
        post = site["post"]
        orig = site["orig"]
        patched = site["patched"]

        if data[off - len(pre) : off] != pre or data[off + 4 : off + 4 + len(post)] != post:
            print(
                f"[ERROR] Context mismatch at {site['name']} (0x{off:x})",
                file=sys.stderr,
            )
            return 1

        cur = bytes(data[off : off + 4])
        if cur == patched:
            print(f"[OK] {site['name']} @ 0x{off:x} already patched ({patched.hex()})")
        elif cur == orig:
            if check_only:
                print(f"[UNPATCHED] {site['name']} @ 0x{off:x} is unpatched ({orig.hex()})")
                modified = True
            else:
                data[off : off + 4] = patched
                modified = True
                print(
                    f"[PATCHED] {site['name']} @ 0x{off:x}: {orig.hex()} -> {patched.hex()}"
                )
        else:
            print(
                f"[ERROR] Unexpected instruction {cur.hex()} at 0x{off:x}",
                file=sys.stderr,
            )
            return 1

    if check_only:
        sha = hashlib.sha256(data).hexdigest()
        print(f"[INFO] SHA-256: {sha}")
        return 1 if modified else 0

    if modified:
        path.write_bytes(data)

    sha = hashlib.sha256(data).hexdigest()
    if sha != PATCHED_SHA256:
        print(
            f"[WARN] Patched SHA-256 {sha} differs from expected {PATCHED_SHA256}",
            file=sys.stderr,
        )
        return 1

    print(f"[SUCCESS] Verified patched SHA-256: {sha}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Patch or verify ONNX Runtime 1.23.2 arm64-v8a/libonnxruntime.so for Oryon SME2 compatibility."
    )
    parser.add_argument("so_file", type=pathlib.Path, help="Path to libonnxruntime.so")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify whether the file is already patched without modifying it",
    )
    args = parser.parse_args()
    return patch_so(args.so_file, check_only=args.check)


if __name__ == "__main__":
    raise SystemExit(main())
