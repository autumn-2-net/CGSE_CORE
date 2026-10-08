package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public class GomoryScaledProbe {
 public static void main(String[]args){int cuts=0,proofs=0,models=0;
  for(int seed=0;seed<100;seed++)for(int exponent:new int[]{0,35}){
   BigInteger scale=BigInteger.TEN.pow(exponent);List<ExactLinearProgram.Constraint>rows=new ArrayList<>();
   for(var r:LpSafetyProbe.rows(8,seed)){Map<Integer,BigInteger>t=new TreeMap<>();r.terms().forEach((i,v)->t.put(i,v.multiply(scale)));rows.add(new ExactLinearProgram.Constraint(t,r.upper().multiply(scale)));}
   var objective=LpSafetyProbe.objective(8,seed);PlanningBudget budget=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);CountProof.Journal journal=new CountProof.Journal(2<<20);budget.proofJournal(journal);
   try(var lp=new ExactLinearProgram(8,rows,objective,budget,null,true)){while(!lp.step()){}if(lp.result()!=ExactLinearProgram.Result.OPTIMAL)throw new AssertionError("LP");
    try(var dense=lp.takeBasis();var sparse=new ExactLinearProgram.Basis(8,rows,objective,dense.basic,budget,0)){
     for(var basis:new ExactLinearProgram.Basis[]{dense,sparse}){var generated=CountGomory.separate(basis,lp.point(),budget);cuts+=generated.size();models++;
      for(int mask=0;mask<64;mask++){BigInteger[]p=new BigInteger[8];Arrays.fill(p,BigInteger.ZERO);for(int i=0;i<6;i++)p[i]=BigInteger.valueOf((mask>>i)&1);if(GomorySafetyProbe.accepts(rows,p)&&!GomorySafetyProbe.accepts(generated,p))throw new AssertionError("false scaled cut");}
     }
    }
   }
   for(var proof:journal.rounding()){if(CountProof.verify(proof,4_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad scaled proof");proofs++;}
   for(var proof:journal.derivations())if(CountProof.verify(proof,4_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad scaled derivation");
   if(budget.reservedBytes()!=0)throw new AssertionError("leak");
  }
  if(cuts!=proofs||cuts<50)throw new AssertionError("no coverage");System.out.println("GOMORY_SCALED models="+models+" cuts="+cuts+" proofs="+proofs);
 }
}
