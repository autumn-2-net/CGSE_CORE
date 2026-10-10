#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Sequential warmed BC/CB timing for the same retained subset boundary and request cap."""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import statistics
import subprocess
import tarfile

from compare_planning import fingerprint

ROOT = Path(__file__).resolve().parents[2]
CASE = 'ff19ef4b291940ad997c1dc17e0c4a304210e1b5e8251e0361586aa8f0e2c26c'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--work', type=int, default=80_000_000)
    parser.add_argument('--warmup', type=int, default=2)
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--cycles', type=int, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if min(args.work, args.repetitions, args.cycles) < 1 or args.warmup < 0:
        parser.error('Require positive limits/counts and nonnegative warmup')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = subprocess.check_output(['git', 'rev-parse', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    old = output / ('source-' + baseline)
    with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT))) as tar:
        for member in tar:
            if member.isfile() and member.name.endswith('.java'):
                path = (old / member.name).resolve()
                if not path.is_relative_to(old.resolve()):
                    raise ValueError('Unsafe source archive member')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(tar.extractfile(member).read())
    spec = importlib.util.spec_from_file_location('count_inputs', ROOT / 'tests/cases/run.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    population = [r for r in json.loads((ROOT / 'tests/cases/manifest.json').read_text(encoding='utf-8'))['cases'] if r['kind'] == 'count-model']
    models = [case for order in range(3) for case in helper.count_model_order(population, order)
              if case['id'].partition('/order=')[0] == CASE]
    helper.write_models(output / 'models.bin', models)
    harness = ROOT / 'tests/benchmarks/SubsetMatchingThroughput.java'
    source_hash = fingerprint((ROOT / 'src/main/java').rglob('*.java'))
    jdk = Path(args.java_home) / 'bin'
    extension = '.exe' if (jdk / 'java.exe').exists() else ''
    classes = {}
    for name, root in [('baseline', old), ('candidate', ROOT)]:
        classes[name] = output / name / 'classes'
        classes[name].mkdir(parents=True, exist_ok=True)
        argfile = output / name / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sorted((root / 'src/main/java').rglob('*.java')) + [harness]), encoding='utf-8')
        subprocess.run([str(jdk / ('javac' + extension)), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes[name]), '@' + str(argfile)], check=True)
    observations = {}
    for cycle in range(args.cycles):
        for name in (('baseline', 'candidate') if cycle % 2 == 0 else ('candidate', 'baseline')):
            path = output / f'{name}-{cycle}.csv'
            with path.open('w', encoding='utf-8') as stream:
                subprocess.run([str(jdk / ('java' + extension)), '-ea', '-Xmx2g', '-Dfile.encoding=UTF-8', '-cp', str(classes[name]),
                                'org.cgse.core.SubsetMatchingThroughput', str(output / 'models.bin'), str(args.warmup),
                                str(args.repetitions), str(args.work)], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                               timeout=600, check=True)
            rows = list(csv.DictReader(path.read_text(encoding='utf-8').splitlines()))
            if len(rows) != len(models) * args.repetitions:
                raise AssertionError('Incomplete timing batch')
            observations.setdefault(name, []).extend(rows)
            print(name, cycle, 'checked', len(rows), flush=True)
    if source_hash != fingerprint((ROOT / 'src/main/java').rglob('*.java')):
        raise AssertionError('Production sources changed during timing')
    summary = {'baseline_revision': baseline, 'candidate_source_sha256': source_hash,
               'harness_sha256': fingerprint([Path(__file__), harness, ROOT / 'tests/cases/run.py']),
               'input_sha256': hashlib.sha256((output / 'models.bin').read_bytes()).hexdigest(),
               'work': args.work, 'memory_bytes': 256 << 20, 'milliseconds': 0, 'workers': 1,
               'warmup': args.warmup, 'repetitions': args.repetitions, 'cycles': args.cycles, 'models_per_batch': len(models),
               'scope': 'Direct retained matching, explicit larger equal cap so both revisions reach a witness. '
                        'Not whole-planner timing, not a default-budget increase; cumulative allocation and reserved workspace are not RSS.'}
    for name, rows in observations.items():
        first = {row['case']: {key: row[key] for key in ('work', 'witness')} for row in rows[:len(models)]}
        if any({key: row[key] for key in ('work', 'witness')} != first[row['case']] for row in rows):
            raise AssertionError('Nondeterministic witness/work')
        batches = [{key: sum(int(row[key]) for row in rows[start:start + len(models)])
                    for key in ('nanos', 'allocated_bytes', 'work')} for start in range(0, len(rows), len(models))]
        summary[name] = {'cases': first, 'batches': batches,
                         'median_batch': {key: statistics.median(batch[key] for batch in batches) for key in batches[0]},
                         'max_peak_reserved_bytes': max(int(row['peak_reserved_bytes']) for row in rows)}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
