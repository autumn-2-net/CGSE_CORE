package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.io.*;
import com.google.gson.*;
public final class PolicyFirstWitnessProbe {
 record Case(String id,List<GraphRecipe<String>> recipes,Map<String,Long> stock,String target,long amount){}
 static Case random(int seed){
  var r=new Random(901+seed*7001L);int n=6+r.nextInt(10),keys=3+r.nextInt(4);var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();for(int k=0;k<keys;k++)stock.put("k"+k,1L+r.nextInt(4));
  for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int j=0;j<1+r.nextInt(2);j++)in.merge("k"+r.nextInt(keys),1L+r.nextInt(3),Long::sum);for(int j=0;j<1+r.nextInt(2);j++)out.merge("k"+r.nextInt(keys),1L+r.nextInt(4),Long::sum);recipes.add(OrderRetryRegression.recipe("r"+i,in,out));}
  var held=new HashMap<>(stock);for(int step=0;step<80;step++){var ready=new ArrayList<GraphRecipe<String>>();for(var recipe:recipes)if(recipe.inputs().entrySet().stream().allMatch(e->held.getOrDefault(e.getKey(),0L)>=e.getValue()))ready.add(recipe);if(ready.isEmpty())break;var recipe=ready.get(r.nextInt(ready.size()));recipe.inputs().forEach((k,v)->held.merge(k,-v,Long::sum));recipe.outputs().forEach((k,v)->held.merge(k,v,Long::sum));}
  String target=stock.keySet().stream().max(Comparator.comparingLong(k->held.getOrDefault(k,0L)-stock.get(k))).orElseThrow();long end=held.get(target),start=stock.get(target);return end>start?new Case("random-"+seed,recipes,stock,target,start+(end-start+1)/2):null;
 }
 static List<Case> cases(Path file)throws Exception{
  var cases=new ArrayList<Case>();for(int seed=0;cases.size()<128;seed++){var c=random(seed);if(c!=null)cases.add(c);}
  for(var raw:JsonParser.parseString(Files.readString(file)).getAsJsonArray()){
   var group=raw.getAsJsonObject();var catalog=LargeSweep.catalog(group.getAsJsonObject("catalog"));cases.add(new Case(group.get("id").getAsString(),catalog.recipes(),LargeSweep.amounts(group.getAsJsonObject("stock")),group.get("target").getAsString(),group.getAsJsonArray("requests").get(0).getAsJsonObject().get("amount").getAsLong()));
  }return cases;
 }
 static Map<String,Object> solve(Case c){
  var b=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);long start=System.nanoTime();GraphPlan<String> plan=null;String error="";long firstWork=-1,firstNanos=-1;boolean infeasible=false;
  try(var search=new IntegerCountSearch<>(new GraphCompiler<>(c.recipes),c.target,c.amount,c.stock,Map.of(),Set.of(),Set.of(),false,false,b,start)){
   int rounds=0;do{while(!search.step()){plan=search.result();if(plan!=null)break;}if(plan==null)plan=search.result();if(plan!=null||search.infeasible()||!search.paused())break;search.resume();}while(++rounds<1000);
   if(plan!=null){firstWork=b.nodes();firstNanos=System.nanoTime()-start;}infeasible=search.infeasible();
  }catch(PlanningBudget.Exhausted ex){error=ex.limit().name();}catch(Throwable ex){error=ex.toString();}
  if(plan!=null)PlanVerifier.verifyRuntimeInventory(plan);
  var row=new LinkedHashMap<String,Object>();row.put("id",c.id);row.put("verified",plan!=null);row.put("firstWork",firstWork);row.put("firstNanos",firstNanos);row.put("infeasible",infeasible);row.put("work",b.nodes());row.put("error",error);row.put("diagnostics",b.diagnostics());row.put("reserved",b.reservedBytes());if(b.reservedBytes()!=0)throw new AssertionError(row);return row;
 }
 public static void main(String[]args)throws Exception{var cases=cases(Path.of(args[0]));for(int i=0;i<32;i++)solve(cases.get(i));try(var writer=Files.newBufferedWriter(Path.of(args[1]))){var gson=new Gson();for(var c:cases){writer.write(gson.toJson(solve(c)));writer.newLine();}}System.out.println("cases="+cases.size());}
}
