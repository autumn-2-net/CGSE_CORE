package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;
import com.google.gson.Gson;

public class ScheduleProbe {
 static long checks; static int guided,unknown;static final List<Map<String,Object>> rows=new ArrayList<>();
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static void tally(PlanStep p,Map<String,BigInteger> out,BigInteger times){if(p instanceof PlanStep.Batch b)out.merge(b.recipe(),times.multiply(BigInteger.valueOf(b.runs())),BigInteger::add);else if(p instanceof PlanStep.Repeat r)tally(r.body(),out,times.multiply(BigInteger.valueOf(r.times())));else for(var c:((PlanStep.Sequence)p).children())tally(c,out,times);}
 static void verify(String name,List<GraphRecipe<String>> recipes,Map<String,Long> stock,Set<String> external,BigInteger[] counts,Boolean feasible,boolean greedy)throws Exception{
  var b=budget();long started=System.nanoTime();var status=CountSchedule.Result.UNKNOWN;long schedulingWork=0;
  try(var model=RecipeCountModel.forShell(recipes,Map.of(),stock,external,b)) {long at=b.nodes();try(var schedule=new CountSchedule<>(model,counts,b)){
   if(greedy){var field=CountSchedule.class.getDeclaredField("startupChecked");field.setAccessible(true);field.set(schedule,true);}
   while(!schedule.step()){}status=schedule.result();schedulingWork=b.nodes()-at;if(feasible!=null){if(feasible)check(status!=CountSchedule.Result.DEAD,"false DEAD "+name);else check(status!=CountSchedule.Result.WITNESS,"false witness "+name);}
   if(status==CountSchedule.Result.WITNESS){var byId=new LinkedHashMap<String,GraphRecipe<String>>();recipes.forEach(r->byId.put(r.id(),r));try(var summary=new SummaryComputation<>(schedule.witness(),byId,b)){while(!summary.step()){}for(var e:summary.result().required().entrySet())check(external.contains(e.getKey())||e.getValue().compareTo(BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)))<=0,"bad prefix "+name+" "+e);}
    var actual=new HashMap<String,BigInteger>();tally(schedule.witness(),actual,BigInteger.ONE);for(int i=0;i<counts.length;i++)check(counts[i].equals(actual.getOrDefault(recipes.get(i).id(),BigInteger.ZERO)),"wrong counts "+name);
   } else if(status==CountSchedule.Result.UNKNOWN)unknown++;
  }}
  check(b.reservedBytes()==0,"leak "+name);boolean repair=b.diagnostics().contains("count_schedule_repair");if(repair)guided++;rows.add(Map.of("name",name,"status",status.toString(),"work",b.nodes(),"scheduleWork",schedulingWork,"ms",(System.nanoTime()-started)/1e6,"guided",repair));
 }
 static boolean oracle(List<GraphRecipe<String>> rs,Map<String,Long> stock,BigInteger[] counts){return dfs(rs,new HashMap<>(stock),counts.clone(),new HashSet<>());}
 static boolean dfs(List<GraphRecipe<String>> rs,Map<String,Long> held,BigInteger[] c,Set<List<BigInteger>> seen){boolean done=true;for(var v:c)done&=v.signum()==0;if(done)return true;if(!seen.add(List.of(c.clone())))return false;for(int i=0;i<c.length;i++){if(c[i].signum()==0)continue;var r=rs.get(i);boolean enabled=true;for(var e:r.inputs().entrySet())enabled&=held.getOrDefault(e.getKey(),0L)>=e.getValue();if(!enabled)continue;var next=new HashMap<>(held);r.inputs().forEach((k,v)->next.merge(k,-v,Long::sum));r.outputs().forEach((k,v)->next.merge(k,v,Long::sum));c[i]=c[i].subtract(BigInteger.ONE);if(dfs(rs,next,c,seen)){c[i]=c[i].add(BigInteger.ONE);return true;}c[i]=c[i].add(BigInteger.ONE);}return false;}
 public static void main(String[]args)throws Exception{
  var trap=List.of(recipe("sink",Map.of("a",1L),Map.of("goal",1L)),recipe("return",Map.of("c",1L),Map.of("a",1L)),recipe("start",Map.of("a",1L),Map.of("c",1L)));
  for(int i=0;i<18;i++){final int sample=i;var rs=new ArrayList<>(trap);Collections.shuffle(rs,new Random(i));BigInteger[] counts=rs.stream().map(r->r.id().equals("sink")?BigInteger.ONE:BigInteger.valueOf(sample%3==0?100:1)).toArray(BigInteger[]::new);verify("trap"+i,rs,Map.of("a",1L),Set.of(),counts,true,false);verify("greedy-trap"+i,rs,Map.of("a",1L),Set.of(),counts,true,true);}
  for(int seed=0;seed<1200;seed++){var r=new Random(seed*1009L);int n=3+r.nextInt(4);var rs=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();for(int k=0;k<4;k++)stock.put("k"+k,(long)r.nextInt(4));BigInteger[] counts=new BigInteger[n];for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int j=0;j<1+r.nextInt(2);j++)in.merge("k"+r.nextInt(4),1L+r.nextInt(2),Long::sum);for(int j=0;j<1+r.nextInt(2);j++)out.merge("k"+r.nextInt(4),1L+r.nextInt(3),Long::sum);rs.add(recipe("r"+i,in,out));counts[i]=BigInteger.valueOf(r.nextInt(3));}boolean truth=oracle(rs,stock,counts);verify("random"+seed,rs,stock,Set.of(),counts,truth,seed%3==0);}
  for(int seed=0;seed<800;seed++){var random=new Random(seed*7919L);int n=6+random.nextInt(12),resources=3+random.nextInt(4);var rs=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();for(int k=0;k<resources;k++)stock.put("k"+k,3L+random.nextInt(6));BigInteger[] counts=new BigInteger[n];Arrays.fill(counts,BigInteger.ZERO);
   for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int j=0;j<1+random.nextInt(2);j++)in.merge("k"+random.nextInt(resources),1L+random.nextInt(3),Long::sum);for(int j=0;j<1+random.nextInt(2);j++)out.merge("k"+random.nextInt(resources),1L+random.nextInt(4),Long::sum);rs.add(recipe("r"+i,in,out));}
   var held=new HashMap<>(stock);for(int step=0;step<180;step++){var ready=new ArrayList<Integer>();for(int i=0;i<n;i++){boolean enabled=true;for(var e:rs.get(i).inputs().entrySet())enabled&=held.getOrDefault(e.getKey(),0L)>=e.getValue();if(enabled)ready.add(i);}if(ready.isEmpty())break;int chosen=ready.get(random.nextInt(ready.size()));counts[chosen]=counts[chosen].add(BigInteger.ONE);rs.get(chosen).inputs().forEach((k,v)->held.merge(k,-v,Long::sum));rs.get(chosen).outputs().forEach((k,v)->held.merge(k,v,Long::sum));}
   verify("feasible"+seed,rs,stock,Set.of(),counts,true,false);
  }
  var g=new Gson();Files.writeString(Path.of(args[0],"schedule-results.json"),g.toJson(rows));var report=Map.of("cases",rows.size(),"assertions",checks,"guided",guided,"unknown",unknown);Files.writeString(Path.of(args[0],"schedule-probe.json"),g.toJson(report));System.out.println(report);
 }
}
