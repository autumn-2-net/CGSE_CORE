from pathlib import Path
import subprocess,sys
root=Path.cwd();area=root/'.local/fused-20260926';classes=area/'oracle-classes';classes.mkdir(exist_ok=True)
java=Path('C:/Users/autumn/.jdks/corretto-20.0.2.1/bin')
sources=list((root/'src/main/java/org/gtlcore/gtlcore/integration/ae2/graph/core').glob('*.java'))
sources += [root/'.local/general-search-20260926'/f for f in ['GeneralSearchReview.java','SeedOracleReview.java','SeedThreadReview.java','PortfolioOracle.java']]
sources += [root/'.local/byproduct-followup-20260926'/f for f in ['ReusableProofReview.java','SharedConflictReview.java']]
sources += [root/'.local/f4cb-counterexamples-20260926'/f for f in ['FiniteOracle.java','LatticeOracle.java']]
sources += [root/'.local/completion-20260926'/f for f in ['CompletionReview.java','BoxOracle.java']]
sources += [area/'FusedOracle.java']
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
for test in sys.argv[1:] or ['FusedOracle','FiniteOracle','LatticeOracle','BoxOracle','CompletionReview','SeedOracleReview','GeneralSearchReview','ReusableProofReview','SharedConflictReview','PortfolioOracle','SeedThreadReview']:
    with (area/(test+'.log')).open('w',encoding='utf8') as log:
        p=subprocess.run([str(java/'java.exe'),'-Xmx2g','-cp',str(classes),'org.gtlcore.gtlcore.integration.ae2.graph.core.'+test],stdout=log,stderr=subprocess.STDOUT)
    print(test,'exit',p.returncode,flush=True)
    if p.returncode:
        print((area/(test+'.log')).read_text(encoding='utf8'));sys.exit(p.returncode)
