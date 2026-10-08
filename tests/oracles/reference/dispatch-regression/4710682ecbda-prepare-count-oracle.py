from pathlib import Path
p=Path(__file__).resolve().parent
s=(p.parent/'extreme-cycle/LargerOracle.java').read_text()
s='package org.gtlcore.gtlcore.integration.ae2.graph.core;\n'+s.replace('public final class LargerOracle','public final class CountOracle')
s=s.replace('ComplexCycleStress.recipe("r"+j,in,out)','new GraphRecipe<>("r"+j,"r"+j,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out)')
a=s.index('        var budget=new PlanningBudget(');b=s.index('\n    public static void main',a)
s=s[:a]+'''        var budget=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);
        var work=new IntegerCountSearch<>(new GraphCompiler<>(recipes),""+(initial.length-1),n,stock,Map.of(),Set.of(),Set.of(),false,false,budget,System.nanoTime());
        while(!work.step()){}proved=work.infeasible();return work.result();
    }
    static boolean proved;
'''+s[b:]
s=s.replace('if(p.feasible()){','if(p!=null){').replace('}else if(ref){','}else if(ref){\n                if(proved)throw new AssertionError("FALSE INFEASIBILITY sample="+sample);')
s=s.replace('p.result()+" nodes="+p.searchNodes()','"UNKNOWN"').replace('for(var r:recipes)System.out.println("RECIPE "+r.id()+" "+r.inputs()+" -> "+r.outputs());','')
(p/'CountOracle.java').write_text(s)
