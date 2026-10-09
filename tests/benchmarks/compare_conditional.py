#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Run one identical planted/order harness against two revisions with fixed local and shared caps."""
import argparse
import csv
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

from compare_planning import ROOT, fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--campaign', choices=['conditional', 'continuations', 'residual'], default='conditional')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/conditional-comparison')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = subprocess.check_output(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    conditional = args.campaign == 'conditional'
    residual = args.campaign == 'residual'
    harness = ROOT / {'conditional': 'tests/core/java/org/cgse/core/InterfaceContinuationTest.java',
                      'continuations': 'tests/benchmarks/ContinuationComparison.java',
                      'residual': 'tests/core/java/org/cgse/core/ResidualFrontierTest.java'}[args.campaign]
    harness_paths = [Path(__file__).resolve(), Path(__file__).with_name('compare_planning.py'), harness]
    source_hash = fingerprint((ROOT / 'src/main/java').rglob('*.java'))
    harness_hash = fingerprint(harness_paths)
    sources = {'baseline': output / ('source-' + baseline), 'candidate': ROOT}
    archive = subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar:
            if not member.isfile() or not member.name.endswith('.java'):
                continue
            dest = (sources['baseline'] / member.name).resolve()
            if not dest.is_relative_to(sources['baseline'].resolve()):
                raise ValueError('Archive path escapes output')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(tar.extractfile(member).read())
    java = args.java_home / 'bin/java.exe'
    javac = args.java_home / 'bin/javac.exe'
    if not java.exists():
        java = args.java_home / 'bin/java'
        javac = args.java_home / 'bin/javac'
    results = {}
    for revision, source in sources.items():
        classes = output / revision / 'classes'
        if classes.exists():
            if classes.is_symlink() or not classes.resolve().is_relative_to(output):
                raise ValueError('Class output escapes benchmark directory')
            shutil.rmtree(classes)
        classes.mkdir(parents=True, exist_ok=True)
        paths = sorted((source / 'src/main/java').rglob('*.java')) + [harness]
        argfile = output / revision / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in paths), encoding='utf-8')
        subprocess.run([str(javac), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(argfile)], check=True)
        log = output / (revision + '.csv')
        with log.open('w', encoding='utf-8') as stream:
            subprocess.run([str(java), '-ea', '-Xmx1g', '-cp', str(classes), 'org.cgse.core.' + harness.stem, '--measure'],
                           cwd=ROOT, stdout=stream, check=True, timeout=240)
        with log.open(encoding='utf-8') as stream:
            rows = list(csv.DictReader(stream))
        if len(rows) != {'conditional': 384, 'continuations': 256, 'residual': 48}[args.campaign]:
            raise AssertionError('Incomplete ' + args.campaign + ' comparison')
        results[revision] = {
            'cases': len(rows), ('verified_selections' if residual else 'verified_witnesses'): sum(row['witness'] == 'true' for row in rows),
            'unresolved': sum(row['witness'] == 'false' for row in rows),
            'total_work': sum(int(row['work']) for row in rows),
            'max_reserved_bytes': max(int(row['peak_bytes']) for row in rows),
            'raw': log.name,
            'engines': {engine: {'cases': sum(row['engine'] == engine for row in rows),
                                'witnesses': sum(row['engine'] == engine and row['witness'] == 'true' for row in rows)}
                        for engine in sorted({row['engine'] for row in rows})} if args.campaign == 'continuations' else {},
        }
        if residual:
            results[revision]['source_scans'] = sum(int(row['scans']) for row in rows)
        if args.campaign == 'continuations':
            control = output / (revision + '-whole.csv')
            with control.open('w', encoding='utf-8') as stream:
                subprocess.run([str(java), '-ea', '-Xmx1g', '-cp', str(classes), 'org.cgse.core.' + harness.stem, '--whole'],
                               cwd=ROOT, stdout=stream, check=True, timeout=240)
            with control.open(encoding='utf-8') as stream:
                whole = list(csv.DictReader(stream))
            if len(whole) != 256:
                raise AssertionError('Incomplete uninterrupted control')
            results[revision]['uninterrupted_control'] = {
                'verified_witnesses': sum(row['witness'] == 'true' for row in whole),
                'total_work': sum(int(row['work']) for row in whole), 'raw': control.name,
            }
    if source_hash != fingerprint((ROOT / 'src/main/java').rglob('*.java')) or harness_hash != fingerprint(harness_paths):
        raise AssertionError('Sources changed during the comparison')
    summary = {'baseline': baseline, 'candidate_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
               'source_sha256': source_hash, 'harness_sha256': harness_hash,
               'campaign': args.campaign,
               'limits': ({'admissions_per_turn': 4, 'turns': 16, 'request_work': 8000000} if residual else
                          {'local_work': 200000 if conditional else 2048, 'request_work': 4000000 if conditional else 2000000}) |
                         {'memory_bytes': 64 << 20, 'workers': 1},
               'results': results,
               'notes': 'Specialist-only comparison, not whole-planner throughput. The continuation campaign allows retained quanta within the same request cap; old one-shot arms leave the remainder unused. UNKNOWN remains unresolved. Peaks are request reservations, not heap/RSS. Raw timing includes JVM warmup; not a wall-time benchmark.'}
    if residual:
        summary['notes'] = ('Admission-only comparison with independent full-sort validation after every turn. '
                            'Verified selections are source rankings, not complete plans or infeasibility proofs. '
                            'Compare scans/work and reserved bytes; raw timing includes the oracle and JVM warmup.')
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
