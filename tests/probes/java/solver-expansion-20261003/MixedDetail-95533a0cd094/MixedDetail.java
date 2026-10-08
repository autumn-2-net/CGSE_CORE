package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class MixedDetail {
 public static void main(String[] args){
  var rs=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("turn",Map.of("A",1L,"Z",1L,"fuel",1L),Map.of("C",1L,"Z",1L)),OrderProbe.recipe("make",Map.of("raw",1L,"A",1L),Map.of("A",1L,"C",1L,"Z",1L)));
  var stock=Map.of("C",100L,"fuel",100L,"raw",1L);var b=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
  var p=new GraphPlanner<>(new GraphCompiler<>(rs)).plan("C",3,stock,false,true,b);
  System.out.println("goal force=true target=C amount=3 stock="+stock+" result="+p.result()+" initial="+p.initialExact()+" seeds="+p.seeds()+" missing="+p.missingExact()+" counts="+p.patternTimesExact()+" steps="+p.steps());
  BigInteger produced=BigInteger.ZERO,consumed=BigInteger.ZERO;
  for(var e:p.patternTimesExact().entrySet()){var r=p.recipes().get(e.getKey());System.out.println("recipe="+r.id()+" count="+e.getValue()+" inputs="+r.inputs()+" outputs="+r.outputs()+" executionOutputs="+r.executionOutputs());produced=produced.add(e.getValue().multiply(BigInteger.valueOf(r.executionOutputs().getOrDefault("C",0L))));consumed=consumed.add(e.getValue().multiply(BigInteger.valueOf(r.inputs().getOrDefault("C",0L))));}
  System.out.println("exact_physical_produced_C="+produced+" consumed_C="+consumed+" net_C="+produced.subtract(consumed)+" meets_force_production="+(produced.compareTo(BigInteger.valueOf(3))>=0));
  if(p.feasible()){PlanVerifier.verifyRuntimeInventory(p);System.out.println("runtime_inventory_verification=PASS");}
  if(p.feasible())try(var s=new SummaryComputation<>(p.steps(),p.recipes(),b)){while(!s.step()){}for(String k:new String[]{"A","C","Z","raw","fuel"})System.out.println("summary key="+k+" required="+s.result().required(k)+" delta="+s.result().delta(k));}
 }
}
