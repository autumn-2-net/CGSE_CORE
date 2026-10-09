"""Serialize portable corpus inputs without adding a Java JSON dependency."""
from pathlib import Path
import importlib.util
import json
import re
import struct

BASE=Path(__file__).resolve().parent


def write_graphs(path,cases):
    with path.open('wb') as stream:
        def number(value):stream.write(struct.pack('>i',value))
        def long(value):stream.write(struct.pack('>q',int(value)))
        def boolean(value):stream.write(struct.pack('>?',bool(value)))
        def string(value):
            data=str(value).encode('utf-8');number(len(data));stream.write(data)
        def amounts(values):
            number(len(values))
            for key,amount in values.items():string(key);long(amount)
        number(len(cases))
        for case in cases:
            model=case['model'];string(case['id']);string(model['target']);long(model['amount']);string(case['expected']);amounts(model['stock'])
            external=model.get('external',[]);number(len(external))
            for key in external:string(key)
            boolean(model.get('preserve_seeds',False));boolean(model.get('force_craft',True))
            number(len(model['recipes']))
            for recipe in model['recipes']:
                string(recipe['id']);string(recipe.get('binding',recipe['id']))
                slots=recipe.get('slots',[{'key':key,'amount':amount} for key,amount in recipe.get('inputs',{}).items()]);number(len(slots))
                for slot in slots:
                    string(slot['key']);long(slot['amount']);number(slot.get('input_slot',-1));boolean(slot.get('configuration',False));boolean(slot.get('reusable',False))
                amounts(recipe['outputs'])


def write_models(path,cases):
    with path.open('wb') as stream:
        def number(value):stream.write(struct.pack('>i',value))
        def string(value):
            data=('' if value is None else str(value)).encode('utf-8');number(len(data));stream.write(data)
        number(len(cases))
        for case in cases:
            model=case['model'];string(case['id']);string(case['expected']);number(len(model['lower']))
            for low,high in zip(model['lower'],model['upper']):string(low);string(high)
            number(len(model['rows']))
            for row in model['rows']:
                string(row['upper']);number(len(row['terms']))
                for variable,coefficient in row['terms'].items():number(int(variable));string(coefficient)


def run(args,output,execute):
    manifest=json.loads((BASE/'manifest.json').read_text(encoding='utf-8'))
    shard,shards=map(int,args.shard.split('/'))
    spec=importlib.util.spec_from_file_location('fixture_helpers',BASE.parent/'regression/run.py')
    helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
    kinds=['count-model'] if args.suite=='models' else ['graph','count-model']
    for kind in kinds:
        selected=[c for c in manifest['cases'] if c['kind']==kind and re.search(args.case,c['id']+' '+ ' '.join(c['names']))]
        selected=[c for i,c in enumerate(selected) if i%shards==shard]
        for offset in range(0,len(selected),32):
            cases=[json.loads((BASE.parent/c['path']).read_text(encoding='utf-8')) for c in selected[offset:offset+32]]
            path=output/(kind+'-'+str(offset)+'.bin')
            if kind=='graph':
                write_graphs(path,cases)
                entry='CorpusRegression'
            else:
                write_models(path,cases);entry='CountModelRegression'
            arguments=[path,args.milliseconds,args.work]
            if kind=='count-model':arguments.extend([args.model_engine,str(args.model_trace).lower()])
            execute(kind+'-'+str(offset),'org.cgse.core.'+entry,arguments)


if __name__=='__main__':
    import subprocess,sys
    raise SystemExit(subprocess.call([sys.executable,str(BASE.parent/'run.py'),'--suite','corpus',*sys.argv[1:]]))
