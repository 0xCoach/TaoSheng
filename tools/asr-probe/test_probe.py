"""本卡安全边界与协议边界离线测试；绝不读取真实凭证、绝不联网。"""
import gzip
import json
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import analyze
import probe


class ProbeTests(unittest.TestCase):
    def test_budget_persists_and_rejects_overflow_and_duplicate(self):
        with tempfile.TemporaryDirectory() as t, patch.object(probe, 'ROOT', Path(t)):
            probe.reserve('first', 280.0065)
            for label, seconds in [('over', 20), ('first', 1)]:
                with self.assertRaises(ValueError):
                    probe.reserve(label, seconds)
            entries = json.loads((Path(t)/'results/budget.json').read_text())
            self.assertEqual(len(entries), 1)
            self.assertEqual(entries[0]['reserved_seconds'], 280.0065)

    def test_compressed_final_and_error_frames(self):
        payload = gzip.compress(json.dumps({'result': {'text': '完成'}}).encode())
        packet = bytes([0x11, 0x93, 0x11, 0]) + struct.pack('>iI', -4, len(payload)) + payload
        parsed = probe.parse_frame(packet)
        self.assertEqual(parsed['sequence'], -4)
        self.assertEqual(parsed['payload']['result']['text'], '完成')
        err = b'resource not granted'
        packet = bytes([0x11, 0xf0, 0, 0]) + struct.pack('>II', 45000030, len(err)) + err
        self.assertEqual(probe.parse_frame(packet)['code'], 45000030)
        self.assertEqual(probe.parse_frame(packet)['payload'], err.decode())

    def test_output_scrubs_credential_and_components(self):
        key = '-'.join(['abcdefgh', 'ijkl', 'mnop', 'qrst', 'uvwxyzabcdef'])
        result = probe.safe({'message': key.upper(), 'nested': {'part': 'ijkl'}}, key)
        self.assertNotIn(key.upper(), json.dumps(result))
        self.assertEqual(result['nested']['part'], '[REDACTED]')

    def test_accuracy_keeps_real_word_errors(self):
        self.assertEqual(analyze.normalized('上午十点。'), analyze.normalized('上午10点'))
        self.assertGreater(analyze.distance(analyze.normalized('回响'), analyze.normalized('回想')), 0)


if __name__ == '__main__':
    unittest.main()
