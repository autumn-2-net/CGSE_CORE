package org.cgse.core;
import java.math.*;import java.util.*;
public class GomoryOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){var sum=Z;for(var e:r.terms().entrySet())sum=sum.add(e.getValue().multiply(x[e.getKey()]));if(sum.compareTo(r.upper())>0)return false;}return true;}
 public static void main(String[]args){Random random=new Random(922318);int cuts=0;long solutions=0;
 for(int sample=0;sample<2000;sample++){int n=2+random.nextInt(4);var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger[] objective=new BigInteger[n];for(int i=0;i<n;i++){rows.add(new ExactLinearProgram.Constraint(Map.of(i,O),BigInteger.valueOf(4)));objective[i]=BigInteger.valueOf(random.nextInt(9)-4);}
 for(int r=0;r<2+random.nextInt(6);r++){Map<Integer,BigInteger> terms=new TreeMap<>();for(int i=0;i<n;i++){var a=BigInteger.valueOf(random.nextInt(13)-6);if(sample%19==0)a=a.shiftLeft(90);if(a.signum()!=0)terms.put(i,a);}rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(random.nextInt(20)-5)));}
 var b=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);var journal=new CountProof.Journal(16L<<20);b.proofJournal(journal);try(var lp=new ExactLinearProgram(n,rows,objective,b,null,true)){while(!lp.step()){}if(lp.result()==ExactLinearProgram.Result.OPTIMAL){try(var basis=lp.takeBasis()){var found=CountGomory.separate(basis,lp.point(),b);cuts+=found.size();int limit=(int)Math.pow(5,n);for(int code=0;code<limit;code++){int c=code;var x=new BigInteger[n];for(int i=0;i<n;i++){x[i]=BigInteger.valueOf(c%5);c/=5;}if(valid(rows,x)){solutions++;if(!valid(found,x))throw new AssertionError("bad Gomory "+sample);}}}}}
 for(var proof:journal.rounding())if(CountProof.verify(proof,2000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad proof "+sample);if(b.reservedBytes()!=0)throw new AssertionError("leak");}
 if(cuts==0)throw new AssertionError("no generated cuts");System.out.println("PASS Gomory 2000 integer tableaux; cuts="+cuts+" independently checked solutions="+solutions+" exact rounding certificates");}
}
