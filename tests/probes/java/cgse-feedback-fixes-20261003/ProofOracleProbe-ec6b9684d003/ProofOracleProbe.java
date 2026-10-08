package org.cgse.core;
import java.util.*;
public final class ProofOracleProbe {
 public static void main(String[]args){var rng=new Random(730202610);int total=0,possible=0,unknown=0,proved=0;
  for(int sample=0;sample<100;sample++){
   var stock=Map.of("A",(long)rng.nextInt(4),"B",(long)rng.nextInt(16),"C",(long)rng.nextInt(7));int amount=1+rng.nextInt(12);
   for(boolean force:new boolean[]{false,true})for(int order=0;order<2;order++){
    var recipes=OrderProbe.recipes(order==0);if(order==1)Collections.shuffle(recipes,rng);
    boolean oracle=OrderRegression.oracle(stock,amount,force);if(oracle)possible++;
    var budget=new PlanningBudget(0,2000000,128L<<20,()->false,System::nanoTime);
    var compiler=new GraphCompiler<>(recipes);
    try(var work=new IntegerCountSearch<>(compiler,"C",amount,stock,Map.of(),Set.of(),Set.of(),false,force,budget,System.nanoTime())){
     while(!work.step()){}var p=work.result();total++;
     OrderRegression.check(!work.infeasible()||!oracle,"false count proof sample="+sample+" force="+force+" stock="+stock+" amount="+amount);
     OrderRegression.check(p==null||oracle,"false count witness");
     if(p!=null)OrderRegression.verify(p,stock,force);else if(oracle)unknown++;
     if(work.infeasible())proved++;
    }
   }
  }
  System.out.println("PASS count proof oracle total="+total+" oracle_possible="+possible+" possible_unknown="+unknown+" infeasible_proofs="+proved+" false_infeasible=0 false_feasible=0");
 }
}
