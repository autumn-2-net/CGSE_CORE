package org.cgse.core;
import java.util.*;
public final class RegionGrossProbe {
 public static void main(String[] args) {
  QuantityProbe.main(args);
  var rs=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),
      OrderProbe.recipe("turn",Map.of("A",1L,"Z",1L,"fuel",1L),Map.of("C",1L,"Z",1L)),
      OrderProbe.recipe("make",Map.of("raw",1L,"A",1L),Map.of("A",1L,"C",1L,"Z",1L)));
  var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
  var compiler=new GraphCompiler<>(rs);
  var graph=compiler.compile("C",Map.of("Z",1),Set.of(),budget);
  System.out.println("mixed graph="+graph.regions());
  var p=OrderProbe.solve(rs,3,Map.of("C",100L,"fuel",100L,"raw",1L),true);
  System.out.println("mixed="+p.result());
 }
}
