"""New test files and suite drivers must enter all without editing its runner."""
import importlib.util
import json
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('discovery', BASE / 'discovery.py')
discovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(discovery)


class DiscoveryTest(unittest.TestCase):
    def test_new_nested_tests_and_registered_driver(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = root / 'new module' / 'nested'
            nested.mkdir(parents=True)
            (nested / 'test_new.py').write_text('import unittest\nclass NewTest(unittest.TestCase):\n    def test_new(self): self.assertTrue(True)\n')
            (nested / 'driver.py').write_text('raise SystemExit(0)')
            (nested / 'suite.json').write_text(json.dumps({'schema': 'cgse-test-suite-v1', 'tests': [
                {'id': 'new-driver', 'script': 'driver.py', 'arguments': ['--output', '{test_output}', '--work', '{work}']},
                {'id': 'external', 'requires': 'explicit host'}]}))
            values = dict(output=str(root / 'results'), work='40000000', java_home='')
            found = {r['name']: r for r in discovery.extra_tests(root, values)}
            self.assertEqual(3, len(found))
            self.assertIn('python/new module/nested/test_new.py', found)
            # No __main__ guard: unittest discovery must still execute it.
            executed = subprocess.run(found['python/new module/nested/test_new.py']['command'], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, executed.returncode, executed.stderr)
            self.assertIn('Ran 1 test', executed.stderr)
            command = found['new module/nested/new-driver']['command']
            self.assertEqual(str(nested / 'driver.py'), command[1])
            self.assertEqual('40000000', command[-1])
            self.assertEqual('NOT_RUN', found['new module/nested/external']['status'])
            (nested / 'Main.java').write_text('package example.changed; public class Main { public static void main(String[] args) {} }')
            (nested / 'Helper.java').write_text('package example.changed; public class Helper {}')
            self.assertEqual([(nested / 'Main.java', 'example.changed.Main')], discovery.java_tests(root))

    def test_bad_or_missing_registration_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'suite.json'
            for test in [{'id': 'missing', 'script': 'absent.py'}, {'id': '../bad', 'requires': 'host'},
                         {'id': 'empty-requirement', 'requires': ''}]:
                manifest.write_text(json.dumps({'schema': 'cgse-test-suite-v1', 'tests': [test]}))
                with self.assertRaises(ValueError):
                    discovery.extra_tests(root, {'output': directory})

    def test_new_or_deleted_corpus_input_cannot_be_silently_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases = root / 'cases'
            cases.mkdir()
            (cases / 'registered.json').write_text('{}')
            manifest = [{'path': 'cases/registered.json'}]
            discovery.verify_manifest(root, manifest, ['cases'], '.json')
            (cases / 'new.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'unregistered=.*new.json'):
                discovery.verify_manifest(root, manifest, ['cases'], '.json')
            (cases / 'registered.json').unlink()
            with self.assertRaisesRegex(ValueError, 'missing=.*registered.json'):
                discovery.verify_manifest(root, manifest, ['cases'], '.json')

    def test_real_all_inventory_without_jdk(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(BASE / 'run.py'), '--suite', 'all', '--list-tests',
                                     '--java-home', str(Path(directory) / 'no-jdk'), '--output', directory],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            inventory = json.loads((Path(directory) / 'inventory.json').read_text())
            self.assertIn('GraphBenchmark', {r['name'] for r in inventory['java']})
            names = {r['name'] for r in inventory['extra_tests']}
            self.assertTrue({'python/test_discovery.py', 'datasets/named-serial', 'datasets/named-parallel',
                             'datasets/integrity', 'datasets/complete-catalog', 'ae-dump/migration',
                             'adapter/host-contracts', 'adapter/live-server'} <= names)
            self.assertGreater(len(inventory['corpus']), 20_000)
            self.assertGreater(len(inventory['probes']), 600)

    def test_omitted_probe_reports_the_actual_prerequisites(self):
        spec = importlib.util.spec_from_file_location('probe_discovery', BASE / 'probes/run.py')
        probes = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probes)
        with tempfile.TemporaryDirectory() as directory:
            probes.BASE = Path(directory)
            probes.TESTS = probes.BASE
            entries = [dict(id='legacy', tier='historical', main=True, external_imports=['native.Reader'],
                            path='legacy.java', coverage_note='Old API and original campaign required.'),
                       dict(id='portable', tier='standalone', main=True, external_imports=['native.Reader'],
                            path='portable.java')]
            (probes.BASE / 'manifest.json').write_text(json.dumps({'probes': entries}))
            args = SimpleNamespace(original_probes=True, compile_only=False, argument=[], shard='0/1',
                                   include_historical=False, classpath='', suite='all', case='.*')
            rows = []
            probes.run(args, None, None, None, None, rows, lambda: None)
            self.assertIn('API/campaign', rows[0]['reason'])
            self.assertEqual(entries[0]['coverage_note'], rows[0]['coverage_note'])
            self.assertIn('external test classpath', rows[1]['reason'])
            # A supplied classpath does not migrate a historical program's API
            # or invent its original input arguments. Keep that reason explicit.
            (probes.BASE / 'manifest.json').write_text(json.dumps({'probes': entries[:1]}))
            args.classpath = 'provided-native.jar'
            rows = []
            probes.run(args, None, None, None, None, rows, lambda: None)
            self.assertIn('API/campaign', rows[0]['reason'])
            self.assertEqual(['native.Reader'], rows[0]['external_imports'])

    def test_all_keeps_later_results_and_missing_prerequisites(self):
        with tempfile.TemporaryDirectory() as directory:
            tests = Path(directory) / 'tests'
            tests.mkdir()
            for name in ('run.py', 'discovery.py'):
                shutil.copyfile(BASE / name, tests / name)
            for folder, file, value in [('probes', 'manifest.json', {'probes': []}),
                                        ('cases', 'manifest.json', {'cases': []}),
                                        ('datasets', 'scenarios.json', {'files': []})]:
                target = tests / folder
                target.mkdir()
                (target / file).write_text(json.dumps(value))
            for folder in ('probes', 'cases', 'regression'):
                (tests / folder).mkdir(exist_ok=True)
                (tests / folder / 'run.py').write_text('def run(*args): pass\n')
            checks = tests / 'checks'
            checks.mkdir()
            (checks / 'failure.py').write_text('raise SystemExit(1)\n')
            (checks / 'success.py').write_text('print("actually executed")\n')
            (checks / 'suite.json').write_text(json.dumps({'schema': 'cgse-test-suite-v1', 'tests': [
                {'id': 'first-fails', 'script': 'failure.py'},
                {'id': 'later-runs', 'script': 'success.py'},
                {'id': 'host', 'requires': 'explicit disposable server'}]}))
            output = Path(directory) / 'results'
            result = subprocess.run([sys.executable, str(tests / 'run.py'), '--suite', 'all', '--no-compile',
                                     '--case', '^checks/', '--output', str(output)], capture_output=True, text=True, timeout=30)
            self.assertEqual(1, result.returncode, result.stderr)
            rows = json.loads((output / 'results.json').read_text())['results']
            self.assertEqual(['FAIL', 'PASS', 'NOT_RUN'], [r['status'] for r in rows])
            self.assertIn('actually executed', (output / rows[1]['log']).read_text())


if __name__ == '__main__':
    unittest.main()
