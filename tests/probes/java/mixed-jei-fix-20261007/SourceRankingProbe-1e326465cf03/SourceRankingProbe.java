package org.cgse.core;
import java.util.*;
public final class SourceRankingProbe {
 static long checks;static void ok(boolean x){checks++;if(!x)throw new AssertionError("check "+checks);}
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){var slots=new ArrayList<GraphRecipe.Slot<String>>();int n=0;for(var e:in.entrySet())slots.add(new GraphRecipe.Slot<>(e.getKey(),e.getValue(),n++,false,false));return new GraphRecipe<>(id,id,slots,out);}
 static PlanningBudget b(){return new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);}
 static void verify(GraphCompiler<String> c,Map<String,Long> stock,Set<String> external,String target){
  var budget=b();var reachable=new HashSet<String>(external);stock.forEach((k,v)->{if(v>0)reachable.add(k);});boolean change=true;while(change){change=false;for(var r:c.catalog())if(reachable.containsAll(r.inputs().keySet()))change|=reachable.addAll(r.executionOutputs().keySet());}
  try(var ranking=GraphSourceRanking.create(c,stock,external,target,true,budget)){ok(ranking!=null);for(boolean cost:List.of(false,true)){var ordered=ranking.sources(target,cost);ok(ordered.size()==c.producers(target).size());ok(new HashSet<>(ordered).equals(new HashSet<>(c.producers(target))));boolean unknown=false,nonproductive=false;for(var r:ordered){boolean productive=r.executionOutputs().getOrDefault(target,0L)>r.inputs().getOrDefault(target,0L);if(!productive&&!nonproductive){nonproductive=true;unknown=false;}if(productive)ok(!nonproductive);boolean live=reachable.containsAll(r.inputs().keySet());if(!live)unknown=true;else ok(!unknown);}}}
  ok(budget.reservedBytes()==0);
 }
 public static void main(String[] args){
  var rs=List.of(r("bad",Map.of("missing",1L),Map.of("t",1L)),r("a",Map.of("a",1L),Map.of("t",1L)),r("b",Map.of("b",1L),Map.of("t",1L)));var c=new GraphCompiler<>(rs);Object index=null;
  for(String seed:List.of("a","b","a","b")){var budget=b();try(var ranking=GraphSourceRanking.create(c,Map.of(seed,1L),Set.of(),"t",true,budget)){ok(ranking.sources("t",true).get(0).id().equals(seed));}ok(budget.reservedBytes()==0);if(index==null)index=c.sourceIndex();else ok(index==c.sourceIndex());}
  var cycle=new GraphCompiler<>(List.of(r("bad",Map.of("missing",1L),Map.of("t",1L)),r("up",Map.of("t",1L),Map.of("a",2L)),r("down",Map.of("a",1L),Map.of("t",2L))));verify(cycle,Map.of("t",1L),Set.of(),"t");
  var containers=new GraphCompiler<>(List.of(r("return",Map.of("container",1L),Map.of("container",1L,"product",1L)),r("make",Map.of("metal",1L),Map.of("container",1L))));var containerBudget=b();try(var ranking=GraphSourceRanking.create(containers,Map.of("container",1L,"metal",1L),Set.of(),"container",true,containerBudget)){ok(ranking.sources("container",false).get(0).id().equals("make"));ok(ranking.sources("container",true).get(0).id().equals("make"));}
  Random random=new Random(834819);for(int trial=0;trial<1000;trial++){var catalog=new ArrayList<GraphRecipe<String>>();for(int id=0;id<100;id++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int k=0;k<random.nextInt(4);k++)in.put("k"+random.nextInt(30),1+random.nextLong(4));for(int k=0;k<1+random.nextInt(2);k++)out.put("k"+random.nextInt(30),1+random.nextLong(4));catalog.add(r("r"+id,in,out));}var compiler=new GraphCompiler<>(catalog);verify(compiler,Map.of("k1",3L,"k5",7L),Set.of("k2"),"k0");verify(compiler,Map.of("k3",7L),Set.of(),"k0");}
  var many=new ArrayList<GraphRecipe<String>>();many.add(r("bad",Map.of("dead",1L),Map.of("t",1L)));many.add(r("good",Map.of("k1000",1L),Map.of("t",1L)));for(int i=0;i<1000;i++)many.add(r("path"+i,Map.of("k"+i,1L),Map.of("k"+(i+1),1L)));for(int i=0;i<68000;i++)many.add(r("noise"+i,Map.of("no"+i,1L),Map.of("out"+i,1L)));var big=new GraphCompiler<>(many);var budget=b();try(var ranking=GraphSourceRanking.create(big,Map.of("k0",1L),Set.of(),"t",true,budget)){ok(ranking.sources("t",true).get(0).id().equals("good"));}ok(budget.reservedBytes()==0);System.out.println("large prep+rank work="+budget.nodes()+" index="+(big.sourceIndex()!=null));
  var limited=b();try(var ranking=GraphSourceRanking.create(big,Map.of("k0",1L),Set.of(),"t",true,limited,1024)){ok(limited.nodes()<=1024);ok(ranking.sources("t",true).size()==2);}ok(limited.reservedBytes()==0);
  System.out.println("SourceRankingProbe checks="+checks);
 }
}
