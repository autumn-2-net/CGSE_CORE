#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Equal-cap main-count comparison on seeded, signed low-density subset families."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import random
import subprocess
import tarfile

from compare_planning import fingerprint

ROOT = Path(__file__).resolve().parents[2]


def models(seeds, permutations, large=False):
    records = []
    for seed in range(seeds):
        for gap in (0, 8):
            rng = random.Random(2026101100 + 2 * seed + gap)
            sizes = (42, 44, 46, 48, 50, 52) if large else (42, 44, 46, 48)
            n = sizes[seed % len(sizes)]
            bits = min(n + gap, 55) if n > 48 else n + gap
            weights = [rng.randrange(1 << (bits - 1), 1 << bits) | 1 for _ in range(n)]
            witness = [rng.randrange(2) for _ in range(n)]
            # Shifts deliberately exceed machine integers, while binary option
            # differences retain the scalar tool's certified long arithmetic.
            lower = [-(1 << (80 + i % 3)) + i if seed % 2 else 0 for i in range(n)]
            weights = [v * (-1 if seed % 2 and i % 3 == 0 else 1) for i, v in enumerate(weights)]
            target = sum(a * (l + x) for a, l, x in zip(weights, lower, witness))
            for order in range(permutations):
                ids = list(range(n))
                if order:
                    random.Random(2026101200 + seed * 31 + gap * 7 + order).shuffle(ids)
                rows = [{'terms': {str(i): str(sign * weights[old]) for i, old in enumerate(ids)},
                         'upper': str(sign * target)} for sign in (1, -1)]
                if order % 2:
                    rows.reverse()
                records.append({'id': f'subset-lattice-n{n}-bits{bits}-s{seed}/order={order}',
                                'kind': 'count-model', 'expected': 'SAT',
                                'model': {'lower': [str(lower[i]) for i in ids],
                                          'upper': [str(lower[i] + 1) for i in ids], 'rows': rows}})
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--seeds', type=int, default=12)
    parser.add_argument('--permutations', type=int, default=3)
    parser.add_argument('--work', type=int, default=20_000_000)
    parser.add_argument('--large', action='store_true', help='Also cover 50/52-coordinate inputs within certified scalar range')
    parser.add_argument('--timeout', type=int, default=1800)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if min(args.seeds, args.permutations, args.work) < 1:
        parser.error('Require positive seeds, permutations and work')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = subprocess.check_output(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    old = output / ('source-' + baseline)
    archive = subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar:
            if member.isfile() and member.name.endswith('.java'):
                destination = (old / member.name).resolve()
                if not destination.is_relative_to(old.resolve()):
                    raise ValueError('Unsafe source archive member')
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(tar.extractfile(member).read())
    spec = importlib.util.spec_from_file_location('inputs', ROOT / 'tests/cases/run.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    inputs = models(args.seeds, args.permutations, args.large)
    helper.write_models(output / 'models.bin', inputs)
    (output / 'models.json').write_text(json.dumps(inputs), encoding='utf-8')
    harness = [ROOT / path for path in ('tests/regression/java/GraphFixtureRegression.java',
               'tests/corpus/java/CountModelRegression.java', 'tests/corpus/java/CountModelReplay.java')]
    jdk = Path(args.java_home) / 'bin'
    extension = '.exe' if (jdk / 'java.exe').exists() else ''
    summary = {'baseline': baseline, 'candidate_source_sha256': fingerprint((ROOT / 'src/main/java').rglob('*.java')),
               'harness_sha256': fingerprint(harness + [Path(__file__), ROOT / 'tests/cases/run.py']),
               'input_sha256': hashlib.sha256((output / 'models.bin').read_bytes()).hexdigest(),
               'work': args.work, 'milliseconds': 0, 'memory_bytes': 256 << 20, 'workers': 1,
               'seeds': args.seeds, 'permutations': args.permutations, 'large': args.large,
               'note': 'Main integer-count stage; witnesses independently checked against original rows and domains. '
                       'Planted SAT; UNKNOWN is not infeasibility. Functional campaign, not an isolated timing comparison.'}
    for name, source in [('baseline', old), ('candidate', ROOT)]:
        classes = output / name / 'classes'
        classes.mkdir(parents=True, exist_ok=True)
        paths = sorted((source / 'src/main/java').rglob('*.java')) + harness
        argfile = output / name / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in paths), encoding='utf-8')
        subprocess.run([str(jdk / ('javac' + extension)), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(argfile)], check=True)
        with (output / f'{name}.log').open('w', encoding='utf-8') as stream:
            result = subprocess.run([str(jdk / ('java' + extension)), '-ea', '-Xmx2g', '-Dfile.encoding=UTF-8',
                                     '-cp', str(classes), 'org.cgse.core.CountModelRegression',
                                     str(output / 'models.bin'), '0', str(args.work), 'main-counts'],
                                    cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=args.timeout)
        if result.returncode not in (0, 2):
            raise RuntimeError(f'{name} failed: {output / (name + ".log")}')
        rows = [line.split('\t') for line in (output / f'{name}.log').read_text(encoding='utf-8').splitlines() if '\t' in line]
        if len(rows) != len(inputs):
            raise AssertionError('Incomplete comparison')
        summary[name] = {row[0]: row[1:] for row in rows}
        print(f'{name}: {len(rows)} checked', flush=True)
    if summary['candidate_source_sha256'] != fingerprint((ROOT / 'src/main/java').rglob('*.java')):
        raise AssertionError('Production sources changed during comparison')
    summary['gained'] = [key for key in summary['baseline'] if summary['baseline'][key][0] != 'PASS' and summary['candidate'][key][0] == 'PASS']
    summary['lost'] = [key for key in summary['baseline'] if summary['baseline'][key][0] == 'PASS' and summary['candidate'][key][0] != 'PASS']
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(f'gained={len(summary["gained"])} lost={len(summary["lost"])}', flush=True)


if __name__ == '__main__':
    main()
