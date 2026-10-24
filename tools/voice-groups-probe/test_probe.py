"""仅用合成数据验证卡 035 的易错路径，不读取真实 private 数据。"""
import hashlib
from pathlib import Path
import sqlite3
import struct
import tempfile
import unittest

import numpy as np

import probe


def vector(angle=0):
    out = np.zeros(192)
    out[:2] = np.cos(angle), np.sin(angle)
    return out


def item(angle=0, segment_id=1, duration=5000, label=None, manual=False, playback=0):
    segment = {"id": segment_id, "path": "synthetic.pcm", "startAt": 0,
               "endAt": 200000, "playbackMillis": playback, "sourceId": "mic"}
    line = {"id": segment_id, "startAt": 0, "endAt": duration, "speakerTag": "0",
            "speakerName": label, "speakerManual": manual, "text": "SYNTHETIC_PRIVATE_TEXT"}
    return probe.Item(segment, "tag:0", [line], duration, duration, vector(angle), label, manual, False, False)


class ProbeTest(unittest.TestCase):
    def test_big_endian_and_null_feature(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "feature"
            path.write_bytes(struct.pack(">iqB192f", 1, 12345, 1, *(vector() * 2)))
            duration, value = probe.read_feature(path)
            self.assertEqual(duration, 12345)
            np.testing.assert_allclose(value, vector())
            path.write_bytes(struct.pack(">iqB", 1, 1400, 0))
            self.assertEqual(probe.read_feature(path), (1400, None))

    def test_corrupt_feature_rejected(self):
        good = struct.pack(">iqB192f", 1, 2000, 1, *vector())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "feature"
            for data in (good[:-1], good + b"x", struct.pack(">iqB", 2, 0, 0),
                         struct.pack(">iqB192f", 1, 2000, 1, *np.zeros(192)),
                         struct.pack(">iqB192f", 1, 2000, 1, *np.full(192, np.nan))):
                with self.subTest(length=len(data)):
                    path.write_bytes(data)
                    with self.assertRaises(ValueError):
                        probe.read_feature(path)

    def test_production_key_and_null_tag(self):
        sample = item()
        line = sample.lines[0]
        line['speakerTag'] = None
        expected = "campplus-v1|1|synthetic.pcm|0|200000|1:0:5000:line:1"
        self.assertEqual(probe.cache_key(sample.segment, [line]), hashlib.sha256(expected.encode()).hexdigest())
        line['id'] = 2
        self.assertNotEqual(probe.group_key(line), "line:1")

    def test_wal_loaded_without_touching_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            private = Path(tmp)
            (private / "features").mkdir()
            connection = sqlite3.connect(private / "capture.db")
            try:
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute("PRAGMA wal_autocheckpoint=0")
                connection.executescript("""
                    CREATE TABLE segments(id INTEGER, startAt INTEGER, endAt INTEGER, path TEXT,
                        playbackMillis INTEGER, sourceId TEXT);
                    CREATE TABLE transcript_lines(id INTEGER, segmentId INTEGER, startAt INTEGER,
                        endAt INTEGER, speakerTag TEXT, speakerName TEXT, speakerManual INTEGER, text TEXT);
                """)
                connection.execute("INSERT INTO segments VALUES (1,0,200000,'synthetic.pcm',0,'mic')")
                connection.execute("INSERT INTO transcript_lines VALUES (1,1,0,5000,'0',NULL,0,'PRIVATE')")
                connection.commit()
                sample = item()
                key = probe.cache_key(sample.segment, sample.lines)
                (private / "features" / key).write_bytes(struct.pack(">iqB192f", 1, 5000, 1, *vector()))
                before = probe.input_hashes(private)
                items, stats = probe.load_data(private)
                self.assertEqual((len(items), stats['cache_matched']), (1, 1))
                self.assertEqual(probe.input_hashes(private), before)
            finally:
                connection.close()

    def test_playback_short_and_uncertain_filters(self):
        self.assertEqual(probe.reject_reason(item(playback=1), 3), "playback")
        self.assertEqual(probe.reject_reason(item(duration=2999), 3), "short_speech")
        self.assertIsNone(probe.reject_reason(item(duration=3000), 3))
        sample = item()
        sample.uncertain = True
        self.assertEqual(probe.reject_reason(sample, 3), "uncertain_speaker")

    def test_three_methods_known_partition(self):
        items = [item(0), item(.1, 2), item(1.5, 3), item(1.6, 4)]
        for method in probe.METHODS:
            with self.subTest(method=method):
                self.assertEqual(probe.cluster(items, method, .9, merge_every=2), [[0, 1], [2, 3]])
                self.assertEqual(len(probe.cluster(items, method, .9999)), 4)

    def test_periodic_merge_preserves_member_weights(self):
        items = [item(0, n) for n in range(8)] + [item(.8, 8)]
        groups = probe.merge_groups(items, [list(range(8)), [8]], .65)
        self.assertEqual(groups, [list(range(9))])
        expected = probe.unit(vector() * 8 + vector(.8))
        np.testing.assert_allclose(probe.center(items, groups[0]), expected)
        self.assertLess(float(probe.center(items, groups[0])[1]), .1)

    def test_occurrences_dedup_and_duration_overlap_cap(self):
        items = [item(duration=100000), item(duration=100000), item(segment_id=2, duration=100000)]
        group = [0, 1, 2]
        self.assertEqual(probe.occurrences(items, group), 2)
        self.assertEqual(probe.group_duration(items, group), 200000)
        self.assertFalse(probe.triggered(items, group))
        items.append(item(segment_id=3, duration=100000))
        self.assertTrue(probe.triggered(items, [0, 1, 2, 3]))

    def test_negative_probe_does_not_train_centers(self):
        items = [item()]
        group = [[0]]
        before = items[0].vector.copy()
        result = probe.probes(items, group, [item(.1), item(1.5)], .9)
        self.assertEqual(result, {"hits": 1, "total": 2})
        np.testing.assert_array_equal(before, items[0].vector)
        self.assertEqual(group, [[0]])

    def test_labels_used_only_for_evaluation(self):
        items = [item(0), item(.1, 2, label="我", manual=True),
                 item(1.5, 3, label="SYNTHETIC_NAME", manual=True),
                 item(1.6, 4, label="SYNTHETIC_NAME", manual=True)]
        result, unknown, groups = probe.evaluate(items, "periodic", .9)
        self.assertEqual((len(unknown), result['self'], result['manual_self']),
                         (1, {"hits": 1, "total": 1}, {"hits": 1, "total": 1}))
        self.assertEqual(result['named']['people'], [{"samples": 2, "groups": 1, "largest": 2, "manual_samples": 2}])
        self.assertNotIn("SYNTHETIC_NAME", str(result))
        self.assertNotIn("SYNTHETIC_PRIVATE_TEXT", str(result))

    def test_empty_pool_and_zero_denominator(self):
        for method in probe.METHODS:
            result, _, _ = probe.evaluate([], method, .7)
            self.assertEqual((result['groups'], result['triggered']), (0, 0))
            self.assertEqual(probe.ratio(result['manual_self']), "0/0（不可评估）")


if __name__ == "__main__":
    unittest.main()
