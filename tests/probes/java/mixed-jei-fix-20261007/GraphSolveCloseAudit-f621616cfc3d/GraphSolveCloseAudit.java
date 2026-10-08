package org.cgse.core;
import java.util.*;
public class GraphSolveCloseAudit {
 static int checks;static void ok(boolean x,String s){checks++;if(!x)throw new AssertionError(s);}
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[] args){
  for(int size:new int[]{2,3,7,20}) {
   var rs=new ArrayList<GraphRecipe<String>>();var stock=new HashMap<String,Long>();var byid=new LinkedHashMap<String,GraphRecipe<String>>();var selected=new LinkedHashMap<String,GraphRecipe<String>>();stock.put("a0",1L);
   for(int i=0;i<size;i++){var outs=new LinkedHashMap<String,Long>();outs.put("a"+((i+1)%size),2L);if(i==size-1)outs.put("target",1L);var rr=r("r"+i,Map.of("a"+i,1L,"raw"+i,1L),outs);rs.add(rr);byid.put(rr.id(),rr);for(var k:outs.keySet())selected.put(k,rr);stock.put("raw"+i,10000L);}
   var graph=new GraphCompiler.Compiled<>(byid,selected,List.of(new GraphCompiler.Region<>(rs,true)));
   for(long memory:new long[]{1024,4096,16384,65536,1048576,16777216})for(int stop=0;stop<=600;stop+=7){
    var b=new PlanningBudget(0,1000000,memory+128,()->false,System::nanoTime);b.reserve(128);
    try(var solve=new GraphSolve<>(graph,"target",100,stock,Set.of(),Map.of(),true,true,b,System.nanoTime(),CatalystPolicy.MINIMAL,stock)){for(int i=0;i<stop;i++)if(solve.step())break;}catch(PlanningBudget.Exhausted exhausted){}
    ok(b.reservedBytes()==128,"size="+size+" memory="+memory+" stop="+stop+" left="+b.reservedBytes());
   }
  }
  System.out.println("assertions="+checks);
 }
}