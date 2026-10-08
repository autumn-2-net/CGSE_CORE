from pathlib import Path
from collections import Counter,defaultdict,deque
import json
ROOT=Path(__file__).resolve().parent
def bounds(f):
 rs=f['recipes'];prod=defaultdict(set);degree=Counter()
 for i,r in enumerate(rs):
  for k in r['outputs']:prod[k].add(i)
  for k in r['inputs']:degree[k]+=1
 essential={next(iter(r['inputs'])) for r in rs if len(r['inputs'])==1}
 candidates=sorted((k for k in degree if k in prod and k not in essential),key=lambda k:(-degree[k],k))
 for take in range(min(64,len(candidates))+1):
  ignored=set(candidates[:take]);children=[[] for _ in rs];parents=[]
  for i,r in enumerate(rs):
   dep=set().union(*(prod[k] for k in r['inputs'] if k not in ignored))
   parents.append(len(dep))
   for j in dep:children[j].append(i)
  q=deque(i for i,v in enumerate(parents) if not v);order=[]
  while q:
   i=q.popleft();order.append(i)
   for j in children[i]:
    parents[j]-=1
    if not parents[j]:q.append(j)
  if len(order)!=len(rs):continue
  held=dict(f['stock']);upper={};valid=True
  for i in order:
   r=rs[i];terms=[held.get(k,0)//v for k,v in r['inputs'].items() if k not in ignored]
   if not terms:valid=False;break
   upper[r['id']]=n=min(terms)
   for k,v in r['outputs'].items():held[k]=held.get(k,0)+v*n
  if valid:return upper,{'ignored_input_keys':sorted(ignored),'recipe_topological_order':[rs[i]['id'] for i in order]}
 raise ValueError('No finite relaxed bound: '+f['name'])
def main():
 out=ROOT/'manual-models';out.mkdir(exist_ok=True)
 for path in [*(ROOT/'manual-input').glob('*.json'),*(ROOT/'min-input').glob('*.json')]:
  f=json.loads(path.read_text());g={k:f[k] for k in ['name','target','amount','stock','recipes','dag']};g['count_upper'],g['bound_certificate']=bounds(f);(out/path.name).write_text(json.dumps(g,separators=(',',':')))
 print('BOUNDED',len(list(out.glob('*.json'))))
if __name__=='__main__':main()
