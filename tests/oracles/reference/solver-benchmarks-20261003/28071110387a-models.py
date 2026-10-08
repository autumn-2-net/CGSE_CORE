from pathlib import Path
import sys,json,re,math,runpy,copy,time,random,hashlib
from fractions import Fraction
A=Path(__file__).resolve().parent
sys.path.insert(0,str(A/'python-deps'))
from ortools.sat.python import cp_model
import z3

def row(terms,b):
    vals=[Fraction(v) for v in terms.values()]+[Fraction(b)]
    den=math.lcm(*(v.denominator for v in vals))
    return {'terms':{str(k):str(int(Fraction(v)*den)) for k,v in terms.items() if v},'upper':str(int(Fraction(b)*den))}
def mps(path):
    kinds={}; cols={}; rhs={};bounds={}; integers=set(); sec=None;integral=False; objective=None
    for s in path.read_text().splitlines():
        if not s.strip() or s.startswith('*'):continue
        t=s.split()
        if not s[0].isspace():
            sec=t[0]
            if sec not in ['NAME','ROWS','COLUMNS','RHS','BOUNDS','ENDATA']:raise ValueError('unsupported MPS section '+sec)
            continue
        if sec=='ROWS':
            kinds[t[1]]=t[0]
            if t[0]=='N':objective=t[1]
        elif sec=='COLUMNS':
            if 'MARKER' in s:integral='INTORG' in s;continue
            v=t[0];cols.setdefault(v,{})
            if integral:integers.add(v)
            for k in range(1,len(t),2):cols[v][t[k]]=cols[v].get(t[k],0)+Fraction(t[k+1])
        elif sec=='RHS':
            for k in range(1,len(t),2):rhs[t[k]]=Fraction(t[k+1])
        elif sec=='BOUNDS':
            typ,v=t[0],t[2];b=bounds.setdefault(v,[Fraction(0),None]);q=Fraction(t[3]) if len(t)>3 else None
            if typ=='BV':b[:]=[Fraction(0),Fraction(1)];integers.add(v)
            elif typ in ['UP','UI']:b[1]=q
            elif typ in ['LO','LI']:b[0]=q
            elif typ=='FX':b[:]=[q,q]
            else:raise ValueError('unsupported MPS bound '+typ)
            if typ in ['UI','LI']:integers.add(v)
    names=list(cols)
    if set(names)-integers:raise ValueError('continuous variables: '+str(len(set(names)-integers)))
    domains=[bounds.get(v,[Fraction(0),Fraction(1)]) for v in names] # MPS integer default upper=1
    for lo,hi in domains:
        if lo is None or lo.denominator!=1 or hi is not None and hi.denominator!=1:raise ValueError('nonintegral/unbounded lower domain')
    rows=[]
    for r,k in kinds.items():
        if k=='N':continue
        terms={i:cols[v].get(r,0) for i,v in enumerate(names)};b=rhs.get(r,0)
        if k in ['L','E']:rows.append(row(terms,b))
        if k in ['G','E']:rows.append(row({i:-v for i,v in terms.items()},-b))
    return {'id':'scip/'+path.stem,'source':str(path.relative_to(A)),'names':names,'lower':[str(int(x[0])) for x in domains],'upper':[str(int(x[1])) if x[1] is not None else None for x in domains],'rows':rows,'objective':row({i:cols[v].get(objective,0) for i,v in enumerate(names)},0)['terms']}

def lp(path):
    sections={}; sec=None
    for s in path.read_text().splitlines():
        s=s.strip()
        if not s or s.startswith('\\'):continue
        if s.lower() in ['minimize','maximize','subject to','bounds','binaries','binary','general','generals','end']:
            sec='binaries' if s.lower()=='binary' else 'generals' if s.lower()=='general' else s.lower();sections[sec]=[]
        else:sections.setdefault(sec,[]).append(s)
    if 'generals' in sections:
        g=' '.join(sections['generals']).split();explicit=set()
        for b in sections.get('bounds',[]):
            z=re.fullmatch(r'0\s*<=\s*([\w.#]+)\s*<=\s*1',b)
            if z:explicit.add(z[1])
        if not set(g)<=explicit:raise ValueError('general LP variables require explicit [0,1] bounds in this parser')
        sections.setdefault('binaries',[]).extend(g)
    names=' '.join(sections.get('binaries',[])).split();index={v:i for i,v in enumerate(names)}
    def expr(s):
        result={};pattern=r'([+-]?)\s*(\d+(?:\.\d*)?(?:[eE][+-]?\d+)?\s*)?([A-Za-z_][\w.#]*)'
        matches=list(re.finditer(pattern,s))
        residual=re.sub(pattern,'',s).strip()
        if residual:raise ValueError('unknown expression '+residual)
        for m in matches:
            v=m[3]
            if v not in index:raise ValueError('nonbinary variable '+v)
            q=Fraction((m[1] or '+')+(m[2] or '1').strip());i=index[v];result[i]=result.get(i,0)+q
        return result
    constraints=re.split(r'\b[\w.#]+\s*:', ' '.join(sections['subject to']))[1:];rows=[]
    for s in constraints:
        parts=re.split(r'(<=|>=|=)',s)
        if len(parts)!=3:raise ValueError('unknown row '+s)
        t,op,b=expr(parts[0]),parts[1],Fraction(parts[2].strip())
        if op in ['<=','=']:rows.append(row(t,b))
        if op in ['>=','=']:rows.append(row({i:-v for i,v in t.items()},-b))
    lo=[0]*len(names);hi=[1]*len(names)
    for s in sections.get('bounds',[]):
        m=re.fullmatch(r'([+-]?[\d.]+)\s*<=\s*([\w.#]+)\s*<=\s*([+-]?[\d.]+)',s)
        if not m:raise ValueError('unknown bounds '+s)
        lo[index[m[2]]]=int(Fraction(m[1]));hi[index[m[2]]]=int(Fraction(m[3]))
    obj=' '.join(sections.get('minimize',sections.get('maximize',[])));obj=obj.split(':',1)[-1]
    objective=expr(obj)
    if 'maximize' in sections:objective={i:-v for i,v in objective.items()}
    return {'id':'scip/'+path.stem,'source':str(path.relative_to(A)),'names':names,'lower':list(map(str,lo)),'upper':list(map(str,hi)),'rows':rows,'objective':row(objective,0)['terms']}

def cpsample(path):
    class Captured(Exception):pass
    saved=[];original=cp_model.CpSolver.solve
    def intercept(self,model,*args,**kwargs):saved.append(model);raise Captured()
    cp_model.CpSolver.solve=intercept
    try:
        try:runpy.run_path(str(path),run_name='__main__')
        except Captured:pass
    finally:cp_model.CpSolver.solve=original
    if len(saved)!=1:raise ValueError('expected one official model')
    p=saved[0].proto;names=[v.name for v in p.variables];lo=[];hi=[];rows=[]
    for v in p.variables:
        if len(v.domain)!=2:raise ValueError('disjoint integer domains')
        lo.append(str(v.domain[0]));hi.append(str(v.domain[1]))
    for c in p.constraints:
        begin=len(rows)
        if c.has_linear():
            d=c.linear.domain
            if len(d)!=2:raise ValueError('disjoint linear domain')
            t=dict(zip(c.linear.vars,c.linear.coeffs))
            if d[1]!=(1<<63)-1:rows.append(row(t,d[1]))
            if d[0]!=-(1<<63):rows.append(row({i:-v for i,v in t.items()},-d[0]))
        elif c.has_at_most_one() or c.has_exactly_one() or c.has_bool_or():
            lit=c.at_most_one.literals if c.has_at_most_one() else c.exactly_one.literals if c.has_exactly_one() else c.bool_or.literals
            t={};offset=0
            for k in lit:
                i=k if k>=0 else -k-1;t[i]=t.get(i,0)+(1 if k>=0 else -1);offset+=int(k<0)
            if not c.has_bool_or():rows.append(row(t,1-offset))
            if not c.has_at_most_one():rows.append(row({i:-v for i,v in t.items()},offset-1))
        else:raise ValueError('nonlinear/global constraint '+str(c).splitlines()[0])
        # Exact implication linearization: relax a bounded row by its maximum
        # possible violation whenever at least one enforcement literal is false.
        for r in rows[begin:] if len(c.enforcement_literal) else []:
            bound=int(r['upper']);maximum=sum(int(v)*int(hi[int(i)] if int(v)>0 else lo[int(i)]) for i,v in r['terms'].items());big_m=max(0,maximum-bound);terms={int(k):int(v) for k,v in r['terms'].items()}
            for lit in c.enforcement_literal:
                i=lit if lit>=0 else -lit-1;terms[i]=terms.get(i,0)+(big_m if lit>=0 else -big_m)
                if lit>=0:bound+=big_m
            r.update(row(terms,bound))
    obj=dict(zip(p.objective.vars,p.objective.coeffs))
    return {'id':'ortools/'+path.stem,'source':str(path.relative_to(A)),'names':names,'lower':lo,'upper':hi,'rows':rows,'objective':row(obj,0)['terms']}

def cpbuild(m):
    p=cp_model.CpModel();xs=[]
    for i,(lo,hi) in enumerate(zip(m['lower'],m['upper'])):
        if hi is None:raise ValueError('CP oracle unbounded domain unsupported')
        xs.append(p.new_int_var(int(lo),int(hi),str(i)))
    for r in m['rows']:p.add(sum(int(v)*xs[int(i)] for i,v in r['terms'].items())<=int(r['upper']))
    return p,xs

def derive(m):
    result=[m];objective=m['objective']
    if not objective:return result
    try:p,xs=cpbuild(m)
    except ValueError:return result
    p.minimize(sum(int(v)*xs[int(i)] for i,v in objective.items()));solver=cp_model.CpSolver();solver.parameters.max_time_in_seconds=5;solver.parameters.num_search_workers=1
    t=time.perf_counter();status=solver.solve(p);m['optimization']={'status':solver.status_name(status),'seconds':time.perf_counter()-t,'best_bound':solver.best_objective_bound}
    if status not in [cp_model.OPTIMAL,cp_model.FEASIBLE]:return result
    optimum=sum(int(v)*solver.value(xs[int(i)]) for i,v in objective.items());m['optimization']['value']=str(optimum)
    for delta in [0,-1]:
        q=copy.deepcopy(m);q['id']+='-objective-'+str(optimum+delta);q['rows'].append({'terms':objective,'upper':str(optimum+delta)});q['derivation']='official minimization objective <= '+str(optimum+delta);result.append(q)
    return result

def oracle(m):
    answers={}
    try:
        p,xs=cpbuild(m);s=cp_model.CpSolver();s.parameters.max_time_in_seconds=4;s.parameters.num_search_workers=1
        t=time.perf_counter();status=s.solve(p);answers['cpsat']={'status':s.status_name(status),'seconds':time.perf_counter()-t}
        if status in [cp_model.OPTIMAL,cp_model.FEASIBLE]:answers['cpsat']['witness']=[str(s.value(x)) for x in xs]
    except Exception as e:answers['cpsat']={'status':'UNSUPPORTED','reason':str(e)}
    xs=[z3.Int('x'+str(i)) for i in range(len(m['lower']))];s=z3.Solver();s.set(timeout=4000)
    for i,(lo,hi) in enumerate(zip(m['lower'],m['upper'])):
        s.add(xs[i]>=int(lo))
        if hi is not None:s.add(xs[i]<=int(hi))
    for r in m['rows']:s.add(sum(int(v)*xs[int(i)] for i,v in r['terms'].items())<=int(r['upper']))
    t=time.perf_counter();status=s.check();answers['z3']={'status':str(status),'seconds':time.perf_counter()-t}
    if status==z3.sat:answers['z3']['witness']=[str(s.model().eval(x,model_completion=True)) for x in xs]
    m['oracle']=answers
    for o in answers.values():
        if 'witness' in o:
            x=list(map(int,o['witness']));assert all(x[i]>=int(lo) and (hi is None or x[i]<=int(hi)) for i,(lo,hi) in enumerate(zip(m['lower'],m['upper'])))
            assert all(sum(int(v)*x[int(i)] for i,v in r['terms'].items())<=int(r['upper']) for r in m['rows'])
    sat=any(o['status'] in ['sat','OPTIMAL','FEASIBLE'] for o in answers.values());unsat=any(o['status'] in ['unsat','INFEASIBLE'] for o in answers.values());assert not(sat and unsat)
    m['expected']='SAT' if sat else 'UNSAT' if unsat else 'UNKNOWN'
    print(m['id'],len(xs),len(m['rows']),m['expected'],{k:v['status'] for k,v in answers.items()},flush=True)

if __name__=='__main__':
    cases=[];unsupported=[]
    for p in sorted((A/'official/scipopt_scip/files/check/instances/MIP').glob('*'))+sorted((A/'official/google_or-tools/files/ortools/sat/samples').glob('*.py')):
        try:
            model=mps(p) if p.suffix=='.mps' else lp(p) if p.suffix=='.lp' else cpsample(p) if p.suffix=='.py' else None
            if model is None:raise ValueError('format not yet converted')
            cases.extend(derive(model))
        except Exception as e:unsupported.append({'source':str(p.relative_to(A)),'reason':str(e)})
    (A/'cases.json').write_text(json.dumps(cases,indent=2));(A/'unsupported.json').write_text(json.dumps(unsupported,indent=2))
    for m in cases:oracle(m);(A/'cases.json').write_text(json.dumps(cases,indent=2))
    (A/'oracle-versions.json').write_text(json.dumps({'z3':z3.get_version_string(),'ortools':__import__('ortools').__version__,'python':sys.version},indent=2))
