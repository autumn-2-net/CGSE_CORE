package org.cgse.core;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class RestartSafety {
 static int checks;
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static PlanningBudget b(long nodes,long bytes){return new PlanningBudget(0,nodes,bytes,()->false,System::nanoTime);}
 public static void main(String[]args){
  var recipes=List.of(OrderProbe.recipe("priority",Map.of("raw",2L),Map.of("C",1L)),OrderProbe.recipe("cheap",Map.of("raw",1L),Map.of("C",1L)));
  var compiler=new GraphCompiler<>(recipes);var budget=b(131072,16L<<20);
  var p=GraphFallback.plan(compiler,"C",2,Map.of("raw",4L),Set.of(),Map.of(),false,true,budget);
  check(p.feasible()&&p.patternTimes().equals(Map.of("priority",2L)),"provider priority changed");check(budget.reservedBytes()==0,"priority memory");
  for(long nodes:new long[]{10,1000,24575}){var limited=b(nodes,16L<<20);try(var sources=GraphFallbackSources.create(compiler,Map.of("raw",4L),Set.of(),"C",true,limited)){check(sources==null,"low work must skip optional helper");}check(limited.reservedBytes()==0,"low work leaked");}
  for(long bytes:new long[]{1,1024,65536}){var limited=b(131072,bytes);try(var sources=GraphFallbackSources.create(compiler,Map.of("raw",4L),Set.of(),"C",true,limited)){check(sources==null,"low memory must skip optional helper");}check(limited.reservedBytes()==0,"low memory leaked");}
  var reverse=new ArrayList<GraphRecipe<String>>();for(int i=249;i>=0;i--)reverse.add(OrderProbe.recipe("chain"+i,Map.of("R"+i,1L),Map.of("R"+(i+1),1L)));
  var interrupted=b(131072,16L<<20);try(var sources=GraphFallbackSources.create(new GraphCompiler<>(reverse),Map.of("R0",1L),Set.of(),"R250",true,interrupted)){check(sources==null,"partial reachability must not prune");}check(interrupted.reservedBytes()==0,"partial closure memory");
  var live=List.of(OrderProbe.recipe("impossible",Map.of("absent",1L),Map.of("C",1L)),OrderProbe.recipe("returned",Map.of("tool",1L,"raw",1L),Map.of("tool",1L,"C",1L)),OrderProbe.recipe("external",Map.of("remote",1L),Map.of("C",1L)));
  var exact=b(131072,16L<<20);try(var sources=GraphFallbackSources.create(new GraphCompiler<>(live),Map.of("tool",1L,"raw",1L),Set.of("remote"),"C",true,exact)){check(sources!=null,"helper absent");for(boolean cost:new boolean[]{false,true}){var selected=sources.sources("C",cost);check(selected.size()==2&&selected.stream().noneMatch(r->r.id().equals("impossible")),"closure tools/external");}}check(exact.reservedBytes()==0,"completed closure memory");
  var calls=new AtomicInteger();var cancelled=new PlanningBudget(0,131072,16L<<20,()->calls.incrementAndGet()>10,System::nanoTime);boolean stopped=false;try(var sources=GraphFallbackSources.create(new GraphCompiler<>(reverse),Map.of("R0",1L),Set.of(),"R250",true,cancelled)){}catch(java.util.concurrent.CancellationException expected){stopped=true;}check(stopped,"cancellation ignored");check(cancelled.reservedBytes()==0,"cancellation memory");
  var large=List.of(OrderProbe.recipe("overflow",Map.of("raw",2L),Map.of("C",1L)));var big=b(131072,16L<<20);p=GraphFallback.plan(new GraphCompiler<>(large),"C",Long.MAX_VALUE,Map.of("raw",Long.MAX_VALUE),Set.of(),Map.of(),false,true,big);check(!p.feasible(),"overflow synthesizes stock");check(big.reservedBytes()==0,"overflow memory");
  for(long nodes:new long[]{8,32,128,1024}){var small=b(nodes,16L<<20);try{GraphFallback.plan(new GraphCompiler<>(reverse),"R250",1,Map.of("R0",1L),Set.of(),Map.of(),false,true,small);}catch(PlanningBudget.Exhausted expected){}check(small.reservedBytes()==0,"exhaustion memory "+nodes);}
  System.out.println("PASS restart_safety="+checks+" provider_priority, optional_limits, incomplete_closure, returned_tools, external_inputs, cancellation, overflow, exhaustion");
 }
}
