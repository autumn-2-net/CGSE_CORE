#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Serial warmed fork comparison with unchanged per-case results/work and actual allocated bytes."""
import argparse
import csv
import io
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import tarfile

from compare_planning import ROOT, fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/throughput-comparison')
    parser.add_argument('--forks', type=int, default=3)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--cases', type=int, default=12)
    parser.add_argument('--engines', default='lcg-binary,lcg-finite,lcg-offset,lp-unit,lp-dense,lattice,lattice-dense,lattice-offset')
    args = parser.parse_args()
    if min(args.forks, args.warmup, args.samples, args.cases) <= 0:
        parser.error('Require positive forks, warmup, samples and cases')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = subprocess.check_output(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    harness = ROOT / 'tests/benchmarks/CountThroughput.java'
    harness_paths = [Path(__file__).resolve(), Path(__file__).with_name('compare_planning.py'), harness]
    source_hash = fingerprint((ROOT / 'src/main/java').rglob('*.java'))
    harness_hash = fingerprint(harness_paths)
    sources = {'baseline': output / ('source-' + baseline), 'candidate': ROOT}
    with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT))) as archive:
        for member in archive:
            if not member.isfile() or not member.name.endswith('.java'):
                continue
            dest = (sources['baseline'] / member.name).resolve()
            if not dest.is_relative_to(sources['baseline'].resolve()):
                raise ValueError('Unsafe archive path')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.extractfile(member).read())
    java = args.java_home / 'bin/java.exe'
    javac = args.java_home / 'bin/javac.exe'
    if not java.exists():
        java = args.java_home / 'bin/java'
        javac = args.java_home / 'bin/javac'
    version = subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True)
    for revision, source in sources.items():
        classes = output / revision / 'classes'
        if classes.exists():
            if classes.is_symlink() or not classes.resolve().is_relative_to(output):
                raise ValueError('Class output escapes comparison directory')
            shutil.rmtree(classes)
        classes.mkdir(parents=True, exist_ok=True)
        paths = sorted((source / 'src/main/java').rglob('*.java')) + [harness]
        argfile = classes.parent / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in paths), encoding='utf-8')
        subprocess.run([str(javac), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(argfile)], check=True)
    rows = []
    expected = {}
    for fork in range(args.forks):
        for revision in (['baseline', 'candidate'] if fork % 2 == 0 else ['candidate', 'baseline']):
            path = output / f'{revision}-{fork}.csv'
            with path.open('w', encoding='utf-8') as stream:
                subprocess.run([str(java), '-ea', '-Xms512m', '-Xmx512m', '-cp', str(output / revision / 'classes'),
                                'org.cgse.core.CountThroughput', str(args.cases), str(args.warmup), str(args.samples), args.engines],
                               cwd=ROOT, stdout=stream, check=True, timeout=600)
            batch = list(csv.DictReader(path.open(encoding='utf-8')))
            if len(batch) != args.samples * len(args.engines.split(',')):
                raise AssertionError('Incomplete measurement')
            for row in batch:
                for field in ('sample', 'cases', 'nanos', 'allocated_bytes', 'work'):
                    row[field] = int(row[field])
                signature = (row['work'], row['results'])
                if expected.setdefault(row['engine'], signature) != signature:
                    raise AssertionError('Search/work changed: ' + revision + '/' + row['engine'])
                row.update(revision=revision, fork=fork)
            rows.extend(batch)
            print(f'{revision} fork={fork}: fixed work and results checked', flush=True)
    groups = {}
    for engine in args.engines.split(','):
        groups[engine] = {}
        for revision in sources:
            group = [r for r in rows if (r['engine'], r['revision']) == (engine, revision)]
            forks = [statistics.median(r['nanos'] for r in group if r['fork'] == fork) for fork in range(args.forks)]
            groups[engine][revision] = {'median_ns': statistics.median(forks), 'fork_median_ns': forks,
                                       'median_allocated_bytes': statistics.median(r['allocated_bytes'] for r in group),
                                       'work': group[0]['work'], 'cases': args.cases}
            outcomes = [case.split(':', 2)[1] for case in group[0]['results'].split(';')]
            groups[engine][revision]['outcomes'] = {outcome: outcomes.count(outcome) for outcome in sorted(set(outcomes))}
        old, new = groups[engine]['baseline'], groups[engine]['candidate']
        groups[engine]['time_ratio'] = new['median_ns'] / old['median_ns']
        groups[engine]['allocation_ratio'] = new['median_allocated_bytes'] / old['median_allocated_bytes']
    if source_hash != fingerprint((ROOT / 'src/main/java').rglob('*.java')) or harness_hash != fingerprint(harness_paths):
        raise AssertionError('Sources changed during measurement')
    (output / 'rows.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
    summary = {'baseline': baseline, 'source_sha256': source_hash, 'harness_sha256': harness_hash,
               'java': version, 'warmup': args.warmup, 'samples': args.samples, 'forks': args.forks, 'groups': groups,
               'notes': 'Serial fixed-search specialist throughput. Thread allocation is measured, not heap/RSS. Same case fingerprints, work and results checked in every sample. Does not establish whole-planner or server speed.'}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(groups, indent=2))


if __name__ == '__main__':
    main()
