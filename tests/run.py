"""Standalone CGSE regression runner: Python 3 and JDK 17, no game bootstrap."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import time
import threading
from discovery import extra_tests, java_tests, verify_manifest

BASE = Path(__file__).resolve().parent
ROOT = BASE.parent


def main():
    sys.stdout.reconfigure(errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--suite', choices=['standard', 'core', 'view', 'oracles', 'fixtures', 'probes', 'corpus', 'models', 'benchmarks', 'all'], default='standard')
    parser.add_argument('--case', default='.*', help='Regular expression selecting names / probe IDs')
    parser.add_argument('--fixture-dir', type=Path, default=BASE / 'regression/fixtures', help='Curated or generated graph fixtures')
    parser.add_argument('--permutations', type=int, default=3)
    parser.add_argument('--milliseconds', type=int, default=3000)
    parser.add_argument('--work', type=int, default=40_000_000,
                        help='Per-request work cap (default: 40000000); pass 20000000 for historical comparisons')
    parser.add_argument('--model-engine', choices=['lcg-first', 'lcg-retained', 'lp-retained', 'integer-lp-retained', 'pb-retained',
                                                 'affine-retained', 'mitm-retained', 'views', 'main-counts'], default='lcg-first',
                        help='Count models only: direct original-coordinate algorithms, views, or main integer-count stage')
    parser.add_argument('--model-trace', action='store_true', help='Include count-model diagnostics, also on budget exits')
    parser.add_argument('--timeout', type=int, default=600, help='Process wall limit in seconds')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/tests')
    parser.add_argument('--shard', default='0/1', help='Deterministic zero-based shard, e.g. 0/4')
    parser.add_argument('--no-compile', action='store_true')
    parser.add_argument('--keep-going', action='store_true')
    parser.add_argument('--jobs', type=int, choices=range(1, 5), default=1,
                        help='Independent corpus processes only, 1..4; all other suites remain serial')
    parser.add_argument('--list-tests', action='store_true', help='Write discovery inventory and list tests without compiling or running')
    parser.add_argument('--compile-only', action='store_true', help='Audit isolated historical probe compatibility')
    parser.add_argument('--include-historical', action='store_true', help='Also select versioned/argument-dependent probe sources')
    parser.add_argument('--original-probes', action='store_true', help='Run archived probe code instead of its maintained migration')
    parser.add_argument('--argument', action='append', default=[], help='Argument for an explicitly selected historical probe')
    parser.add_argument('--classpath', default='', help='Optional test-only jars for historical probes')
    args = parser.parse_args()
    # A complete run must collect later failures too, rather than silently
    # stopping at the first known hard input. Explicit smaller suites retain
    # their fail-fast default.
    args.keep_going |= args.suite == 'all'
    shard, shards = map(int, args.shard.split('/'))
    if not 0 <= shard < shards or args.work <= 0 or args.permutations <= 0:
        parser.error('Require 0 <= shard < shards and positive work/permutations')
    output = args.output.resolve()
    classes = output / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    discovered = {name: java_tests(BASE / path) for name, path in
                  [('core', 'core/java'), ('view', 'view/java'), ('oracles', 'regression/java')]}
    extras = extra_tests(BASE, dict(output=str(output), java_home=args.java_home or '',
                                   timeout=str(args.timeout), work=str(args.work), milliseconds=str(args.milliseconds)))
    inventory = {'suite': args.suite, 'case': args.case, 'shard': args.shard,
                 'java': [{'suite': suite, 'name': p.stem, 'class': cls, 'source': p.relative_to(BASE).as_posix()}
                          for suite, entries in discovered.items() for p, cls in entries],
                 'extra_tests': extras,
                 'fixtures': [p.relative_to(BASE).as_posix() if p.is_relative_to(BASE) else str(p)
                              for p in sorted(args.fixture_dir.resolve().rglob('*.json'))],
                 'corpus': json.loads((BASE / 'cases/manifest.json').read_text(encoding='utf-8'))['cases'],
                 'probes': json.loads((BASE / 'probes/manifest.json').read_text(encoding='utf-8'))['probes'],
                 'archived_scenarios': json.loads((BASE / 'datasets/scenarios.json').read_text(encoding='utf-8'))['files'],
                 'scope': 'Archived scenario inputs are preserved data, not all standalone assertions. Named full-capture regressions run via datasets/suite.json; native/argument-dependent campaigns retain their explicit drivers.'}
    (output / 'inventory.json').write_text(json.dumps(inventory, indent=2), encoding='utf-8')
    if args.suite == 'all':
        verify_manifest(BASE, inventory['corpus'], ['cases/graph', 'cases/count-model'], '.json')
        verify_manifest(BASE, inventory['probes'], ['probes/java'], '.java')
    if args.list_tests:
        for item in inventory['java'] + extras:
            if re.search(args.case, item['name']):
                print(item['name'] + (': ' + item['reason'] if 'reason' in item else ''))
        print('Inventory: ' + str(output / 'inventory.json'))
        return
    def java(name):
        if args.java_home:
            result = Path(args.java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
            if result.is_file():
                return str(result)
        result = shutil.which(name)
        if not result:
            parser.error('Cannot find ' + name + '; specify --java-home')
        return result
    sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
    sources += sorted((BASE / 'core/java').rglob('*.java'))
    sources += sorted((BASE / 'shared/java').rglob('*.java'))
    sources += sorted((BASE / 'regression/java').rglob('*.java'))
    sources += sorted((BASE / 'corpus/java').glob('*.java'))
    if args.suite in ('view', 'standard', 'all'):
        sources += sorted((ROOT / 'tools/view/src/main/java').rglob('*.java'))
        sources += sorted((BASE / 'view/java').rglob('*.java'))
    if not args.no_compile:
        source_list = output / 'sources.args'
        source_list.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
        subprocess.run([java('javac'), '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(source_list)], check=True)
    results = []
    result_lock = threading.RLock()
    def save():
        with result_lock:
            (output / 'results.json').write_text(json.dumps({'suite': args.suite, 'model_engine': args.model_engine,
                                                           'work': args.work, 'milliseconds': args.milliseconds,
                                                           'results': results}, indent=2), encoding='utf-8')
    def execute(name, command, validate=None, timeout=None, main_class=None):
        log_path = output / (re.sub(r'[^a-zA-Z0-9_.-]', '_', name) + '.log')
        started = time.monotonic()
        with log_path.open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=os.name != 'nt')
            try:
                code = process.wait(timeout=timeout or args.timeout)
                status = 'PASS' if code == 0 else 'INCONCLUSIVE' if code == 2 else 'FAIL'
            except subprocess.TimeoutExpired:
                status = 'TIMEOUT'
                # A Python suite may own a Java child. Do not leave it running
                # when moving to the next serial job after a driver timeout.
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW, timeout=30)
                else:
                    import signal
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=30)
        if status == 'PASS' and validate is not None:
            problem = validate(log_path.read_text(encoding='utf-8', errors='replace'))
            if problem:
                with log_path.open('a', encoding='utf-8') as log:
                    log.write('\nFAIL: ' + problem + '\n')
                status = 'FAIL'
        if status == 'PASS' and name.startswith('python/'):
            report = log_path.read_text(encoding='utf-8', errors='replace')
            count = re.search(r'Ran (\d+) tests?\b', report)
            if count is None or int(count[1]) == 0:
                with log_path.open('a', encoding='utf-8') as log:
                    log.write('\nFAIL: Python discovery executed no unittest cases; register standalone drivers in suite.json.\n')
                status = 'FAIL'
        with result_lock:
            results.append({'name': name, 'status': status, 'seconds': round(time.monotonic() - started, 3),
                            'log': str(log_path.relative_to(output)), 'command': command, 'main_class': main_class})
            save()
        print(name + ': ' + status, flush=True)
        if status != 'PASS':
            print(log_path.read_text(encoding='utf-8', errors='replace')[-6000:])
            if not args.keep_going:
                raise SystemExit(2 if status in ('INCONCLUSIVE', 'TIMEOUT') else 1)
    def run(name, main_class, arguments=(), extra_classpath='', validate=None):
        command = [java('java'), '-ea', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Xmx2g', '-cp',
                   os.pathsep.join(x for x in [extra_classpath, str(classes), args.classpath] if x), main_class, *map(str, arguments)]
        execute(name, command, validate, main_class=main_class)
    selected = lambda values: [value for i, value in enumerate(values) if i % shards == shard]
    if args.suite in ('view', 'standard', 'all'):
        for source, entry in selected(discovered['view']):
            if re.search(args.case, source.stem):
                run(source.stem, entry)
    if args.suite in ('core', 'standard', 'all'):
        entries = sorted(discovered['core'], key=lambda pair: (pair[0].stem != 'GraphCoreTest', pair[0]))
        for source, entry in selected([pair for pair in entries if pair[0].stem != 'GraphBenchmark']):
            if re.search(args.case, source.stem):
                run(source.stem, entry)
    if args.suite in ('oracles', 'standard', 'all'):
        for p, entry in selected(discovered['oracles']):
            if p.stem != 'GraphFixtureRegression' and re.search(args.case, p.stem):
                run(p.stem, entry)
        if shard == 0 and re.search(args.case, 'CountModelBoundaries'):
            spec = importlib.util.spec_from_file_location('count_boundaries', BASE / 'cases/run.py')
            boundaries_helper = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(boundaries_helper)
            boundary = json.loads((BASE / 'regression/count-boundaries.json').read_text(encoding='utf-8'))
            manifest = json.loads((BASE / 'cases/manifest.json').read_text(encoding='utf-8'))
            records = [c for c in manifest['cases'] if c['kind'] == 'count-model']
            # An order-sensitive boundary must use the complete corpus stream,
            # not the different permutation obtained by shuffling a subset.
            ordered = {order: {c['id']: c for c in boundaries_helper.count_model_order(records, order)}
                       for order in {c.get('corpus_order', 0) for c in boundary['cases']}}
            cases = []
            for entry in boundary['cases']:
                order = entry.get('corpus_order', 0)
                case_id = entry['id'] + ('/order=' + str(order) if order else '')
                cases.append(ordered[order][case_id])
            batches = {}
            for case, oracle in zip(cases, boundary['cases']):
                # Maintained witnesses / independent oracle verdicts override
                # this check only; keep historical corpus records unchanged.
                case['expected'] = oracle['expected']
                work = oracle.get('work', boundary['work'])
                if not isinstance(work, int) or work <= 0:
                    raise ValueError('Invalid count boundary work cap')
                engine = oracle.get('engine', 'main-counts')
                if engine not in ('main-counts', 'mitm-retained', 'integer-lp-retained'):
                    raise ValueError('Invalid count boundary engine')
                batches.setdefault((engine, work), []).append(case)
            for (engine, work), batch in sorted(batches.items()):
                suffix = '' if work == boundary['work'] else '-' + str(work)
                if engine != 'main-counts':
                    suffix = '-' + engine + suffix
                path = output / ('count-boundaries' + suffix + '.bin')
                boundaries_helper.write_models(path, batch)
                run('CountModelBoundaries' + suffix, 'org.cgse.core.CountModelRegression',
                    [path, 0, work, engine, 'true'])
        if shard == 0 and re.search(args.case, 'GraphCandidateBoundaries'):
            spec = importlib.util.spec_from_file_location('graph_boundaries', BASE / 'regression/run.py')
            helper = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(helper)
            batches = {}
            for case, work in helper.graph_boundaries():
                batches.setdefault(work, []).append(case)
            for work, cases in sorted(batches.items()):
                path = output / ('graph-boundaries-' + str(work) + '.bin')
                helper.write_cases(path, cases)
                run('GraphCandidateBoundaries-' + str(work), 'org.cgse.core.GraphFixtureRegression', [path, 0, work])
        if shard == 0 and re.search(args.case, 'GraphExecutionBoundaries'):
            import hashlib
            import random
            spec = importlib.util.spec_from_file_location('execution_boundaries', BASE / 'cases/run.py')
            helper = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(helper)
            manifest = json.loads((BASE / 'regression/execution-boundaries.json').read_text(encoding='utf-8'))
            batches = {}
            cost_limits = {}
            for entry in manifest['cases']:
                source = BASE / entry['source']
                raw = source.read_text(encoding='utf-8')
                if hashlib.sha256(raw.encode('utf-8')).hexdigest() != entry['sha256_lf']:
                    raise ValueError('Execution boundary source changed: ' + str(source))
                for order in entry['orders']:
                    case = json.loads(raw)
                    case['id'] += '/' + entry['name'] + '/order=' + str(order)
                    case['expected'] = entry['expected']
                    case['model']['stock'].update(entry.get('stock', {}))
                    if order:
                        random.Random(order).shuffle(case['model']['recipes'])
                    if 'maximum_observed_work' in entry:
                        cost_limits[case['id']] = entry['maximum_observed_work']
                    batches.setdefault((entry['work'], entry.get('workers', 0), entry.get('expanded_budget', False)), []).append(case)
            for (work, workers, expanded), cases in sorted(batches.items()):
                suffix = str(work) + (('-w' + str(workers) + ('-expanded' if expanded else '-fixed')) if workers else '')
                path = output / ('execution-boundaries-' + suffix + '.bin')
                helper.write_graphs(path, cases)
                limits = {c['id']: cost_limits[c['id']] for c in cases if c['id'] in cost_limits}
                def validate_costs(log):
                    observed = {}
                    for line in log.splitlines():
                        parts = line.split('\t')
                        if len(parts) >= 4 and parts[1] == 'PASS':
                            values = [int(p[5:]) for p in parts[3:] if p.startswith('work=')]
                            if values: observed[parts[0]] = values[0]
                    for name, limit in limits.items():
                        if name not in observed or observed[name] > limit:
                            return name + ': observed work ' + str(observed.get(name)) + ' exceeds ' + str(limit)
                run('GraphExecutionBoundaries-' + suffix, 'org.cgse.core.CorpusRegression',
                    [path, 0, work, workers, str(expanded).lower()], validate=validate_costs)
    if args.suite in ('benchmarks', 'all') and shard == 0 and re.search(args.case, 'GraphBenchmark'):
        run('GraphBenchmark', 'org.cgse.core.GraphBenchmark')
    if args.suite in ('fixtures', 'all'):
        import random
        spec = importlib.util.spec_from_file_location('fixture_helpers', BASE / 'regression/run.py')
        fixture_helpers = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture_helpers)
        cases = [json.loads(p.read_text(encoding='utf-8')) for p in sorted(args.fixture_dir.rglob('*.json'))]
        cases = selected([c for c in cases if re.search(args.case, c['name'])])
        if not cases and args.suite == 'fixtures':
            parser.error('No graph fixtures selected')
        for permutation in range(args.permutations if cases else 0):
            copied = json.loads(json.dumps(cases))
            rng = random.Random(20260926 + permutation)
            for case in copied:
                rng.shuffle(case['recipes'])
                for recipe in case['recipes']:
                    for field in ['inputs', 'outputs']:
                        items = list(recipe[field].items())
                        rng.shuffle(items)
                        recipe[field] = dict(items)
            path = output / ('fixtures-' + str(permutation) + '.bin')
            fixture_helpers.write_cases(path, copied)
            run('fixtures-' + str(permutation), 'org.cgse.core.GraphFixtureRegression', [path, args.milliseconds, args.work])
    if args.suite in ('probes', 'all'):
        spec = importlib.util.spec_from_file_location('probe_helpers', BASE / 'probes/run.py')
        probe_helpers = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe_helpers)
        probe_helpers.run(args, java, classes, output, run, results, save)
    if args.suite in ('corpus', 'models', 'all'):
        spec = importlib.util.spec_from_file_location('corpus_helpers', BASE / 'cases/run.py')
        corpus_helpers = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(corpus_helpers)
        corpus_helpers.run(args, output, run)
    if args.suite == 'all':
        for test in selected(extras):
            if not re.search(args.case, test['name']):
                continue
            if test.get('status') == 'NOT_RUN':
                results.append(test)
                save()
                print(test['name'] + ': NOT_RUN: ' + test['reason'], flush=True)
            else:
                execute(test['name'], test['command'], timeout=args.timeout * test['timeout_multiplier'])
    save()
    if not results:
        parser.error('No tests selected')
    if all(r['status'] == 'PASS' for r in results):
        raise SystemExit(0)
    if all(r['status'] in ('PASS', 'INCONCLUSIVE', 'TIMEOUT', 'NOT_RUN') for r in results):
        raise SystemExit(2)
    raise SystemExit(1)


if __name__ == '__main__':
    main()
