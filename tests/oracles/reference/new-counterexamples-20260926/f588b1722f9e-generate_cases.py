from pathlib import Path
from fractions import Fraction
import random,json,copy
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'new-input';OUT.mkdir(exist_ok=True)
def batch(r,n=1):return {'batch':r,'n':n}
def seq(*s):return {'sequence':list(s)}
def repeat(s,n):return {'repeat':s,'n':n}
def recipe(id,ins,outs):return dict(id=id,inputs=ins,outputs=outs)
def base(n,c,cap,seed):
 rng=random.Random(seed);rs=[];stock={};need={};steps=[]
 for i in range(n):
  stock['U'+str(i)]=cap;need['D'+str(i)]=cap;weights=[rng.randrange(1,1001) for _ in range(2)];allocation=[0]*c
  for _ in range(cap):allocation[rng.randrange(c)]+=1
  for j in range(c):
   outs={'D'+str(i):1,**{f'X{j}_{d}':w for d,w in enumerate(weights)}};rid=f'r{i}_{j}';rs.append(recipe(rid,{'U'+str(i):1},outs))
   if allocation[j]:
    steps.append(batch(rid,allocation[j]))
    for k,v in outs.items():
     if not k.startswith('D'):need[k]=need.get(k,0)+v*allocation[j]
 rs.append(recipe('deliver',need,{'GOAL':1}));steps.append(batch('deliver'))
 return dict(name=f'choice_n{n}_c{c}_cap{cap}_s{seed}',target='GOAL',amount=1,stock=stock,recipes=rs,dag=True,truth='SAT',witness=seq(*steps),weights={'U0':1,'D0':1,'GOAL':cap})
def wrap(f,kind):
 f=copy.deepcopy(f);old=f['recipes'];f['recipes']=[];bodies={};weights={k:Fraction(v) for k,v in f['weights'].items()};ratio=kind.startswith('ratio');scale=3 if ratio else 1
 f['stock']={k:v*scale for k,v in f['stock'].items()};f['stock']['CAT']=scale;f['amount']*=scale
 depth=12 if kind=='deep12' else 4 if kind=='deep4' else 2
 for index,r in enumerate(old):
  rid=r['id'];ins={**r['inputs'],'CAT':1};outs={k:v*scale for k,v in r['outputs'].items()};outs['CAT']=scale
  value=sum(Fraction(q)*weights.get(k,0) for k,q in r['inputs'].items())
  if ratio:
   p,q=rid+'.P',rid+'.Q';weights[p]=value/2;weights[q]=value*Fraction(3,2)
   pieces=[recipe(rid+'.start',ins,{p:2}),recipe(rid+'.middle',{p:3},{q:1}),recipe(rid+'.end',{q:2},outs)]
   body=seq(batch(pieces[0]['id'],3),batch(pieces[1]['id'],2),batch(pieces[2]['id']))
  else:
   pieces=[];prev=None
   for j in range(depth):
    end=j==depth-1;outkey=rid+f'.P{j}';weights[outkey]=value
    pieces.append(recipe(rid+f'.stage{j}',ins if j==0 else {prev:1},outs if end else {outkey:1}));prev=outkey
   body=seq(*[batch(x['id']) for x in pieces])
  if kind in ('joint','ratio_joint'):
   pieces[0]['outputs'][rid+'.SIDE']=1
  if kind in ('entry','ratio_entry'):
   k=next(iter(pieces[0]['outputs']));f['stock'][k]=f['stock'].get(k,0)+1
  if kind in ('alias','ratio_alias'):
   alias=copy.deepcopy(pieces[0]);alias['id']+='_alternative';f['recipes'].append(alias)
  if kind in ('destructive','ratio_destructive'):
   last=pieces[-1];burn=copy.deepcopy(last);burn['id']+='_burn';burn['outputs'].pop('CAT');f['recipes'].append(burn)
  f['recipes']+=pieces;bodies[rid]=body
 def lift(s):
  if 'batch' in s:return repeat(bodies[s['batch']],s['n'])
  return seq(*[lift(x) for x in s['sequence']])
 f['witness']=lift(f['witness']);f['weights']={k:str(v) for k,v in weights.items() if v};f['dag']=False;f['name']+='_'+kind;return f
def put(f,short=False):
 f=copy.deepcopy(f)
 if short:
  f['stock']['U0']-=1;f['name']+='_short1';f['truth']='UNSAT';f.pop('witness')
 weights={k:Fraction(v) for k,v in f.pop('weights').items()}
 if short:
  for r in f['recipes']:
   delta=sum(weights.get(k,0)*v for k,v in r['outputs'].items())-sum(weights.get(k,0)*v for k,v in r['inputs'].items());assert delta<=0,(f['name'],r['id'],delta)
  initial=sum(weights.get(k,0)*v for k,v in f['stock'].items());required=weights[f['target']]*f['amount'];assert initial<required
  f['invariant']={'weights':{k:str(v) for k,v in weights.items()},'initial':str(initial),'required':str(required)}
 (OUT/(f['name']+'.json')).write_text(json.dumps(f,separators=(',',':')))
for n,c,cap in [(6,2,3),(12,2,3),(18,2,1),(12,3,1)]:
 for seed in [101,211,307,401]:
  f=base(n,c,cap,seed);put(f);put(f,True)
  for kind in ['private','deep4','deep12','joint','entry','alias','destructive','ratio','ratio_joint','ratio_entry','ratio_alias','ratio_destructive']:
   g=wrap(f,kind);put(g)
   if kind in ['private','deep4','ratio','destructive']:put(g,True)
print('GENERATED',len(list(OUT.glob('*.json'))))
