import java.util.*;
import java.util.concurrent.*;
import org.cgse.core.*;

public class ParallelBench {
 static List<ComplexCycleStress.Case> cases=List.of(ExtremeCycleStress.dag(8192,"deep",Long.MAX_VALUE),ComplexCycleStress.ring(8192,Long.MAX_VALUE,1),
   new ComplexCycleStress.Case("nested60long",DeepLongBoundary.nested(60),"Q0",Long.MAX_VALUE,Map.of("C0",1L),Map.of(),null,true),ComplexCycleStress.hub(2048,100_000_017,1));
 record Sample(double ms,int active,long slices){}
 static Sample batch(PlanningScheduler scheduler,int count,int first) throws Exception {
  var works=new ArrayList<GraphPlanningWork<String>>();var budgets=new ArrayList<PlanningBudget>();var used=new ArrayList<ComplexCycleStress.Case>();
  for(int i=0;i<count;i++){var c=cases.get((first+i)%cases.size());used.add(c);var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
   works.add(new GraphPlanningWork<>(new GraphCompiler<>(c.recipes()),c.target(),c.amount(),c.stock(),true,true,b).catalysts(CatalystPolicy.MINIMAL));budgets.add(b);}
  long before=scheduler.slices(),start=System.nanoTime();var futures=new ArrayList<CompletableFuture<GraphPlan<String>>>();
  for(int i=0;i<count;i++)futures.add(scheduler.submit(works.get(i),budgets.get(i)));
  CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60,TimeUnit.SECONDS);double ms=(System.nanoTime()-start)/1e6;
  for(int i=0;i<count;i++){var p=futures.get(i).join();if(!p.feasible())throw new AssertionError(used.get(i).name()+"="+p.result()+" work="+budgets.get(i).nodes()+" detail="+budgets.get(i).failureDetail());ExtremeCycleStress.validate(used.get(i),p);}
  return new Sample(ms,scheduler.peakActive(),scheduler.slices()-before);
 }
 static double median(double[] v){Arrays.sort(v);return v[v.length/2];}
 public static void main(String[]args)throws Exception{
  System.out.println("CPU logical="+Runtime.getRuntime().availableProcessors()+" java="+System.getProperty("java.version"));
  for(int warm=0;warm<3;warm++)try(var s=new PlanningScheduler(4,32,4096,2_000_000)){batch(s,16,0);}
  for(int threads:new int[]{1,4,8,16}){
   try(var s=new PlanningScheduler(threads,32,4096,2_000_000)){
    for(int c=0;c<cases.size();c++){batch(s,1,c);var times=new double[5];for(int i=0;i<5;i++)times[i]=batch(s,1,c).ms();
     System.out.printf(Locale.ROOT,"THREAD single name=%s workers=%d median_ms=%.3f peak_active=%d%n",cases.get(c).name(),threads,median(times),s.peakActive());}
   }
   try(var s=new PlanningScheduler(threads,32,4096,2_000_000)){
    batch(s,16,0);var times=new double[5];for(int i=0;i<5;i++)times[i]=batch(s,16,0).ms();
    double ms=median(times);System.out.printf(Locale.ROOT,"THREAD batch orders=16 workers=%d median_ms=%.3f orders_per_s=%.3f peak_active=%d%n",threads,ms,16000/ms,s.peakActive());
   }
  }
 }
}
