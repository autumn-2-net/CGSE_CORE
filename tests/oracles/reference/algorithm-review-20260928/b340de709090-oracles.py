from pathlib import Path
import sys, subprocess
area = Path(__file__).resolve().parent
old = area.parent/'web-review-20260927'
sys.path.insert(0, str(old))
import review
tag = sys.argv[1] if len(sys.argv) > 1 else 'audit-v9'
review.compile_into(old/tag)
checks = area/('checks-'+tag)
sources = list(area.glob('*.java'))
subprocess.run([str(review.jdk/'javac.exe'),'--release','17','-encoding','UTF-8','-cp',str(old/tag),'-d',str(checks),*map(str,sources)],check=True)
for name in (sys.argv[2:] or ['AuditOracle','NetworkOracle','RevisedOracle','ScheduleOracle']):
    command = [str(review.jdk/'java.exe'),'-ea','-Xmx2g','-cp',str(checks)+';'+str(old/tag),'org.gtlcore.gtlcore.integration.ae2.graph.core.'+name]
    if name == 'AuditOracle': command.append(str(area/(tag+'-cliques.cgp')))
    log = area/(tag+'-'+name+'.log')
    with log.open('w',encoding='utf8') as out:
        subprocess.run(command,stdout=out,stderr=subprocess.STDOUT,check=True)
    print(log.read_text(),flush=True)
