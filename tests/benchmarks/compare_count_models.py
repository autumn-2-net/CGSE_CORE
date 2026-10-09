#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Compare the main count stage on identical canonical inputs, harness and request limits."""
import argparse
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

from compare_planning import fingerprint

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--case', default='.*')
    parser.add_argument('--parity', action='store_true', help='Also compare the 72 deterministic odd-cycle/neighbor cases')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/count-comparison')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = subprocess.check_output(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT, text=True).strip()
    baseline_source = output / ('source-' + baseline)
    archive = subprocess.check_output(['git', 'archive', baseline, 'src/main/java'], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar:
            if not member.isfile() or not member.name.endswith('.java'):
                continue
            dest = (baseline_source / member.name).resolve()
            if not dest.is_relative_to(baseline_source.resolve()):
                raise ValueError('Unsafe source archive member')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(tar.extractfile(member).read())

    spec = importlib.util.spec_from_file_location('count_inputs', ROOT / 'tests/cases/run.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    manifest = json.loads((ROOT / 'tests/cases/manifest.json').read_text(encoding='utf-8'))
    selected = [c for c in manifest['cases'] if c['kind'] == 'count-model' and re.search(args.case, c['id'] + ' ' + ' '.join(c['names']))]
    if not selected:
        parser.error('No count models selected')
    helper.write_models(output / 'models.bin', [json.loads((ROOT / 'tests' / c['path']).read_text(encoding='utf-8')) for c in selected])
    harness = [ROOT / path for path in ['tests/regression/java/GraphFixtureRegression.java',
               'tests/corpus/java/CountModelRegression.java', 'tests/corpus/java/CountModelReplay.java',
               'tests/regression/java/ParityPipelineReview.java']]
    java_dir = Path(args.java_home) / 'bin'
    extension = '.exe' if (java_dir / 'java.exe').exists() else ''
    summary = {'baseline': baseline, 'candidate_source_sha256': fingerprint((ROOT / 'src/main/java').rglob('*.java')),
               'harness_sha256': fingerprint(harness + [Path(__file__), ROOT / 'tests/cases/run.py']),
               'request_work': 20_000_000, 'milliseconds': 0, 'memory_bytes': 256 << 20, 'workers': 1,
               'corpus_ids': [c['id'] for c in selected], 'models': {}, 'parity': {},
               'note': 'Main integer-count stage, not GraphPlanner. UNKNOWN and unsupported conversions are not proofs. No wall-time or JVM heap claim.'}
    for name, source in [('baseline', baseline_source), ('candidate', ROOT)]:
        classes = output / name / 'classes'
        classes.mkdir(parents=True, exist_ok=True)
        paths = sorted((source / 'src/main/java').rglob('*.java')) + harness
        argfile = output / name / 'sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in paths), encoding='utf-8')
        subprocess.run([str(java_dir / ('javac' + extension)), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(argfile)], check=True)
        command = [str(java_dir / ('java' + extension)), '-ea', '-Xmx2g', '-Dfile.encoding=UTF-8', '-cp', str(classes)]
        for campaign in ['models'] + (['parity'] if args.parity else []):
            log = output / f'{name}-{campaign}.log'
            arguments = ['org.cgse.core.CountModelRegression', str(output / 'models.bin'), '0', '20000000', 'main-counts'] if campaign == 'models' else ['org.cgse.core.ParityPipelineReview', 'measure']
            with log.open('w', encoding='utf-8') as stream:
                result = subprocess.run(command + arguments, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=args.timeout)
            if result.returncode not in ((0, 2) if campaign == 'models' else (0,)):
                raise RuntimeError(f'{name} {campaign} failed: {log}')
            rows = [line.split('\t') for line in log.read_text(encoding='utf-8').splitlines() if '\t' in line]
            summary[campaign][name] = {row[0]: row[1:] for row in rows}
            if len(rows) != (len(selected) if campaign == 'models' else 72):
                raise AssertionError('Incomplete comparison: ' + str(log))
            print(f'{name} {campaign}: {len(rows)} checked', flush=True)
    for campaign in ['models'] + (['parity'] if args.parity else []):
        before, after = summary[campaign]['baseline'], summary[campaign]['candidate']
        conclusive = {'PASS', 'SAT', 'UNSAT'}
        summary[campaign]['gained'] = [key for key in before if before[key][0] not in conclusive and after[key][0] in conclusive]
        summary[campaign]['lost'] = [key for key in before if before[key][0] in conclusive and after[key][0] not in conclusive]
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    if any(summary[c]['lost'] for c in ['models'] + (['parity'] if args.parity else [])):
        raise SystemExit('Conclusions regressed; inspect summary.json')


if __name__ == '__main__':
    main()
