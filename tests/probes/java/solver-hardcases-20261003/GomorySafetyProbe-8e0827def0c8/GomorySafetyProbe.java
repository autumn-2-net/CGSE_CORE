package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

public class GomorySafetyProbe {
 static int cuts, proofs, models, feasible;
 static boolean accepts(List<ExactLinearProgram.Constraint> rows,BigInteger[]p){for(var r:rows){BigInteger v=BigInteger.ZERO;for(var t:r.terms().entrySet())v=v.add(p[t.getKey()].multiply(t.getValue()));if(v.compareTo(r.upper())>0)return false;}return true;}
 public static void main(String[]args){
  for(int seed=0;seed<1200;seed++){
   PlanningBudget budget=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);CountProof.Journal journal=new CountProof.Journal(2<<20);budget.proofJournal(journal);
   var rows=LpSafetyProbe.rows(64,seed);
   try(var lp=new ExactLinearProgram(64,rows,LpSafetyProbe.objective(64,seed),budget,null,true)){
    while(!lp.step()){}if(lp.result()!=ExactLinearProgram.Result.OPTIMAL)throw new AssertionError("LP failed");
    try(var basis=lp.takeBasis()){
     if(basis.revised==null)throw new AssertionError("not revised");
     var generated=CountGomory.separate(basis,lp.point(),budget);cuts+=generated.size();models++;
     for(int mask=0;mask<64;mask++){BigInteger[]p=new BigInteger[64];Arrays.fill(p,BigInteger.ZERO);for(int i=0;i<6;i++)p[i]=BigInteger.valueOf((mask>>i)&1);if(accepts(rows,p)){feasible++;if(!accepts(generated,p))throw new AssertionError("cut false "+seed);}}
    }
   }
   for(var proof:journal.rounding()){if(CountProof.verify(proof,4_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad proof");proofs++;}
   for(var proof:journal.derivations())if(CountProof.verify(proof,4_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad derivation");
   if(budget.reservedBytes()!=0)throw new AssertionError("leak "+budget.reservedBytes());
  }
  for(int cancel=0;cancel<6000;cancel+=11){final int stop=cancel;boolean[]cutPhase={false};int[]checks={0};PlanningBudget budget=new PlanningBudget(0,4_000_000,128L<<20,()->cutPhase[0]&&checks[0]++>=stop,System::nanoTime);
   try(var lp=new ExactLinearProgram(64,LpSafetyProbe.rows(64,3),LpSafetyProbe.objective(64,3),budget,null,true)){
    while(!lp.step()){}try(var basis=lp.takeBasis()){cutPhase[0]=true;CountGomory.separate(basis,lp.point(),budget);}
   }catch(CancellationException expected){}
   if(budget.reservedBytes()!=0)throw new AssertionError("cancel leak "+cancel+" "+budget.reservedBytes());
  }
  if(cuts<100||proofs!=cuts)throw new AssertionError("no cut coverage");
  System.out.println("GOMORY_SAFETY models="+models+" cuts="+cuts+" proofs="+proofs+" feasibleIntegerAssignments="+feasible);
 }
}
