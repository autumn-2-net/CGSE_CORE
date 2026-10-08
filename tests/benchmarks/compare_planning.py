#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Compare revisions under equal limits and cache states; never change either checkout."""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path
import random
import re
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]


def fingerprint(paths):
    digest = hashlib.sha256()
    for path in sorted(paths):
        label = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
        digest.update(label.as_posix().encode('utf-8'))
        digest.update(b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--baseline', default='HEAD', help='Local Git revision, resolved and archived read-only')
    parser.add_argument('--case', default='.*')
    parser.add_argument('--fixture-dir', type=Path, default=ROOT / 'tests/regression/fixtures')
    parser.add_argument('--permutations', type=int, default=3)
    parser.add_argument('--preserve-order', action='store_true', help='Replay recorded recipe/slot order without shuffling')
    parser.add_argument('--milliseconds', type=int, default=3000)
    parser.add_argument('--work', type=int, default=20_000_000)
    parser.add_argument('--memory-mib', type=int, default=64)
    parser.add_argument('--modes', nargs='+', choices=['accounts', 'total', 'wall'], default=['accounts', 'total'])
    parser.add_argument('--output', type=Path, default=ROOT / 'build/planning-comparison')
    args = parser.parse_args()
    if args.permutations <= 0 or args.milliseconds < 0 or args.work <= 0 or args.memory_mib <= 0:
        parser.error('Invalid budget or permutation count')
    if 'wall' in args.modes and args.milliseconds == 0:
        parser.error('Wall-only comparison requires a finite --milliseconds limit')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    java_home = Path(args.java_home)
    java = java_home / 'bin/java.exe' if (java_home / 'bin/java.exe').exists() else java_home / 'bin/java'
    javac = java_home / 'bin/javac.exe' if (java_home / 'bin/javac.exe').exists() else java_home / 'bin/javac'
    baseline = subprocess.check_output(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    candidate = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    harness_paths = [Path(__file__).resolve(), ROOT / 'tests/benchmarks/PlanningComparison.java',
                     ROOT / 'tests/regression/java/GraphFixtureRegression.java', ROOT / 'tests/regression/run.py']
    candidate_fingerprint = fingerprint((ROOT / 'src/main/java').rglob('*.java'))
    harness_fingerprint = fingerprint(harness_paths)
    sources = {'baseline': output / ('source-' + baseline), 'candidate': ROOT}
    archive = subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT)
    # Extract regular Java sources only, never Git metadata, links or paths outside the workspace.
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar:
            if not member.isfile() or not member.name.endswith('.java'):
                continue
            dest = (sources['baseline'] / member.name).resolve()
            if not dest.is_relative_to(sources['baseline'].resolve()):
                raise ValueError('Unsafe archive member')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(tar.extractfile(member).read())
    for revision, source in sources.items():
        classes = output / revision / 'classes'
        if classes.exists():
            if classes.is_symlink() or not classes.resolve().is_relative_to(output):
                raise ValueError('Class output escapes the selected benchmark directory')
            shutil.rmtree(classes)
        classes.mkdir(parents=True, exist_ok=True)
        paths = sorted((source / 'src/main/java').rglob('*.java')) + [
            ROOT / 'tests/regression/java/GraphFixtureRegression.java',
            ROOT / 'tests/benchmarks/PlanningComparison.java']
        argfile = output / revision / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in paths), encoding='utf-8')
        subprocess.run([str(javac), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(argfile)], check=True)
    spec = importlib.util.spec_from_file_location('fixtures', ROOT / 'tests/regression/run.py')
    fixtures = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures)
    fixture_paths = sorted(args.fixture_dir.resolve().rglob('*.json'))
    fixture_fingerprint = fingerprint(fixture_paths)
    cases = [json.loads(p.read_text(encoding='utf-8')) for p in fixture_paths]
    cases = [case for case in cases if re.search(args.case, case['name'])]
    if not cases:
        parser.error('No cases selected')
    rows = []
    for permutation in range(args.permutations):
        permuted = json.loads(json.dumps(cases))
        if not args.preserve_order:
            rng = random.Random(20260926 + permutation)
            for case in permuted:
                rng.shuffle(case['recipes'])
                for recipe in case['recipes']:
                    for field in ('inputs', 'outputs'):
                        entries = list(recipe[field].items())
                        rng.shuffle(entries)
                        recipe[field] = dict(entries)
        fixture = output / f'fixtures-{permutation}.bin'
        fixtures.write_cases(fixture, permuted)
        for mode in args.modes:
            # Alternate order to avoid always giving one revision the warmer machine.
            for revision in (['baseline', 'candidate'] if permutation % 2 == 0 else ['candidate', 'baseline']):
                log = output / f'{revision}-{mode}-{permutation}.tsv'
                with log.open('w', encoding='utf-8') as stream:
                    subprocess.run([str(java), '-ea', '-Xmx2g', '-Dfile.encoding=UTF-8', '-cp', str(output / revision / 'classes'),
                                    'org.cgse.core.PlanningComparison', str(fixture), str(args.milliseconds), str(args.work),
                                    str(args.memory_mib << 20), mode], stdout=stream, check=True,
                                   timeout=max(120, len(cases) * 3 * (args.milliseconds / 1000 + 3)))
                with log.open(encoding='utf-8') as stream:
                    batch = list(csv.DictReader(stream, delimiter='\t'))
                for row in batch:
                    row.update(revision=revision, permutation=permutation)
                    for field in ('search', 'compilation', 'total', 'prepare_ns', 'solve_ns', 'peak_reserved_bytes',
                                  'first_verified_search', 'first_verified_compilation', 'first_verified_ns',
                                  'cache_estimated_bytes', 'compiler_count', 'active_searches'):
                        row[field] = int(row[field])
                rows.extend(batch)
                (output / 'rows.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
                print(f'{revision} {mode} permutation={permutation}: {len(batch)} checked', flush=True)
    groups = []
    for revision in sources:
        for mode in args.modes:
            for cache in ('cold', 'warm', 'replaced'):
                group = [row for row in rows if (row['revision'], row['mode'], row['cache']) == (revision, mode, cache)]
                results = {}
                for row in group:
                    results[row['result']] = results.get(row['result'], 0) + 1
                def quantile(field, q):
                    values = sorted(row[field] for row in group)
                    return values[max(0, math.ceil(q * len(values)) - 1)]
                groups.append(dict(revision=revision, mode=mode, cache=cache, cases=len(group), results=results,
                                   p50_ns=quantile('solve_ns', .5), p95_ns=quantile('solve_ns', .95), p99_ns=quantile('solve_ns', .99),
                                   max_peak_reserved_bytes=max(row['peak_reserved_bytes'] for row in group),
                                   max_cache_estimated_bytes=max(row['cache_estimated_bytes'] for row in group),
                                   total_search=sum(row['search'] for row in group), total_compilation=sum(row['compilation'] for row in group)))
    if (candidate_fingerprint != fingerprint((ROOT / 'src/main/java').rglob('*.java')) or
            harness_fingerprint != fingerprint(harness_paths) or fixture_fingerprint != fingerprint(fixture_paths)):
        raise RuntimeError('Benchmark inputs changed during the run; rerun before comparing revisions')
    summary = dict(baseline=baseline, candidate_head=candidate, candidate_worktree=True, workers=1,
                   candidate_source_sha256=candidate_fingerprint, harness_source_sha256=harness_fingerprint,
                   fixture_source_sha256=fixture_fingerprint,
                   milliseconds=args.milliseconds, work=args.work, memory_mib=args.memory_mib, modes=args.modes,
                   recipe_order='input' if args.preserve_order else 'shuffled',
                   cache_estimates='Request reservations and logical compiler-cache estimates are separate; neither measures JVM heap/RSS. Older cache telemetry is -1.', groups=groups)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(output / 'summary.json')


if __name__ == '__main__':
    main()
