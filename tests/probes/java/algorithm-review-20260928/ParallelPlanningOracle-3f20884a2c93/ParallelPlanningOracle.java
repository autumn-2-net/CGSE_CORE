package org.cgse.core;
import java.io.*;import java.nio.file.*;import java.math.*;import java.util.*;import java.util.concurrent.*;
public class ParallelPlanningOracle {
 record Case(String name,String target,long amount,String truth,Map<String,Long> stock,List<GraphRecipe<String>> recipes){}
 public static void main(String[]args)throws Exception {
  var cases=new ArrayList<Case>();
  try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))){for(int c=in.readInt();c>0;c--){String name=GraphFixtureRegression.string(in),target=GraphFixtureRegression.string(in);long amount=in.readLong();String truth=GraphFixtureRegression.string(in);var stock=GraphFixtureRegression.amounts(in);var recipes=new ArrayList<GraphRecipe<String>>();for(int n=in.readInt();n>0;n--){String id=GraphFixtureRegression.string(in);var input=GraphFixtureRegression.amounts(in);var output=GraphFixtureRegression.amounts(in);recipes.add(new GraphRecipe<>(id,id,input.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),output));}cases.add(new Case(name,target,amount,truth,stock,recipes));}}
  for(int width:new int[]{1,4,8,16})try(var scheduler=new PlanningScheduler(width,8,128,1000000)){
   for(int repeat=0;repeat<3;repeat++)for(var c:cases){
    var budget=new PlanningBudget(3000,20000000,256L<<20,()->false,System::nanoTime);long start=System.nanoTime();String result;
    try{var plan=scheduler.submit(new GraphPlanner<>(new GraphCompiler<>(c.recipes())).begin(c.target(),c.amount(),c.stock(),false,true,budget),budget).get(15,TimeUnit.SECONDS);result=plan.result().toString();
     if(plan.feasible()){
      if(!c.truth().equals("SAT"))throw new AssertionError("false feasible");PlanVerifier.verify(plan);
      var primitive=new LinkedHashMap<String,GraphRecipe<String>>();c.recipes().forEach(r->primitive.put(r.id(),r));
      var s=GraphFixtureRegression.summary(plan.steps(),primitive,new IdentityHashMap<>());
      for(var e:s.need().entrySet())if(BigInteger.valueOf(c.stock().getOrDefault(e.getKey(),0L)).compareTo(e.getValue())<0)throw new AssertionError("false original prefix");
      if(s.delta().getOrDefault(c.target(),BigInteger.ZERO).compareTo(BigInteger.valueOf(c.amount()))<0)throw new AssertionError("missing target");
     }else if(Set.of(GraphPlan.Result.MISSING_INPUT,GraphPlan.Result.MISSING_SEED,GraphPlan.Result.INFEASIBLE).contains(plan.result())&&c.truth().equals("SAT"))throw new AssertionError("false infeasible");
    }catch(ExecutionException e){if(!(e.getCause() instanceof PlanningBudget.Exhausted))throw e;result=e.getCause().toString();}
    // GraphCompilation/choice arenas are conservatively charged for the order's
    // lifetime and retained with its plan/cache. Transient solver zero-balance
    // is checked separately by PortfolioOracle, not inferred from this counter.
    if(budget.reservedBytes()<0||budget.reservedBytes()>(256L<<20))throw new AssertionError("graph accounting");
    System.out.println(width+"\t"+repeat+"\t"+c.name()+"\t"+result+"\tms="+(System.nanoTime()-start)/1e6+"\twork="+budget.nodes());
    if(!result.startsWith("FEASIBLE"))System.out.println("TRACE "+budget.diagnostics());
   }
  }
 }
}
