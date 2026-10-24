"""012 预算离线测试，不读密钥、不联网。"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import long012


class Long012Test(unittest.TestCase):
    def test_budget_retains_failed_reservations_and_blocks_repeat_or_overflow(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(long012, 'RESULTS', Path(directory)):
            for label, seconds in [('dns_failed', 70.0065), ('retry70', 70.0065), ('150', 150.0065), ('290', 290.0065)]:
                long012.reserve(label, seconds)
            for label, seconds in [('retry70', 1), ('extra', 20)]:
                with self.assertRaises(ValueError):
                    long012.reserve(label, seconds)
            entries = json.loads((Path(directory) / 'budget.json').read_text())
            self.assertEqual(4, len(entries))
            self.assertAlmostEqual(580.026, sum(entry['seconds'] for entry in entries))
