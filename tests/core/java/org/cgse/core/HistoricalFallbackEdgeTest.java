// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
// Nine original inputs from FallbackEdgeProbe-aed28cd71e35, both recipe domains.
package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public final class HistoricalFallbackEdgeTest {
 static int checks;
 static GraphMode mode;
 static void check(boolean okay,String reason){if(!okay)throw new AssertionError(mode+": "+reason);}
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){
  return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
 }
 static GraphPlan<String> plan(List<GraphRecipe<String>> recipes,long amount,Map<String,Long> stock,Map<String,Long> seeds,boolean force,long workLimit,long memory){
  var budget=new PlanningBudget(0,workLimit,memory,()->false,System::nanoTime);
  var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"C",amount,stock,Set.of(),seeds,false,force,budget,mode);
  GraphPlan<String> plan;
  try {while(!work.step()){}plan=work.result();}finally{work.close();}
  check(budget.reservedBytes()==0,"workspace leak="+budget.reservedBytes());
  if(plan.feasible())verify(plan,stock,force);
  else if(mode==GraphMode.ACYCLIC)check(plan.result()==GraphPlan.Result.INCOMPLETE_MODEL,"restricted negative escaped: "+plan.result());
  checks++;return plan;
 }
 static void verify(GraphPlan<String> plan,Map<String,Long> stock,boolean force){
  PlanVerifier.verify(plan);
  var held=new HashMap<>(plan.initialExact());
  held.forEach((key,value)->check(value.compareTo(BigInteger.valueOf(stock.getOrDefault(key,0L)))<=0,"inventory overdraw: "+key));
  var cursor=new PlanCursor(plan.steps());int runs=0;
  for(var batch=cursor.current();batch!=null;batch=cursor.current()){
   var recipe=plan.recipes().get(batch.recipe());
   for(long i=0;i<batch.runs();i++){
    check(++runs<10000,"unexpected long witness");
    recipe.inputs().forEach((key,value)->check(held.getOrDefault(key,BigInteger.ZERO).compareTo(BigInteger.valueOf(value))>=0,"unfunded input: "+key));
    recipe.inputs().forEach((key,value)->held.merge(key,BigInteger.valueOf(value).negate(),BigInteger::add));
    recipe.executionOutputs().forEach((key,value)->held.merge(key,BigInteger.valueOf(value),BigInteger::add));
   }
   cursor.dispatched(batch.runs());
  }
  check(held.getOrDefault("C",BigInteger.ZERO).compareTo(BigInteger.valueOf(plan.amount()).add(BigInteger.valueOf(plan.seeds().getOrDefault("C",0L))))>=0,"short delivery");
  plan.seeds().forEach((key,value)->check(held.getOrDefault(key,BigInteger.ZERO).compareTo(BigInteger.valueOf(value))>=0,"final seed lost: "+key));
  check(!force||SequenceSummary.of(plan.steps(),plan.recipes()).delta("C").compareTo(BigInteger.valueOf(plan.amount()))>=0,"force target loan replaced manufacturing");
 }
 static void run(){
  var tool=List.of(recipe("tool",Map.of("A",1L,"B",1L),Map.of("A",1L,"C",1L)));
  var p=plan(tool,7,Map.of("A",1L,"B",7L,"C",100L),Map.of("A",1L),true,131072,64L<<20);
  check(mode == GraphMode.ACYCLIC ? !p.feasible() : p.feasible() && p.patternTimes().get("tool")==7,"returned tool requires only one copy in FULL; excluded physical self-return in ACYCLIC");
  check(!plan(tool,7,Map.of("B",7L),Map.of(),true,131072,64L<<20).feasible(),"missing tool invented");
  var rs=List.of(recipe("need",Map.of("X",1L,"Y",1L),Map.of("C",1L)),recipe("cheapX",Map.of("A",1L),Map.of("X",1L)),recipe("expensiveY",Map.of("A",1L),Map.of("Y",1L)),recipe("otherX",Map.of("B",1L),Map.of("X",1L)));
  check(plan(rs,3,Map.of("A",3L,"B",3L),Map.of(),true,131072,64L<<20).feasible(),"sibling resource backtrack");
  var loops=List.of(recipe("out",Map.of("C",1L),Map.of("A",1L)),recipe("back",Map.of("A",1L,"B",1L),Map.of("C",1L)));
  check(!plan(loops,3,Map.of("C",100L,"B",100L),Map.of(),true,131072,64L<<20).feasible(),"zero-output fuel loop");
  var simple=List.of(recipe("make",Map.of("A",1L),Map.of("C",1L)));
  check(plan(simple,3,Map.of("A",3L,"C",100L),Map.of(),true,131072,64L<<20).patternTimes().get("make")==3,"force stored zero-run");
  check(plan(simple,3,Map.of("A",3L,"C",100L),Map.of(),false,131072,64L<<20).patternTimes().isEmpty(),"non-force stock");
  var parentTool=List.of(recipe("parent",Map.of("A",1L,"X",1L),Map.of("C",1L)),recipe("child",Map.of("A",1L,"B",1L),Map.of("A",1L,"X",1L)));
  check(plan(parentTool,1,Map.of("A",1L,"B",1L),Map.of(),true,131072,64L<<20).feasible() == (mode == GraphMode.FULL),"parent input tool borrow retained in FULL; excluded physical self-return in ACYCLIC");
  var partial=List.of(recipe("first",Map.of("X",1L),Map.of("C",1L)),recipe("second",Map.of("Y",1L),Map.of("C",1L)),recipe("x",Map.of("A",1L),Map.of("X",1L)),recipe("y",Map.of("B",1L),Map.of("Y",1L)));
  check(plan(partial,10,Map.of("A",4L,"B",6L),Map.of(),true,131072,64L<<20).feasible(),"indirect exact partial source 4+6");
  var many=new ArrayList<GraphRecipe<String>>();for(int n=0;n<32;n++)many.add(recipe("dead"+n,Map.of("missing"+n,1L),Map.of("C",1L)));many.addAll(simple);
  check(plan(many,3,Map.of("A",3L),Map.of(),true,131072,64L<<20).feasible(),"ordinary alternatives beyond old 16 source cap");
 }
 public static void main(String[]args){for(var domain:GraphMode.values()){mode=domain;run();}check(checks==18,"input count changed");System.out.println("HistoricalFallbackEdgeTest PASS inputs="+checks);}
}
