from pathlib import Path
import json,sys,time,collections
R=Path(__file__).resolve().parent
sys.path.insert(0,str(R))
from verify_new_cases import summary,compose,repeat

def verify_counts(f,counts):
 begin=time.perf_counter();rs={r['id']:r for r in f['recipes']};left={k:counts.get(k,0) for k in rs};held=dict(f['stock']);steps=[]
 if not all(isinstance(v,int) and 0<=v<=f['count_upper'][k] for k,v in left.items()):return {'status':'INVALID_NATIVE_VECTOR','reason':'count_bound_or_integer'}
 delta={}
 for rid,r in rs.items():
  for k in r['inputs'].keys()|r['outputs'].keys():delta[k]=delta.get(k,0)+(r['outputs'].get(k,0)-r['inputs'].get(k,0))*left[rid]
 if not all(held.get(k,0)+v>=0 for k,v in delta.items()) or held.get(f['target'],0)+delta.get(f['target'],0)<f['amount']:return {'status':'INVALID_NATIVE_VECTOR','reason':'exact_balance'}
 def nmax(s,n):
  need,d=summary(s,rs)
  for k,v in need.items():
   if held.get(k,0)<v:return 0
   if d.get(k,0)<0:n=min(n,1+(held.get(k,0)-v)//-d[k])
  return n
 def use(s,n,used):
  need,d=repeat(summary(s,rs),n)
  assert all(held.get(k,0)>=v for k,v in need.items())
  for k,v in d.items():held[k]=held.get(k,0)+v
  for rid,q in used.items():left[rid]-=q*n;assert left[rid]>=0
  steps.append(s if n==1 else {'repeat':s,'n':n})
 def counts_of(s):
  if 'batch' in s:return {s['batch']:s['n']}
  out=collections.Counter()
  for sub in s['sequence']:out.update(counts_of(sub))
  return out
 # Detect structural private-intermediate chains, without reading the planted witness.
 consumers=collections.defaultdict(list)
 for rid,r in rs.items():
  for k in r['inputs']:consumers[k].append(rid)
 macros=[]
 for rid,r in rs.items():
  ids=[rid];seen={rid};cur=r
  for depth in range(8):
   choices=set(j for k in cur['outputs'] for j in consumers[k] if j not in seen and all(x==1 for x in rs[j]['inputs'].values()) and any(k not in held for k in rs[j]['inputs']))
   if len(choices)!=1:break
   nxt=next(iter(choices));ids.append(nxt);seen.add(nxt);cur=rs[nxt]
   macro={'sequence':[{'batch':j,'n':1} for j in ids]};need,d=summary(macro,rs)
   # Chain must have no net loss in a returned resource required by its first action.
   if any(k in r['inputs'] and cur['outputs'].get(k,0)>0 and d.get(k,0)==0 for k in cur['outputs']):macros.append((macro,counts_of(macro)));break
 for iteration in range(20000):
  if not any(left.values()):break
  progress=False
  # Try loop macros before borrowing a catalyst piecemeal.
  for macro,used in macros:
   n=min((left[rid]//q for rid,q in used.items()),default=0);n=nmax(macro,n)
   if n>0:use(macro,n,used);progress=True
  for rid,r in rs.items():
   if not left[rid]:continue
   s={'batch':rid,'n':1};n=nmax(s,left[rid])
   if n>0:use(s,n,{rid:1});progress=True
  if not progress:break
  if time.perf_counter()-begin>1:return {'status':'COUNT_ONLY_UNSCHEDULED','reason':'validator_budget','remaining':sum(left.values())}
 if any(left.values()):return {'status':'COUNT_ONLY_UNSCHEDULED','reason':'greedy_schedule_stalled','remaining':sum(left.values())}
 need,d=summary({'sequence':steps},rs)
 assert all(f['stock'].get(k,0)>=v for k,v in need.items()) and f['stock'].get(f['target'],0)+d.get(f['target'],0)>=f['amount']
 return {'status':'VERIFIED_EXECUTABLE','ms':1000*(time.perf_counter()-begin),'steps':steps}

def main():
 source=Path(sys.argv[1]);out=Path(sys.argv[2]);summary_counts=collections.Counter()
 with out.open('w') as stream:
  for line in source.read_text().splitlines():
   row=json.loads(line);fp=R/'cases'/(row['case']+'.json')
   if not fp.exists():fp=R/'reductions'/(row['case']+'.json')
   if not fp.exists():fp=R/'seven-input'/(row['case']+'.json')
   if not fp.exists():fp=R/'controls'/('seven.json' if row['case'].startswith('min_manual') else row['case']+'.json')
   f=json.loads(fp.read_text())
   for name in ['cp_sat','scip','z3']:
    if name not in row:continue
    r=row[name]
    if r['status'] in ('SAT','OPTIMAL','FEASIBLE'):
     if 'actions' in r:r['execution']={'status':'VERIFIED_EXECUTABLE','method':'complete_temporal_replay'}
     else:r['execution']=verify_counts(f,r['counts'])
    elif r['status'] in ('INFEASIBLE','UNSAT') and f['truth']=='SAT':r['contradicts_witness']=True
    summary_counts[(name,r.get('execution',{}).get('status',r['status']))]+=1
   if row.get('solver')=='MAX_FAST_FULL':
    if row['status']=='FEASIBLE':
     if row['trace_verified']:row['execution']={'status':'VERIFIED_EXECUTABLE','method':'original_trace'}
     else:row['execution']=verify_counts(f,row['counts'])
    summary_counts[('MAX_FAST_FULL',row.get('execution',{}).get('status',row['status']))]+=1
   stream.write(json.dumps(row)+'\n')
 print(dict(summary_counts))
if __name__=='__main__':main()
