package org.cgse.core;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigInteger;
import java.nio.file.*;
import com.google.gson.Gson;

public class CatalogProbe {
 static long checks; static synchronized void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
 static List<GraphRecipe<String>> catalog(){var list=new ArrayList<GraphRecipe<String>>();
  for(int i=0;i<40;i++){var in=new ArrayList<GraphRecipe.Slot<String>>();in.add(new GraphRecipe.Slot<>("k"+i,1+i%5));in.add(new GraphRecipe.Slot<>("raw"+i%3,2));var outputs=new LinkedHashMap<String,Long>();outputs.put("k"+(i+1),3L+i%4);outputs.put("side"+i%3,1L);
   if(i%3==0)in.add(new GraphRecipe.Slot<>("config",1,-1,true));
   if(i%7==0){in.add(new GraphRecipe.Slot<>("reusable",1,-1,true,true));outputs.put("reusable",1L);}
   list.add(new GraphRecipe<>("r"+i,"r"+i,in,outputs));
   if(i%4==0)list.add(new GraphRecipe<>("alt"+i,"alt"+i,List.of(new GraphRecipe.Slot<>("k"+(i+1),2)),Map.of("k"+i,4L)));
  }return list;
 }
 static void compare(GraphCompiler<String> c,int seed){var r=new Random(seed*97L);String target="k"+(1+r.nextInt(40));long amount=seed%7==0?Long.MAX_VALUE:1+r.nextInt(5000);var stock=new LinkedHashMap<String,Long>();for(int i=0;i<43;i++)stock.put("k"+i,seed%11==0?Long.MAX_VALUE:r.nextLong(500));stock.put("config",3L);stock.put("reusable",1L);
  var seeds=new LinkedHashMap<String,Long>();if(seed%3==0)seeds.put("k0",seed%13L);if(seed%5==0)seeds.put(target,seed%7L);if(seed%17==0)seeds.put("unrelated",2L);
  Set<String> external=seed%4==0?Set.of("raw0","raw1","config"):Set.of();Set<String> excluded=seed%6==0?Set.of("r4"):Set.of();boolean force=seed%2==0;
  var a=budget();var b=budget();
  try(var expected=ColdRecipeCountModel.create(c,target,amount,stock,seeds,external,excluded,force,a);var got=RecipeCountModel.create(c,target,amount,stock,seeds,external,excluded,force,b)){
   check(expected!=null&&got!=null,"null model");check(expected.recipes.equals(got.recipes),"recipes "+seed);check(expected.keys.equals(got.keys),"keys "+seed);check(expected.ids.equals(got.ids),"ids "+seed);check(expected.constraints.equals(got.constraints),"rows "+seed);check(expected.rowKeys.equals(got.rowKeys),"row ids");check(expected.goals.equals(got.goals),"goals");check(expected.productionGoals.equals(got.productionGoals),"force");
   got.constraints.clear();got.goals.clear(); // Never mutate the retained matrix.
  }check(a.reservedBytes()==0&&b.reservedBytes()==0,"leak");
 }
 static void hotAndScope(){var c=new GraphCompiler<>(catalog());for(int seed=0;seed<1800;seed++){compare(c,seed);compare(c,seed);} // immediate hot lookup and mutated overlays
  var b=budget();try(var first=RecipeCountModel.create(c,"k40",1,Map.of("k0",Long.MAX_VALUE),Map.of(),Set.of(),Set.of(),false,b)){}
  try(var second=RecipeCountModel.create(c,"k40",Long.MAX_VALUE,Map.of(),Map.of(),Set.of("raw0"),Set.of(),true,b)){check(b.diagnostics().contains("reused"),"no hot hit");check(second.goal("k40").equals(BigInteger.valueOf(Long.MAX_VALUE)),"stale goal");}
  var changed=new ArrayList<>(catalog());changed.removeIf(r->r.id().equals("r39"));var c2=new GraphCompiler<>(changed);try(var second=RecipeCountModel.create(c2,"k40",1,Map.of(),Map.of(),Set.of(),Set.of(),false,b)){check(second.recipes.isEmpty(),"stale snapshot");}
  check(b.reservedBytes()==0,"scope leak");
 }
 static void concurrent()throws Exception{var c=new GraphCompiler<>(catalog());var pool=Executors.newFixedThreadPool(4);try{var tasks=new ArrayList<Callable<Void>>();for(int n=0;n<4;n++){final int offset=n;tasks.add(()->{for(int i=0;i<180;i++)compare(c,3000+(i+offset)%120);return null;});}for(var f:pool.invokeAll(tasks))f.get();}finally{pool.shutdownNow();}}
 static void cancel(){for(int cap=1;cap<=160;cap++){var c=new GraphCompiler<>(catalog());final int stop=cap*7;var steps=new java.util.concurrent.atomic.AtomicInteger();var b=new PlanningBudget(0,20_000_000,32L<<20,()->steps.incrementAndGet()>=stop,System::nanoTime);try(var m=RecipeCountModel.create(c,"k40",123,Map.of(),Map.of(),Set.of(),Set.of(),true,b)){}catch(java.util.concurrent.CancellationException ok){}check(b.reservedBytes()==0,"cancel leak "+cap);compare(c,cap);}}
 public static void main(String[]args)throws Exception{hotAndScope();concurrent();cancel();var report=Map.of("assertions",checks,"overlay_comparisons",4480,"cancel_cases",160);Files.writeString(Path.of(args[0],"catalog-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
