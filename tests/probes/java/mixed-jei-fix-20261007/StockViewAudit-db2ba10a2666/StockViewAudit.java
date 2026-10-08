package org.cgse.core;
import java.util.*;import java.math.*;
public class StockViewAudit {
 static int checks;static void ok(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[] args){
  var rs=List.of(r("goal",Map.of("x",1L),Map.of("target",1L)),r("x",Map.of("raw",2L),Map.of("x",1L)));
  var c=new GraphCompiler<>(rs);var stock=Map.of("x",1L,"raw",20L);
  for(long bytes:new long[]{128,256,512,1024,2048,4096,8192,32768,65536,1<<20})for(int stop=0;stop<150;stop++) {
   var b=new PlanningBudget(0,1000000,bytes+128,()->false,System::nanoTime);b.reserve(128);b.failureDetail("retained");
   try(var w=new GraphStockViewWork<>(c,"target",5,stock,Set.of(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),b,System.nanoTime())){
    for(int i=0;i<stop;i++)if(w.step())break;
    if(w.result()!=null){PlanVerifier.verify(w.result());PlanVerifier.verifyRuntimeInventory(w.result());}
   }
   ok(b.reservedBytes()==128,"abandon leak memory="+bytes+" stop="+stop+" left="+b.reservedBytes());ok(b.failureDetail().equals("retained"),"optional memory changedfailure");
  }
  for(long work:new long[]{1,8,64,256,4096,32768,131072,1000000}){
   var b=new PlanningBudget(0,work,16L<<20,()->false,System::nanoTime);
   try(var w=new GraphStockViewWork<>(c,"target",5,stock,Set.of(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),b,System.nanoTime())){while(!w.step()){} if(w.result()!=null){PlanVerifier.verify(w.result());PlanVerifier.verifyRuntimeInventory(w.result());}}
   ok(b.reservedBytes()==0,"quota leak");
  }
  var b=new PlanningBudget(0,1000000,16L<<20,()->false,System::nanoTime);try(var w=new GraphStockViewWork<>(c,"target",5,stock,Set.of(),Map.of(),Set.of(),true,true,new CatalystPolicy(4096,64),b,System.nanoTime())){while(!w.step()){}ok(w.result()!=null&&w.result().feasible(),"refinementdidnotsolve");PlanVerifier.verify(w.result());PlanVerifier.verifyRuntimeInventory(w.result());}
  System.out.println("assertions="+checks);
 }
}