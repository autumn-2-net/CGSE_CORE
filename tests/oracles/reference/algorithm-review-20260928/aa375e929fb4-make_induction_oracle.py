from pathlib import Path
p=Path(__file__).resolve().parent/'ForwardOracle.java'
s=p.read_text()
s=s.replace('ForwardOracle','InductionOracle').replace('test<3000','test<500').replace('networks=3000','networks=500')
s=s.replace('new ForwardCoverability<>','new CountInduction<>')
s=s.replace('recipes.stream().map(r->new BackwardCoverability.Action<String>(new PlanStep.Batch(r.id(),1),SequenceSummary.recipe(r))).toList(),','')
s=s.replace('pdrunsat++;if(possible)','pdrunsat++;if(CountInduction.verify(p.proof(),20000000,u->{})!=CountProof.Verdict.VERIFIED)throw new AssertionError("induction proof");if(possible)')
p.with_name('InductionOracle.java').write_text(s)
