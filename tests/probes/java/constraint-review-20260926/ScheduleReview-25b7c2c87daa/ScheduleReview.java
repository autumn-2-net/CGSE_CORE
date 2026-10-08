package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.lang.reflect.*;

public class ScheduleReview {
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static BigInteger z(long n){return BigInteger.valueOf(n);}
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static boolean possible(List<GraphRecipe<String>> recipes,int[] counts,Map<String,Long> stock){
  var pending=new ArrayDeque<List<Integer>>();var seen=new HashSet<List<Integer>>();var initial=Arrays.stream(counts).boxed().toList();pending.add(initial);seen.add(initial);
  while(!pending.isEmpty()){var left=pending.remove();if(left.stream().allMatch(n->n==0))return true;Map<String,Long> held=new HashMap<>(stock);
   for(int i=0;i<counts.length;i++){long used=counts[i]-left.get(i);for(var e:recipes.get(i).inputs().entrySet())held.merge(e.getKey(),-used*e.getValue(),Long::sum);for(var e:recipes.get(i).outputs().entrySet())held.merge(e.getKey(),used*e.getValue(),Long::sum);}
   for(int i=0;i<counts.length;i++)if(left.get(i)>0&&recipes.get(i).inputs().entrySet().stream().allMatch(e->held.getOrDefault(e.getKey(),0L)>=e.getValue())){var next=new ArrayList<>(left);next.set(i,next.get(i)-1);next= new ArrayList<>(next);if(seen.add(next))pending.add(next);}
  }return false;
 }
 static void replay(PlanStep step,Map<String,GraphRecipe<String>> recipes,Map<String,BigInteger> held){
  if(step instanceof PlanStep.Sequence seq){for(var child:seq.children())replay(child,recipes,held);return;}
  if(step instanceof PlanStep.Repeat rep){for(long i=0;i<rep.times();i++)replay(rep.body(),recipes,held);return;}
  var batch=(PlanStep.Batch)step;var r=recipes.get(batch.recipe());for(long i=0;i<batch.runs();i++){for(var e:r.inputs().entrySet()){check(held.getOrDefault(e.getKey(),z(0)).compareTo(z(e.getValue()))>=0,"invalid schedule prefix");held.merge(e.getKey(),z(-e.getValue()),BigInteger::add);}for(var e:r.outputs().entrySet())held.merge(e.getKey(),z(e.getValue()),BigInteger::add);}
 }
 static CountSchedule.Result exact(List<GraphRecipe<String>> recipes,int[] counts,Map<String,Long> stock)throws Exception{
  var budget=new PlanningBudget(10000,2_000_000,128L<<20,()->false,System::nanoTime);
  var all=new ArrayList<>(recipes);Set<String> keys=new HashSet<>();for(var r:recipes){keys.addAll(r.inputs().keySet());keys.addAll(r.outputs().keySet());}
  for(String key:keys)all.add(recipe("dummy_"+key,Map.of(key,1L),Map.of(key,1L)));
  var amounts=new BigInteger[all.size()];Arrays.fill(amounts,z(0));for(int i=0;i<counts.length;i++)amounts[i]=z(counts[i]);
  CountSchedule.Result result;
  try(var model=RecipeCountModel.region(all,Map.of(),stock,Set.of(),budget);var schedule=new CountSchedule<>(model,amounts,budget)){
   Field attempt=CountSchedule.class.getDeclaredField("attempt");attempt.setAccessible(true);attempt.set(schedule,32);
   Method start=CountSchedule.class.getDeclaredMethod("nextAttempt");start.setAccessible(true);start.invoke(schedule);
   while(!schedule.step()){}result=schedule.result();
   if(result==CountSchedule.Result.WITNESS){Map<String,GraphRecipe<String>> map=new HashMap<>();all.forEach(r->map.put(r.id(),r));Map<String,BigInteger> held=new HashMap<>();stock.forEach((key,n)->held.put(key,z(n)));replay(schedule.witness(),map,held);}
   if(recipes.size()>12)System.out.println("POR wide trace "+budget.diagnostics());
  }
  check(budget.reservedBytes()==0,"schedule memory leak");return result;
 }
 public static void main(String[]args)throws Exception{Random r=new Random(192926);int pass=0,fail=0;
  for(int t=0;t<600;t++){int n=2+r.nextInt(5);var recipes=new ArrayList<GraphRecipe<String>>();int[] counts=new int[n];Map<String,Long> stock=new LinkedHashMap<>();for(int k=0;k<4;k++)stock.put("m"+k,(long)r.nextInt(4));
   for(int i=0;i<n;i++){Map<String,Long> in=new LinkedHashMap<>(),out=new LinkedHashMap<>();for(int k=0;k<4;k++){long a=r.nextInt(3),b=r.nextInt(3);if(a>0)in.put("m"+k,a);if(b>0)out.put("m"+k,b);}if(out.isEmpty())out.put("m0",1L);recipes.add(recipe("r"+i,in,out));counts[i]=1+r.nextInt(3);}
   boolean wanted=possible(recipes,counts,stock);var got=exact(recipes,counts,stock);check(got!=(CountSchedule.Result.UNKNOWN),"small exact cutoff");check((got==CountSchedule.Result.WITNESS)==wanted,"POR missed schedule test="+t);if(wanted)pass++;else fail++;
  }
  List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,Long> stock=new HashMap<>();int[] counts=new int[32];Arrays.fill(counts,1);
  for(int i=0;i<16;i++){stock.put("a"+i,1L);recipes.add(recipe("out"+i,Map.of("a"+i,1L),Map.of("b"+i,1L)));recipes.add(recipe("back"+i,Map.of("b"+i,1L),Map.of("a"+i,1L)));}
  check(exact(recipes,counts,stock)==CountSchedule.Result.WITNESS,"independent component expansion limit");
  // Two branches compete for the same catalyst and must not be separated.
  recipes=List.of(recipe("take",Map.of("C",1L),Map.of("A",1L)),recipe("restore",Map.of("A",1L),Map.of("C",1L)),recipe("consume",Map.of("C",1L),Map.of("B",1L)));
  check(exact(recipes,new int[]{1,1,1},Map.of("C",1L))==CountSchedule.Result.WITNESS,"shared catalyst order lost");
  System.out.println("PASS POR vs independent exhaustive multiset search cases=600 feasible="+pass+" dead="+fail+" plus 16 independent cycles and shared catalyst");
 }
}
