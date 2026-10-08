package org.cgse.core;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigInteger;
public class ThreadReview {
 record F(List<GraphRecipe<String>> recipes,int[] stock,int n){}
 static Map<Integer,F> fixtures(){var rng=new Random(90525117);var found=new LinkedHashMap<Integer,F>();for(int sample=0;sample<=887;sample++){int size=4+rng.nextInt(4),total=3+rng.nextInt(8),n=1+rng.nextInt(total);int[] stock=new int[size];for(int i=0;i<total;i++)stock[rng.nextInt(size-1)]++;var recipes=new ArrayList<GraphRecipe<String>>();for(int j=0,rs=4+rng.nextInt(13);j<rs;j++){var in=new HashMap<String,Long>();var out=new HashMap<String,Long>();for(int k=0,tokens=1+rng.nextInt(4);k<tokens;k++){in.merge(""+rng.nextInt(size),1L,Long::sum);out.merge(""+rng.nextInt(size),1L,Long::sum);}recipes.add(new GraphRecipe<>("r"+j,"r"+j,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out));}if(Set.of(8,41,185,311,618,887).contains(sample))found.put(sample,new F(recipes,stock,n));}return found;}
 public static void main(String[]args)throws Exception{int checks=0;for(var e:fixtures().entrySet())for(int width:new int[]{1,4,8,16}){F f=e.getValue();var stock=new LinkedHashMap<String,Long>();for(int i=0;i<f.stock.length;i++)stock.put(""+i,(long)f.stock[i]);var b=new PlanningBudget(15000,4_000_000,256L<<20,()->false,System::nanoTime);long start=System.nanoTime();
  try(var scheduler=new PlanningScheduler(width,16,256,1_000_000);var work=new IntegerCountSearch<>(new GraphCompiler<>(f.recipes),""+(f.stock.length-1),f.n,stock,Map.of(),Set.of(),Set.of(),false,false,b,start)){
   var task=new PlanningScheduler.Work<GraphPlan<String>>(){public boolean advance(PlanningScheduler.Slice slice){return work.step(slice);}public GraphPlan<String> result(){return work.result();}public CompletableFuture<?> waitingFor(){return work.waitingFor();}public void close(){work.close();}};
   var plan=scheduler.submit(task,b).get(20,TimeUnit.SECONDS);if(plan==null)throw new AssertionError("MISS sample="+e.getKey()+" width="+width+" "+b.diagnostics());PlanVerifier.verify(plan);var held=new HashMap<String,BigInteger>();stock.forEach((k,n)->held.put(k,BigInteger.valueOf(n)));ScheduleReview.replay(plan.steps(),plan.recipes(),held);if(held.getOrDefault(""+(f.stock.length-1),BigInteger.ZERO).compareTo(BigInteger.valueOf(f.n))<0)throw new AssertionError("goal");checks++;System.out.println("PASS sample="+e.getKey()+" width="+width+" work="+b.nodes()+" peak="+scheduler.peakActive());
  }
  if(b.reservedBytes()!=0)throw new AssertionError("worker memory leak="+b.reservedBytes());
 }System.out.println("PASS shared execution proofs workers1/4/8/16 cases="+checks);}
}
