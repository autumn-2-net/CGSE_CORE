#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Named full-capture regressions with explicit feasible expectations and work caps."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

BASE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--case', default='.*')
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--workers', type=int, default=1)
    parser.add_argument('--expanded-budget', action='store_true')
    parser.add_argument('--keep-going', action='store_true')
    parser.add_argument('--output', type=Path, default=BASE.parents[1] / 'build/captured-regressions')
    args = parser.parse_args()
    campaigns = json.loads((BASE / 'regressions.json').read_text(encoding='utf-8'))['campaigns']
    campaigns = [case for case in campaigns if re.search(args.case, case['name'])]
    if not campaigns:
        parser.error('No campaigns selected')
    results = []
    args.output.mkdir(parents=True, exist_ok=True)
    for case in campaigns:
        if case['expected'] != 'SAT':
            raise ValueError('This runner requires independently validated positive regression expectations')
        command = [sys.executable, str(BASE / 'run.py'), '--require-feasible', '--source', case['source'],
                   '--extra', case['extra'], '--target', case['target'], '--modes', ','.join(case['modes']),
                   '--amounts', ','.join(map(str, case['amounts'])), '--seed', str(case['seed']),
                   '--work', str(case['work']), '--milliseconds', str(case['milliseconds']),
                   '--memory-mib', str(case['memory_mib']), '--timeout', str(args.timeout),
                   '--workers', str(args.workers),
                   '--output', str(args.output.resolve() / case['name'])]
        if args.java_home:
            command += ['--java-home', args.java_home]
        if args.expanded_budget:
            command += ['--expanded-budget']
        code = subprocess.run(command).returncode
        results.append({'name': case['name'], 'exit_code': code, 'command': command,
                        'status': 'PASS' if code == 0 else 'INCONCLUSIVE' if code == 2 else 'FAIL'})
        (args.output / 'results.json').write_text(json.dumps({'results': results}, indent=2), encoding='utf-8')
        if code and not args.keep_going:
            raise SystemExit(code)
    if any(r['status'] == 'FAIL' for r in results):
        raise SystemExit(1)
    if any(r['status'] != 'PASS' for r in results):
        raise SystemExit(2)


if __name__ == '__main__':
    main()
