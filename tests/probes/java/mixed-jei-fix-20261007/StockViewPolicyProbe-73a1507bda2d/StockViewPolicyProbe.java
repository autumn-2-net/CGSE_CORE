package org.cgse.core;
import java.util.*;
public class StockViewPolicyProbe {
 public static void main(String[] a){var recipes=List.of(new GraphRecipe<String>("xy","xy",List.of(new GraphRecipe.Slot<>("x",1)),Map.of("y",1L)),new GraphRecipe<String>("yx","yx",List.of(new GraphRecipe.Slot<>("y",1)),Map.of("x",2L)));var compiler=new GraphCompiler<>(recipes);var b=new PlanningBudget(0,1000000,16L<<20,()->false,System::nanoTime);
 try(var w=new GraphStockViewWork<>(compiler,"y",1,Map.of("x",1L),Set.of(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),b,System.nanoTime())){while(!w.step()){}var p=w.result();System.out.println(p==null?"null":p.result()+" seeds="+p.seeds()+" initial="+p.initialExact()+" counts="+p.patternTimesExact());}
 }
}