#!/usr/bin/env python3
"""Independent exact certificate checks for BatchProbe's declared families.

No CGSE or OR-Tools code is imported. Python integers are arbitrary precision.
These are family-specific checkers, not a claimed general recipe solver.
"""
import collections
import json
from pathlib import Path
import re
import sys
import time

class JavaRandom:
    def __init__(self, seed): self.state=(seed ^ 0x5DEECE66D)&((1<<48)-1)
    def next(self, bits):
        self.state=(self.state*0x5DEECE66D+0xB)&((1<<48)-1)
        return self.state>>(48-bits)
    def next_int(self, bound):
        if bound & (bound-1) == 0: return (bound*self.next(31))>>31
        while True:
            bits=self.next(31);v=bits%bound
            if bits-v+(bound-1)<(1<<31):return v
    def boolean(self):return self.next(1)!=0

def execute(fixture, counts=None, sequence=None):
    rs={r['id']:r for r in fixture['recipes']}; held=dict(fixture['stock'])
    steps=[(x,1) for x in sequence] if sequence is not None else list(counts.items())
    for rid,n in steps:
        assert isinstance(n,int) and n>=0
        if n==0:continue
        r=rs[rid]
        for key in r['inputs'].keys() | r['outputs'].keys():
            incoming=r['inputs'].get(key,0);delta=r['outputs'].get(key,0)-incoming
            required=incoming+max(-delta,0)*(n-1)
            assert held.get(key,0)>=required,(fixture['name'],rid,key,held.get(key,0),required)
        for key in r['inputs'].keys() | r['outputs'].keys():
            held[key]=held.get(key,0)+n*(r['outputs'].get(key,0)-r['inputs'].get(key,0))
            assert held[key]>=0
    assert held.get(fixture['target'],0)>=fixture['amount']
    return held

def summary(recipe):
    keys=recipe['inputs'].keys() | recipe['outputs'].keys()
    return (dict(recipe['inputs']),{k:recipe['outputs'].get(k,0)-recipe['inputs'].get(k,0) for k in keys})

def compose(a,b):
    ar,ad=a;br,bd=b;keys=ar.keys()|ad.keys()|br.keys()|bd.keys()
    return ({k:max(ar.get(k,0),br.get(k,0)-ad.get(k,0),0) for k in keys},
            {k:ad.get(k,0)+bd.get(k,0) for k in keys})

def counter(f):
    bits,shortage=map(int,re.fullmatch(r'counter_bits(\d+)_short(\d+)',f['name']).groups())
    rs={r['id']:r for r in f['recipes']}
    assert set(rs)=={f'inc{i}' for i in range(bits)}|{'finish'}
    assert f['stock']=={'F':(1<<bits)-1-shortage,**{f'Z{i}':1 for i in range(bits)}}
    assert rs['finish']['inputs']=={f'O{i}':1 for i in range(bits)}
    assert rs['finish']['outputs']=={'GOAL':1}
    block=({},{});grammar={};counts={}
    for i in range(bits):
        r=rs[f'inc{i}']
        assert r['inputs']=={'F':1,f'Z{i}':1,**{f'O{j}':1 for j in range(i)}}
        assert r['outputs']=={f'O{i}':1,**{f'Z{j}':1 for j in range(i)}}
        # Pi+1 = Pi ; inci ; Pi. Summaries are memoized, no 2^bits expansion.
        block=compose(compose(block,summary(r)),block)
        grammar[f'P{i+1}']=[f'P{i}',f'inc{i}',f'P{i}']
        counts[f'inc{i}']=1<<(bits-i-1)
    full=compose(block,summary(rs['finish']))
    if shortage==0:
        assert all(f['stock'].get(k,0)>=v for k,v in full[0].items())
        assert f['stock'].get('GOAL',0)+full[1].get('GOAL',0)>=1
        return dict(status='SAT',method='exact_recursive_program_summary',expanded_steps=(1<<bits),grammar=grammar,
                    grammar_base='P0 is empty',counts={**counts,'finish':1})
    # Every increment increases sum(2^i * Oi) by exactly one and spends one F.
    # Before finish, all Oi are required. Rank + F is invariant, initially too small.
    assert shortage>0
    return dict(status='UNSAT',method='integer_rank_plus_fuel_invariant',required_fuel=(1<<bits)-1,available_fuel=f['stock']['F'])

def matching(f):
    size,bridge=re.fullmatch(r'matching_odd(\d+)_bridge(true|false)',f['name']).groups();size=int(size);bridge=bridge=='true'
    assert f['stock']=={f'U{i}':1 for i in range(2*size)}
    rs={r['id']:r for r in f['recipes']}
    assert rs['finish']['inputs']=={'PAIR':size} and rs['finish']['outputs']=={'GOAL':1}
    edges={}; adjacency={i:set() for i in range(2*size)}
    for r in f['recipes']:
        if r['id']=='finish':continue
        assert len(r['inputs'])==2 and set(r['inputs'].values())=={1} and r['outputs']=={'PAIR':1}
        a,b=sorted(int(k[1:]) for k in r['inputs']);edges[(a,b)]=r['id'];adjacency[a].add(b);adjacency[b].add(a)
    counts={}
    if bridge:
        counts[edges[(0,size)]]=1
        for group in range(2):
            for i in range(1,size,2):counts[edges[(group*size+i,group*size+i+1)]]=1
        counts['finish']=1;execute(f,counts)
        return dict(status='SAT',method='exact_pairing_witness',counts=counts)
    seen=set();components=[]
    for v in adjacency:
        if v in seen:continue
        q=[v];seen.add(v);component=[]
        while q:
            a=q.pop();component.append(a)
            for b in adjacency[a]:
                if b not in seen:seen.add(b);q.append(b)
        components.append(component)
    upper=sum(len(c)//2 for c in components)
    assert upper<size
    return dict(status='UNSAT',method='disconnected_component_matching_upper_bound',components=components,upper_pairs=upper,required_pairs=size)

def coloring(f):
    n=int(re.match(r'color_n(\d+)_',f['name'])[1]);rs={r['id']:r for r in f['recipes']}
    assert rs['finish']['inputs']=={f'D{v}':1 for v in range(n)} and rs['finish']['outputs']=={'GOAL':1}
    uses=collections.defaultdict(list);edges={};adj=[set() for _ in range(n)]
    for v in range(n):
        for c in range(3):
            r=rs[f'v{v}c{c}'];assert r['outputs']=={f'D{v}':1};assert r['inputs'][f'U{v}']==1
            for k,q in r['inputs'].items():
                assert q==1
                if k==f'U{v}':continue
                match=re.fullmatch(r'E(\d+)c(\d+)',k);assert match and int(match[2])==c
                uses[(int(match[1]),c)].append(v)
    for (e,c),vertices in uses.items():
        assert len(vertices)==2
        pair=tuple(sorted(vertices));edges.setdefault(e,pair);assert edges[e]==pair
        assert all(sorted(uses[(e,d)])==list(pair) for d in range(3))
    assert f['stock']=={**{f'U{v}':1 for v in range(n)},**{f'E{e}c{c}':1 for e in edges for c in range(3)}}
    for a,b in edges.values():adj[a].add(b);adj[b].add(a)
    assignment=[-1]*n;visited=0;start=time.perf_counter()
    def dfs(done):
        nonlocal visited
        visited+=1
        if visited>5_000_000 or time.perf_counter()-start>15:raise TimeoutError('oracle budget')
        if done==n:return True
        vertex=max((v for v in range(n) if assignment[v]<0),key=lambda v:(len({assignment[u] for u in adj[v] if assignment[u]>=0}),len(adj[v])))
        forbidden={assignment[u] for u in adj[vertex] if assignment[u]>=0}
        used={c for c in assignment if c>=0};new_tried=False
        for c in range(3):
            if c in forbidden:continue
            if c not in used:
                if new_tried:continue
                new_tried=True
            assignment[vertex]=c
            if dfs(done+1):return True
            assignment[vertex]=-1
        return False
    sat=dfs(0);counts={}
    if sat:
        counts={f'v{v}c{assignment[v]}':1 for v in range(n)};counts['finish']=1;execute(f,counts)
    return dict(status='SAT' if sat else 'UNSAT',method='exhaustive_dsatur_with_color_symmetry',states=visited,counts=counts)

def planted(f,family):
    counts={};rs={r['id']:r for r in f['recipes']}
    if family=='bounded_integer':
        n,cap,dims,scale,seed,shift=map(int,re.fullmatch(r'bounded_n(\d+)_cap(\d+)_d(\d+)_w(\d+)_s(\d+)_shift(\d+)',f['name']).groups())
        assert shift==0;rng=JavaRandom(seed)
        for i in range(n):
            take=rng.next_int(cap+1)
            if i==0:take=1
            for d in range(dims):assert rs[f'a{i}']['outputs'][f'X{d}']==1+rng.next_int(scale)
            counts[f'a{i}']=take;counts[f'b{i}']=cap-take
    elif family in ('interval_balance','many_dimensions'):
        if family=='interval_balance':n,dims,seed,slack=map(int,re.fullmatch(r'interval_n(\d+)_d(\d+)_s(\d+)_slack(\d+)',f['name']).groups());scale=1000
        else:n,dims,scale,seed,shift=map(int,re.fullmatch(r'multi_n(\d+)_d(\d+)_w(\d+)_s(\d+)_shift(\d+)',f['name']).groups());assert shift==0
        rng=JavaRandom(seed)
        for i in range(n):
            take=rng.boolean()
            for d in range(dims):assert rs[f'a{i}']['outputs'][f'X{d}']==1+rng.next_int(scale)
            counts[f'a{i}']=int(take);counts[f'b{i}']=int(not take)
    elif family=='multiway':
        n,colors,seed=map(int,re.fullmatch(r'multiway_n(\d+)_c(\d+)_s(\d+)',f['name']).groups());rng=JavaRandom(seed)
        for i in range(n):
            c=i if i<colors else rng.next_int(colors)
            for d in range(2):assert rs[f'r{i}c{c}']['outputs'][f'C{c}_{d}']==1+rng.next_int(1000)
            counts[f'r{i}c{c}']=1
    elif family=='exact_cover':
        n=int(re.match(r'cover_n(\d+)_',f['name'])[1])
        for i in range(0,n,3):
            desired={f'U{v}':1 for v in range(i,i+3)}
            rid=next(r['id'] for r in f['recipes'] if r['inputs']==desired)
            counts[rid]=1
    else:raise ValueError(family)
    counts['finish']=1;execute(f,counts)
    return dict(status='SAT',method='planted_counts_exact_prefix_replay',counts=counts)

def check(f,row):
    family=row['family']
    if family=='binary_counter':return counter(f)
    if family=='odd_matching':return matching(f)
    if family=='shared_exclusion':return coloring(f)
    if family=='fuelled_order':
        execute(f,sequence=row['witness'])
        return dict(status='SAT',method='explicit_original_recipe_sequence',witness=row['witness'])
    return planted(f,family)

if __name__=='__main__':
    folder=Path(sys.argv[1]);rows=[json.loads(x) for x in (folder/'results.jsonl').read_text().splitlines()]
    seen=set()
    for row in rows:
        if row['case'] in seen:continue
        seen.add(row['case']);f=json.loads((folder/'cases'/(row['case']+'.json')).read_text());start=time.perf_counter()
        proof=check(f,row)
        if row['truth']!='UNKNOWN':assert proof['status']==row['truth']
        print(json.dumps({'case':row['case'],'family':row['family'],'ms':(time.perf_counter()-start)*1000,**proof},sort_keys=True),flush=True)
