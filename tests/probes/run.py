"""Compile historical probes in isolation; never mix conflicting historical classes."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import json
import os
import re
import shutil
import subprocess

BASE=Path(__file__).resolve().parent
TESTS=BASE.parent


def run(args,java,classes,output,execute,results,save):
    probes=json.loads((BASE/'manifest.json').read_text(encoding='utf-8'))['probes']
    shard,shards=map(int,args.shard.split('/'))
    eligible=[p for p in probes if args.compile_only or args.include_historical or p.get('tier')=='standalone']
    if not args.classpath and not args.compile_only and not args.include_historical:
        eligible=[p for p in eligible if not p['external_imports']]
    chosen=[p for i,p in enumerate(eligible) if i%shards==shard and re.search(args.case,p['id'])]
    helpers=output/'probe-helper-sources'
    helpers.mkdir(parents=True,exist_ok=True)
    seen_helpers=set()
    for probe in sorted(probes,key=lambda p:(not p.get('baseline_compiled',False),p['id'])):
        package,name=probe['class'].rsplit('.',1) if '.' in probe['class'] else ('',probe['class'])
        target=helpers/Path(*package.split('.'))/(name+'.java')
        if probe['class'] not in seen_helpers:
            target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(TESTS/probe['path'],target)
            seen_helpers.add(probe['class'])
    def compile_one(probe):
        directory=output/'probes'/probe['id']
        destination=directory/'classes'
        destination.mkdir(parents=True,exist_ok=True)
        source=TESTS/probe['path']
        command=[java('javac'),'-J-Duser.language=en','-J-Dfile.encoding=UTF-8','--release','17','-encoding','UTF-8','-cp',os.pathsep.join(x for x in [str(classes),args.classpath] if x),
                 '-sourcepath',str(helpers),'-d',str(destination),str(source)]
        with (directory/'compile.log').open('w',encoding='utf-8') as log:
            try:
                completed=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=60)
                return probe,destination,completed.returncode==0
            except subprocess.TimeoutExpired:return probe,destination,False
    with ThreadPoolExecutor(max_workers=min(4,os.cpu_count() or 1)) as executor:
        for probe,destination,compiled in executor.map(compile_one,chosen):
            if not compiled:
                results.append({'name':probe['id'],'status':'COMPILE_FAILED','log':str((destination.parent/'compile.log').relative_to(output))})
                save();print(probe['id']+': COMPILE_FAILED',flush=True)
                continue
            if args.compile_only:
                results.append({'name':probe['id'],'status':'PASS','mode':'compile-only'})
                save();print(probe['id']+': COMPILED',flush=True);continue
            if not probe['main']:
                results.append({'name':probe['id'],'status':'PASS','mode':'helper-compile'})
                save();continue
            if not args.argument and probe.get('arguments') is None and probe['uses_arguments']:
                text=(TESTS/probe['path']).read_text(encoding='utf-8')
                if not re.search(r'\b(?:args|arguments|a)\.length\s*[><=!]',text):
                    results.append({'name':probe['id'],'status':'ARGUMENTS_REQUIRED','coverage':probe.get('equivalent'),'log':str((destination.parent/'compile.log').relative_to(output))})
                    save();print(probe['id']+': ARGUMENTS_REQUIRED',flush=True);continue
            execute(probe['id'],probe['class'],args.argument or probe.get('arguments',[]),str(destination))


if __name__=='__main__':
    import sys
    raise SystemExit(subprocess.call([sys.executable,str(TESTS/'run.py'),'--suite','probes',*sys.argv[1:]]))
