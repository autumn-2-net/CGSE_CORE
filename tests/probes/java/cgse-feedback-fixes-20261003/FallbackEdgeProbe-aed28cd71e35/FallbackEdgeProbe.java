package org.cgse.core;
import java.util.*;
public final class FallbackEdgeProbe {
 static int checks;
 static GraphPlan<String> plan(List<GraphRecipe<String>> rs,long amount,Map<String,Long> stock,Map<String,Long> seeds,boolean force,long work,long memory){
  var b=new PlanningBudget(0,work,memory,()->false,System::nanoTime);
  var p=GraphFallback.plan(new GraphCompiler<>(rs),"C",amount,stock,Set.of(),seeds,false,force,b);
  if(b.reservedBytes()!=0)throw new AssertionError("leak="+b.reservedBytes());
  if(p.feasible())OrderRegression.verify(p,stock,force);checks++;return p;
 }
 static void check(boolean v,String msg){if(!v)throw new AssertionError(msg);}
 public static void main(String[]args){
  var tool=List.of(OrderProbe.recipe("tool",Map.of("A",1L,"B",1L),Map.of("A",1L,"C",1L)));
  var p=plan(tool,7,Map.of("A",1L,"B",7L,"C",100L),Map.of("A",1L),true,131072,64L<<20);
  check(p.feasible()&&p.patternTimes().get("tool")==7,"returned tool requires only one copy");
  check(!plan(tool,7,Map.of("B",7L),Map.of(),true,131072,64L<<20).feasible(),"missing tool invented");
  var rs=List.of(OrderProbe.recipe("need",Map.of("X",1L,"Y",1L),Map.of("C",1L)),OrderProbe.recipe("cheapX",Map.of("A",1L),Map.of("X",1L)),OrderProbe.recipe("expensiveY",Map.of("A",1L),Map.of("Y",1L)),OrderProbe.recipe("otherX",Map.of("B",1L),Map.of("X",1L)));
  check(plan(rs,3,Map.of("A",3L,"B",3L),Map.of(),true,131072,64L<<20).feasible(),"sibling resource backtrack");
  var loops=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("back",Map.of("A",1L,"B",1L),Map.of("C",1L)));
  check(!plan(loops,3,Map.of("C",100L,"B",100L),Map.of(),true,131072,64L<<20).feasible(),"zero-output fuel loop");
  var simple=List.of(OrderProbe.recipe("make",Map.of("A",1L),Map.of("C",1L)));
  check(plan(simple,3,Map.of("A",3L,"C",100L),Map.of(),true,131072,64L<<20).patternTimes().get("make")==3,"force stored zero-run");
  check(plan(simple,3,Map.of("A",3L,"C",100L),Map.of(),false,131072,64L<<20).patternTimes().isEmpty(),"non-force stock");
  var parentTool=List.of(OrderProbe.recipe("parent",Map.of("A",1L,"X",1L),Map.of("C",1L)),OrderProbe.recipe("child",Map.of("A",1L,"B",1L),Map.of("A",1L,"X",1L)));
  check(plan(parentTool,1,Map.of("A",1L,"B",1L),Map.of(),true,131072,64L<<20).feasible(),"parent input tool cannot be locked before child borrows it");
  var partial=List.of(OrderProbe.recipe("first",Map.of("X",1L),Map.of("C",1L)),OrderProbe.recipe("second",Map.of("Y",1L),Map.of("C",1L)),OrderProbe.recipe("x",Map.of("A",1L),Map.of("X",1L)),OrderProbe.recipe("y",Map.of("B",1L),Map.of("Y",1L)));
  check(plan(partial,10,Map.of("A",4L,"B",6L),Map.of(),true,131072,64L<<20).feasible(),"indirect exact partial source 4+6");
  var many=new ArrayList<GraphRecipe<String>>();for(int n=0;n<32;n++)many.add(OrderProbe.recipe("dead"+n,Map.of("missing"+n,1L),Map.of("C",1L)));many.addAll(simple);
  check(plan(many,3,Map.of("A",3L),Map.of(),true,131072,64L<<20).feasible(),"ordinary alternatives beyond old 16 source cap");
  System.out.println("PASS fallback edge cases="+checks+" returned tool, missing tool, sibling rollback, fuel cycle, forced and ordinary stored target, parent tool borrow, indirect 4+6, 32 dead sources");
 }
}
