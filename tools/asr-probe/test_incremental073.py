import unittest
from incremental073 import Incremental, canonical


def body(*utterances):
    return {'result': {'utterances': list(utterances)}}


def line(start, text, definite=True):
    return dict(start_time=start, end_time=start+1000, text=text, definite=definite,
                additions={'speaker_id': '0'})


class Incremental073Test(unittest.TestCase):
    def test_revision_repeat_and_final_preserve_all_sentences(self):
        acc = Incremental()
        acc.accept(body(line(0, '第一句'), line(1200, '暂', False)))
        acc.accept(body(line(0, '第一句'), line(1200, '第二句', True)))
        acc.accept(body(line(2400, '末句', False)), final=True)
        self.assertEqual(canonical([line(0, '第一句'), line(1200, '第二句'), line(2400, '末句')]),
                         canonical(acc.result()))

    def test_unconfirmed_tail_is_not_committed_on_disconnect(self):
        acc = Incremental()
        acc.accept(body(line(0, '确定句'), line(1200, '未完成', False)))
        self.assertEqual(canonical([line(0, '确定句')]), canonical(acc.result()))


if __name__ == '__main__':
    unittest.main()
