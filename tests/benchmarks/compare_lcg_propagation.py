#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Sequential warmed LCG/PB comparison at identical work; run without concurrent regressions."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import statistics
import subprocess
import tarfile

from compare_planning import fingerprint

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--case', default='sparse-equality|wide-equality|wide-79')
    parser.add_argument('--engine', choices=['lcg', 'pb'], default='lcg')
    parser.add_argument('--warmup', type=int, default=2)
    parser.add_argument('--samples', type=int, default=3)
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/lcg-propagation-comparison')
    args = parser.parse_args()
    if args.warmup < 1 or args.samples < 1:
        parser.error('Require positive warmup and sample counts')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = subprocess.check_output(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    source = output / ('source-' + baseline)
    archive = subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar:
            if not member.isfile() or not member.name.endswith('.java'):
                continue
            path = (source / member.name).resolve()
            if not path.is_relative_to(source.resolve()):
                raise ValueError('Unsafe source member')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(tar.extractfile(member).read())
    spec = importlib.util.spec_from_file_location('count_inputs', ROOT / 'tests/cases/run.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    manifest = json.loads((ROOT / 'tests/cases/manifest.json').read_text(encoding='utf-8'))
    records = [c for c in manifest['cases'] if c['kind'] == 'count-model' and re.search(args.case, c['id'] + ' ' + ' '.join(c['names']))]
    if args.engine == 'pb':
        def boolean_model(record):
            model = json.loads((ROOT / 'tests' / record['path']).read_text(encoding='utf-8'))['model']
            return len(model['lower']) <= 1024 and all(hi is not None and 0 <= int(hi) - int(lo) <= 1
                    for lo, hi in zip(model['lower'], model['upper']))
        records = [c for c in records if boolean_model(c)]
    if not records:
        parser.error('No count models selected')
    helper.write_models(output / 'models.bin', [json.loads((ROOT / 'tests' / c['path']).read_text(encoding='utf-8')) for c in records])
    java_dir = Path(args.java_home) / 'bin'
    ext = '.exe' if (java_dir / 'java.exe').exists() else ''
    harness = [ROOT / path for path in ['tests/benchmarks/java/LcgPropagationBenchmark.java',
               'tests/regression/java/GraphFixtureRegression.java', 'tests/corpus/java/CountModelRegression.java',
               'tests/corpus/java/CountModelReplay.java']]
    revision_sources = {'baseline': source, 'candidate': ROOT}
    sources_hash = fingerprint((ROOT / 'src/main/java').rglob('*.java'))
    for name, tree in revision_sources.items():
        classes = output / name / 'classes'
        classes.mkdir(parents=True, exist_ok=True)
        argfile = output / name / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sorted((tree / 'src/main/java').rglob('*.java')) + harness), encoding='utf-8')
        subprocess.run([str(java_dir / ('javac' + ext)), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(argfile)], check=True)
    summary = {'baseline': baseline, 'candidate_source_sha256': sources_hash,
               'harness_sha256': fingerprint(harness + [Path(__file__), ROOT / 'tests/cases/run.py']),
               'input_sha256': hashlib.sha256((output / 'models.bin').read_bytes()).hexdigest(),
               'corpus_ids': [c['id'] for c in records], 'warmup': args.warmup,
               'engine': args.engine, 'slice_work': 262144, 'request_work': 8000000, 'memory_bytes': 128 << 20,
               'java': subprocess.run([str(java_dir / ('java' + ext)), '-version'], capture_output=True, text=True, check=True).stderr.strip(),
               'samples': {'baseline': [], 'candidate': []},
               'note': 'Sequential B/C/C/B processes, warmed original-model slices. Allocation is current-thread allocated bytes, not retained heap or RSS. No whole-planner timing claim.'}
    for index, name in enumerate(['baseline', 'candidate', 'candidate', 'baseline']):
        log = output / f'{index}-{name}.log'
        command = [str(java_dir / ('java' + ext)), '-ea', '-Xms512m', '-Xmx2g', '-Dfile.encoding=UTF-8', '-cp', str(output / name / 'classes'),
                   'org.cgse.core.LcgPropagationBenchmark', str(output / 'models.bin'), str(args.warmup), str(args.samples), args.engine]
        with log.open('w', encoding='utf-8') as stream:
            subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
        values = [list(map(int, line.split('\t'))) for line in log.read_text(encoding='utf-8').splitlines()]
        if len(values) != args.samples or any(v[1] != len(records) for v in values):
            raise AssertionError('Incomplete sample: ' + str(log))
        summary['samples'][name] += [dict(zip(['sample', 'models', 'nanoseconds', 'allocated_bytes', 'work', 'signature'], v)) for v in values]
        print(f'{index} {name}: {len(values)} samples', flush=True)
    if fingerprint((ROOT / 'src/main/java').rglob('*.java')) != sources_hash:
        raise AssertionError('Production sources changed during benchmark')
    metrics = {name: {key: statistics.median(v[key] for v in values) for key in ['nanoseconds', 'allocated_bytes', 'work']}
               for name, values in summary['samples'].items()}
    summary['medians'] = metrics
    # No changed trajectory or reweighted budget may masquerade as faster arithmetic.
    signatures = {(v['work'], v['signature']) for values in summary['samples'].values() for v in values}
    summary['identical_trajectories'] = len(signatures) == 1
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    if len(signatures) != 1:
        raise SystemExit('Trajectory/work changed; inspect samples before interpreting timing')


if __name__ == '__main__':
    main()
