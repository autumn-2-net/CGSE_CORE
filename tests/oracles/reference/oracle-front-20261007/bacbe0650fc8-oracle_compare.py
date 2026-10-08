"""Local independent integer-state oracle. Never treats bounded SAT as an executable plan."""
from pathlib import Path
from collections import defaultdict, deque, Counter
import sys, json, math, time, hashlib, argparse

ROOT = Path(__file__).resolve().parents[2]
AREA = Path(__file__).resolve().parent
BASE = ROOT / '.local/mixed-jei-stress-20261007'
sys.path.insert(0, str(ROOT / '.local/solver-benchmarks-20261003/python-deps'))
# Optional system accelerators were built for another NumPy ABI.
sys.modules['numexpr'] = None
sys.modules['bottleneck'] = None
from ortools.sat.python import cp_model
import ortools, pyscipopt, z3

def read(path):
    return json.loads(path.read_text('utf8'))

def prepare(data):
    recipes = []
    producers = defaultdict(list)
    for r in data['recipes']:
        inputs, configs, reusable = defaultdict(int), defaultdict(int), defaultdict(int)
        for s in r['slots']:
            k, a = s['key'], int(s['amount'])
            inputs[k] += a
            if s.get('configuration'): configs[k] += a
            if s.get('reusable'): reusable[k] += a
        outputs = {k: int(a) for k, a in r['outputs'].items()}
        physical = {k: a-reusable[k] for k, a in outputs.items() if a > reusable[k]}
        # Consumed configuration is per push, not per execution. Ignoring it
        # is a safe relaxation; returned reusable tokens remain exact read arcs.
        net = {k: outputs.get(k, 0)-inputs[k]+configs[k]-reusable[k]
               for k in inputs.keys() | outputs.keys()}
        idx = len(recipes)
        recipes.append(dict(id=r['id'], inputs=dict(inputs), outputs=outputs,
                            physical=physical, net=net,
                            batch_sensitive=dict(configs) != dict(reusable)))
        for k in physical: producers[k].append(idx)
    return recipes, producers

def model(recipes, producers, stock, external, target, amount):
    # Independent all-provider backward closure; source order never limits scope.
    queue = deque([target]); seen = {target}; chosen = set()
    while queue:
        k = queue.popleft()
        for i in producers.get(k, []):
            if i in chosen: continue
            chosen.add(i)
            for key in recipes[i]['inputs']:
                if key not in seen: seen.add(key); queue.append(key)
    selected = [recipes[i] for i in sorted(chosen)]
    terms = defaultdict(dict)
    for i, r in enumerate(selected):
        for k, n in r['net'].items():
            if n and k not in external: terms[k][i] = -n
    terms.setdefault(target, {})
    rows = []
    for k, vals in terms.items():
        if k in external: continue
        rhs = int(stock.get(k, 0)) - (amount if k == target else 0)
        rows.append((vals, rhs, k))
    if target not in external:
        rows.append(({i: -r['physical'][target] for i, r in enumerate(selected)
                      if r['physical'].get(target, 0)}, -amount, '@physical_target'))
        # ForceCraftProof always requires positive target gain. Keep this
        # separate from the final target stock and physical-output obligation.
        rows.append(({i: -r['net'][target] for i, r in enumerate(selected)
                      if r['net'].get(target, 0)}, -1, '@positive_target_gain'))
    normalized = []
    for vals, rhs, key in rows:
        g = math.gcd(*vals.values()) if vals else 1
        g = abs(g) or 1
        normalized.append(({i: a//g for i, a in vals.items()}, rhs//g, key))
    return selected, normalized

def startup_projection(recipes, stock, external):
    """Independent exact optimistic-box fixpoint, before balance construction."""
    waiting=[];consumers=defaultdict(list);ready=deque();growing=set();enabled=set()
    for i,r in enumerate(recipes):
        deficient=[k for k,a in r['inputs'].items() if k not in external and stock.get(k,0)<a]
        waiting.append(len(deficient))
        for k in deficient:consumers[k].append(i)
        if not deficient:ready.append(i)
    while ready:
        i=ready.popleft();enabled.add(i)
        for k,a in recipes[i]['net'].items():
            if a<=0 or k in growing or k in external:continue
            growing.add(k)
            for j in consumers[k]:
                waiting[j]-=1
                if not waiting[j]:ready.append(j)
    selected=[r for i,r in enumerate(recipes) if i in enabled];producers=defaultdict(list)
    for i,r in enumerate(selected):
        for k in r['physical']:producers[k].append(i)
    return selected,producers

def exact_check(rows, counts):
    return all(x >= 0 for x in counts) and all(sum(a*counts[i] for i, a in vals.items()) <= b for vals, b, _ in rows)

def execute(selected, counts, stock, external, target, amount):
    """Exact independent sequential batching. Failure means only not scheduled."""
    if any(r['batch_sensitive'] and n for r, n in zip(selected, counts)):
        return {'status': 'BATCH_SENSITIVE_NOT_CHECKED'}
    available = defaultdict(int, {k: int(v) for k, v in stock.items()})
    todo = {i: n for i, n in enumerate(counts) if n}
    passes = 0; batches = 0; produced = 0
    start = time.perf_counter()
    while todo and passes < 4096 and time.perf_counter()-start < 1:
        changed = False; passes += 1
        for i in list(todo):
            r = selected[i]; times = todo[i]
            for k, a in r['inputs'].items():
                if k in external: continue
                have = available[k]
                if have < a: times = 0; break
                d = a-r['outputs'].get(k, 0)
                if d > 0: times = min(times, 1+(have-a)//d)
            if not times: continue
            changed = True; batches += 1
            for k in r['inputs'].keys() | r['outputs'].keys():
                if k not in external:
                    available[k] += times*(r['outputs'].get(k, 0)-r['inputs'].get(k, 0))
                    assert available[k] >= 0
            produced += times*r['physical'].get(target, 0)
            todo[i] -= times
            if not todo[i]: del todo[i]
        if not changed: break
    if not todo and available[target] >= amount and produced >= amount and available[target] > stock.get(target, 0):
        return {'status': 'EXECUTABLE', 'passes': passes, 'batches': batches,
                'final_target': str(available[target]), 'physical_produced': str(produced)}
    return {'status': 'UNSCHEDULED', 'remaining_recipes': len(todo), 'passes': passes}

def cpsat(rows, n, seconds):
    m = cp_model.CpModel()
    scale = max([n, 1] + [sum(abs(a) for a in vals.values()) for vals, _, _ in rows])
    cap = min(1 << 50, (1 << 60)//scale)
    if cap < 1:
        return {'status': 'INTEGER_RANGE_UNSUPPORTED', 'largest_row_activity': str(scale)}, None
    xs = [m.new_int_var(0, cap, f'x{i}') for i in range(n)]
    for vals, b, _ in rows:
        # A large positive inventory bound is redundant in the explicitly
        # bounded CP model. Decide that exactly, without rounding inventory.
        largest = sum(max(0, a)*cap for a in vals.values())
        smallest = sum(min(0, a)*cap for a in vals.values())
        if b >= largest: continue
        if b < smallest: m.add(False)
        else: m.add(sum(a*xs[i] for i, a in vals.items()) <= b)
    invalid = m.validate()
    if invalid: return {'status': 'MODEL_INVALID', 'reason': invalid}, None
    s = cp_model.CpSolver(); s.parameters.num_search_workers = 1
    s.parameters.max_time_in_seconds = seconds; s.parameters.random_seed = 73
    begin = time.perf_counter(); status = s.solve(m)
    out = {'status': 'BOUNDED_INFEASIBLE' if status==cp_model.INFEASIBLE else s.status_name(status), 'seconds': time.perf_counter()-begin,
           'domain_cap': str(cap), 'bounded_only': True,
           'branches': s.num_branches, 'conflicts': s.num_conflicts}
    counts = [s.value(x) for x in xs] if status in (cp_model.OPTIMAL, cp_model.FEASIBLE) else None
    if counts is not None: out['exact_balance'] = exact_check(rows, counts)
    return out, counts

def scip(rows, n, seconds):
    if any(not vals and b < 0 for vals, b, _ in rows):
        return {'status': 'infeasible', 'empty_row_exact': True}, None
    m = pyscipopt.Model(); m.hideOutput()
    m.setIntParam('parallel/maxnthreads', 1)
    m.setRealParam('limits/memory', 512)
    xs = [m.addVar(f'x{i}', vtype='INTEGER', lb=0, ub=None) for i in range(n)]
    for vals, b, _ in rows:
        if vals: m.addCons(pyscipopt.quicksum(a*xs[i] for i, a in vals.items()) <= b)
    m.setObjective(0)
    # SCIP's total time includes building the model, unlike CpSolver.solve.
    # Budget the actual solve here; preserve construction time separately.
    construction=m.getTotalTime()
    m.setRealParam('limits/time', seconds+construction)
    begin = time.perf_counter(); m.optimize()
    out = {'status': str(m.getStatus()), 'seconds': time.perf_counter()-begin,
           'nodes': m.getNNodes(), 'construction_seconds': construction, 'floating_point_status': True}
    counts = None
    if m.getNSols():
        sol = m.getBestSol(); counts = [round(m.getSolVal(sol, x)) for x in xs]
        out['exact_balance'] = exact_check(rows, counts)
        if not out['exact_balance']:
            violated=[]
            for vals,b,key in rows:
                activity=sum(a*counts[i] for i,a in vals.items())
                if activity>b:violated.append(dict(key=key,activity=str(activity),bound=str(b),violation=str(activity-b)))
            out['violations']=violated[:8]
            out['maximum_integrality_error']=max([abs(m.getSolVal(sol,x)-round(m.getSolVal(sol,x))) for x in xs] or [0])
            counts = None
    m.freeProb()
    return out, counts

def exact_z3(rows, n, seconds):
    s = z3.Solver(); s.set(timeout=int(seconds*1000))
    xs = [z3.Int(f'x{i}') for i in range(n)]
    s.add(*[x >= 0 for x in xs])
    for vals, b, _ in rows: s.add(z3.Sum([a*xs[i] for i, a in vals.items()]) <= b)
    begin = time.perf_counter(); status = s.check()
    out = {'status': str(status), 'seconds': time.perf_counter()-begin, 'unbounded_exact_integer': True}
    counts = [s.model().eval(x, model_completion=True).as_long() for x in xs] if status == z3.sat else None
    if counts is not None: out['exact_balance'] = exact_check(rows, counts)
    return out, counts

def main():
    p = argparse.ArgumentParser(); p.add_argument('--label', default='manual')
    p.add_argument('--extra', action='store_true'); p.add_argument('--failed-only', action='store_true')
    p.add_argument('--seconds', type=float, default=1); p.add_argument('--z3', action='store_true')
    p.add_argument('--startup-prune', action='store_true');p.add_argument('--small-only', action='store_true')
    p.add_argument('--limit',type=int,default=1000000);p.add_argument('--stride',type=int,default=1)
    args = p.parse_args()
    data = read(BASE/'manual.json')
    if args.extra: data['recipes'] += read(BASE/'extra-virtual.json')['recipes']
    recipes, producers = prepare(data); stock = {k:int(v) for k,v in data['stock'].items()}; external = set(data.get('external', []))
    original_size=len(recipes);prune_start=time.perf_counter()
    if args.startup_prune:recipes,producers=startup_projection(recipes,stock,external)
    prune_seconds=time.perf_counter()-prune_start
    original = ROOT/'.local/mixed-jei-fix-20261007/final-v22/runs'/('virtual-manual-first.jsonl' if args.extra else 'manual-default.jsonl')
    requests = [json.loads(l) for l in original.read_text('utf8').splitlines()]
    if args.failed_only: requests = [r for r in requests if not r['feasible']]
    if args.small_only: requests=[r for r in requests if int(r['amount'])<=1000]
    requests=requests[::args.stride][:args.limit]
    output = AREA/(args.label+'.jsonl')
    if output.exists(): raise FileExistsError(output)
    versions = {'ortools': ortools.__version__, 'pyscipopt': pyscipopt.__version__, 'z3': z3.get_version_string(),
                'catalog_sha256': hashlib.sha256(json.dumps(data['recipes'], sort_keys=True).encode()).hexdigest(),
                'recipes': len(recipes), 'original_recipes': original_size,'startup_prune_seconds':prune_seconds,
                'semantics': 'Necessary all-provider integer balance and physical force-production; SAT additionally requires startup/order verification. CP-SAT UNSAT is bounded only. SCIP floating UNSAT is advisory; Z3 uses unbounded exact integers.'}
    (AREA/(args.label+'-model.json')).write_text(json.dumps(versions, indent=2), 'utf8')
    with output.open('w', encoding='utf8') as out:
        for at, request in enumerate(requests):
            target, amount = request['target'], int(request['amount'])
            selected, rows = model(recipes, producers, stock, external, target, amount)
            row = {k: request[k] for k in ['id','target','amount','result','feasible']}
            row.update(variables=len(selected), rows=len(rows), count_models=[])
            for name, solver in [('cp_sat', cpsat), ('scip', scip)] + ([('z3', exact_z3)] if args.z3 else []):
                result, counts = solver(rows, len(selected), args.seconds if name != 'z3' else max(5, args.seconds))
                if counts is not None and result.get('exact_balance'):
                    result['execution'] = execute(selected, counts, stock, external, target, amount)
                    row['count_models'].append({'solver': name, 'counts': {r['id']: str(n) for r,n in zip(selected,counts) if n}})
                row[name] = result
            out.write(json.dumps(row, separators=(',', ':'))+'\n'); out.flush()
            print(at+1, '/', len(requests), request['id'], 'cgse='+row['result'],
                  ' '.join(name+'='+row[name]['status'] for name in ['cp_sat','scip','z3'] if name in row), flush=True)

if __name__ == '__main__': main()
