from oracle_compare import *
import random

def generate():
    cases=[];summary=Counter();rng=random.Random(1072026)
    for case in range(384):
        n=12+case%21;m=6+case%13;resources=2+case%5
        chosen=rng.sample(range(n),max(3,n//4));coverage=[];costs=[]
        for i in range(n):
            coverage.append(set(rng.sample(range(m),2+rng.randrange(min(m-1,5)))))
            costs.append([1+rng.randrange(20) for _ in range(resources)])
        # Plant a cover; vary only the shared resource envelope around it.
        for j in range(m):coverage[chosen[j%len(chosen)]].add(j)
        factor=[.55,.8,1,1.15][case%4]
        stock={f'budget{k}':max(1,int(sum(costs[i][k] for i in chosen)*factor)) for k in range(resources)}
        recipes=[]
        for i in range(n):
            stock[f'permit{i}']=1
            recipes.append(dict(id=f'pick{i}',slots=[dict(key=f'permit{i}',amount=1)]+[dict(key=f'budget{k}',amount=a) for k,a in enumerate(costs[i])],outputs={f'goal{j}':1 for j in sorted(coverage[i])}))
        recipes.append(dict(id='finish',slots=[dict(key=f'goal{j}',amount=1) for j in range(m)],outputs={'T':1}))
        data=dict(recipes=recipes,stock=stock,external=[])
        parsed,producers=prepare(data);rs,rows=model(parsed,producers,stock,set(),'T',1)
        # Private permits bound each choice to one firing. All goal producers
        # precede the finishing recipe, so the balance model is sufficient here.
        cp=cp_model.CpModel();xs=[cp.new_int_var(0,n,f'x{i}') for i in range(len(rs))]
        for t,b,_ in rows:cp.add(sum(a*xs[i] for i,a in t.items())<=b)
        solver=cp_model.CpSolver();solver.parameters.max_time_in_seconds=5;solver.parameters.num_search_workers=1
        begin=time.perf_counter();st=solver.solve(cp);seconds=time.perf_counter()-begin
        assert st in (cp_model.OPTIMAL,cp_model.INFEASIBLE),(case,solver.status_name(st))
        sat=st==cp_model.OPTIMAL
        if sat:
            counts=[solver.value(x) for x in xs];assert exact_check(rows,counts)
            assert execute(rs,counts,stock,set(),'T',1)['status']=='EXECUTABLE'
        sc,scounts=scip(rows,len(rs),5)
        assert sc['status']==('optimal' if sat else 'infeasible'),(case,sc)
        if sat:assert scounts is not None and execute(rs,scounts,stock,set(),'T',1)['status']=='EXECUTABLE'
        expected='FEASIBLE' if sat else 'INFEASIBLE';summary[expected]+=1
        cases.append(dict(id=f'cover-{case}',**data,target='T',amount=1,expected=expected,fired=[],
                          cp_sat=solver.status_name(st),cp_seconds=seconds,scip=sc))
        if (case+1)%64==0:print(case+1,dict(summary),flush=True)
    (AREA/'cover-cases.json').write_text(json.dumps(cases),'utf8')
    (AREA/'cover-oracle-summary.json').write_text(json.dumps(dict(summary),indent=2),'utf8')

if __name__=='__main__':generate()
