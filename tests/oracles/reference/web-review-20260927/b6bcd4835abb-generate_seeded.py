from pathlib import Path
import json,random,copy,sys,collections,hashlib
R=Path(__file__).resolve().parent;OLD=R
sys.path.insert(0,str(R))
from verify_new_cases import check,summary
from prepare_manual_models import bounds
OUT=R/'cases';OUT.mkdir(exist_ok=True);MODELS=R/'models';MODELS.mkdir(exist_ok=True)
B=lambda rid,n=1:{'batch':rid,'n':n}
S=lambda *s:{'sequence':list(s)}
P=lambda s,n:{'repeat':s,'n':n}
def recipe(rid,ins,outs):return {'id':rid,'inputs':ins,'outputs':outs}
def size(s):
 if 'batch' in s:return s['n']
 if 'repeat' in s:return size(s['repeat'])*s['n']
 return sum(map(size,s['sequence']))
def lattice(seed,n=3,c=2,d=1,scale=1):
 rng=random.Random(seed);caps=[rng.randint(2,9)*scale+rng.randrange(max(1,scale//10)) for _ in range(n)];weights=[[rng.randint(37,100003) for _ in range(d)] for i in range(n)]
 # Seed from the seven-recipe counterexample's integer allocation topology, mutate coefficients and counts.
 if seed%4==0:weights=[[w*(2 if j==0 else 3) for j,w in enumerate(row)] for row in weights]
 rs=[];stock={f'U{i}':q for i,q in enumerate(caps)};goal={};steps=[]
 for i,q in enumerate(caps):
  cuts=sorted([0,q]+[rng.randint(0,q) for _ in range(c-1)]);allocation=[b-a for a,b in zip(cuts,cuts[1:])];rng.shuffle(allocation)
  goal[f'D{i}']=q
  for j in range(c):
   o={f'D{i}':1,**{f'X{j}_{k}':weights[i][k] for k in range(d)}};rid=f'r{i}_{j}';rs.append(recipe(rid,{f'U{i}':1},o));steps.append(B(rid,allocation[j]))
   for k,w in enumerate(weights[i]):goal[f'X{j}_{k}']=goal.get(f'X{j}_{k}',0)+w*allocation[j]
 goal={k:v for k,v in goal.items() if v};rs.append(recipe('finish',goal,{'GOAL':1}));steps.append(B('finish'))
 return {'target':'GOAL','amount':1,'recipes':rs,'stock':stock,'dag':True,'truth':'SAT','witness':S(*steps),'seed_origin':'min_manual_large_n3_c2_d1_a1_s43_wide_weights'}
def rewrite(s,fn):
 if 'batch' in s:return fn(s)
 if 'repeat' in s:return P(rewrite(s['repeat'],fn),s['n'])
 return S(*(rewrite(a,fn) for a in s['sequence']))
def split(f,steps=2,cat=False):
 out=[];mapping={}
 if cat:f['stock']['CAT']=1;f['dag']=False
 for r in f['recipes']:
  if r['id']=='finish':out.append(r);continue
  ids=[]
  for j in range(steps):
   rid=r['id']+f'.s{j}';ids.append(rid)
   ins=dict(r['inputs']) if j==0 else {r['id']+f'.p{j-1}':1}
   outs=dict(r['outputs']) if j==steps-1 else {r['id']+f'.p{j}':1}
   if cat and j==0:ins['CAT']=1
   if cat and j==steps-1:outs['CAT']=1
   out.append(recipe(rid,ins,outs))
  mapping[r['id']]=ids
 def trans(s):
  ids=mapping.get(s['batch'])
  if not ids:return s
  return P(S(*(B(k) for k in ids)),s['n']) if cat else S(*(B(k,s['n']) for k in ids))
 f['recipes']=out;f['witness']=rewrite(f['witness'],trans)
def rename(f,prefix,share=False):
 def key(k):return 'RAW'+k[1:] if share and k.startswith('U') else prefix+k
 for r in f['recipes']:
  r['id']=prefix+r['id'];r['inputs']={key(k):v for k,v in r['inputs'].items()};r['outputs']={key(k):v for k,v in r['outputs'].items()}
 f['stock']={key(k):v for k,v in f['stock'].items()};f['target']=key(f['target']);f['witness']=rewrite(f['witness'],lambda s:B(prefix+s['batch'],s['n']))
def fuel(f):
 f['stock']['F']=size(f['witness'])
 for r in f['recipes']:r['inputs']['F']=1

def make(family,i):
 seed=470000+i*101+sum(map(ord,family));rng=random.Random(seed);scale=[1,3,100,10000,100000][i%5]
 f=lattice(seed,n=3 if family not in ('joint_accounts','near_short_unsat') else 4+i%5,c=3 if family=='joint_accounts' else 2,d=2 if family=='joint_accounts' else 1,scale=scale)
 if family=='split_pipeline':split(f,steps=2+i%3)
 elif family=='shared_catalyst':
  for r in f['recipes'][:-1]:r['inputs']['CAT']=1;r['outputs']['CAT']=1
  f['stock']['CAT']=1;f['dag']=False
 elif family=='multi_step_return':split(f,steps=2+i%4,cat=True)
 elif family=='optional_lossy':
  # Optional choices introduce intersecting feedback paths; original witness stays valid.
  fuel(f);f['dag']=False
  f['recipes'] += [recipe('loss_A',{'D0':2,'F':1},{'JUNK':1}),recipe('loss_B',{'JUNK':1,'F':1},{'U0':1}),recipe('cross',{'X0_0':100003,'F':1},{'D1':1}),recipe('drain',{'U1':2,'F':1},{'D1':1})]
 elif family=='fused_seed':
  g=lattice(seed+19,n=3,scale=scale);rename(f,'a.',True);rename(g,'b.',True)
  f['recipes']+=g['recipes'];f['recipes'].append(recipe('joint_finish',{f['target']:1,g['target']:1},{'GOAL':1}))
  for k,v in g['stock'].items():f['stock'][k]=f['stock'].get(k,0)+v
  f['witness']=S(f['witness'],g['witness'],B('joint_finish'));f['target']='GOAL'
 elif family=='near_short_unsat':
  cap=f['stock']['U0'];f['stock']['U0']-=1;f['truth']='UNSAT';f.pop('witness');f['invariant']={'weights':{'U0':1,'D0':1,'GOAL':cap},'initial':cap-1,'required':cap}
 # Input/output order and alias perturbations test option monotonicity + memoization order sensitivity.
 if i%4==1:
  for r in f['recipes']:
   for side in ['inputs','outputs']:
    xs=list(r[side].items());rng.shuffle(xs);r[side]=dict(xs)
 if i%4==2:
  r=copy.deepcopy(f['recipes'][0]);r['id']+='-alias';f['recipes'].append(r)
 rng.shuffle(f['recipes'])
 f.update(name=f'{family}_{i:04d}',family=family,random_seed=seed,mutation={'scale':scale,'permutation':True,'alias':i%4==2})
 return f

def save(f):
 certificate=check(f)
 if all(r['inputs'].get('F')==1 and not r['outputs'].get('F',0) for r in f['recipes']):
  upper={r['id']:f['stock']['F'] for r in f['recipes']};cert={'fuel_key':'F','fuel_stock':f['stock']['F']}
 else:upper,cert=bounds(f)
 f['count_upper']=upper;f['bound_certificate']=cert
 p=OUT/(f['name']+'.json');p.write_text(json.dumps(f,separators=(',',':')))
 model={k:f[k] for k in ['name','target','amount','recipes','stock','dag','count_upper','bound_certificate']};(MODELS/p.name).write_text(json.dumps(model,separators=(',',':')))
 return {'case':f['name'],'truth':f['truth'],'family':f['family'],'recipes':len(f['recipes']),'certificate':certificate,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}

def main():
 records=[];families=['lattice_wide','joint_accounts','split_pipeline','shared_catalyst','multi_step_return','optional_lossy','fused_seed','near_short_unsat']
 for family in families:
  for i in range(256):records.append(save(make(family,i)))
 # Cyclic random mutations seeded by the hardest available SAT networks from the prior BFS campaign.
 hard=json.loads((R/'random-seed-origin.json').read_text())
 for i in range(2048):
  origin=hard[i%len(hard)];f=json.loads((R/'seeds'/(origin['case']+'.json')).read_text());rng=random.Random(910000+i)
  f['witness']=S(*(B(r) for r in origin['witness']));f['truth']='SAT';f['dag']=False
  keys=sorted(set(f['stock'])|set().union(*(r['inputs'].keys()|r['outputs'].keys() for r in f['recipes'])));keys=[k for k in keys if k!='F']
  # A mutation may add options/outputs/stock but never weaken the original witness.
  for j in range(1+i%4):
   a,b=rng.sample(keys,2);rid=f'mut{j}'
   f['recipes'].append(recipe(rid,{a:rng.randint(1,3),'F':1},{b:rng.randint(1,2)}))
  if i%3==0:
   r=copy.deepcopy(rng.choice(f['recipes']));r['id']='alias';f['recipes'].append(r)
  if i%3==1:
   r=rng.choice(f['recipes']);k=rng.choice(keys);r['outputs'][k]=r['outputs'].get(k,0)+1
  rng.shuffle(f['recipes'])
  for r in f['recipes']:
   for side in ['inputs','outputs']:
    kv=list(r[side].items());rng.shuffle(kv);r[side]=dict(kv)
  f.update(name=f'hard_random_{i:04d}',family='hard_random',seed_origin=origin['case'],random_seed=910000+i)
  records.append(save(f))
 (R/'certificates.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
 # Predeclared balanced sample: 16 of each structural family plus 64 random mutants.
 selected=[f'{fam}_{(j*17+7)%256:04d}' for fam in families for j in range(16)]+[f'hard_random_{(j*31+11)%2048:04d}' for j in range(64)]
 (R/'balanced-selected.txt').write_text('\n'.join(selected)+'\n')
 (R/'count-selected.txt').write_text('\n'.join(k for k in selected if not k.startswith('hard_random'))+'\n')
 (R/'temporal-selected.txt').write_text('\n'.join(k for k in selected if k.startswith('hard_random'))+'\n')
 print('GENERATED',len(records),'BALANCED',len(selected),dict(collections.Counter(r['truth'] for r in records)))
if __name__=='__main__':main()
