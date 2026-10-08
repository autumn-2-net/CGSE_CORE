from generate_round3 import *
R=Path(__file__).resolve().parent
rows=[json.loads(l) for l in (R/'cgse-balanced/results.jsonl').read_text().splitlines()]
seed=[]
for family,n in [('hypermatch',4),('latin',3),('pebble',4)]:
 options=[r for r in rows if r['case'].startswith(family+'_') and r['cgse']['status'] in ['TIMEOUT','SEARCH_LIMIT']]
 if family=='pebble':options.sort(key=lambda r:(r['truth']!='SAT',r['recipes'],r['case']))
 else:options.sort(key=lambda r:(r['recipes'],r['case']))
 seed+=options[:n]
records=[]
for row in seed:
 original=json.loads((R/'cases'/(row['case']+'.json')).read_text())
 def uses(s):
  if 'batch' in s:return {s['batch']} if s['n'] else set()
  if 'repeat' in s:return uses(s['repeat'])
  return set().union(*(uses(x) for x in s['sequence']))
 required=uses(original['witness']) if 'witness' in original else set()
 optional=[r['id'] for r in original['recipes'] if r['id'] not in required]
 for divisor in [4,2,1]:
  f=copy.deepcopy(original);take=max(1,len(optional)//divisor);remove=set(optional[:take]);f['recipes']=[r for r in f['recipes'] if r['id'] not in remove]
  if not f['recipes']:continue
  if f['truth']=='UNSAT' and 'oracle' in f:f.pop('truth');f.pop('oracle')
  f.update(name=f'reduced_{row["case"]}_drop_{take}',family='reduced_'+original['family'],reduction={'origin':original['name'],'removed':sorted(remove),'uses_witness_only_for_experiment':True})
  record=save(f,R/'reductions')
  if record:records.append(record)
(R/'reduction_certificates.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
print('REDUCED',len(records),flush=True)
