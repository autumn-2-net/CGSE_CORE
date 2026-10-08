from pathlib import Path
area=Path(__file__).resolve().parent
p=area/'PbOracle.java'
s=p.read_text()
s=s.replace('public static void main(String[] args){','public static void main(String[] args)throws Exception{')
s=s.replace('weighted=0;', 'weighted=0,repaired=0;var repairField=CountCdcl.class.getDeclaredField("repairedReasons");repairField.setAccessible(true);')
s=s.replace('learned+=s.learnedConflicts().size();','learned+=s.learnedConflicts().size();repaired+=repairField.getInt(s);')
s=s.replace('weighted_resolvents="+weighted+', 'weighted_resolvents="+weighted+" repaired="+repaired+')
p.write_text(s)
p=area/'InductionOracle.java'
s=p.read_text()
start=s.index(' for(int test=0;test<0;test++)')
end=s.index(' for(int test=0;test<500;test++)',start)
s=s[:start]+s[end:]
s=s.replace('PASS exact OBBT/pump/strong branching models=2000 bounds="+cuts+" witnesses="+pump+" strong="+strong+"; general POR vs complete BFS networks=500','PASS k-induction vs complete BFS networks=500')
p.write_text(s)
