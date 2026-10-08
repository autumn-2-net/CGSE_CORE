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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/conditional-comparison')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = subprocess.check_output(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    harness = ROOT / 'tests/core/java/org/cgse/core/InterfaceContinuationTest.java'
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
            subprocess.run([str(java), '-ea', '-Xmx1g', '-cp', str(classes), 'org.cgse.core.InterfaceContinuationTest', '--measure'],
                           cwd=ROOT, stdout=stream, check=True, timeout=240)
        with log.open(encoding='utf-8') as stream:
            rows = list(csv.DictReader(stream))
        if len(rows) != 384:
            raise AssertionError('Incomplete conditional comparison')
        results[revision] = {
            'cases': len(rows), 'verified_witnesses': sum(row['witness'] == 'true' for row in rows),
            'unresolved': sum(row['witness'] == 'false' for row in rows),
            'total_work': sum(int(row['work']) for row in rows),
            'max_reserved_bytes': max(int(row['peak_bytes']) for row in rows),
            'raw': log.name,
        }
    if source_hash != fingerprint((ROOT / 'src/main/java').rglob('*.java')) or harness_hash != fingerprint(harness_paths):
        raise AssertionError('Sources changed during the comparison')
    summary = {'baseline': baseline, 'candidate_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
               'source_sha256': source_hash, 'harness_sha256': harness_hash,
               'limits': {'local_work': 200000, 'request_work': 4000000, 'memory_bytes': 64 << 20, 'workers': 1},
               'results': results,
               'notes': 'UNKNOWN remains unresolved. Peaks are request reservations, not heap/RSS. Raw timing includes JVM warmup; not a wall-time benchmark.'}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
