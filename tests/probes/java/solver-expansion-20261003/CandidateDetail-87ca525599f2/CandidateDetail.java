package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
public final class CandidateDetail {
 public static void main(String[]args){
  for(int[] v:new int[][]{{0,15,6,11},{1,13,4,10},{1,14,4,10},{1,9,6,8},{0,6,3,4},{1,7,5,5}}){
   var stock=Map.of("A",(long)v[0],"B",(long)v[1],"C",(long)v[2]);var budget=new PlanningBudget(0,3_000_000,128L<<20,()->false,System::nanoTime);
   var p=new GraphPlanner<>(new GraphCompiler<>(OrderProbe.recipes(false))).plan("C",v[3],stock,false,true,budget);
   var net=BigInteger.ZERO;var gross=BigInteger.ZERO;for(var e:p.patternTimesExact().entrySet()){var r=p.recipes().get(e.getKey());long made=r.executionOutputs().getOrDefault("C",0L);gross=gross.add(e.getValue().multiply(BigInteger.valueOf(made)));net=net.add(e.getValue().multiply(BigInteger.valueOf(made-r.inputs().getOrDefault("C",0L))));}
   System.out.println("CASE stock="+stock+" force="+v[3]+" result="+p.result()+" initial="+p.initialExact()+" counts="+p.patternTimesExact()+" gross="+gross+" net="+net+" seeds="+p.seeds()+" steps="+p.steps());
  }
 }
}
