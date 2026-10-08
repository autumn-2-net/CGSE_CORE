package org.cgse.core;
import java.util.*;
public final class OriginalPermutationProbe {
 public static void main(String[]args){var rng=new Random(20261003);int cases=0;
  for(long scale:new long[]{1,2,17,1000000000L,Long.MAX_VALUE/3})for(boolean force:new boolean[]{false,true})for(int order=0;order<64;order++){
   var recipes=new ArrayList<GraphRecipe<String>>();
   recipes.add(OrderProbe.recipe("main",Map.of("A",2*scale,"B",3*scale),Map.of("A",scale,"C",3*scale)));
   recipes.add(OrderProbe.recipe("recycle",Map.of("C",scale),Map.of("A",scale)));
   for(int i=0;i<8;i++)recipes.add(OrderProbe.recipe("dead"+i,Map.of("X"+i,scale),Map.of("C",scale)));
   if(order==1)Collections.rotate(recipes,-2);else if(order>1)Collections.shuffle(recipes,rng);
   var stock=Map.of("A",scale,"B",3*scale,"C",scale);
   var budget=new PlanningBudget(0,3000000,128L<<20,()->false,System::nanoTime);
   var p=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("C",3*scale,stock,false,force,budget);
   OrderRegression.check(p.feasible(),"original permutation failed scale="+scale+" force="+force+" order="+order+" result="+p.result());
   OrderRegression.check(p.patternTimes().equals(Map.of("main",1L,"recycle",1L)),"extra production "+p.patternTimes());
   OrderRegression.verify(p,stock,force);cases++;
  }
  System.out.println("PASS original/scaled counterexample "+cases+" cases; two force values; 64 orders; scale through Long.MAX_VALUE/3; all sequentially verified");
 }
}
