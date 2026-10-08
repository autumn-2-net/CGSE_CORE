#!/usr/bin/env python3
"""Z3 integer rows on DAGs and certified composable wrappers; never general cycle net-balance SAT."""
import json,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'z3-python'))
import z3
from verify_fusions import validate
z3.set_param('parallel.enable',False)

def replay_dag(f,counts):
    held=dict(f['stock'])
    for r in f['recipes']:
        n=counts[r['id']]
        if not n:continue
        keys=r['inputs'].keys()|r['outputs'].keys()
        for k in keys:
            a=r['inputs'].get(k,0);delta=r['outputs'].get(k,0)-a
            assert held.get(k,0)>=a+max(0,-delta)*(n-1),(f['name'],r['id'],k)
        for k in keys:held[k]=held.get(k,0)+n*(r['outputs'].get(k,0)-r['inputs'].get(k,0))
    assert held.get(f['target'],0)>=f['amount']

def run(f,budget):
    start=time.perf_counter();s=z3.SolverFor('QF_LIA');s.set(timeout=budget,random_seed=0)
    x=[z3.Int('x'+str(i)) for i in range(len(f['recipes']))];upper=f.get('upper',max([f['amount'],*f['stock'].values()]))
    s.add(*[z3.And(v>=0,v<=(1 if r['id'].endswith('finish') else upper)) for v,r in zip(x,f['recipes'])])
    keys=set(f['stock'])|{f['target']}
    for r in f['recipes']:keys.update(r['inputs']);keys.update(r['outputs'])
    for k in sorted(keys):
        terms=[v*(r['outputs'].get(k,0)-r['inputs'].get(k,0)) for v,r in zip(x,f['recipes']) if r['outputs'].get(k,0)!=r['inputs'].get(k,0)]
        s.add(z3.Sum(terms)>=(f['amount'] if k==f['target'] else 0)-f['stock'].get(k,0))
    built=time.perf_counter();status=s.check();end=time.perf_counter();counts={}
    if status==z3.sat:
        model=s.model();counts={r['id']:model.eval(v).as_long() for r,v in zip(f['recipes'],x)}
        if 'decode' in f:validate(f,counts)
        else:replay_dag(f,counts)
    return {'case':f['name'],'solver':'Z3 '+z3.get_version_string(),'status':str(status).upper(),'ms':1000*(end-start),'build_ms':1000*(built-start),'solve_ms':1000*(end-built),'reason':s.reason_unknown() if status==z3.unknown else '', 'counts':counts,'budget_ms':budget}

def main():
    source=Path(sys.argv[1]);out=Path(sys.argv[2]);budget=int(sys.argv[3]) if len(sys.argv)>3 else 3000
    selected=set(Path(sys.argv[4]).read_text().splitlines()) if len(sys.argv)>4 else None
    # Warm solver construction on a small QF_LIA formula.
    for i in range(5):s=z3.SolverFor('QF_LIA');v=z3.Int('warm');s.add(v>=1,v<=2);s.check()
    out.parent.mkdir(exist_ok=True,parents=True)
    with out.open('w') as stream:
        for path in sorted(source.glob('*.json')):
            f=json.loads(path.read_text())
            if selected is not None and f['name'] not in selected:continue
            if not f.get('dag') and 'decode' not in f:continue
            row=run(f,budget);stream.write(json.dumps(row)+'\n');stream.flush();print(row['case'],row['status'],round(row['ms'],2),flush=True)
if __name__=='__main__':main()
