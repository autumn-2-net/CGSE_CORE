from pathlib import Path
import sys,json,random,time
root=Path.cwd().resolve();area=root/'.local/support-completion-20261007'
sys.path.insert(0,str(root/'.local/oracle-front-20261007'))
from oracle_compare import prepare,model,scip,execute,exact_check,cp_model,ortools,pyscipopt,Counter
from finite_oracles import explore

def native(data,target,amount,cap):
 parsed,producers=prepare(data);rs,rows=model(parsed,producers,data['stock'],set(),target,amount)
 cp=cp_model.CpModel();xs=[cp.new_int_var(0,cap,f'x{i}') for i in range(len(rs))]
 for terms,b,_ in rows:cp.add(sum(a*xs[i] for i,a in terms.items())<=b)
 solver=cp_model.CpSolver();solver.parameters.max_time_in_seconds=5;solver.parameters.num_search_workers=1
 status=solver.solve(cp);assert status in (cp_model.OPTIMAL,cp_model.INFEASIBLE),solver.status_name(status)
 if status==cp_model.OPTIMAL:assert exact_check(rows,[solver.value(x) for x in xs])
 sc,counts=scip(rows,len(rs),5)
 assert sc['status'] in ('optimal','infeasible'),sc
 if counts is not None:assert exact_check(rows,counts)
 return solver.status_name(status),sc,rs,counts

randomizer=random.Random(202610071933);summary={};all_cases=[]
for family,size in [('finite',1024),('cover',512)]:
 out=area/(f'fresh-{family}-cases.json');assert not out.exists();cases=[];stats=Counter();started=time.monotonic()
 for case in range(size):
  r=randomizer
  if family=='finite':
   n=3+case%2;target=n-1;amount=1+case%3;initial=tuple(r.randrange(3) if k!=target else 0 for k in range(n));rs=[];recipes=[]
   for i in range(5+case%6):
    inputs=Counter(r.randrange(n) for _ in range(1+r.randrange(4)));outputs=Counter(r.randrange(n) for _ in range(1+r.randrange(sum(inputs.values()))))
    rs.append(dict(in_=dict(inputs),out=dict(outputs)))
    recipes.append(dict(id=f'r{i}',slots=[dict(key=f'p{k}',amount=v) for k,v in inputs.items()],outputs={f'p{k}':v for k,v in outputs.items()}))
   simple=[{'in':q['in_'],'out':q['out']} for q in rs]
   states,fired,witness=explore(simple,initial,target,amount);sat=witness is not None
   data=dict(recipes=recipes,stock={f'p{k}':v for k,v in enumerate(initial) if v},external=[]);target=f'p{target}'
   cp,sc,_,_=native(data,target,amount,max(1,len(states)))
   if sat:assert cp=='OPTIMAL' and sc['status']=='optimal'
   fired=[f'r{i}' for i in sorted(fired)]
  else:
   n=12+case%25;m=6+case%13;resource_count=2+case%5;chosen=r.sample(range(n),max(3,n//4));coverage=[];costs=[]
   for i in range(n):coverage.append(set(r.sample(range(m),2+r.randrange(min(m-1,5)))));costs.append([1+r.randrange(20) for _ in range(resource_count)])
   for j in range(m):coverage[chosen[j%len(chosen)]].add(j)
   factor=[.55,.8,1,1.15][case%4];stock={f'budget{k}':max(1,int(sum(costs[i][k] for i in chosen)*factor)) for k in range(resource_count)};recipes=[]
   for i in range(n):
    stock[f'permit{i}']=1;recipes.append(dict(id=f'pick{i}',slots=[dict(key=f'permit{i}',amount=1)]+[dict(key=f'budget{k}',amount=a) for k,a in enumerate(costs[i])],outputs={f'goal{j}':1 for j in sorted(coverage[i])}))
   recipes.append(dict(id='finish',slots=[dict(key=f'goal{j}',amount=1) for j in range(m)],outputs={'T':1}))
   data=dict(recipes=recipes,stock=stock,external=[]);target='T';amount=1;fired=[]
   cp,sc,rs,counts=native(data,target,amount,n);sat=cp=='OPTIMAL';assert sc['status']==('optimal' if sat else 'infeasible')
   if sat:assert counts is not None and execute(rs,counts,stock,set(),'T',1)['status']=='EXECUTABLE'
  expected='FEASIBLE' if sat else 'INFEASIBLE';stats[expected]+=1
  cases.append(dict(id=f'fresh-{family}-{case}',**data,target=target,amount=amount,expected=expected,fired=fired,cp_sat=cp,scip=sc))
 out.write_text(json.dumps(cases),'utf8');summary[family]=dict(cases=size,expected=dict(stats),seconds=time.monotonic()-started);print(family,summary[family],flush=True)
(area/'fresh-oracle-summary.json').write_text(json.dumps(dict(versions=dict(ortools=ortools.__version__,pyscipopt=pyscipopt.__version__),seed=202610071933,results=summary),indent=2),'utf8')
