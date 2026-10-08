from oracle_compare import *
import random

def explore(rs, initial, target, amount):
    queue=deque([initial]);distance={initial:0};fired=set();witness=None
    while queue:
        state=queue.popleft()
        if state[target]>=amount and witness is None: witness=distance[state]
        for i,r in enumerate(rs):
            if all(state[k]>=a for k,a in r['in'].items()):
                fired.add(i);after=list(state)
                for k,a in r['in'].items():after[k]-=a
                for k,a in r['out'].items():after[k]+=a
                after=tuple(after)
                if after not in distance:distance[after]=distance[state]+1;queue.append(after)
    return distance,fired,witness

def native(rows,n,cap):
    m=cp_model.CpModel();xs=[m.new_int_var(0,cap,f'x{i}') for i in range(n)]
    for t,b,_ in rows:m.add(sum(a*xs[i] for i,a in t.items())<=b)
    s=cp_model.CpSolver();s.parameters.max_time_in_seconds=1;s.parameters.num_search_workers=1
    status=s.solve(m);counts=[s.value(v) for v in xs] if status in [cp_model.OPTIMAL,cp_model.FEASIBLE] else None
    if counts is not None:assert exact_check(rows,counts)
    return s.status_name(status)

def generate():
    cases=[];summary=Counter();rng=random.Random(20261007)
    for case in range(1024):
        n=3+case%2;target=n-1;amount=1+case%3
        initial=tuple(rng.randrange(3) if k!=target else 0 for k in range(n));rs=[];recipes=[]
        for i in range(5+case%6):
            inputs=Counter(rng.randrange(n) for _ in range(1+rng.randrange(4)))
            outputs=Counter(rng.randrange(n) for _ in range(1+rng.randrange(sum(inputs.values()))))
            rs.append({'in':dict(inputs),'out':dict(outputs)})
            recipes.append({'id':f'r{i}','slots':[{'key':f'p{k}','amount':v} for k,v in inputs.items()],
                            'outputs':{f'p{k}':v for k,v in outputs.items()}})
        states,fired,witness=explore(rs,initial,target,amount)
        data={'recipes':recipes,'stock':{f'p{k}':v for k,v in enumerate(initial) if v},'external':[]}
        parsed,producers=prepare(data);chosen,rows=model(parsed,producers,data['stock'],set(),f'p{target}',amount)
        cp=native(rows,len(chosen),max(1,len(states)))
        sc,_=scip(rows,len(chosen),1)
        if witness is not None:assert cp in ['OPTIMAL','FEASIBLE'] and sc['status']=='optimal',(case,cp,sc)
        expected='FEASIBLE' if witness is not None else 'INFEASIBLE'
        summary[expected]+=1;summary['cp_'+cp]+=1;summary['scip_'+sc['status']]+=1
        cases.append(dict(id=f'finite-{case}',**data,target=f'p{target}',amount=amount,expected=expected,
                          reachable_states=len(states),shortest=witness,fired=[f'r{i}' for i in sorted(fired)],
                          cp_sat=cp,scip=sc['status']))
        if (case+1)%128==0:print(case+1,dict(summary),flush=True)
    (AREA/'finite-cases.json').write_text(json.dumps(cases),'utf8')
    (AREA/'finite-oracle-summary.json').write_text(json.dumps(dict(summary),indent=2),'utf8')

if __name__=='__main__':generate()
