"""Serial measured processes; assert case/repetition completeness and preserve every raw log."""
from pathlib import Path
from collections import Counter
import subprocess,json,sys,time,os
R=Path(__file__).resolve().parent
OLD=R.parent/'cgse-seeded-20260927'
PORTABLE=(R/'manifest.json').exists()
CP=(str(R/'cache/classes')+os.pathsep+os.pathsep.join(str(p) for p in sorted((R/'cache/lib').glob('*.jar')))) if PORTABLE else (OLD/'cgse-classpath.txt').read_text().strip()
LP=(str(R/'cache/legacy-classes')+os.pathsep+CP) if PORTABLE else (OLD/'legacy-classpath.txt').read_text().strip()
PREFIX='org.gtlcore.gtlcore.integration.ae2.graph.core.'
def run(name,kind,selected='all',ms=3000,reps=1,folder='cases'):
 out=R/name;out.mkdir(exist_ok=True);result=out/'results.jsonl'
 selection=R/selected if selected!='all' else 'all';input=R/folder
 expected=set(Path(selection).read_text().splitlines()) if selected!='all' else {json.loads(p.read_text())['name'] for p in input.glob('*.json')}
 if result.exists():raise RuntimeError('Use fresh results directory: '+name)
 if kind.startswith('z3'):
  command=['python3',str((R if PORTABLE else OLD/'package')/'z3_probe.py'),kind.split('-')[1],str(input),str(result),str(ms)]
  if selected!='all':command.append(str(selection))
 else:
  cls={'cgse':'CounterexampleProbe','native-counts':'NativeCountProbe','native-temporal':'TemporalNativeProbe','legacy-primary':'FullLegacyProbe','legacy-all':'FullLegacyProbe'}[kind]
  args=[str(input),str(out)]
  if kind.startswith('legacy'):args+=['primary_only' if kind=='legacy-primary' else 'all_outputs',str(ms),str(reps),str(selection)]
  else:
   args += [str(ms),str(selection)]
   if kind!='native-temporal':args.append(str(reps))
  command=['java','-Xmx1g','-cp',LP if kind.startswith('legacy') else CP,PREFIX+cls,*args]
 (out/'command.json').write_text(json.dumps(command,indent=2));start=time.monotonic();print('START',name,'N',len(expected),'budget',ms,flush=True)
 with (out/'console.log').open('w') as log:
  p=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
 if p.returncode:raise RuntimeError((name,p.returncode))
 rows=[json.loads(l) for l in result.read_text().splitlines()];counts=Counter(r['case'] for r in rows);nr=reps if kind in ['cgse','native-counts','legacy-primary','legacy-all'] else 1
 if set(counts)!=expected or set(counts.values())!={nr}:raise RuntimeError(('Incomplete',name,sorted(expected-set(counts)),dict(Counter(counts.values()))))
 if any('error' in row for row in rows):print('ERROR_ROWS',name,sum('error' in row for row in rows),flush=True)
 print('END',name,'seconds',round(time.monotonic()-start,2),'rows',len(rows),flush=True)
 (out/'coverage.json').write_text(json.dumps({'expected_cases':len(expected),'observed_cases':len(counts),'expected_repetitions':nr,'rows':len(rows),'complete':True,'wall_seconds':time.monotonic()-start},indent=2))
if __name__=='__main__':
 mode=sys.argv[1]
 if mode=='screen':run('cgse-screen','cgse',ms=100)
 elif mode=='balanced':
  run('cgse-balanced','cgse','balanced-selected.txt')
  run('native-counts','native-counts','count-selected.txt',folder='models')
  run('native-temporal','native-temporal','temporal-selected.txt')
  run('z3-counts','z3-counts','count-selected.txt',folder='models')
  run('z3-temporal','z3-temporal','temporal-selected.txt')
  run('legacy-primary','legacy-primary','balanced-selected.txt')
  run('legacy-all','legacy-all','balanced-selected.txt')
 elif mode=='custom':run(*sys.argv[2:4],selected=sys.argv[4],ms=int(sys.argv[5]),reps=int(sys.argv[6]),folder=sys.argv[7] if len(sys.argv)>7 else 'cases')
