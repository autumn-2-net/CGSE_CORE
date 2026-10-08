from pathlib import Path
import json,copy,collections,sys,math
R=Path(__file__).resolve().parent;sys.path.insert(0,str(R))
from verify_new_cases import check
from prepare_manual_models import bounds
OUT=R/'reductions';OUT.mkdir(exist_ok=True);MODELS=R/'reduction-models';MODELS.mkdir(exist_ok=True)
def walk(s):
 if 'batch' in s:return {s['batch']} if s['n'] else set()
 if 'repeat' in s:return walk(s['repeat']) if s['n'] else set()
 return set().union(*(walk(x) for x in s['sequence']))
def prune(s,keep):
 if 'batch' in s:return s if s['batch'] in keep else {'sequence':[]}
 if 'repeat' in s:return {'repeat':prune(s['repeat'],keep),'n':s['n']}
 return {'sequence':[prune(x,keep) for x in s['sequence']]}
def clean(f):
 used=walk(f['witness']);f['recipes']=[r for r in f['recipes'] if r['id'] in used];f['witness']=prune(f['witness'],used)
 keys=set().union(*(r['inputs'].keys()|r['outputs'].keys() for r in f['recipes']));f['stock']={k:v for k,v in f['stock'].items() if k in keys}
 return f

def inline(f):
 by={r['id']:r for r in f['recipes']};remove=set();joint=[]
 for r in f['recipes']:
  if list(r['outputs'])==['GOAL'] and r['inputs'] and all(k.endswith('GOAL') for k in r['inputs']):joint.append(r)
 if len(joint)!=1:return None
 out=joint[0];ins=collections.Counter();prod={k:r for r in f['recipes'] for k in r['outputs'] if k.endswith('GOAL') and k!='GOAL'}
 for k,n in out['inputs'].items():
  r=prod[k]
  if r['outputs'][k]!=1 or len(r['outputs'])!=1:return None
  remove.add(r['id']);ins.update({k2:v*n for k2,v in r['inputs'].items()})
 out['inputs']=dict(ins);f['recipes']=[r for r in f['recipes'] if r['id'] not in remove];f['witness']=prune(f['witness'],set(by)-remove);return f

def unit(f):
 keys=set(f['stock'])|set().union(*(r['inputs'].keys()|r['outputs'].keys() for r in f['recipes']))
 changed={}
 for k in keys-{f['target']}:
  vals=[f['stock'].get(k,0)]+[r[s].get(k,0) for r in f['recipes'] for s in ['inputs','outputs']];g=math.gcd(*vals)
  if g>1:
   changed[k]=g
   if k in f['stock']:f['stock'][k]//=g
   for r in f['recipes']:
    for side in ['inputs','outputs']:
     if k in r[side]:r[side][k]//=g
 f['unit_divisors']=changed;return f
rows=[json.loads(l) for l in (R/'results/cgse-balanced/results.jsonl').read_text().splitlines()];families=collections.defaultdict(list)
for x in rows:
 if x['cgse']['status'] in ('TIMEOUT','SEARCH_LIMIT'):
  f=json.loads((R/'cases'/(x['case']+'.json')).read_text());families[f['family']].append(f)
selected=[]
for family,fs in families.items():selected+=sorted(fs,key=lambda f:(max(f['stock'].values()),len(f['recipes']),f['name']))[:3]
manifest=[]
for original in selected:
 for kind,fn in [('drop_unused',clean),('normalize_units',lambda f:unit(clean(f))),('inline_goals',lambda f:inline(clean(f)))]:
  f=fn(copy.deepcopy(original))
  if f is None:continue
  f.update(name='reduced_'+original['name']+'_'+kind,origin=original['name'],reduction=kind);check(f)
  if all(r['inputs'].get('F')==1 and not r['outputs'].get('F',0) for r in f['recipes']):f['count_upper']={r['id']:f['stock']['F'] for r in f['recipes']};f['bound_certificate']={'fuel_key':'F','fuel_stock':f['stock']['F']}
  else:f['count_upper'],f['bound_certificate']=bounds(f)
  (OUT/(f['name']+'.json')).write_text(json.dumps(f,separators=(',',':')));(MODELS/(f['name']+'.json')).write_text(json.dumps({k:f[k] for k in ['name','target','amount','recipes','stock','dag','count_upper','bound_certificate']},separators=(',',':')))
  manifest.append({'case':f['name'],'origin':original['name'],'recipes_before':len(original['recipes']),'recipes_after':len(f['recipes']),'reduction':kind})
(R/'reductions.json').write_text(json.dumps(manifest,indent=2));print('REDUCTIONS',len(manifest))
# Remaining short-budget timeouts from the small cyclic random cohort.
randoms=[json.loads(l)['case'] for l in (R/'results/cgse-screen/results.jsonl').read_text().splitlines() if json.loads(l)['case'].startswith('hard_random_') and json.loads(l)['cgse']['status']=='TIMEOUT']
(R/'random-retry-selected.txt').write_text('\n'.join(randoms)+'\n');print('RANDOM RETRIES',len(randoms))
