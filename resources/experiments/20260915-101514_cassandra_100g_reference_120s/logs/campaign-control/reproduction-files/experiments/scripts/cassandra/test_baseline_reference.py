#!/usr/bin/env python3
"""Safety regressions: reject reference mutation and detect changed DB bytes."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('reference', Path(__file__).with_name('baseline_reference.py'))
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)


class ReferenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.table = self.root / 'canonical/data/ks/table'
        self.table.mkdir(parents=True)
        self.component = self.table / 'oa-1-big-Data.db'
        self.component.write_bytes(b'baseline data')
        self.manifest = self.root / 'evidence/reference.json'
        self.manifest.parent.mkdir()
        row = dict(path=str(self.component), sha256=reference.digest(self.component),
                   **reference.stat_record(self.component))
        self.manifest.write_text(json.dumps(dict(version=1, canonical_root=str(self.root / 'canonical'),
            table_directories=[str(self.table)], components=[row], evidence=[])))

    def tearDown(self):
        self.temp.cleanup()

    def test_valid_snapshot_and_independent_target(self):
        self.assertTrue(reference.validate(self.manifest)['valid'])
        self.assertTrue(reference.validate(self.manifest, full=True)['valid'])
        self.assertTrue(reference.guard(self.manifest, [str(self.root / 'new-run')])['allowed'])

    def test_reject_ancestor_descendant_and_symlink(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.root / 'canonical', target_is_directory=True)
        for path in (self.root, self.root / 'canonical', self.component, alias, self.manifest.parent):
            with self.subTest(path=path), self.assertRaises(ValueError):
                reference.guard(self.manifest, [str(path)])

    def test_reject_hardlink_alias(self):
        alias = self.root / 'checkpoint'
        alias.mkdir()
        os.link(self.component, alias / 'oa-1-big-Data.db')
        with self.assertRaises(ValueError):
            reference.guard(self.manifest, [str(alias)])
        # Link-count ctime changes must not bypass the fast check.
        with self.assertRaises(ValueError):
            reference.validate(self.manifest)
        self.assertTrue(reference.validate(self.manifest, full=True)['valid'])

    def test_detect_same_size_content_change(self):
        old = self.component.stat()
        self.component.write_bytes(b'corrupteddata')
        os.utime(self.component, ns=(old.st_atime_ns, old.st_mtime_ns))
        with self.assertRaises(ValueError):
            reference.validate(self.manifest)
        with self.assertRaises(ValueError):
            reference.validate(self.manifest, full=True)

    def test_reject_added_component(self):
        (self.table / 'oa-2-big-Data.db').write_bytes(b'new')
        with self.assertRaises(ValueError):
            reference.validate(self.manifest)

    def test_reject_tampered_canonical_metrics(self):
        metrics = self.root / 'canonical/baseline_metrics.env'
        metrics.write_text('load_seconds=1448.974\n')
        archived = self.manifest.parent / 'evidence/canonical/baseline_metrics.env'
        archived.parent.mkdir(parents=True)
        archived.write_bytes(metrics.read_bytes())
        record = json.loads(self.manifest.read_text())
        record['evidence'] = [dict(path=str(archived.relative_to(self.manifest.parent)),
                                   source=str(metrics), sha256=reference.digest(metrics))]
        self.manifest.write_text(json.dumps(record))
        self.assertTrue(reference.validate(self.manifest)['valid'])
        metrics.write_text('load_seconds=1.000000\n')
        # Archived evidence and SST bytes remain intact, but publisher input changed.
        self.assertEqual(reference.digest(archived), record['evidence'][0]['sha256'])
        with self.assertRaisesRegex(ValueError, 'Canonical provenance file changed'):
            reference.validate(self.manifest)


if __name__ == '__main__':
    unittest.main()
