from models import *

def convert(path):
    source=path.read_text();text=source.split('(check-sat)',1)[0]
    if any(x in text for x in ['(push','(pop','(reset','(check-sat-assuming','(apply']):raise ValueError('incremental/tactic stream unsupported')
    assertions=z3.parse_smt2_string(text)
    original=assertions
    if path.stem=='pb-bug':
        goal=z3.Goal();goal.add(*assertions);groups=z3.Tactic('propagate-values')(goal)
        assert len(groups)==1;assertions=list(groups[0]);proof=z3.Solver();proof.add(z3.Xor(z3.And(*original),z3.And(*assertions)));assert proof.check()==z3.unsat
    rows=[];vars={}
    def linear(e):
        e=z3.simplify(e)
        if z3.is_int_value(e):return {},e.as_long()
        if e.sort().kind()!=z3.Z3_INT_SORT:raise ValueError('non-integer arithmetic')
        if z3.is_const(e) and e.decl().kind()==z3.Z3_OP_UNINTERPRETED:vars[str(e)]=e;return {str(e):1},0
        kind=e.decl().kind();parts=list(e.children())
        if kind in [z3.Z3_OP_ADD,z3.Z3_OP_SUB,z3.Z3_OP_UMINUS]:
            terms={};const=0
            for i,p in enumerate(parts):
                t,k=linear(p);sign=-1 if kind==z3.Z3_OP_UMINUS or kind==z3.Z3_OP_SUB and i>0 else 1;const+=sign*k
                for name,v in t.items():terms[name]=terms.get(name,0)+sign*v
            return terms,const
        if kind==z3.Z3_OP_MUL:
            scale=1;expr=[]
            for p in parts:
                if z3.is_int_value(p):scale*=p.as_long()
                else:expr.append(p)
            if len(expr)!=1:raise ValueError('nonlinear multiplication')
            t,k=linear(expr[0]);return {i:scale*v for i,v in t.items()},scale*k
        raise ValueError('unsupported arithmetic '+str(e.decl()))
    def atom(e):
        e=z3.simplify(e)
        if z3.is_true(e):return
        if z3.is_false(e):rows.append(({},-1));return
        if z3.is_and(e):
            for p in e.children():atom(p)
            return
        kind=e.decl().kind()
        if z3.is_not(e):
            e=e.arg(0);kind={z3.Z3_OP_LE:z3.Z3_OP_GT,z3.Z3_OP_GE:z3.Z3_OP_LT,z3.Z3_OP_LT:z3.Z3_OP_GE,z3.Z3_OP_GT:z3.Z3_OP_LE}.get(e.decl().kind(),-1)
        if kind not in [z3.Z3_OP_LE,z3.Z3_OP_GE,z3.Z3_OP_LT,z3.Z3_OP_GT,z3.Z3_OP_EQ]:raise ValueError('non-conjunctive/quantified expression '+str(e.decl()))
        t,b=linear(e.arg(0)-e.arg(1))
        if kind in [z3.Z3_OP_LE,z3.Z3_OP_LT,z3.Z3_OP_EQ]:rows.append((t,-b-int(kind==z3.Z3_OP_LT)))
        if kind in [z3.Z3_OP_GE,z3.Z3_OP_GT,z3.Z3_OP_EQ]:rows.append(({k:-v for k,v in t.items()},b-int(kind==z3.Z3_OP_GT)))
    for e in assertions:atom(e)
    if not vars:raise ValueError('no nontrivial integer variables')
    # Single-row domains are exact implied bounds, never a finite truncation.
    lows={};highs={}
    for t,b in rows:
        active={k:v for k,v in t.items() if v}
        if len(active)!=1:continue
        name,v=next(iter(active.items()))
        if v>0:highs[name]=min(highs.get(name,b//v),b//v)
        else:lows[name]=max(lows.get(name,-((-b)//v)),-((-b)//v))
    names=[];mapping={};lower=[];upper=[]
    for name in vars:
        index=len(names)
        if name in lows:
            mapping[name]=[(index,1)];names.append(name);lower.append(str(lows[name]));upper.append(str(highs[name]) if name in highs else None)
        else:
            mapping[name]=[(index,1),(index+1,-1)];names.extend([name+'+',name+'-']);lower.extend(['0','0']);upper.extend([None,None])
    converted=[]
    for t,b in rows:
        terms={}
        for name,v in t.items():
            for i,sign in mapping[name]:terms[i]=sign*v
        converted.append(row(terms,b))
    solver=z3.Solver();solver.add(*assertions);solver.set(timeout=4000);status=solver.check()
    return {'id':'z3/'+path.stem,'source':str(path.relative_to(A)),'names':names,'lower':lower,'upper':upper,'rows':converted,'objective':{},'expected':str(status).upper(),'derivation':'First check-sat; conjunction integer AST; exact x=x_plus-x_minus for free integers; pb-bug propagate-values checked XOR UNSAT','originalZ3':str(status),'variableMapping':mapping}

accepted=[];unsupported=[]
for path in sorted((A/'official/Z3Prover_z3test/files').rglob('*.smt2')):
    try:
        c=convert(path);oracle(c);accepted.append(c)
    except Exception as e:unsupported.append({'source':str(path.relative_to(A)),'reason':str(e)[:250]})
(A/'z3-cases.json').write_text(json.dumps(accepted,indent=2));(A/'z3-unsupported.json').write_text(json.dumps(unsupported,indent=2));print('Accepted',len(accepted),'unsupported',len(unsupported))
