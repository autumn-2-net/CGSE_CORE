package org.cgse.core;
import java.util.*;
public final class ForceSafetyMatrix {
 public static void main(String[]args){
  var out=OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L));
  var turn=OrderProbe.recipe("turn",Map.of("A",1L,"Z",1L,"fuel",1L),Map.of("C",1L,"Z",1L));
  var make=OrderProbe.recipe("make",Map.of("raw",1L,"A",1L),Map.of("A",1L,"C",1L,"Z",1L));
  var all=List.of(out,turn,make);int checked=0;
  for(int first=0;first<3;first++)for(int second=0;second<3;second++)if(first!=second)for(int amount:new int[]{3,5}){
   int third=3-first-second;var rs=List.of(all.get(first),all.get(second),all.get(third));var budget=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);
   var p=new GraphPlanner<>(new GraphCompiler<>(rs)).plan("C",amount,Map.of("C",100L,"fuel",100L,"raw",1L),false,true,budget);
   System.out.println("mixed order="+first+second+third+" force="+amount+" result="+p.result()+" counts="+p.patternTimesExact());
   if(p.feasible())throw new AssertionError("mixed circulation fakes forced production");checked++;
  }
  for(boolean preserve:new boolean[]{false,true}){
   var budget=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);var rs=List.of(OrderProbe.recipe("fresh",Map.of("raw",1L),Map.of("C",3L)));
   var p=new GraphPlanner<>(new GraphCompiler<>(rs)).plan("C",3,Map.of("C",100L,"raw",1L),preserve,true,budget);if(!p.feasible()||!p.patternTimesExact().equals(Map.of("fresh",java.math.BigInteger.ONE)))throw new AssertionError("real production rejected");checked++;
  }
  System.out.println("PASS force_safety="+checked+" mixed permutations/amounts rejected and actual fresh output accepted");
 }
}
