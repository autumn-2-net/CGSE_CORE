// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
// Migrated from AdmissionLifecycleChecks-57a0f0140426; original source remains in tests/probes.
package org.cgse.core;
import java.util.*;
public final class HistoricalAdmissionTest {
 static GraphRecipe<String> recipe(String id,String input,String output){return new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>(input,1)),Map.of(output,1L));}
 static GraphCompiler<String> compiler(int n){var recipes=new ArrayList<GraphRecipe<String>>();recipes.add(recipe("cycle-t","X","T"));recipes.add(recipe("cycle-x","T","X"));recipes.add(recipe("good-t","c0","T"));for(int i=0;i<n;i++)recipes.add(recipe("chain-"+i,"c"+(i+1),"c"+i));return new GraphCompiler<>(recipes);}
 static void run(int n,long limit,long memory,long cancelAt,boolean mustResume)throws Exception{
  var c=compiler(n);final PlanningBudget[] ref=new PlanningBudget[1];
  var budget=new PlanningBudget(0,limit,memory,()->ref[0].nodes()>=cancelAt,System::nanoTime);ref[0]=budget;
  var work=new GraphPlanningWork<>(c,"T",1,Map.of("c"+n,1L),true,true,budget);
  var resume=GraphPlanningWork.class.getDeclaredField("stockResumePhase");resume.setAccessible(true);int resumed=0,old=-1,suspended=-1;
  GraphPlan<String> plan=null;
  try{while(!work.step()){int now=resume.getInt(work);if(old>=0&&now<0)resumed++;if(now>=0)suspended=now;old=now;}plan=work.result();if(plan.feasible()){PlanVerifier.verify(plan);PlanVerifier.verifyRuntimeInventory(plan);}}
  catch(java.util.concurrent.CancellationException expected){}finally{work.close();work.close();}
  if(budget.reservedBytes()!=0)throw new AssertionError("leak n="+n+" bytes="+budget.reservedBytes()+" cancelAt="+cancelAt);
  if(mustResume&&(plan==null||!plan.feasible()))throw new AssertionError("funded chain missed " + (plan==null?"null":plan.result())+" "+budget.diagnostics());
  System.out.println("n="+n+" memory="+memory+" cancelAt="+cancelAt+" phase="+suspended+" resumed="+resumed+" result="+(plan==null?"CANCELLED":plan.result())+" work="+budget.nodes()+" bytes="+budget.reservedBytes());
 }
 public static void main(String[]args)throws Exception{
  run(800,500_000,128L<<20,Long.MAX_VALUE,true);run(2000,2_000_000,128L<<20,Long.MAX_VALUE,true);run(7000,4_000_000,128L<<20,Long.MAX_VALUE,true);
  for(int n:new int[]{800,2000})for(long point:new long[]{0,1_000,10_000,50_000,105_000,150_000,210_000,250_000,500_000})run(n,4_000_000,128L<<20,point,false);
  for(long memory:new long[]{128L<<10,512L<<10,2L<<20,16L<<20})run(2000,2_000_000,memory,Long.MAX_VALUE,false);
 }
}
