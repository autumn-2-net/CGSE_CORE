"""Check conversions and independently replay every native/legacy SAT count vector."""
from pathlib import Path
from collections import Counter
import json, hashlib, itertools, argparse
from convert_orlib import parse
ROOT=Path(__file__).resolve().parent

def verify_conversion():
    for entry in json.loads((ROOT/'downloads.json').read_text()):
        assert hashlib.sha256((ROOT/'raw'/entry['name']).read_bytes()).hexdigest()==entry['sha256']
    metadata=json.loads((ROOT/'case_metadata.json').read_text())
    raw={}
    for p in (ROOT/'raw').glob('mknap*.txt'):
        raw.update({f'{p.stem}_{x["index"]:02d}':x for x in parse(p)})
    for row in metadata:
        f=json.loads((ROOT/'cases'/f'{row["case"]}.json').read_text());s=raw[row['source_instance']]
        assert len(f['recipes'])==s['n'] and f['amount']==row['target']
        expected_stock={f'CAP{i}':v for i,v in enumerate(s['capacity']) if v}
        expected_stock.update({f'ONCE{j}':1 for j in range(s['n'])})
        assert f['stock']==expected_stock and f['target']=='GOAL'
        for j,r in enumerate(f['recipes']):
            assert r['id']==f'pick{j}' and r['outputs']=={'GOAL':s['profit'][j]}
            expected={f'CAP{i}':s['weights'][i][j] for i in range(s['m']) if s['weights'][i][j]}
            expected[f'ONCE{j}']=1
            assert r['inputs']==expected and f['count_upper'][r['id']]==1
        model=json.loads((ROOT/'models'/f'{row["case"]}.json').read_text())
        assert not {'truth','truth_basis','witness','source_instance'} & model.keys()
        assert all(f[k]==v for k,v in model.items())
    # Exhaustive independently enumerated optima for the two smallest original cases.
    optima=[]
    for name in ['mknap1_00','mknap1_01']:
        s=raw[name];opt=0
        for bits in itertools.product((0,1),repeat=s['n']):
            if all(sum(x*w for x,w in zip(bits,a))<=b for a,b in zip(s['weights'],s['capacity'])):
                opt=max(opt,sum(x*p for x,p in zip(bits,s['profit'])))
        assert opt==s['reported_optimum']
        optima.append({'instance':name,'enumerated_optimum_scaled':opt,'scale':s['scale']})
    return {'download_checksums':True,'source_instances':len(raw),'equivalent_conversions':len(metadata),'small_optimum_enumeration':optima}

def replay(f,counts):
    ids={r['id'] for r in f['recipes']};assert not counts.keys()-ids
    held=dict(f['stock']);fires=0
    for r in f['recipes']:
        n=counts.get(r['id'],0)
        assert type(n) is int and 0<=n<=1,(r['id'],n)
        if not n:continue
        assert all(held.get(k,0)>=v for k,v in r['inputs'].items()),r['id']
        for k,v in r['inputs'].items():held[k]-=v
        for k,v in r['outputs'].items():held[k]=held.get(k,0)+v
        fires+=1
    assert held.get(f['target'],0)>=f['amount']
    return {'verified':True,'fires':fires,'produced':held[f['target']]}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--results',nargs='+',type=Path,help='Optional explicit result JSONL files; defaults to the formal native and legacy comparisons.')
    args=parser.parse_args()
    checks=verify_conversion();valid=[];bad=[]
    paths=args.results or [ROOT/name/'results.jsonl' for name in ['native-detail','z3-detail','legacy-detail-complete','native-confirm']]
    for path in paths:
        for row in map(json.loads,path.read_text().splitlines()):
            f=json.loads((ROOT/'cases'/f'{row["case"]}.json').read_text())
            for method in ['cp_sat','scip','z3']:
                if method not in row:continue
                s=row[method]
                if s['status'] in ('OPTIMAL','FEASIBLE','SAT'):
                    try:proof=replay(f,s['counts'])
                    except Exception as e:bad.append({'run':path.parent.name,'case':row['case'],'method':method,'error':repr(e)});continue
                    assert f['truth']!='UNSAT'
                    valid.append({'run':path.parent.name,'case':row['case'],'method':method,**proof})
                elif s['status'] in ('INFEASIBLE','UNSAT') and f['truth']=='SAT':
                    bad.append({'run':path.parent.name,'case':row['case'],'method':method,'error':'contradicts published feasible value'})
            if row.get('status')=='FEASIBLE':
                try:proof=replay(f,{k:int(v) for k,v in row['counts'].items()})
                except Exception as e:bad.append({'run':path.parent.name,'case':row['case'],'method':'max_fast','error':repr(e)});continue
                valid.append({'run':path.parent.name,'case':row['case'],'method':'max_fast',**proof})
    checks.update(validated_count_plans=len(valid),invalid_results=bad)
    (ROOT/'independent_validation.json').write_text(json.dumps(checks,indent=2))
    (ROOT/'validated_plans.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in valid))
    print(json.dumps(checks,indent=2));assert not bad

if __name__=='__main__':main()
