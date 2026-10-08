package org.cgse.core;
import java.nio.file.*;import java.util.*;import java.math.*;
public class FallbackScopeProbe {
 static int checks;
 static void ok(boolean x,String s){checks++;if(!x)throw new AssertionError(s);}
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> o){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),o);}
 public static void main(String[] a)throws Exception {
  var all=new ArrayList<GraphRecipe<String>>();all.add(r("bad",Map.of("absent",1L),Map.of("target",1L)));all.add(r("good",Map.of("stock",1L),Map.of("target",1L)));
  for(int i=0;i<70000;i++)all.add(r("noise"+i,Map.of("raw"+i,1L),Map.of("extra"+i,1L)));
  var c=new GraphCompiler<>(all);var b=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
  try(var order=GraphFallbackSources.create(c,Map.of("stock",1L),Set.of(),"target",true,b)){
   ok(order!=null,"full catalog gate");var src=order.sources("target",true);ok(src.size()==2,"unknown candidate dropped");ok(src.get(0).id().equals("good"),"stock ordering");ok(b.nodes()<100,"irrelevant catalog visited");
  }ok(b.reservedBytes()==0,"scope leak");
  b=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
  try(var order=GraphFallbackSources.create(c,Map.of("absent",1L),Set.of(),"target",true,b)){ok(order.sources("target",true).get(0).id().equals("bad"),"inventory scope reused");}ok(b.reservedBytes()==0,"updated scope leak");
  var wide=new LinkedHashMap<String,Long>();for(int i=0;i<10000;i++)wide.put("missing"+i,1L);
  c=new GraphCompiler<>(List.of(r("wide",wide,Map.of("target",1L)),r("later",Map.of("stock",1L),Map.of("target",1L))));
  b=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
  try(var order=GraphFallbackSources.create(c,Map.of("stock",1L),Set.of(),"target",true,b)){ok(order!=null,"partial scope discarded");var src=order.sources("target",true);ok(src.size()==2,"cutoff source removed");ok(src.get(0).id().equals("later"),"unvisited funded source lost");}ok(b.reservedBytes()==0,"partial scope leak");
  Path base=Path.of(a[0]);var manual=MixedSweep.read(base.resolve("manual.json"));var extra=MixedSweep.read(base.resolve("reduced-extra.json"));
  for(String mode:List.of("manual-first","extra-first"))for(var ids:List.of(new int[]{0,1,2},new int[]{0,2,1},new int[]{1,0,2},new int[]{1,2,0},new int[]{2,0,1},new int[]{2,1,0})){
   var rs=new ArrayList<GraphRecipe<String>>();for(int id:ids)rs.add(extra.recipes().get(id));var ps=new LinkedHashMap<String,List<GraphRecipe<String>>>();for(var rr:rs)for(var key:rr.executionOutputs().keySet())ps.computeIfAbsent(key,k->new ArrayList<>()).add(rr);
   var compiler=MixedSweep.compiler(manual,new MixedSweep.Data(rs,ps,Map.of(),Set.of()),mode,0);
   b=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);var p=GraphFallback.plan(compiler,"{\"#c\":\"ae2:f\",id:\"gtceu:miracle\"}",1,manual.stock(),manual.external(),Map.of(),true,true,b);
   ok(p.feasible(),"reduced order "+mode+Arrays.toString(ids));PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);p.initialExact().forEach((k,v)->ok(v.compareTo(BigInteger.valueOf(manual.stock().getOrDefault(k,0L)))<=0,"inventory overdraft"));
   ok(b.reservedBytes()==0,"reduced leak");System.out.println(mode+Arrays.toString(ids)+" feasible "+b.nodes());
  }
  System.out.println("assertions="+checks);
 }
}