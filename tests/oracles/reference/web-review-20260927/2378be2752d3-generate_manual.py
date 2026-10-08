from pathlib import Path
import random,json,copy,sys
ROOT=Path(__file__).resolve().parent

from verify_new_cases import check
def batch(r,n=1):return {'batch':r,'n':n}
def seq(xs):return {'sequence':xs}
def rec(r,ins,outs):return {'id':r,'inputs':ins,'outputs':outs}
def make(n,c,dims,mode,accounts,seed,variant):
 rng=random.Random(seed);rs=[];stock={};need={};actions=[];returns=[];burns=[];fuel={};cats={};minimum={}
 weights_proof={'U0':1,'D0':1};caps=[]
 for i in range(n):
  cap=(1000003+2*i+seed%17) if mode=='large' else rng.randint(1,3)
  caps.append(cap);stock[f'U{i}']=cap;need[f'D{i}']=cap
  # All allocations are generated independently of the solvers.
  cuts=sorted([0,cap]+[rng.randint(0,cap) for _ in range(c-1)]);alloc=[cuts[j+1]-cuts[j] for j in range(c)]
  rng.shuffle(alloc);weights=[rng.randint(1,100000 if variant=='wide_weights' else 1000) for _ in range(dims)]
  for j in range(c):
   rid=f'r{i}_{j}';outs={f'D{i}':1,**{f'X{j}_{d}':w for d,w in enumerate(weights)}}
   for k,v in outs.items():
    if k.startswith('X'):need[k]=need.get(k,0)+v*alloc[j]
   if mode!='fuel':
    rs.append(rec(rid,{f'U{i}':1},outs))
    if alloc[j]:actions.append(batch(rid,alloc[j]))
   else:
    a=rng.randrange(accounts);b=rng.randrange(accounts);cat=f'CAT{a}';fk=f'FUEL{b}';cu=rng.randint(1,5);price=rng.randint(1,13);pending=rid+'.P'
    rs.append(rec(rid+'.start',{f'U{i}':1,cat:cu},{pending:1}))
    rs.append(rec(rid+'.return',{pending:1,fk:price},{**outs,cat:cu}));rs.append(rec(rid+'.burn',{pending:1},outs))
    if variant=='aliases':rs.append(rec(rid+'.alias',{f'U{i}':1,cat:cu},{pending:1}))
    if variant=='entry' and (i+j)%4==0:stock[pending]=1
    if i==0:weights_proof[pending]=1
    burned=rng.randrange(alloc[j]+1);returned=alloc[j]-burned
    if returned:
     returns.append({'repeat':seq([batch(rid+'.start'),batch(rid+'.return')]),'n':returned})
     minimum[cat]=max(minimum.get(cat,0),cu);fuel[fk]=fuel.get(fk,0)+returned*price
    if burned:
     burns.append({'repeat':seq([batch(rid+'.start'),batch(rid+'.burn')]),'n':burned})
     cats[cat]=cats.get(cat,0)+burned*cu
 if mode=='fuel':
  for k in cats.keys()|minimum.keys():stock[k]=max(cats.get(k,0),minimum.get(k,0))
  stock.update(fuel);actions=returns+burns
 need={k:v for k,v in need.items() if v};rs.append(rec('finish',need,{'GOAL':1}));actions.append(batch('finish'))
 if variant in ['shuffle','aliases','entry']:rng.shuffle(rs)
 weights_proof['GOAL']=caps[0]
 f={'name':f'manual_{mode}_n{n}_c{c}_d{dims}_a{accounts}_s{seed}_{variant}','family':mode,'target':'GOAL','amount':1,'recipes':rs,'stock':stock,'dag':mode!='fuel','truth':'SAT','witness':seq(actions)}
 check(f)
 return f,weights_proof
def main():
 out=ROOT/'manual-input';out.mkdir(exist_ok=True);cert=[];idx=0
 def put(f):
  nonlocal idx
  proof=check(f);(out/(f['name']+'.json')).write_text(json.dumps(f,separators=(',',':')));cert.append({'case':f['name'],'truth':f['truth'],'certificate':proof});idx+=1
 for n in [16,24,40,64]:
  for c in [2,3]:
   for dims in [1,3]:
    for mode in ['small','large']:
     for seed in [43,107,313]:
      f,w=make(n,c,dims,mode,1,seed,'wide_weights' if dims==1 else 'shuffle');put(f)
      if seed==43:
       g=copy.deepcopy(f);g['name']+='_short1';g['truth']='UNSAT';g.pop('witness');g['stock']['U0']-=1
       g['invariant']={'weights':w,'initial':g['stock']['U0'],'required':w['GOAL']};put(g)
 for n in [4,8,12,24]:
  for c in [2,3]:
   for accounts in [1,2,3]:
    for seed in [43,107,313]:
     for variant in ['shuffle','aliases','entry']:
      f,w=make(n,c,2,'fuel',accounts,seed,variant);put(f)
 # Large residual choice models beyond the dense-branch thresholds.
 for n in [96,128]:
  for mode in ['small','large']:
   for dims in [1,3,9]:
    f,w=make(n,2,dims,mode,1,719,'shuffle');put(f)
 (ROOT/'manual-certificates.jsonl').write_text('\n'.join(json.dumps(x) for x in cert)+'\n');print('MANUAL_CASES',idx)
if __name__=='__main__':main()
