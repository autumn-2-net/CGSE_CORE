from pathlib import Path
import sys,json,time
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'cache/z3-python'))
import z3
z3.set_param('parallel.enable',False)
def check_path(f,path):
 held=dict(f['stock']);rs={r['id']:r for r in f['recipes']}
 for rid in path:
  r=rs[rid];assert all(held.get(k,0)>=v for k,v in r['inputs'].items())
  for k in r['inputs'].keys()|r['outputs'].keys():held[k]=held.get(k,0)+r['outputs'].get(k,0)-r['inputs'].get(k,0)
 assert held.get(f['target'],0)>=f['amount']
def solve(f,mode,ms):
 start=time.perf_counter();s=z3.SolverFor('QF_LIA');s.set(timeout=ms,random_seed=0)
 rs=f['recipes'];keys=sorted(set(f['stock'])|{f['target']}|set().union(*(r['inputs'].keys()|r['outputs'].keys() for r in rs)))
 if mode=='counts':
  x=[z3.Int('x'+str(i)) for i in range(len(rs))]
  for r,v in zip(rs,x):s.add(v>=0,v<=f['count_upper'][r['id']])
  for k in keys:s.add(z3.Sum([v*(r['outputs'].get(k,0)-r['inputs'].get(k,0)) for r,v in zip(rs,x) if r['outputs'].get(k,0)!=r['inputs'].get(k,0)])>=(f['amount'] if k==f['target'] else 0)-f['stock'].get(k,0))
 else:
  h=f['stock']['F'];assert all(r['inputs'].get('F',0)==1 and r['outputs'].get('F',0)==0 for r in rs)
  q=[[z3.Int(f'q{t}_{k}') for k in range(len(keys))] for t in range(h+1)];a=[[z3.Bool(f'a{t}_{i}') for i in range(len(rs))] for t in range(h)]
  for k,key in enumerate(keys):
   maximum=f['stock'].get(key,0)+h*max(r['outputs'].get(key,0) for r in rs)
   for t in range(h+1):s.add(q[t][k]>=0,q[t][k]<=maximum)
   s.add(q[0][k]==f['stock'].get(key,0))
  for t in range(h):
   s.add(z3.Sum([z3.If(v,1,0) for v in a[t]])<=1)
   for k,key in enumerate(keys):
    for i,r in enumerate(rs):
     if r['inputs'].get(key,0):s.add(q[t][k]>=z3.If(a[t][i],r['inputs'][key],0))
    s.add(q[t+1][k]==q[t][k]+z3.Sum([z3.If(a[t][i],r['outputs'].get(key,0)-r['inputs'].get(key,0),0) for i,r in enumerate(rs) if r['inputs'].get(key,0)!=r['outputs'].get(key,0)]))
  s.add(q[h][keys.index(f['target'])]>=f['amount'])
 built=time.perf_counter();status=s.check();end=time.perf_counter();r={'status':str(status).upper(),'ms':1000*(end-start),'build_ms':1000*(built-start),'solve_ms':1000*(end-built),'reason':s.reason_unknown() if status==z3.unknown else ''}
 if status==z3.sat:
  m=s.model()
  if mode=='counts':r['counts']={row['id']:m.eval(v).as_long() for row,v in zip(rs,x)}
  else:r['actions']=[rs[i]['id'] for t in range(h) for i in range(len(rs)) if z3.is_true(m.eval(a[t][i]))];check_path(f,r['actions'])
 return r
if __name__=='__main__':
 mode=sys.argv[1];folder=Path(sys.argv[2]);out=Path(sys.argv[3]);ms=int(sys.argv[4]);select=set(Path(sys.argv[5]).read_text().splitlines()) if len(sys.argv)>5 else None
 out.parent.mkdir(parents=True,exist_ok=True)
 with out.open('w') as stream:
  for path in sorted(folder.glob('*.json')):
   f=json.loads(path.read_text())
   if select is not None and f['name'] not in select:continue
   row={'case':f['name'],'z3':solve(f,mode,ms)};stream.write(json.dumps(row)+'\n');stream.flush();print(f['name'],row['z3']['status'],round(row['z3']['ms'],3),flush=True)
