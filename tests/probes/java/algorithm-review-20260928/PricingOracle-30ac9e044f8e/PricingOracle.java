package org.cgse.core;
import java.math.*;import java.util.*;
public final class PricingOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static PlanningBudget budget(){return new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);}
 public static void main(String[]args){var random=new Random(917810);int sat=0,unsat=0;
  for(int test=0;test<1500;test++){int n=1+random.nextInt(3);var rows=new ArrayList<ExactLinearProgram.Constraint>();var objective=new BigInteger[n];
   for(int i=0;i<n;i++){rows.add(new ExactLinearProgram.Constraint(Map.of(i,O),BigInteger.valueOf(1+random.nextInt(7))));rows.add(new ExactLinearProgram.Constraint(Map.of(i,O.negate()),Z));objective[i]=BigInteger.valueOf(random.nextInt(9)-4);}
   for(int r=0;r<1+random.nextInt(5);r++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++){var c=BigInteger.valueOf(random.nextInt(11)-5);if(c.signum()!=0)terms.put(i,c);}rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(random.nextInt(14)-5)));}
   RevisedOracle.best=null;RevisedOracle.vertices(rows,objective,new int[n],0,0);var expected=RevisedOracle.best;var b=budget();var journal=new CountProof.Journal(8L<<20);b.proofJournal(journal);
   try(var p=new ExactColumnProgram(n,rows,objective,b)){while(!p.step()){}if(expected==null){unsat++;if(p.result()!=ExactLinearProgram.Result.INFEASIBLE)throw new AssertionError("UNSAT "+test+" "+b.diagnostics());}else{sat++;if(p.result()!=ExactLinearProgram.Result.OPTIMAL)throw new AssertionError("OPT "+test+" "+b.diagnostics());var actual=ExactRational.ZERO;for(int j=0;j<n;j++)actual=actual.add(p.point()[j].multiply(ExactRational.of(objective[j])));if(!actual.equals(expected))throw new AssertionError("objective "+test);}}
   for(var proof:journal.entries())if(CountProof.verify(proof,2000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("certificate");if(b.reservedBytes()!=0)throw new AssertionError("leak");
  }
  int n=1100;var rows=new ArrayList<ExactLinearProgram.Constraint>();var objective=new BigInteger[n];Arrays.fill(objective,Z);objective[n-1]=O.negate();rows.add(new ExactLinearProgram.Constraint(Map.of(n-1,O.negate()),BigInteger.valueOf(-7)));rows.add(new ExactLinearProgram.Constraint(Map.of(n-1,O),BigInteger.TEN));var b=budget();
  try(var p=new ExactLinearProgram(n,rows,objective,b)){while(!p.step()){}if(p.result()!=ExactLinearProgram.Result.OPTIMAL||!p.point()[n-1].equals(ExactRational.of(BigInteger.valueOf(7))))throw new AssertionError("last catalog producer "+b.diagnostics());System.out.println(b.diagnostics());}if(b.reservedBytes()!=0)throw new AssertionError("parent leak");
  for(int stop=1;stop<512;stop++){int[]calls={0};int limit=stop;var budget=new PlanningBudget(0,20000000,256L<<20,()->++calls[0]>=limit,System::nanoTime);try(var p=new ExactColumnProgram(n,rows,objective,budget)){while(!p.step()){}}catch(java.util.concurrent.CancellationException expected){}if(budget.reservedBytes()!=0)throw new AssertionError("cancel leak "+stop);}
  System.out.println("PASS pricing independent vertices=1500 SAT="+sat+" UNSAT="+unsat+" complete-column Farkas; last omitted source; 511 cancellations");
 }
}
