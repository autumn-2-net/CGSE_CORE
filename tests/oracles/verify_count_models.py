#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Independent count-model oracles; preserve unbounded domains and verify every witness exactly.

Optional test-only dependencies: z3-solver, ortools, pyscipopt. No CGSE code is
imported. UNKNOWN, unsupported representations and native errors remain separate.
SCIP negative answers are cross-checks, not exact proof certificates.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import time

ROOT = Path(__file__).resolve().parents[2]


def valid(model, values):
    if len(values) != len(model['lower']):
        return False
    for value, low, high in zip(values, model['lower'], model['upper']):
        if value < int(low) or high is not None and value > int(high):
            return False
    return all(sum(int(a) * values[int(i)] for i, a in row['terms'].items()) <= int(row['upper'])
               for row in model['rows'])


def solve(engine, data, seconds):
    low = [int(v) for v in data['lower']]
    high = [None if v is None else int(v) for v in data['upper']]
    rows = [(dict((int(i), int(a)) for i, a in row['terms'].items()), int(row['upper'])) for row in data['rows']]
    witness, status = None, 'UNKNOWN'
    started = time.monotonic()
    if engine == 'z3':
        import z3
        model = z3.Solver()
        model.set(timeout=round(seconds * 1000), random_seed=0)
        variables = [z3.Int('x' + str(i)) for i in range(len(low))]
        for v, lo, hi in zip(variables, low, high):
            model.add(v >= lo)
            if hi is not None:
                model.add(v <= hi)
        for terms, bound in rows:
            model.add(z3.Sum([a * variables[i] for i, a in terms.items()]) <= bound)
        answer = model.check()
        if answer == z3.sat:
            witness = [model.model().eval(v, model_completion=True).as_long() for v in variables]
        elif answer == z3.unsat:
            status = 'UNSAT'
    elif engine == 'cp-sat':
        from ortools.sat.python import cp_model
        if any(v is None for v in high):
            return {'status': 'UNSUPPORTED', 'reason': 'Unbounded domains are not clipped for CP-SAT'}
        scalars = low + high + [a for terms, b in rows for a in [b, *terms.values()]]
        if any(abs(a) >= 2**62 for a in scalars):
            return {'status': 'UNSUPPORTED', 'reason': 'Outside safe CP-SAT integer representation'}
        model = cp_model.CpModel()
        variables = [model.new_int_var(lo, hi, 'x' + str(i)) for i, (lo, hi) in enumerate(zip(low, high))]
        for terms, bound in rows:
            model.add(sum(a * variables[i] for i, a in terms.items()) <= bound)
        validation = model.validate()
        if validation:
            return {'status': 'UNSUPPORTED', 'reason': validation}
        solver = cp_model.CpSolver()
        solver.parameters.max_time_in_seconds = seconds
        solver.parameters.num_search_workers = 1
        solver.parameters.random_seed = 0
        answer = solver.solve(model)
        if answer in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            witness = [solver.value(v) for v in variables]
        elif answer == cp_model.INFEASIBLE:
            status = 'UNSAT'
    else:
        from pyscipopt import Model, quicksum
        scalars = low + [v for v in high if v is not None] + [a for terms, b in rows for a in [b, *terms.values()]]
        if any(abs(a) > 2**53 for a in scalars):
            return {'status': 'UNSUPPORTED', 'reason': 'SCIP double input would round integer coefficients'}
        model = Model()
        model.hideOutput()
        model.setRealParam('limits/time', seconds)
        model.setIntParam('parallel/maxnthreads', 1)
        variables = [model.addVar('x' + str(i), vtype='I', lb=lo, ub=hi) for i, (lo, hi) in enumerate(zip(low, high))]
        for terms, bound in rows:
            model.addCons(quicksum(a * variables[i] for i, a in terms.items()) <= bound)
        model.optimize()
        if model.getNSols():
            witness = [round(model.getVal(v)) for v in variables]
        elif str(model.getStatus()) == 'infeasible':
            status = 'UNSAT'
        model.freeProb()
    if witness is not None:
        if not valid(data, witness):
            return {'status': 'INVALID_WITNESS', 'values': [str(v) for v in witness]}
        status = 'SAT'
    return {'status': status, 'seconds': round(time.monotonic() - started, 3),
            **({'values': [str(v) for v in witness]} if witness is not None else {})}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', default='.*')
    parser.add_argument('--ledger', type=Path, default=ROOT / 'tests/provenance/parity-root-20261009.json')
    parser.add_argument('--seconds', type=float, default=15)
    parser.add_argument('--engines', nargs='+', choices=['z3', 'cp-sat', 'scip'], default=['z3', 'cp-sat', 'scip'])
    parser.add_argument('--output', type=Path, default=ROOT / 'build/count-oracles.json')
    args = parser.parse_args()
    if args.seconds <= 0:
        parser.error('Time limit must be positive')
    ledger = json.loads(args.ledger.read_text('utf-8'))['canonical_models']
    selected = {c['id'] for c in ledger['remaining'] if c['expected'] == 'UNKNOWN'}
    selected.update(c['id'] for c in ledger['unsupported'])
    cases = [c for c in json.loads((ROOT / 'tests/cases/manifest.json').read_text('utf-8'))['cases']
             if c['id'] in selected and re.search(args.case, ' '.join(c['names']))]
    if not cases:
        parser.error('No models selected')
    packages = {'z3': 'z3-solver', 'cp-sat': 'ortools', 'scip': 'pyscipopt'}
    report = {'seconds_per_engine': args.seconds, 'workers_per_engine': 1,
              'versions': {s: importlib.metadata.version(packages[s]) for s in args.engines}, 'cases': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for case in cases:
        raw = (ROOT / 'tests' / case['path']).read_bytes()
        model = json.loads(raw)['model']
        entry = {k: case[k] for k in ['id', 'names', 'expected']}
        entry['sha256'] = hashlib.sha256(raw).hexdigest()
        entry['oracles'] = {engine: solve(engine, model, args.seconds) for engine in args.engines}
        statuses = {r['status'] for r in entry['oracles'].values()}
        if 'INVALID_WITNESS' in statuses or {'SAT', 'UNSAT'} <= statuses:
            raise AssertionError('Oracle disagreement: ' + str(entry))
        entry['truth'] = 'SAT' if 'SAT' in statuses else 'UNSAT' if any(
            r['status'] == 'UNSAT' for e, r in entry['oracles'].items() if e != 'scip') else 'UNKNOWN'
        report['cases'].append(entry)
        args.output.write_text(json.dumps(report, indent=2) + '\n', 'utf-8')
        print(','.join(case['names']) + ': ' + str({e: r['status'] for e, r in entry['oracles'].items()}), flush=True)


if __name__ == '__main__':
    main()
