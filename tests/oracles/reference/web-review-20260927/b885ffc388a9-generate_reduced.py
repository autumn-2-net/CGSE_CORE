from pathlib import Path
import json
from generate_manual import make
from verify_new_cases import check
ROOT=Path(__file__).resolve().parent
out=ROOT/'min-input';out.mkdir(exist_ok=True);cert=[]
for n in [2,3,4,6,8,10,12,14]:
    for seed in [43,107]:
        for dims in [1,3]:
            f,_=make(n,2,dims,'large',1,seed,'wide_weights' if dims==1 else 'shuffle')
            f['name']='min_'+f['name'];proof=check(f)
            (out/(f['name']+'.json')).write_text(json.dumps(f,separators=(',',':')))
            cert.append({'case':f['name'],'truth':'SAT','certificate':proof})
(ROOT/'min-certificates.jsonl').write_text('\n'.join(map(json.dumps,cert))+'\n')
print('Reduced fixtures',len(cert))
