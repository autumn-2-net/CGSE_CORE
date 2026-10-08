"""Mutate newly observed hard seeds; no production solver participates in truth generation."""
from generate_round3 import *
from collections import Counter
R=Path(__file__).resolve().parent
FAMILIES=list(GENERATORS)
def loadrows(folder):return [json.loads(l) for l in (R/folder/'results.jsonl').read_text().splitlines()]
def main():
 balanced=loadrows('cgse-balanced');screen=loadrows('cgse-screen');seeds=[]
 for family in FAMILIES:
  stable=[r for r in balanced if r['case'].startswith(family+'_') and r['cgse']['status'] in ['TIMEOUT','SEARCH_LIMIT','UNKNOWN']]
  initial=[r for r in screen if r['case'].startswith(family+'_') and r['cgse']['status'] in ['TIMEOUT','SEARCH_LIMIT','UNKNOWN']]
  selected=sorted(stable or initial,key=lambda r:(r['recipes'],r['case']))[:4]
  for r in selected:seeds.append({'case':r['case'],'family':family,'seed_observation_budget_ms':3000 if stable else 100})
 (R/'evolved_seed_origins.json').write_text(json.dumps(seeds,indent=2))
 seen=set(json.loads((R/'baseline_fingerprints.json').read_text())['topology_hashes'])
 for p in ['certificates.jsonl','manual_certificates.jsonl']:
  seen.update(json.loads(l)['topology_hash'] for l in (R/p).read_text().splitlines())
 records=[];trial=0;reject=Counter()
 while len(records)<512:
  origin=seeds[trial%len(seeds)];f=json.loads((R/'cases'/(origin['case']+'.json')).read_text());family=origin['family'];f.pop('oracle',None);rng=random.Random(3100000+trial);trial+=1
  rs=f['recipes'];existing={(tuple(sorted(r['inputs'].items())),tuple(sorted(r['outputs'].items()))) for r in rs}
  def add(ins,outs):
   shape=(tuple(sorted(ins.items())),tuple(sorted(outs.items())))
   if shape not in existing:rs.append(rec('mutation'+str(len(rs)),ins,outs));existing.add(shape)
  for _ in range(1+trial%4):
   if family=='hypermatch':
    n=f['parameters']['n'];d=f['parameters']['dimensions'];add({f'G{j}.{rng.randrange(n)}':1 for j in range(d)},{'GOAL':1})
   elif family=='latin':
    n=f['parameters']['order'];i,j=rng.randrange(n),rng.randrange(n);add({f'R{i}':1,f'C{j}':1,f'S{(i+j)%n}':1},{'GOAL':1})
   elif family=='pebble':
    n=f['parameters']['nodes'];i=rng.randrange(n)
    if rng.random()<.6:
     ps=rng.sample([j for j in range(n) if j!=i],rng.randint(0,min(3,n-1)))
     add({'F':1,'W':1,'OFF'+str(i):1,**{'P'+str(j):1 for j in ps}},{'P'+str(i):1,**{'P'+str(j):1 for j in ps}})
    else:
     j=rng.choice([j for j in range(n) if j!=i]);add({'F':1,'P'+str(i):1,'P'+str(j):1},{'OFF'+str(i):1,'OFF'+str(j):1,'W':2})
   elif family=='rendezvous':
    sizes=f['parameters']['states'];agents=rng.sample(range(len(sizes)),rng.randint(1,min(3,len(sizes))))
    add({'F':1,**{f'A{i}.{rng.randrange(sizes[i])}':1 for i in agents}},{f'A{i}.{rng.randrange(sizes[i])}':1 for i in agents})
   else:
    n=f['parameters']['branches'];i,j=rng.sample(range(n),2)
    if rng.random()<.5:add({'F':1,f'DL{i}':1,f'DR{j}':1},{'CAT':1,'A':1,'B':1,f'D{i}':1})
    else:add({'F':1,f'L{i}':1,f'R{j}':1},{f'L{j}':1,f'R{i}':1})
  rng.shuffle(rs)
  if f['truth']=='UNSAT' and family in ['pebble','rendezvous']:f.pop('truth');f.pop('oracle',None)
  f.update(name=f'evolved_{family}_{len(records):05d}',family='evolved_'+family,seed_origin=origin,random_seed=3100000+trial-1)
  h=fingerprint(f)
  if h in seen:reject['topology_duplicate']+=1;continue
  row=save(f,R/'mutants')
  if row is None:reject['oracle_limit']+=1;continue
  seen.add(h);records.append(row)
 (R/'evolved_certificates.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
 (R/'evolved_generation_summary.json').write_text(json.dumps({'accepted':len(records),'rejected':dict(reject),'truth':dict(Counter(r['truth'] for r in records)),'families':dict(Counter(r['family'] for r in records))},indent=2))
 models=R/'mutant-models';models.mkdir(exist_ok=True)
 for p in (R/'mutants').glob('*.json'):
  f=json.loads(p.read_text());g={k:f[k] for k in ['name','target','amount','stock','recipes','dag','count_upper','bound_certificate']};(models/p.name).write_text(json.dumps(g,separators=(',',':')))
 print('EVOLVED',len(records),'SEEDS',len(seeds),'REJECT',dict(reject),flush=True)
if __name__=='__main__':main()
