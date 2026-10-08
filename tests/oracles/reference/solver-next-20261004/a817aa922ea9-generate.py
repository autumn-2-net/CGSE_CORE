from pathlib import Path
import hashlib,json,random
a=Path(__file__).resolve().parent;MAX=(1<<63)-1;cases=[]
def row(terms,bound):return {'terms':{str(i):str(c) for i,c in terms.items() if c},'upper':str(bound)}
def equation(rows,terms,rhs):rows.extend([row(terms,rhs),row({i:-c for i,c in terms.items()},-rhs)])
def add(name,lo,hi,rows,point,description,seed,proof=None):
    if point is not None:
        assert all(l<=v<=h for l,v,h in zip(lo,point,hi))
        assert all(sum(int(v)*point[int(i)] for i,v in rr['terms'].items())<=int(rr['upper']) for rr in rows)
    # Check exact finite-domain graph embedding intermediates fit signed long.
    for rr in rows:
        terms={int(i):int(v) for i,v in rr['terms'].items()};bound=int(rr['upper'])-sum(v*lo[i] for i,v in terms.items());buf=max(0,bound,sum(v*(hi[i]-lo[i]) for i,v in terms.items() if v>0))
        assert 0<=buf<=MAX and 0<=buf-bound<=MAX and all(abs(v)<=MAX for v in terms.values()),name
    case={'id':'generated/'+name,'source':{'type':'deterministic synthetic integer model','seed':seed,'generator':'generate.py','description':description},'names':['x'+str(i) for i in range(len(lo))],'lower':list(map(str,lo)),'upper':list(map(str,hi)),'rows':rows,'expected':'SAT' if point is not None else 'UNSAT','oracle':{'kind':'exact direct integer witness' if point is not None else 'independent combinatorial certificate','witness':list(map(str,point)) if point is not None else None,'proof':proof}}
    cases.append(case)
for n in [12,24,48,96,144,257]:
    for seed in range(3):
        actual_seed=2026100400+n*31+seed;rng=random.Random(actual_seed);lo=[-2 if i%7==0 else 0 for i in range(n)];hi=[v+7 for v in lo];p=[rng.randint(l,h) for l,h in zip(lo,hi)];rows=[]
        for j in range(max(3,n//2)):
            ids=rng.sample(range(n),min(n,5+seed*2));t={i:rng.choice([-13,-7,-3,2,5,11]) for i in ids};equation(rows,t,sum(v*p[i] for i,v in t.items()))
        add(f'sparse-equality-n{n}-s{seed}',lo,hi,rows,p,'Sparse signed equalities with independently verified planted bounded-integer witness.',actual_seed)
for width in [31,10000,1000000]:
    for n in [8,16,32,64]:
        seed=810000+n+width;rng=random.Random(seed);lo=[0]*n;hi=[width]*n;p=[rng.randrange(width+1) for _ in range(n)];rows=[]
        for j in range(max(2,n//4)):
            ids=rng.sample(range(n),min(7,n));t={i:rng.choice([-101,-37,-7,11,43,103]) for i in ids};equation(rows,t,sum(v*p[i] for i,v in t.items()))
        add(f'wide-equality-n{n}-w{width}',lo,hi,rows,p,'Mixed bounded count domains; large quantities without unary expansion.',seed)
for n in [24,32,40,48]:
    for bits in [12,24,40,48]:
        seed=101000+n*100+bits;rng=random.Random(seed);w=[rng.randrange(1<<(bits-1),1<<bits)|1 for _ in range(n)];p=[rng.randrange(2) for _ in range(n)];target=sum(v*x for v,x in zip(w,p));rows=[];equation(rows,dict(enumerate(w)),target)
        add(f'subset-n{n}-bits{bits}',[0]*n,[1]*n,rows,p,'Exact subset equality with wide coefficients and a verified witness; not labelled UNSAT by timeout.',seed)
for n in [16,64,128,257]:
    for closed in [False,True]:
        seed=201000+n*2+closed;rng=random.Random(seed);lo=[0]*n;hi=[1000000]*n;p=[rng.randrange(1000001) for _ in range(n)];rows=[]
        for i in range(n if closed else n-1):
            j=(i+1)%n;t={i:3,j:-2};equation(rows,t,3*p[i]-2*p[j])
        add(f'ratio-{"ring" if closed else "chain"}-n{n}',lo,hi,rows,p,'Long integer-ratio chain/ring with exact independently replayed assignment.',seed)
for colors in [3,4,5,6,7,8,9,10,11,12]:
    vertices=colors+1;n=vertices*colors;rows=[]
    for v in range(vertices):equation(rows,{v*colors+c:1 for c in range(colors)},1)
    for v in range(vertices):
        for w in range(v+1,vertices):
            for c in range(colors):rows.append(row({v*colors+c:1,w*colors+c:1},1))
    add(f'clique-coloring-k{vertices}-c{colors}',[0]*n,[1]*n,rows,None,'Complete graph requires distinct colors; full pairwise encoding tests implied cardinality structure.',colors,{'kind':'pigeonhole','vertices':vertices,'colors':colors,'argument':'Each Boolean one-hot vertex needs one color. Every pair of vertices is forbidden from sharing each color. Thus at least vertices distinct colors are necessary, but vertices=colors+1.'})
(a/'generated-64.json').write_text(json.dumps(cases,indent=2))
meta={'cases':len(cases),'sat':sum(c['expected']=='SAT' for c in cases),'unsat':sum(c['expected']=='UNSAT' for c in cases),'generator_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'oracle':'All SAT witnesses checked using Python arbitrary-precision arithmetic. UNSAT clique cases carry explicit pigeonhole certificate; no unknown result was labelled UNSAT. All graph embedding intermediate long conversions checked.'}
(a/'generated-manifest.json').write_text(json.dumps(meta,indent=2));print(json.dumps(meta,indent=2))
