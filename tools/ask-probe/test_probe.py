import datetime as dt
import importlib.util
import json
import pathlib
import sqlite3
import unittest
spec = importlib.util.spec_from_file_location('probe', pathlib.Path(__file__).with_name('probe.py'))
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)

class ProbeTest(unittest.TestCase):
    def test_calendar_and_inclusive_date(self):
        self.assertIn('2026-10-21 周三（本周周三，前天）', p.calendar(dt.date(2026,10,23)))
        self.assertEqual(86400000,p.timestamp('2026-10-23',True)-p.timestamp('2026-10-23'))
        self.assertEqual(60000,p.timestamp('2026-10-23 15:00',True)-p.timestamp('2026-10-23 15:00'))
    def test_sql_uses_parameters_and_or(self):
        sql,args,kind=p.query('search_lines',{'keywords':['老王',"%' OR 1=1 --"],'speaker':'我'})
        self.assertNotIn('老王',sql); self.assertNotIn('1=1',sql)
        self.assertIn(' OR ',sql); self.assertEqual('line',kind)
        self.assertEqual('%老王%',args[0]); self.assertTrue(args[1].startswith('%\\%'))
    def test_real_snapshot_citations_and_bounds(self):
        db=sqlite3.connect(f'file:{p.PRIVATE / "snapshot.db"}?mode=ro',uri=True); db.row_factory=sqlite3.Row
        s=p.Search(db)
        for name,args in [('search_lines',{'keywords':[]}),('search_events',{}),('list_items',{}),('get_day',{'date':'2026-10-23'})]:
            out=s.call(name,args)
            self.assertLessEqual(len(out['results']),40)
            self.assertLessEqual(len(json.dumps(out,ensure_ascii=False,separators=(',',':'))),6000)
            for row in out['results']:
                source=s.sources[row['ref']-1]
                self.assertEqual(row['id'],source['id'])
                if name=='search_lines':
                    text=db.execute('select text from transcript_lines where id=?',(source['id'],)).fetchone()[0]
                    self.assertTrue(text.startswith(source['quote']))
        self.assertEqual([],s.call('search_lines',{'keywords':['火星基地氦三燃料009不存在']})['results'])
        db.close()

if __name__=='__main__': unittest.main()
