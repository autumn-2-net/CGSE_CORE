from campaign import run,R
import subprocess,json

def script(name):subprocess.run(['python3',str(R/name)],check=True)
script('generate_evolved.py');script('generate_reduced.py');script('prepare_followup.py')
run('cgse-manual','cgse',ms=3000,folder='manual')
run('cgse-evolved','cgse',ms=100,folder='mutants')
run('cgse-reduced','cgse',ms=3000,folder='reductions')
# Repeat a predeclared selection from the confirmed-budget panel, including both
# positive reachability and negative proofs, not every numerical variant.
rows=[json.loads(l) for l in (R/'cgse-balanced/results.jsonl').read_text().splitlines()]
chosen=[]
for prefix,truth,count in [('hypermatch','SAT',3),('latin','UNSAT',2),('pebble','SAT',2),('pebble','UNSAT',2)]:
 pool=[r for r in rows if r['case'].startswith(prefix+'_') and r['truth']==truth and r['cgse']['status'] in ['TIMEOUT','SEARCH_LIMIT']]
 chosen.extend(r['case'] for r in sorted(pool,key=lambda r:(r['recipes'],r['case']))[:count])
(R/'repeat-selected.txt').write_text('\n'.join(chosen)+'\n')
run('cgse-repeat','cgse','repeat-selected.txt',reps=2)
run('native-manual-counts','native-counts','manual-count-selected.txt',folder='manual-models')
run('native-manual-temporal','native-temporal','manual-temporal-selected.txt',folder='manual')
# This separate relaxation audit does not enter the complete-model ranking.
run('native-relaxation-audit','native-counts','manual-temporal-selected.txt',folder='manual-models')
subprocess.run(['python3',str(R/'bfs_benchmark.py'),'cases','temporal-selected.txt','bfs-balanced.jsonl'],check=True)
print('FOLLOWUP_DONE',flush=True)
