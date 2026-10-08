package org.cgse.core;
import java.util.*;
public class MainSeedPolicyProbe {
 static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 public static void main(String[]a){
  for(int kind=0;kind<5;kind++)for(long amount:new long[]{1,2,10,100}){
   var rs=new ArrayList<GraphRecipe<String>>();if(kind==1)rs.add(r("xbad",Map.of("y",1L,"missing",1L),Map.of("x",2L)));if(kind==2)rs.add(r("xbad",Map.of("u",1L),Map.of("x",2L)));
   if(kind==2||kind==3)rs.add(r("ubad",Map.of("y",1L,"missing",1L),Map.of("u",2L)));
   if(kind==4){rs.add(r("ubad",Map.of("y",1L),Map.of("u",1L)));rs.add(r("xbad",Map.of("u",1L,"missing",1L),Map.of("x",2L)));}
   rs.add(r("xy",Map.of("x",1L),Map.of("y",1L)));rs.add(r("yx",Map.of("y",1L),Map.of("x",2L)));if(kind>=3)rs.add(r("target",Map.of("u",1L,"y",1L),Map.of("target",1L)));
   var stock=kind>=3?Map.of("x",1L,"u",1L):Map.of("x",1L);String target=kind>=3?"target":"y";var compiler=new GraphCompiler<>(rs);var b=new PlanningBudget(0,20000000,128L<<20,()->false,System::nanoTime);
   var work=new CatalystPlanningWork<String>(new CatalystPolicy(4096,64),b,policy->new GraphPlanningWork<>(compiler,target,amount,stock,Set.of(),Map.of(),true,true,b).catalysts(policy));try{while(!work.step()){}var p=work.result();if(p.feasible()){PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);}System.out.println("kind="+kind+" amount="+amount+" "+p.result()+" seeds="+p.seeds()+" initial="+p.initialExact()+" counts="+p.patternTimesExact()+" diag="+b.diagnostics());}finally{work.close();}
  }
 }
}