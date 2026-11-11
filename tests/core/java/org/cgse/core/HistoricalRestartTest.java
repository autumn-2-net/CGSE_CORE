// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
// Migrated from RestartSafety-d2a049fe09fd; original source remains in tests/probes.
package org.cgse.core;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class HistoricalRestartTest {
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static int checks;
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static PlanningBudget b(long nodes,long bytes){return new PlanningBudget(0,nodes,bytes,()->false,System::nanoTime);}
 public static void main(String[]args){
  var recipes=List.of(recipe("priority",Map.of("raw",2L),Map.of("C",1L)),recipe("cheap",Map.of("raw",1L),Map.of("C",1L)));
  var compiler=new GraphCompiler<>(recipes);var budget=b(131072,16L<<20);
  var p=GraphFallback.plan(compiler,"C",2,Map.of("raw",4L),Set.of(),Map.of(),false,true,budget);
  // Preserve the original provider-priority bill's four-unit upper bound.
  // A previous eager scout found raw2 here, but its unconditional overhead
  // broke existing compilation-work gates; optimality is not promised.
  var summary=SequenceSummary.of(p.steps(),p.recipes());
  check(p.feasible()&&p.initialExact().keySet().equals(Set.of("raw"))&&p.initialExact().get("raw").compareTo(java.math.BigInteger.valueOf(4))<=0&&summary.delta("raw").negate().compareTo(java.math.BigInteger.valueOf(4))<=0&&summary.delta("C").compareTo(java.math.BigInteger.TWO)>=0,"source choice regressed original raw4 bound or manufacturing");PlanVerifier.verify(p);check(budget.reservedBytes()==0,"priority memory");
  // Domain construction is mandatory solver compilation, rather than the
  // retired optional inventory helper: limits may interrupt but must release.
  for(long nodes:new long[]{10,1000,24575}){var limited=b(nodes,16L<<20);try(var domain=compiler.beginDomain("C",Set.of(),GraphMode.ACYCLIC,limited)){try{while(!domain.step()){} check(domain.result().producers("C").size()==2,"domain discarded ordinary alternatives");}catch(PlanningBudget.Exhausted expected){}}check(limited.reservedBytes()==0,"low work leaked");}
  for(long bytes:new long[]{1,1024,65536}){var limited=b(131072,bytes);try{try(var domain=compiler.beginDomain("C",Set.of(),GraphMode.ACYCLIC,limited)){while(!domain.step()){} check(domain.result().producers("C").size()==2,"domain discarded ordinary alternatives");}}catch(PlanningBudget.Exhausted expected){}check(limited.reservedBytes()==0,"low memory leaked");}
  var reverse=new ArrayList<GraphRecipe<String>>();for(int i=249;i>=0;i--)reverse.add(recipe("chain"+i,Map.of("R"+i,1L),Map.of("R"+(i+1),1L)));
  var interrupted=b(131072,16L<<20);try(var domain=new GraphCompiler<>(reverse).beginDomain("R250",Set.of(),GraphMode.ACYCLIC,interrupted)){while(!domain.step()){}check(domain.result().producers("R250").stream().anyMatch(r->r.id().equals("chain249")),"completed domain pruned reachable target");}check(interrupted.reservedBytes()==0,"partial closure memory");
  var live=List.of(recipe("impossible",Map.of("absent",1L),Map.of("C",1L)),recipe("returned",Map.of("tool",1L,"raw",1L),Map.of("tool",1L,"C",1L)),recipe("external",Map.of("remote",1L),Map.of("C",1L)));
  var exact=b(131072,16L<<20);try(var domain=new GraphCompiler<>(live).beginDomain("C",Set.of(),GraphMode.ACYCLIC,exact)){while(!domain.step()){}var selected=domain.result().producers("C");check(selected.size()==2&&selected.stream().noneMatch(r->r.id().equals("returned")),"strict domain retained physical tool return or removed terminal alternatives");}check(exact.reservedBytes()==0,"completed closure memory");
  var calls=new AtomicInteger();var cancelled=new PlanningBudget(0,131072,16L<<20,()->calls.incrementAndGet()>10,System::nanoTime);boolean stopped=false;try(var domain=new GraphCompiler<>(reverse).beginDomain("R250",Set.of(),GraphMode.ACYCLIC,cancelled)){while(!domain.step()){}}catch(java.util.concurrent.CancellationException expected){stopped=true;}check(stopped,"cancellation ignored");check(cancelled.reservedBytes()==0,"cancellation memory");
  var large=List.of(recipe("overflow",Map.of("raw",2L),Map.of("C",1L)));var big=b(131072,16L<<20);p=GraphFallback.plan(new GraphCompiler<>(large),"C",Long.MAX_VALUE,Map.of("raw",Long.MAX_VALUE),Set.of(),Map.of(),false,true,big);check(!p.feasible(),"overflow synthesizes stock");check(big.reservedBytes()==0,"overflow memory");
  for(long nodes:new long[]{8,32,128,1024}){var small=b(nodes,16L<<20);try{GraphFallback.plan(new GraphCompiler<>(reverse),"R250",1,Map.of("R0",1L),Set.of(),Map.of(),false,true,small);}catch(PlanningBudget.Exhausted expected){}check(small.reservedBytes()==0,"exhaustion memory "+nodes);}
  System.out.println("PASS restart_safety="+checks+" primary_source_cost, domain_limits, incomplete_closure, physical_tool_exclusion, external_inputs, cancellation, overflow, exhaustion");
 }
}
