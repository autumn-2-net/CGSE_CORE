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
    parser.add_argument('--work', type=int, default=20_000_000)
    parser.add_argument('--model-engine', choices=['lcg-first', 'lcg-retained', 'views', 'main-counts'], default='lcg-first',
                        help='Count models only: original first LCG slice, retained LCG, views, or main integer-count stage')
    parser.add_argument('--model-trace', action='store_true', help='Include count-model diagnostics, also on budget exits')
    parser.add_argument('--timeout', type=int, default=180, help='Process wall limit in seconds')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/tests')
    parser.add_argument('--shard', default='0/1', help='Deterministic zero-based shard, e.g. 0/4')
    parser.add_argument('--no-compile', action='store_true')
    parser.add_argument('--keep-going', action='store_true')
    parser.add_argument('--compile-only', action='store_true', help='Audit isolated historical probe compatibility')
    parser.add_argument('--include-historical', action='store_true', help='Also select versioned/argument-dependent probe sources')
    parser.add_argument('--argument', action='append', default=[], help='Argument for an explicitly selected historical probe')
    parser.add_argument('--classpath', default='', help='Optional test-only jars for historical probes')
    args = parser.parse_args()
    shard, shards = map(int, args.shard.split('/'))
    if not 0 <= shard < shards or args.work <= 0 or args.permutations <= 0:
        parser.error('Require 0 <= shard < shards and positive work/permutations')
    output = args.output.resolve()
    classes = output / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
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
    sources += sorted((BASE / 'regression/java').glob('*.java'))
    sources += sorted((BASE / 'corpus/java').glob('*.java'))
    if args.suite in ('view', 'standard', 'all'):
        sources += sorted((ROOT / 'tools/view/src/main/java').rglob('*.java'))
        sources += sorted((BASE / 'view/java').rglob('*.java'))
    if not args.no_compile:
        source_list = output / 'sources.args'
        source_list.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
        subprocess.run([java('javac'), '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(source_list)], check=True)
    results = []
    def save():
        (output / 'results.json').write_text(json.dumps({'suite': args.suite, 'model_engine': args.model_engine,
                                                       'work': args.work, 'milliseconds': args.milliseconds,
                                                       'results': results}, indent=2), encoding='utf-8')
    def run(name, main_class, arguments=(), extra_classpath=''):
        log_path = output / (re.sub(r'[^a-zA-Z0-9_.-]', '_', name) + '.log')
        started = time.monotonic()
        command = [java('java'), '-ea', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Xmx2g', '-cp', os.pathsep.join(x for x in [extra_classpath, str(classes), args.classpath] if x), main_class, *map(str, arguments)]
        with log_path.open('w', encoding='utf-8') as log:
            try:
                result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
                status = 'PASS' if result.returncode == 0 else 'INCONCLUSIVE' if result.returncode == 2 else 'FAIL'
            except subprocess.TimeoutExpired:
                status = 'TIMEOUT'
        results.append({'name': name, 'status': status, 'seconds': round(time.monotonic() - started, 3), 'log': str(log_path.relative_to(output))})
        save()
        print(name + ': ' + status, flush=True)
        if status != 'PASS':
            print(log_path.read_text(encoding='utf-8', errors='replace')[-6000:])
            if not args.keep_going:
                raise SystemExit(2 if status in ('INCONCLUSIVE', 'TIMEOUT') else 1)
    selected = lambda values: [value for i, value in enumerate(values) if i % shards == shard]
    if args.suite in ('view', 'standard', 'all'):
        for source in selected(sorted((BASE / 'view/java').rglob('*.java'))):
            if re.search(args.case, source.stem) and re.search(r'public\s+static\s+void\s+main\s*\(', source.read_text(encoding='utf-8')):
                run(source.stem, 'org.cgse.view.' + source.stem)
    if args.suite in ('core', 'standard', 'all'):
        entries = ['GraphCoreTest']
        for p in sorted((BASE / 'core/java').rglob('*.java')):
            if p.stem not in {'GraphCoreTest', 'GraphBenchmark'} and re.search(r'public\s+static\s+void\s+main\s*\(', p.read_text(encoding='utf-8')):
                entries.append(p.stem)
        for name in selected(entries):
            if re.search(args.case, name):
                run(name, 'org.cgse.core.' + name)
    if args.suite in ('oracles', 'standard', 'all'):
        for p in selected(sorted((BASE / 'regression/java').glob('*.java'))):
            if p.stem != 'GraphFixtureRegression' and re.search(args.case, p.stem):
                run(p.stem, 'org.cgse.core.' + p.stem)
    if args.suite == 'benchmarks' and re.search(args.case, 'GraphBenchmark'):
        run('GraphBenchmark', 'org.cgse.core.GraphBenchmark')
    if args.suite in ('fixtures', 'all'):
        import random
        spec = importlib.util.spec_from_file_location('fixture_helpers', BASE / 'regression/run.py')
        fixture_helpers = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture_helpers)
        cases = [json.loads(p.read_text(encoding='utf-8')) for p in sorted(args.fixture_dir.rglob('*.json'))]
        cases = selected([c for c in cases if re.search(args.case, c['name'])])
        if not cases:
            parser.error('No graph fixtures selected')
        for permutation in range(args.permutations):
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
    save()
    if not results:
        parser.error('No tests selected')
    if all(r['status'] == 'PASS' for r in results):
        raise SystemExit(0)
    if all(r['status'] in ('PASS', 'INCONCLUSIVE', 'TIMEOUT') for r in results):
        raise SystemExit(2)
    raise SystemExit(1)


if __name__ == '__main__':
    main()
