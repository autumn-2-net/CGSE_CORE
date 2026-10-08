package org.cgse.core;
import java.util.*;
public class ProducerRepairAudit {
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[] args){var rs=List.of(r("goal",Map.of("fluid",10L),Map.of("target",1L)),r("melt",Map.of("ingot",1L),Map.of("fluid",1L)),r("cast",Map.of("fluid",1L),Map.of("ingot",1L)),r("make",Map.of("raw",1L),Map.of("fluid",10L)));var c=new GraphCompiler<>(rs);var stock=Map.of("fluid",1L,"raw",100L);var b=new PlanningBudget(0,1000000,1<<20,()->false,System::nanoTime);try(var w=new GraphStockViewWork<>(c,"target",1,stock,Set.of(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),b,System.nanoTime(),0,900000,()->null)){while(!w.step()){}System.out.println("result="+(w.result()==null?"null":w.result().result())+" "+b.diagnostics());if(w.result()!=null){PlanVerifier.verify(w.result());PlanVerifier.verifyRuntimeInventory(w.result());}}System.out.println("reserved="+b.reservedBytes());}
}

