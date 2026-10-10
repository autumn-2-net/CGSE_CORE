#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Sequential BC/CB comparison of exact repeated-sum matching at identical request limits."""
import argparse
from collections import Counter
import csv
import io
import json
from pathlib import Path
import statistics
import subprocess
import tarfile

from compare_planning import fingerprint

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--cases', type=int, default=12)
    parser.add_argument('--work', type=int, default=20_000_000)
    parser.add_argument('--warmup', type=int, default=2)
    parser.add_argument('--repetitions', type=int, default=5)
    parser.add_argument('--cycles', type=int, default=2)
    parser.add_argument('--family', choices=['repeated', 'distinct', 'all'], default='repeated')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/quarter-join-comparison')
    args = parser.parse_args()
    if min(args.cases, args.work, args.repetitions, args.cycles) < 1 or args.warmup < 0:
        parser.error('Require positive counts/cap and nonnegative warmup')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    batch_size = args.cases * 3 * (2 if args.family == 'all' else 1)
    baseline = subprocess.check_output(['git', 'rev-parse', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    source = output / ('source-' + baseline)
    archive = subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for entry in tar:
            if not entry.isfile() or not entry.name.endswith('.java'):
                continue
            path = (source / entry.name).resolve()
            if not path.is_relative_to(source.resolve()):
                raise ValueError('Unsafe source path')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(tar.extractfile(entry).read())
    java_dir = Path(args.java_home) / 'bin'
    extension = '.exe' if (java_dir / 'java.exe').exists() else ''
    harness = ROOT / 'tests/benchmarks/QuarterJoinThroughput.java'
    source_hash = fingerprint((ROOT / 'src/main/java').rglob('*.java'))
    class_dirs = {}
    for name, root in [('baseline', source), ('candidate', ROOT)]:
        classes = output / name / 'classes'
        classes.mkdir(parents=True, exist_ok=True)
        paths = sorted((root / 'src/main/java').rglob('*.java')) + [harness]
        argfile = output / name / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in paths), encoding='utf-8')
        subprocess.run([str(java_dir / ('javac' + extension)), '--release', '17', '-encoding', 'UTF-8', '-d',
                        str(classes), '@' + str(argfile)], check=True)
        class_dirs[name] = classes
    summaries, records = {}, {}
    for cycle in range(args.cycles):
        for name in (('baseline', 'candidate') if cycle % 2 == 0 else ('candidate', 'baseline')):
            path = output / f'{name}-{cycle}.csv'
            command = [str(java_dir / ('java' + extension)), '-ea', '-Xmx1g', '-Dfile.encoding=UTF-8', '-cp',
                       str(class_dirs[name]), 'org.cgse.core.QuarterJoinThroughput', str(args.cases), str(args.warmup),
                       str(args.repetitions), str(args.work), args.family]
            with path.open('w', encoding='utf-8') as stream:
                subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=args.timeout)
            rows = list(csv.DictReader(path.read_text(encoding='utf-8').splitlines()))
            if len(rows) != batch_size * args.repetitions:
                raise AssertionError('Incomplete benchmark: ' + str(path))
            records.setdefault(name, []).extend(rows)
            print(f'{name} cycle {cycle}: {len(rows)} checked', flush=True)
    if source_hash != fingerprint((ROOT / 'src/main/java').rglob('*.java')):
        raise AssertionError('Production sources changed during comparison')
    for name, rows in records.items():
        batches = []
        for start in range(0, len(rows), batch_size):
            batch = rows[start:start + batch_size]
            batches.append({field: sum(int(row[field]) for row in batch)
                            for field in ('nanos', 'allocated_bytes', 'work')})
        first = {row['case']: {key: row[key] for key in ('result', 'work', 'mask')} for row in rows[:batch_size]}
        for row in rows:
            if {key: row[key] for key in ('result', 'work', 'mask')} != first[row['case']]:
                raise AssertionError('Nondeterministic verdict/work/witness')
        summaries[name] = {'cases': first, 'outcomes': dict(Counter(row['result'] for row in first.values())),
                           'median_batch': {field: statistics.median(batch[field] for batch in batches)
                                            for field in ('nanos', 'allocated_bytes', 'work')},
                           'max_reserved_bytes': max(int(row['peak_reserved_bytes']) for row in rows)}
    before, after = summaries['baseline']['cases'], summaries['candidate']['cases']
    gained = [key for key in before if before[key]['result'] != 'WITNESS' and after[key]['result'] == 'WITNESS']
    lost = [key for key in before if before[key]['result'] == 'WITNESS' and after[key]['result'] != 'WITNESS']
    summary = {'baseline': baseline, 'candidate_source_sha256': source_hash,
               'harness_sha256': fingerprint([Path(__file__), harness]), 'work': args.work, 'cases': args.cases,
               'orders': 3, 'family': args.family, 'workers': 1, 'memory_bytes': 64 << 20, 'milliseconds': 0, 'warmup': args.warmup,
               'repetitions': args.repetitions, 'cycles': args.cycles, 'results': summaries, 'gained': gained, 'lost': lost,
               'scope': 'Direct candidate-only residue join; planted witnesses checked in original coordinates. '
                        'Reserved workspace and cumulative thread allocation are not JVM heap/RSS. '
                        'No whole-planner or unresolved-case impossibility claim.'}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    if lost:
        raise SystemExit('Lost equal-cap witnesses; inspect summary.json')


if __name__ == '__main__':
    main()
