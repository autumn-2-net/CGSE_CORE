package org.cgse.core;
import java.math.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class PortfolioOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static class Work implements PlanningScheduler.Work<GraphPlan<String>> {
  final IntegerCountSearch<String> search; final AtomicInteger closes=new AtomicInteger();
  Work(IntegerCountSearch<String> search){this.search=search;}
  public boolean advance(PlanningScheduler.Slice slice){while(slice.next()){if(search.step(slice)){if(search.paused()){search.resume();continue;}return true;}if(search.waitingFor()!=null)return false;}return false;}
  public CompletableFuture<?> waitingFor(){return search.waitingFor();}
  public GraphPlan<String> result(){return search.result();}
  public void close(){search.close();closes.incrementAndGet();}
 }
 public static void main(String[]args)throws Exception {
  var force=IntegerCountSearch.class.getDeclaredMethod("enqueuePortfolio");force.setAccessible(true);
  int sat=0,closed=0;
  for(int width:new int[]{1,4,8,16})for(int repetition=0;repetition<5;repetition++)for(boolean impossible:new boolean[]{false,true}){
   var rows=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();var goal=new LinkedHashMap<String,Long>();
   for(int i=0;i<12;i++){stock.put("U"+i,1L);goal.put("D"+i,1L);rows.add(recipe("a"+i,Map.of("U"+i,1L),Map.of("D"+i,1L,"A",(long)(i+1))));rows.add(recipe("b"+i,Map.of("U"+i,1L),Map.of("D"+i,1L,"B",(long)(i+1))));}
   goal.put("A",39L);goal.put("B",impossible?40L:39L);rows.add(recipe("finish",goal,Map.of("Q",1L)));
   var b=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);b.enableMetrics();
   var search=new IntegerCountSearch<>(new GraphCompiler<>(rows),"Q",1,stock,Map.of(),Set.of(),Set.of(),false,true,b,System.nanoTime());force.invoke(search);
   var work=new Work(search);try(var scheduler=new PlanningScheduler(width,8,128,1000000)){
    var plan=scheduler.submit(work,b).get(15,TimeUnit.SECONDS);
    if(impossible){if(plan!=null&&plan.feasible()||!search.infeasible())throw new AssertionError("portfolio wrong UNSAT "+b.diagnostics());closed++;}
    else {if(plan==null||!plan.feasible())throw new AssertionError("lost portfolio witness "+b.diagnostics());PlanVerifier.verify(plan);sat++;}
    if(scheduler.peakActive()>width)throw new AssertionError("worker cap");
   }
   if(work.closes.get()!=1||b.reservedBytes()!=0)throw new AssertionError("portfolio lifecycle "+work.closes+" "+b.reservedBytes());
  }
  System.out.println("PASS heterogeneous actual integer searches at 1,4,8,16 workers; SAT="+sat+" UNSAT="+closed+" original plans verified, worker/workspace accounting");
 }
}
