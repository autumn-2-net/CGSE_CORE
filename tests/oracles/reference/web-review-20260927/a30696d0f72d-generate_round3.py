"""Round 3: new structural generators, label-invariant conservative WL dedup, exact independent oracles."""
from pathlib import Path
from collections import deque,Counter
import json,random,hashlib,sys,copy,time
R=Path(__file__).resolve().parent
from base_verify import summary,check as base_check
B=lambda k,n=1:{'batch':k,'n':n}
S=lambda *x:{'sequence':list(x)}
def rec(k,a,b):return {'id':k,'inputs':a,'outputs':b}
def fixture(name,rs,stock,target='GOAL',amount=1,dag=False):return dict(name=name,recipes=rs,stock=stock,target=target,amount=amount,dag=dag)
def fingerprint(f,quantities=False):
 # Isomorphic colored incidence graphs necessarily have the same hash; collisions
 # are conservatively rejected, never treated as an isomorphism proof.
 keys=sorted(set(f['stock'])|{f['target']}|set().union(*(r['inputs'].keys()|r['outputs'].keys() for r in f['recipes'])))
 index={k:i for i,k in enumerate(keys)};n=len(keys);adj=[[] for _ in range(n+len(f['recipes']))]
 colors=[('M',k==f['target'],f['stock'].get(k,0) if quantities else f['stock'].get(k,0)>0,f['amount'] if quantities and k==f['target'] else 0) for k in keys]+[('R',)]*len(f['recipes'])
 enc=lambda x:hashlib.sha256(repr(x).encode()).hexdigest()[:24]
 colors=list(map(enc,colors))
 for i,r in enumerate(f['recipes']):
  for side in ['inputs','outputs']:
   for k,q in r[side].items():
    label=(side,q if quantities else 1);j=index[k];adj[j].append((n+i,label));adj[n+i].append((j,label))
 for _ in range(6):colors=[enc((colors[i],sorted((lab,colors[j]) for j,lab in row))) for i,row in enumerate(adj)]
 return enc((len(adj),sum(map(len,adj)),sorted(colors)))

def bfs(f,cap=250000,time_limit_seconds=None):
 started=time.perf_counter()
 assert all(r['inputs'].get('F')==1 and not r['outputs'].get('F',0) for r in f['recipes'])
 keys=sorted((set(f['stock'])|{f['target']}|set().union(*(r['inputs'].keys()|r['outputs'].keys() for r in f['recipes'])))-{'F'});idx={k:i for i,k in enumerate(keys)}
 rs=[]
 for r in f['recipes']:
  inp=[(idx[k],q) for k,q in r['inputs'].items() if k!='F'];delta=[(idx[k],r['outputs'].get(k,0)-r['inputs'].get(k,0)) for k in r['outputs'].keys()|r['inputs'].keys() if k!='F' and r['outputs'].get(k,0)!=r['inputs'].get(k,0)];rs.append((r['id'],inp,delta))
 root=tuple(f['stock'].get(k,0) for k in keys);nodes=[root];parents=[(-1,None)];depth=[0];seen={root:0};pos=0;h=f['stock']['F'];ti=idx[f['target']]
 while pos<len(nodes):
  if time_limit_seconds is not None and pos%256==0 and time.perf_counter()-started>=time_limit_seconds:return dict(truth="UNKNOWN",states=len(nodes),method="oracle_time_limit",horizon=h)
  state=nodes[pos]
  if state[ti]>=f['amount']:
   path=[];j=pos
   while parents[j][0]>=0:jprev,rid=parents[j];path.append(rid);j=jprev
   return dict(truth='SAT',states=len(nodes),expanded=pos,witness=S(*(B(k) for k in reversed(path))),method='finite_bfs_fuel_dominance',horizon=h)
  if depth[pos]<h:
   for rid,ins,delta in rs:
    if all(state[k]>=q for k,q in ins):
     nxt=list(state)
     for k,q in delta:nxt[k]+=q
     nxt=tuple(nxt)
     if nxt not in seen:
      seen[nxt]=len(nodes);nodes.append(nxt);parents.append((pos,rid));depth.append(depth[pos]+1)
      if len(nodes)>cap:return dict(truth='UNKNOWN',states=len(nodes),method='oracle_state_cap',horizon=h)
  pos+=1
 digest=hashlib.sha256(json.dumps(sorted(seen),separators=(',',':')).encode()).hexdigest()
 return dict(truth='UNSAT',states=len(nodes),expanded=pos,reachable_sha256=digest,method='finite_bfs_fuel_dominance',horizon=h)

def hypermatch(seed,n=None):
 rng=random.Random(seed);n=n or rng.choice([5,6,8,10,12,16,20,24]);d=rng.choice([3,4]);perms=[list(range(n)) for _ in range(d-1)]
 for p in perms:rng.shuffle(p)
 planted=[tuple([i]+[p[i] for p in perms]) for i in range(n)];edges=set(planted)
 for _ in range(n*rng.randint(2,6)):edges.add(tuple(rng.randrange(n) for _ in range(d)))
 edges=sorted(edges);rng.shuffle(edges);rs=[rec('e'+str(i),{f'G{j}.{x}':1 for j,x in enumerate(e)},{'GOAL':1}) for i,e in enumerate(edges)]
 f=fixture('x',rs,{f'G{j}.{i}':1 for j in range(d) for i in range(n)},amount=n,dag=True);f.update(truth='SAT',witness=S(*(B('e'+str(edges.index(e))) for e in planted)),parameters={'n':n,'dimensions':d});return f

def latin(seed,n=None):
 rng=random.Random(seed);n=n or rng.choice([4,5,6,7,8,9,10,11,12,13,15,17]);edges=[]
 for i in range(n):
  for j in range(n):
   if (n%2 and i==j) or rng.random()<rng.uniform(.3,.8):edges.append((i,j,(i+j)%n))
 rng.shuffle(edges);rs=[rec('cell'+str(q),{f'R{i}':1,f'C{j}':1,f'S{k}':1},{'GOAL':1}) for q,(i,j,k) in enumerate(edges)]
 f=fixture('x',rs,{f'{p}{i}':1 for p in 'RCS' for i in range(n)},amount=n,dag=True);f['parameters']={'order':n,'cyclic_symbol':True}
 if n%2:f.update(truth='SAT',witness=S(*(B('cell'+str(edges.index((i,i,(2*i)%n)))) for i in range(n))))
 else:f.update(truth='UNSAT',proof={'type':'cyclic_latin_parity','n':n})
 return f

def pebble(seed,n=None):
 rng=random.Random(seed);n=n or rng.randint(7,14);rs=[];pred=[]
 for i in range(n):
  p=[] if i<2 else sorted(rng.sample(range(i),rng.randint(1,min(3,i))))
  pred.append(p)
  ins={'F':1,'W':1,'OFF'+str(i):1,**{'P'+str(j):1 for j in p}};outs={'P'+str(i):1,**{'P'+str(j):1 for j in p}}
  rs.append(rec('build'+str(i),ins,outs));rs.append(rec('erase'+str(i),{'F':1,'P'+str(i):1},{'OFF'+str(i):1,'W':1}))
 rng.shuffle(rs);stock={'F':rng.randint(n,4*n),'W':rng.randint(2,min(n,6)),**{'OFF'+str(i):1 for i in range(n)}}
 f=fixture('x',rs,stock,'P'+str(n-1));f['parameters']={'nodes':n,'parents':pred,'workspace':stock['W']};return f

def rendezvous(seed,n=None):
 rng=random.Random(seed);n=n or rng.randint(3,5);sizes=[rng.randint(3,5) for _ in range(n)];state=[0]*n;rs=[];length=rng.randint(5,15);path=[]
 def add(ins,outs):
  rid='t'+str(len(rs));rs.append(rec(rid,{'F':1,**{f'A{i}.{v}':1 for i,v in ins.items()}},{f'A{i}.{v}':1 for i,v in outs.items()}));return rid
 for step in range(length):
  agents=rng.sample(range(n),rng.randint(1,min(n,3)));ins={i:state[i] for i in agents};outs={i:rng.randrange(sizes[i]) for i in agents}
  if ins==outs:outs[agents[0]]=(outs[agents[0]]+1)%sizes[agents[0]]
  path.append(add(ins,outs))
  for i,v in outs.items():state[i]=v
 for _ in range(rng.randint(n*2,n*6)):
  agents=rng.sample(range(n),rng.randint(1,min(n,3)));ins={i:rng.randrange(sizes[i]) for i in agents};outs={i:rng.randrange(sizes[i]) for i in agents}
  if ins!=outs:add(ins,outs)
 rs.append(rec('finish',{'F':1,**{f'A{i}.{v}':1 for i,v in enumerate(state)}},{'GOAL':1}));rng.shuffle(rs)
 f=fixture('x',rs,{'F':rng.randint(max(2,length//2),length+4),**{f'A{i}.0':1 for i in range(n)}});f['parameters']={'automata':n,'states':sizes,'planted_walk_length':length+1};return f

def forkjoin(seed,n=None):
 rng=random.Random(seed);n=n or rng.randint(2,6);rs=[];stock={'CAT':rng.randint(1,3),'A':rng.randint(1,2),'B':rng.randint(1,2),'F':4*n+1};steps=[]
 for i in range(n):
  stock[f'U{i}']=1
  rs.extend([rec(f'split{i}',{'F':1,'CAT':1,f'U{i}':1},{f'L{i}':1,f'R{i}':1}),rec(f'left{i}',{'F':1,f'L{i}':1,'A':1},{f'DL{i}':1}),rec(f'right{i}',{'F':1,f'R{i}':1,'B':1},{f'DR{i}':1}),rec(f'join{i}',{'F':1,f'DL{i}':1,f'DR{i}':1},{'CAT':1,'A':1,'B':1,f'D{i}':1})]);steps.extend(B(x+str(i)) for x in ['split','left','right','join'])
 # Cross-coupled options: swapping half-completed branches or alternative borrowing.
 for j in range(rng.randint(2,2*n+3)):
  a,b=rng.sample(range(n),2);side=rng.choice(['L','R','DL','DR']);rs.append(rec('swap'+str(j),{'F':1,side+str(a):1},{side+str(b):1}))
 for i in rng.sample(range(n),rng.randint(1,n)):
  rs.append(rec(f'leftAlt{i}',{'F':1,f'L{i}':1,'B':1},{f'DLB{i}':1}))
  rs.append(rec(f'joinAlt{i}',{'F':1,f'DLB{i}':1,f'DR{i}':1},{'CAT':1,'B':2,f'D{i}':1}))
 rs.append(rec('finish',{'F':1,**{f'D{i}':1 for i in range(n)}},{'GOAL':1}));steps.append(B('finish'));rng.shuffle(rs)
 f=fixture('x',rs,stock);f.update(truth='SAT',witness=S(*steps),parameters={'branches':n,'split_join':True});return f

GENERATORS={'hypermatch':hypermatch,'latin':latin,'pebble':pebble,'rendezvous':rendezvous,'forkjoin':forkjoin}
def verify(f):
 if f['truth']=='SAT':return base_check(f)
 if f.get('proof',{}).get('type')=='cyclic_latin_parity':
  n=f['proof']['n'];assert n%2==0 and f['target']=='GOAL' and f['amount']==n
  assert f['stock']=={f'{p}{i}':1 for p in 'RCS' for i in range(n)}
  for r in f['recipes']:
   assert r['outputs']=={'GOAL':1} and len(r['inputs'])==3 and set(r['inputs'].values())=={1}
   d={p:next(int(k[1:]) for k in r['inputs'] if k.startswith(p)) for p in 'RCS'};assert d['S']==(d['R']+d['C'])%n
  assert (sum(range(n))*2-sum(range(n)))%n!=0
  return {'method':'integer_modular_conservation','modulus':n,'contradiction_remainder':n//2}
 b=bfs(f);assert b['truth']=='UNSAT' and b['reachable_sha256']==f['oracle']['reachable_sha256'];return b

def save(f,folder):
 if 'truth' not in f:
  oracle=bfs(f);f['truth']=oracle['truth'];f['oracle']={k:v for k,v in oracle.items() if k!='witness'}
  if 'witness' in oracle:f['witness']=oracle['witness']
 if f['truth']=='UNKNOWN':return None
 cert=verify(f)
 if 'F' in f['stock']:f['count_upper']={r['id']:f['stock']['F'] for r in f['recipes']};f['bound_certificate']={'fuel_key':'F','fuel_stock':f['stock']['F']}
 else:f['count_upper']={r['id']:1 for r in f['recipes']};f['bound_certificate']={'each_transition_consumes_initial_unit_resource_never_produced':True}
 folder.mkdir(exist_ok=True,parents=True);(folder/(f['name']+'.json')).write_text(json.dumps(f,separators=(',',':')))
 return dict(case=f['name'],truth=f['truth'],family=f['family'],recipes=len(f['recipes']),certificate=cert,topology_hash=fingerprint(f),weighted_hash=fingerprint(f,True))

def generate(count=16):
 folder=R/'cases';records=[];seen=set();rejected=Counter()
 baseline=R/'baseline_fingerprints.json'
 if baseline.exists():seen.update(json.loads(baseline.read_text())['topology_hashes'])
 for family,gen in GENERATORS.items():
  accepted=0;trial=0
  while accepted<count:
   seed=1700000+sum(map(ord,family))*10000+trial;trial+=1;f=gen(seed);f.update(name=f'{family}_{accepted:05d}',family=family,random_seed=seed)
   h=fingerprint(f)
   if h in seen:rejected[family+'_topology_duplicate']+=1;continue
   row=save(f,folder)
   if row is None:rejected[family+'_oracle_limit']+=1;continue
   seen.add(h);records.append(row);accepted+=1
   if accepted%128==0:print('GENERATE',family,accepted,flush=True)
 (R/'certificates.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records));(R/'generation_summary.json').write_text(json.dumps({'accepted':len(records),'rejected':dict(rejected),'families':dict(Counter(r['family'] for r in records))},indent=2))
 print('GENERATED',len(records),dict(Counter((r['family'],r['truth']) for r in records)),dict(rejected),flush=True)
if __name__=='__main__':generate(int(sys.argv[1]) if len(sys.argv)>1 else 16)
