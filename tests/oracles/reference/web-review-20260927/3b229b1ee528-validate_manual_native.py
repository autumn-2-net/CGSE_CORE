"""Exact balance plus a generic count-fixed schedule; failure is UNKNOWN."""
from pathlib import Path
import json,time,sys,collections
ROOT=Path(__file__).resolve().parent
def replay(f,counts):
 begin=time.perf_counter();rs=f['recipes'];by={r['id']:r for r in rs};left={r['id']:counts.get(r['id'],0) for r in rs};held=dict(f['stock']);actions=[]
 assert all(isinstance(v,int) and 0<=v<=f['count_upper'][k] for k,v in left.items())
 final=dict(held)
 for r in rs:
  for k in r['inputs'].keys()|r['outputs'].keys():final[k]=final.get(k,0)+(r['outputs'].get(k,0)-r['inputs'].get(k,0))*left[r['id']]
 assert min(final.values())>=0 and final.get(f['target'],0)>=f['amount']
 def limit(r,n):
  for k,v in r['inputs'].items():
   if held.get(k,0)<v:return 0
   loss=v-r['outputs'].get(k,0)
   if loss>0:n=min(n,(held.get(k,0)-v)//loss+1)
  return n
 def act(r,n):
  assert n>0 and limit(r,left[r['id']])>=n
  left[r['id']]-=n;actions.append((r['id'],n))
  for k in r['inputs'].keys()|r['outputs'].keys():held[k]=held.get(k,0)+(r['outputs'].get(k,0)-r['inputs'].get(k,0))*n
 if f['dag']:
  for rid in f['bound_certificate']['recipe_topological_order']:
   n=left[rid]
   if n:act(by[rid],n)
 else:
  # Favor completion of existing intermediates, then starts whose continuation
  # is actually funded. Delay an input-identical resource-destroying end.
  dominated={r['id'] for r in rs if any(s is not r and s['inputs']==r['inputs'] and s['outputs']!=r['outputs'] and all(s['outputs'].get(k,0)>=v for k,v in r['outputs'].items()) for s in rs)}
  producers=collections.defaultdict(set)
  for r in rs:
   for k in r['outputs']:producers[k].add(r['id'])
  starters={r['id'] for r in rs if any(k not in producers for k in r['inputs']) and len(r['outputs'])==1}
  for step in range(20000):
   if not any(left.values()):break
   ready=[r for r in rs if left[r['id']]>0 and limit(r,1)>0]
   free=[r for r in ready if r['id'] not in starters and r['id'] not in dominated]
   if free:r=free[0];act(r,limit(r,left[r['id']]));continue
   chosen=None
   for allow_loss in [False,True]:
    for r in ready:
     if r['id'] not in starters:continue
     intermediate=next(iter(r['outputs']))
     for end in rs:
      if not left[end['id']] or intermediate not in end['inputs'] or (end['id'] in dominated and not allow_loss):continue
      post={k:held.get(k,0)-r['inputs'].get(k,0)+r['outputs'].get(k,0) for k in held.keys()|r['inputs'].keys()|r['outputs'].keys()}
      if all(post.get(k,0)>=v for k,v in end['inputs'].items()):chosen=(r,end);break
     if chosen:break
    if chosen:break
   if chosen:act(chosen[0],1);act(chosen[1],1);continue
   burns=[r for r in ready if r['id'] in dominated]
   if burns:act(burns[0],limit(burns[0],left[burns[0]['id']]));continue
   break
 if held.get(f['target'],0)<f['amount']:return {'status':'COUNT_ONLY_UNSCHEDULED','ms':1000*(time.perf_counter()-begin),'remaining':sum(left.values())}
 # Second exact replay, independent of the heuristic's mutable inventory.
 stock=dict(f['stock'])
 for rid,n in actions:
  r=by[rid]
  for k,v in r['inputs'].items():assert stock.get(k,0)>=v+max(0,v-r['outputs'].get(k,0))*(n-1)
  for k in r['inputs'].keys()|r['outputs'].keys():stock[k]=stock.get(k,0)+(r['outputs'].get(k,0)-r['inputs'].get(k,0))*n
 assert stock.get(f['target'],0)>=f['amount']
 return {'status':'VERIFIED_EXECUTABLE','ms':1000*(time.perf_counter()-begin),'actions':actions,'unused_count_sum':sum(left.values())}
if __name__=='__main__':
 summary=collections.Counter()
 with Path(sys.argv[2]).open('w') as out:
  for line in Path(sys.argv[1]).read_text().splitlines():
   r=json.loads(line);f=json.loads((ROOT/'manual-models'/(r['case']+'.json')).read_text())
   for k in ['cp_sat','scip','z3']:
    if k not in r:continue
    v=r[k]
    if v['status'] in ['SAT','OPTIMAL','FEASIBLE']:
     try:v['execution']=replay(f,v['counts'])
     except AssertionError as e:v['execution']={'status':'INVALID_NATIVE_VECTOR','detail':str(e)}
    summary[(k,v.get('execution',{}).get('status',v['status']))]+=1
   out.write(json.dumps(r)+'\n')
 print(dict(summary))
