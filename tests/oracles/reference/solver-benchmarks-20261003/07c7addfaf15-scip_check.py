from models import *
import pyscipopt
def canonical(terms,upper):
    vals={k:Fraction(v) for k,v in terms.items() if v};b=Fraction(upper);den=math.lcm(*[v.denominator for v in list(vals.values())+[b]]);ints={k:int(v*den) for k,v in vals.items()};bound=int(b*den);g=math.gcd(*list(ints.values()),bound) or 1
    return tuple(sorted((k,v//g) for k,v in ints.items())),bound//g
cases=json.loads((A/'cases.json').read_text());report=[]
for c in cases:
    if not c['id'].startswith('scip/'):continue
    m=pyscipopt.Model();m.hideOutput();m.readProblem(str(A/c['source']));vs={v.name:v for v in m.getVars()};names=c['names'];converted={names[i]:(int(lo),None if hi is None else int(hi)) for i,(lo,hi) in enumerate(zip(c['lower'],c['upper']))};assert set(vs)==set(converted)
    for name,v in vs.items():
        assert v.vtype() in ['BINARY','INTEGER','IMPLINT'],(name,v.vtype());lo=v.getLbOriginal();hi=v.getUbOriginal();assert (int(lo),None if m.isInfinity(hi) else int(hi))==converted[name],(name,lo,hi,converted[name])
    expected=[]
    for cons in m.getConss():
        assert cons.getConshdlrName()=='linear';t=m.getValsLinear(cons);lhs=m.getLhs(cons);rhs=m.getRhs(cons)
        if not m.isInfinity(rhs):expected.append(canonical(t,rhs))
        if not m.isInfinity(-lhs):expected.append(canonical({k:-v for k,v in t.items()},-lhs))
    originalrows=c['rows'][:-1] if 'derivation' in c else c['rows'];actual=[canonical({names[int(k)]:v for k,v in r['terms'].items()},r['upper']) for r in originalrows];assert sorted(expected)==sorted(actual),c['id']
    if 'derivation' in c:
        r=c['rows'][-1];m.addCons(pyscipopt.quicksum(int(v)*vs[names[int(k)]] for k,v in r['terms'].items())<=int(r['upper']))
    m.setObjective(0);m.setRealParam('limits/time',5);m.setIntParam('parallel/maxnthreads',1);started=time.perf_counter();m.optimize();o={'status':str(m.getStatus()),'seconds':time.perf_counter()-started,'nodes':m.getNNodes()};c.setdefault('oracle',{})['scipOriginalReader']=o
    if m.getNSols()>0:
        sol=m.getBestSol();x=[int(round(m.getSolVal(sol,vs[name]))) for name in names];assert all(sum(int(v)*x[int(k)] for k,v in r['terms'].items())<=int(r['upper']) for r in c['rows']);o['witness']=list(map(str,x));assert c['expected']!='UNSAT';c['expected']='SAT'
    elif o['status']=='infeasible':assert c['expected']!='SAT';c['expected']='UNSAT'
    report.append({'id':c['id'],'originalTypesBoundsRowsExactMatch':True,'oracle':o});print(c['id'],o['status'],o['seconds'],flush=True)
    (A/'scip-original-check.json').write_text(json.dumps(report,indent=2));(A/'cases.json').write_text(json.dumps(cases,indent=2))
versions=json.loads((A/'oracle-versions.json').read_text());versions.update(pyscipopt=pyscipopt.__version__,scip='.'.join(str(f()) for f in [m.getMajorVersion,m.getMinorVersion,m.getTechVersion]));(A/'oracle-versions.json').write_text(json.dumps(versions,indent=2))
