package org.cgse.core;
import java.util.*;
public class CompilerReuseProbe {
 static PlanningBudget budget(){return new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);}
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static GraphCompiler.Compiled<String> graph(GraphCompiler<String> compiler,String target,PlanningBudget b){try(var w=compiler.beginStockView(target,Set.of(),Map.of(),Set.of(),Set.of(),b)){while(!w.step()){}return w.result();}}
 static GraphPlan<String> solve(GraphCompiler.Compiled<String> graph,String target,long amount,Map<String,Long> stock,GraphDemandProgram<String> p,PlanningBudget b){try(var s=new GraphSolve<>(graph,target,amount,stock,Set.of(),Map.of(),true,true,b,System.nanoTime(),CatalystPolicy.MINIMAL,stock).program(p)){while(!s.step()){}return s.result();}}
 public static void main(String[]args){int plans=0,scopes=0;
  for(int seed=0;seed<256;seed++){
   Random r=new Random(76621+seed);var rs=new ArrayList<GraphRecipe<String>>();
   int depth=3+seed%20;for(int i=0;i<depth;i++)rs.add(recipe("r"+i,Map.of("p"+i,1L+r.nextInt(5)),i==depth-1?Map.of("T",2L,"U",3L):Map.of("p"+(i+1),1L+r.nextInt(5))));
   var c=new GraphCompiler<>(rs);var b=budget();var a=graph(c,"T",b);if(c.demandProgram(a,b)!=null)throw new AssertionError("not repeated");
   var other=graph(c,"U",b);if(a.regions()!=other.regions())throw new AssertionError("topology");var program=c.demandProgram(other,b);if(program==null)throw new AssertionError("no cross-graph program");
   var next=graph(c,"T",b);long before=b.compilationWork();var rebound=c.demandProgram(next,b);if(rebound==null||rebound.graph!=next||rebound.region(0)!=program.region(0)||b.compilationWork()!=before)throw new AssertionError("rebuilt shared ports");
   for(int trial=0;trial<8;trial++){
    var stock=new LinkedHashMap<String,Long>();stock.put("p0",trial%2==0?Long.MAX_VALUE:100000L);for(int i=1;i<depth;i++)stock.put("p"+i,(long)r.nextInt(20));stock.put("T",100L);stock.put("U",200L);
    long amount=trial==7?Long.MAX_VALUE:1L+r.nextInt(5000);String target=trial%2==0?"T":"U";var g=target.equals("T")?next:other;var ports=target.equals("T")?rebound:program;
    var cold=solve(g,target,amount,stock,null,b);var warm=solve(g,target,amount,stock,ports,b);
    if(cold.result()!=warm.result()||!cold.patternTimesExact().equals(warm.patternTimesExact())||!cold.initialExact().equals(warm.initialExact())||!cold.missingExact().equals(warm.missingExact()))throw new AssertionError("changed arithmetic "+seed+"/"+trial);
    if(warm.feasible()){PlanVerifier.verify(warm);PlanVerifier.verifyRuntimeInventory(warm);}plans++;
   }
   var changed=new ArrayList<>(rs);changed.set(depth-1,recipe("r"+(depth-1),Map.of("p"+(depth-1),1L),Map.of("T",7L,"U",11L)));
   var foreign=graph(new GraphCompiler<>(changed),"T",b);try{program.forGraph(foreign);throw new AssertionError("foreign topology accepted");}catch(IllegalArgumentException expected){}
   if(b.reservedBytes()!=0)throw new AssertionError("program leak");
  }
  for(int seed=0;seed<512;seed++){
   Random rnd=new Random(9981+seed);var rs=new ArrayList<GraphRecipe<String>>();for(int i=0;i<16;i++)rs.add(recipe("r"+i,Map.of("p"+rnd.nextInt(6),1L+rnd.nextInt(5)),Map.of("p"+rnd.nextInt(6),1L+rnd.nextInt(7))));
   var cached=new GraphCompiler<>(rs);var b=budget();if(GraphSourceIndex.create(cached,b,100_000)==null)throw new AssertionError("index missing");
   for(int trial=0;trial<8;trial++){
    var stock=new HashMap<String,Long>();for(int k=0;k<6;k++)stock.put("p"+k,(long)rnd.nextInt(7));Set<String> external=trial%2==0?Set.of("p1"):Set.of();Set<String> excluded=trial%3==0?Set.of("r2","r7"):Set.of();
    try(var cold=new MissingStockAnalysis<>(new GraphCompiler<>(rs),"p5",stock,external,Set.of("p3"),excluded,true,b);var warm=new MissingStockAnalysis<>(cached,"p5",stock,external,Set.of("p3"),excluded,true,b)){
     while(!cold.step()){}while(!warm.step()){}if(cold.blocked()!=warm.blocked()||!cold.unreachableRecipes().equals(warm.unreachableRecipes()))throw new AssertionError("scope corruption "+seed+"/"+trial);scopes++;
    }
   }
   if(b.reservedBytes()!=0)throw new AssertionError("index leak");
  }
  System.out.println("PASS rebound_demand_plans="+plans+" inventory_external_exclusion_scopes="+scopes+" pattern_mutations=256");
 }
}
