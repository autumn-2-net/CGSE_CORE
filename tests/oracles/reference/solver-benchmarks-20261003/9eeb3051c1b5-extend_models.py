from models import *
cases=json.loads((A/'cases.json').read_text());old={c['id'] for c in cases};added=[]
for path in [A/'official/scipopt_scip/files/check/instances/MIP/MANN_a9.clq.lp',A/'official/google_or-tools/files/ortools/sat/samples/binpacking_problem_sat.py']:
    m=lp(path) if path.suffix=='.lp' else cpsample(path)
    for c in derive(m):
        if c['id'] not in old:oracle(c);added.append(c)
cases.extend(added);(A/'cases-extended.json').write_text(json.dumps(cases,indent=2));print('Added',len(added))
