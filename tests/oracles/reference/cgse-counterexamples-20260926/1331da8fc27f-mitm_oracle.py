#!/usr/bin/env python3
"""Exact family-specific meet-in-the-middle oracle, not a general crafting planner.

Checks the reduction's preconditions. An exhaustive failure is a proof only for
this independent-token, paired-complement, exact-sum family. Uses Python integers.
"""
import json
from pathlib import Path
import sys
import time


def solve(path):
    fixture = json.loads(Path(path).read_text())
    start = time.perf_counter()
    recipes = fixture['recipes']
    sources = recipes[:-1]
    finish = recipes[-1]
    assert finish['id'] == 'finish' and fixture['amount'] == 1
    assert finish['outputs'] == {fixture['target']: 1}
    assert len(sources) % 2 == 0
    dims = sorted(k for k in finish['inputs'] if k.startswith('X'))
    assert set(finish['inputs']) == set(dims) | {'Y'+k[1:] for k in dims}
    weights = []
    for i in range(len(sources)//2):
        a, b = sources[2*i:2*i+2]
        assert a['id'] == 'a'+str(i) and b['id'] == 'b'+str(i)
        assert a['inputs'] == b['inputs'] == {'U'+str(i): 1}
        assert fixture['stock']['U'+str(i)] == 1
        assert set(a['outputs']) == set(dims)
        assert set(b['outputs']) == {'Y'+k[1:] for k in dims}
        weight = tuple(a['outputs'][k] for k in dims)
        assert all(w > 0 for w in weight)
        assert all(b['outputs']['Y'+k[1:]] == a['outputs'][k] for k in dims)
        weights.append(weight)
    assert set(fixture['stock']) == {'U'+str(i) for i in range(len(weights))}
    for j, k in enumerate(dims):
        # Each token can fire at most once, all weights are positive, and the
        # final recipe needs the entire conserved sum. Hence every pair fires
        # exactly once, and both X and Y thresholds hold with equality.
        assert finish['inputs'][k]+finish['inputs']['Y'+k[1:]] == sum(w[j] for w in weights)
    wanted = tuple(finish['inputs'][k] for k in dims)
    def subsets(vectors):
        values = [((0,)*len(dims), 0)]
        for i, vector in enumerate(vectors):
            values += [(tuple(a+b for a,b in zip(v,vector)), bits | (1 << i)) for v,bits in values]
        return values
    split = len(weights)//2
    left = dict(subsets(weights[:split]))
    right = subsets(weights[split:])
    found = None
    for value, bits in right:
        complement = tuple(a-b for a,b in zip(wanted,value))
        if complement in left:
            found = left[complement] | (bits << split)
            break
    chosen = None
    if found is not None:
        chosen = [i for i in range(len(weights)) if (found >> i) & 1]
        # Independently replay original recipe arcs, with no net-balance shortcut.
        inventory = dict(fixture['stock'])
        sequence = [sources[2*i + (0 if (found >> i) & 1 else 1)] for i in range(len(weights))]+[finish]
        for recipe in sequence:
            for key, count in recipe['inputs'].items():
                assert inventory.get(key,0) >= count
            for key,count in recipe['inputs'].items(): inventory[key] -= count
            for key,count in recipe['outputs'].items(): inventory[key] = inventory.get(key,0)+count
        assert inventory[fixture['target']] >= fixture['amount']
    return dict(name=fixture['name'],status='FEASIBLE' if found is not None else 'INFEASIBLE',
                ms=(time.perf_counter()-start)*1000,left=len(left),right=len(right),chosen=chosen)


if __name__ == '__main__':
    for path in sys.argv[1:]:
        print(json.dumps(solve(path)),flush=True)
