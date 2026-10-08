package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class ForceProofQuota {
 public static void main(String[]args){int cases=0;
  var rs=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("turn",Map.of("A",1L,"Z",1L,"fuel",1L),Map.of("C",1L,"Z",1L,"bonus",1L)),OrderProbe.recipe("make",Map.of("raw",1L,"A",1L),Map.of("A",1L,"C",1L,"Z",1L)));
  var recipes=new LinkedHashMap<String,GraphRecipe<String>>();rs.forEach(r->recipes.put(r.id(),r));
  for(long repeat:new long[]{2,100,Long.MAX_VALUE})for(boolean automaticBonusSeed:new boolean[]{false,true}){
   var program=new PlanStep.Sequence(List.of(new PlanStep.Batch("out",repeat),new PlanStep.Batch("make",1),new PlanStep.Batch("turn",repeat)));
   var p=new GraphPlan<>("C",3,false,program,recipes,Map.of("C",BigInteger.valueOf(repeat),"raw",BigInteger.ONE,"fuel",BigInteger.valueOf(repeat)),automaticBonusSeed?Map.of("bonus",repeat):Map.of(),Map.of(),GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,0,0);
   var vb=new PlanningBudget(0,100000,32L<<20,()->false,System::nanoTime);
   try(var verified=new PlanVerification<>(p,vb)){while(!verified.step()){}
    for(long work:new long[]{16,512,16384,100000,1_000_000})for(long memory:new long[]{1024,32L<<20}){
     var b=new PlanningBudget(0,work,memory,()->false,System::nanoTime);try(var proof=new ForceCraftProof<>(p,verified,Map.of(),b)){while(!proof.step()){}if(proof.proved())throw new AssertionError("wasteful circulation accepted repeat="+repeat+" work="+work);}if(b.reservedBytes()!=0)throw new AssertionError("force proof leak");cases++;
    }
   }if(vb.reservedBytes()!=0)throw new AssertionError("verification leak");
  }
  System.out.println("PASS force_proof_quota="+cases+" gross>=goal zero-useful-cycle rejected including automatic bonus seeds, Long.MAX repeats and low budgets; memory_leaks=0");
 }
}
