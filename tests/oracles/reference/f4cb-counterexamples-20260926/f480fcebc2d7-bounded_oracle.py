#!/usr/bin/env python3
"""Exact independent oracle ONLY for the generated bounded paired-source DAG family.

Every Ui is private and used only by Ai/Bi. Di demand forces Ai+Bi=cap.
Both choices emit identical positive weights to opposite X/Y channels, and
the finish recipe saturates each channel pair. Thus feasibility is exactly
sum_i weight[i,d]*Ai == required[X_d], with integer 0 <= Ai <= cap[i].
The mixed-radix meet-in-the-middle enumerates this entire finite domain.
Any found witness is also replayed against the original JSON recipe arcs.
Python integers are exact; this is not a general cyclic recipe solver.
"""
import itertools
import json
from pathlib import Path
import sys
import time

def solve(path):
    data = json.loads(Path(path).read_text())
    assert data['dag'] and data['amount'] == 1 and data['target'] == 'GOAL'
    stock = data['stock']; recipes = data['recipes']
    assert recipes[-1]['id'] == 'finish' and recipes[-1]['outputs'] == {'GOAL': 1}
    assert len(recipes) % 2 == 1
    n = len(recipes)//2
    assert set(stock) == {f'U{i}' for i in range(n)}
    need = recipes[-1]['inputs']
    dims = len([k for k in need if k.startswith('X')])
    assert dims > 0
    assert set(need) == {f'D{i}' for i in range(n)} | {f'{xy}{d}' for xy in 'XY' for d in range(dims)}
    caps, weights = [], []
    for i in range(n):
        a, b = recipes[2*i:2*i+2]
        assert a['inputs'] == b['inputs'] == {f'U{i}': 1}
        assert set(a['outputs']) == {f'D{i}'} | {f'X{d}' for d in range(dims)}
        assert set(b['outputs']) == {f'D{i}'} | {f'Y{d}' for d in range(dims)}
        assert a['outputs'][f'D{i}'] == b['outputs'][f'D{i}'] == 1
        assert stock[f'U{i}'] == need[f'D{i}'] > 0
        caps.append(stock[f'U{i}'])
        w = tuple(a['outputs'][f'X{d}'] for d in range(dims))
        assert all(x > 0 for x in w)
        assert w == tuple(b['outputs'][f'Y{d}'] for d in range(dims))
        weights.append(w)
    for d in range(dims):
        assert need[f'X{d}'] + need[f'Y{d}'] == sum(caps[i]*weights[i][d] for i in range(n))
    wanted = tuple(need[f'X{d}'] for d in range(dims))
    split = n//2
    def signature(indices, values):
        return tuple(sum(weights[i][d]*x for i, x in zip(indices, values)) for d in range(dims))
    started = time.perf_counter()
    left = {}
    for values in itertools.product(*(range(c+1) for c in caps[:split])):
        left.setdefault(signature(range(split), values), values)
    witness = None; visited_right = 0
    for values in itertools.product(*(range(c+1) for c in caps[split:])):
        visited_right += 1
        sig = signature(range(split, n), values)
        complement = tuple(w-s for w,s in zip(wanted,sig))
        if complement in left:
            witness = left[complement] + values
            break
    elapsed = (time.perf_counter()-started)*1000
    counts = {}
    if witness is not None:
        held = dict(stock)
        for i, x in enumerate(witness):
            for recipe, count in [(recipes[2*i], x), (recipes[2*i+1], caps[i]-x)]:
                counts[recipe['id']] = count
                for k,q in recipe['inputs'].items():
                    assert held.get(k,0) >= q*count
                    held[k] -= q*count
                for k,q in recipe['outputs'].items(): held[k] = held.get(k,0)+q*count
        for k,q in need.items():
            assert held.get(k,0) >= q
            held[k] -= q
        held['GOAL'] = held.get('GOAL',0)+1
        assert held['GOAL'] >= data['amount']
        counts['finish'] = 1
    return dict(name=data['name'], status='SAT' if witness is not None else 'UNSAT',
                exact=True, total_domain=__import__('math').prod(c+1 for c in caps),
                left_distinct=len(left), right_visited=visited_right, ms=elapsed,
                counts=counts)

if __name__ == '__main__':
    for path in sys.argv[1:]: print(json.dumps(solve(path), sort_keys=True), flush=True)
