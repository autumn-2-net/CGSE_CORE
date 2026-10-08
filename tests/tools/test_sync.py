import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('sync', Path(__file__).resolve().parents[2] / 'tools/sync_gtl.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='cgse-sync-test-')
        self.root = Path(self.temporary.name).resolve()
        self.repo, self.host = self.root / 'repo', self.root / 'host'
        self.repo.mkdir()
        self.host.mkdir()
        self.saved_root = sync.ROOT
        sync.ROOT = self.repo
        self.manifest = dict(source_commit='test', roots=[dict(source='product', target='src/main/java')],
                             bootstrap={}, retired={})
        self.put(self.repo / 'product/core.java', b'core v1\n')
        self.put(self.host / 'src/main/java/HostAdapter.java', b'host-owned adapter\n')
        self.put(self.host / 'build.gradle', b'host build\n')
        self.put(self.repo / sync.MANIFEST, (json.dumps(self.manifest) + '\n').encode())

    def tearDown(self):
        sync.ROOT = self.saved_root
        self.temporary.cleanup()

    @staticmethod
    def put(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)

    def init_git(self, path):
        sync.git(path, 'init', '--initial-branch=main')
        sync.git(path, 'config', 'user.name', 'CGSE regression')
        sync.git(path, 'config', 'user.email', 'cgse-regression@example.invalid')
        sync.git(path, 'config', 'core.autocrlf', 'false')
        sync.git(path, 'config', 'commit.gpgsign', 'false')

    def commit_source(self, message):
        sync.git(self.repo, 'add', '--', 'product', sync.MANIFEST)
        sync.git(self.repo, 'commit', '-m', message)
        return sync.git(self.repo, 'rev-parse', 'HEAD').decode().strip()

    def initial(self):
        updates, problems, entries = sync.plan(self.host, self.manifest, {})
        self.assertEqual([], problems)
        sync.apply(self.host, updates)
        return {'files': entries}

    def test_idempotent_and_preserves_host_files_without_bookkeeping(self):
        state = self.initial()
        self.assertEqual(({}, []), sync.plan(self.host, self.manifest, state)[:2])
        self.assertEqual(b'host-owned adapter\n', (self.host / 'src/main/java/HostAdapter.java').read_bytes())
        self.assertEqual(b'host build\n', (self.host / 'build.gradle').read_bytes())
        self.assertFalse((self.host / '.cgse').exists())
        self.assertFalse((self.host / 'CGSE_SOURCE.txt').exists())

    def test_updates_use_previous_content_and_reject_local_changes(self):
        state = self.initial()
        self.put(self.repo / 'product/core.java', b'core v2\n')
        updates, problems, _ = sync.plan(self.host, self.manifest, state)
        self.assertEqual([], problems)
        self.assertEqual(b'core v2\n', updates['src/main/java/core.java'])
        self.put(self.host / 'src/main/java/core.java', b'local change\n')
        updates, problems, _ = sync.plan(self.host, self.manifest, state)
        self.assertEqual(1, len(problems))
        self.assertNotIn('src/main/java/core.java', updates)

    def test_unknown_first_install_and_local_deletion_are_rejected(self):
        self.put(self.host / 'src/main/java/core.java', b'unknown\n')
        self.assertTrue(sync.plan(self.host, self.manifest, {})[1])
        self.put(self.host / 'src/main/java/core.java', b'core v1\n')
        state = self.initial()
        (self.host / 'src/main/java/core.java').unlink()
        self.assertTrue(sync.plan(self.host, self.manifest, state)[1])

    def test_retirement_is_hash_guarded_and_scoped(self):
        state = self.initial()
        (self.repo / 'product/core.java').unlink()
        updates, problems, _ = sync.plan(self.host, self.manifest, state)
        self.assertEqual([], problems)
        self.assertEqual({'src/main/java/core.java': None}, updates)
        self.put(self.host / 'src/main/java/core.java', b'locally maintained\n')
        self.assertTrue(sync.plan(self.host, self.manifest, state)[1])

    def test_escape_and_eol_handling(self):
        with self.assertRaises(ValueError):
            sync.safe(self.host, '../escape.java')
        state = self.initial()
        self.put(self.host / 'src/main/java/core.java', b'core v1\r\n')
        self.put(self.repo / 'product/core.java', b'core v2\n')
        updates, problems, _ = sync.plan(self.host, self.manifest, state)
        self.assertEqual([], problems)
        sync.apply(self.host, updates)
        self.assertEqual(b'core v2\r\n', (self.host / 'src/main/java/core.java').read_bytes())

    def test_failed_write_rolls_back_removal_and_creation(self):
        self.put(self.host / 'old.java', b'original')
        replace = sync.os.replace
        calls = 0
        def fail_second(*args):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError('injected disk error')
            return replace(*args)
        with patch.object(sync.os, 'replace', fail_second):
            with self.assertRaises(OSError):
                sync.apply(self.host, {'old.java': None, 'new.java': b'new', 'second.java': b'second'})
        self.assertEqual(b'original', (self.host / 'old.java').read_bytes())
        self.assertFalse((self.host / 'new.java').exists())
        self.assertFalse((self.host / 'second.java').exists())

    def test_real_git_trailer_baseline_survives_unrelated_host_commits(self):
        self.init_git(self.repo)
        first = self.commit_source('portable v1')
        self.initial()
        self.init_git(self.host)
        sync.git(self.host, 'add', '--', 'src', 'build.gradle')
        sync.git(self.host, 'commit', '-m', 'sync engine', '-m', sync.TRAILER + ': ' + first)
        sync.git(self.host, 'commit', '--allow-empty', '-m', 'unrelated host update')
        self.assertEqual(first, sync.host_revision(self.host))
        self.assertEqual(({}, []), sync.plan(self.host, self.manifest, sync.snapshot(first))[:2])
        self.put(self.repo / 'product/core.java', b'core v2\n')
        second = self.commit_source('portable v2')
        output = io.StringIO()
        with patch('sys.argv', ['sync_gtl.py', '--target', str(self.host), '--apply']), contextlib.redirect_stdout(output):
            sync.main()
        self.assertIn(sync.TRAILER + ': ' + second, output.getvalue())
        self.assertEqual(b'core v2\n', (self.host / 'src/main/java/core.java').read_bytes())
        changed = sync.git(self.host, 'status', '--porcelain', '--untracked-files=all').decode()
        self.assertEqual('M src/main/java/core.java', changed.strip())
        self.assertFalse((self.host / '.cgse').exists())

    def test_invalid_or_missing_commit_cannot_be_used_as_baseline(self):
        self.init_git(self.repo)
        self.commit_source('portable v1')
        for value in ('HEAD', '--help', '0' * 40):
            with self.assertRaises(ValueError):
                sync.snapshot(value)

    def test_apply_rejects_uncommitted_engine_sources(self):
        self.init_git(self.repo)
        self.commit_source('portable v1')
        self.init_git(self.host)
        sync.git(self.host, 'commit', '--allow-empty', '-m', 'host base')
        self.put(self.repo / 'product/core.java', b'not committed\n')
        with patch('sys.argv', ['sync_gtl.py', '--target', str(self.host), '--apply']):
            with self.assertRaisesRegex(ValueError, 'Commit managed CGSE sources'):
                sync.main()
        self.assertFalse((self.host / 'src/main/java/core.java').exists())


if __name__ == '__main__':
    unittest.main()
