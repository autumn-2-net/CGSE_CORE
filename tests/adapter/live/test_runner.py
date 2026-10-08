"""Rollback checks use synthetic directories and never touch a real server."""
from pathlib import Path
import importlib.util
import json
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('migration_live_runner', Path(__file__).with_name('run.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RollbackTest(unittest.TestCase):
    def test_replacement_deletion_and_new_file_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            server, output = root / 'server', root / 'output'
            server.mkdir()
            output.mkdir()
            old = server / 'previous.jar'
            old.write_bytes(b'old jar bytes')
            config = server / 'config.txt'
            config.write_bytes(b'original config\r\n')
            changes = runner.Changes(server.resolve(), output.resolve())
            changes.remove(old)
            changes.write(config, b'changed')
            changes.write(config, b'changed again')
            changes.write(server / 'new.jar', b'new jar bytes')
            manifest = json.loads((output / 'restore.json').read_text(encoding='utf-8'))
            recovered = runner.Changes(server.resolve(), output.resolve())
            recovered.entries = manifest['files']
            recovered.restore()
            self.assertEqual(b'old jar bytes', old.read_bytes())
            self.assertEqual(b'original config\r\n', config.read_bytes())
            self.assertFalse((server / 'new.jar').exists())

    def test_escape_rejected_before_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            server, output = root / 'server', root / 'output'
            server.mkdir()
            output.mkdir()
            outside = root / 'player-install.jar'
            outside.write_bytes(b'untouched')
            changes = runner.Changes(server.resolve(), output.resolve())
            with self.assertRaisesRegex(RuntimeError, 'outside the selected server'):
                changes.write(server / '..' / outside.name, b'changed')
            self.assertEqual(b'untouched', outside.read_bytes())
            self.assertFalse((output / 'restore.json').exists())


if __name__ == '__main__':
    unittest.main()
