#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('install-iverilog.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class Receipt(unittest.TestCase):
    def test_receipt_rejects_changed_source_and_installed_tree(self):
        for mutation in ('none', 'source', 'content', 'missing', 'extra', 'mode', 'escape'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                prefix = Path(temporary) / 'prefix'
                prefix.mkdir()
                binary = prefix / 'tool'
                binary.write_bytes(b'fixture tool')
                receipt = {'schema': 1, 'commit': installer.COMMIT, 'tree': installer.TREE,
                           'repository': installer.REPOSITORY, 'files': installer.inventory(prefix)}
                if mutation == 'source':
                    receipt['commit'] = '0' * 40
                (prefix / 'build-receipt.json').write_text(json.dumps(receipt))
                if mutation == 'content': binary.write_bytes(b'changed')
                if mutation == 'missing': binary.unlink()
                if mutation == 'extra': (prefix / 'extra').write_bytes(b'extra')
                if mutation == 'mode': binary.chmod(0o755)
                if mutation == 'escape':
                    binary.unlink()
                    binary.symlink_to(Path(temporary) / 'outside')
                if mutation == 'none': installer.verify(prefix)
                else:
                    with self.assertRaises(RuntimeError): installer.verify(prefix)


if __name__ == '__main__':
    unittest.main()
