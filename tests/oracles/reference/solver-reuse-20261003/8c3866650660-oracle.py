from pathlib import Path
import subprocess,sys,shutil
a=Path(__file__).resolve().parent;r=a.parents[2];core=a/'oracle-core';core.mkdir(exist_ok=True)
for p in (a/'prototype-core').glob('*.java'):shutil.copy2(p,core/p.name)
reference=(a.parent/'baseline/core/CountLpLearning.java').read_text().replace('CountLpLearning','ReferenceLpLearning')
(core/'ReferenceLpLearning.java').write_text(reference)
subprocess.run([sys.executable,str(a.parent/'run.py'),'lp/'+sys.argv[1],'--source',str(core),'--no-matrix','--probe',str(a/'LpStructureProbe.java')],check=True)
