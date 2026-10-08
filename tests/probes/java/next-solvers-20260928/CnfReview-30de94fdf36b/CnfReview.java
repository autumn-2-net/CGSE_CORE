package org.cgse.core;
import java.util.*;import java.math.*;
public final class CnfReview {
 public static void main(String[] args){var rng=new Random(9283721);int oldSolved=0,newSolved=0,gains=0,losses=0;long oldWork=0,newWork=0;
  for(int test=0;test<192;test++){int n=40+test%4*16;var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);boolean[] plant=new boolean[n];for(int i=0;i<n;i++)plant[i]=rng.nextBoolean();var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int r=0;r<n*5;r++){Set<Integer> ids=new LinkedHashSet<>();while(ids.size()<3)ids.add(rng.nextInt(n));boolean[] sign=new boolean[3];boolean trueClause=false;int j=0;for(int i:ids){sign[j]=rng.nextBoolean();trueClause|=sign[j++]==plant[i];}// Ensure the planted assignment remains an independent witness.
    j=0;trueClause=false;for(int i:ids)trueClause|=sign[j++]==plant[i];if(!trueClause){int which=rng.nextInt(3);j=0;for(int i:ids){if(j==which)sign[j]=plant[i];j++;}}
    var terms=new LinkedHashMap<Integer,BigInteger>();int rhs=-1;j=0;for(int i:ids){if(sign[j++])terms.put(i,BigInteger.ONE.negate());else{terms.put(i,BigInteger.ONE);rhs++;}}rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(rhs)));}
   boolean[] solved=new boolean[2];long[] work=new long[2];for(int mode=0;mode<2;mode++){var b=new PlanningBudget(0,2000000,256L<<20,()->false,System::nanoTime);BigInteger[] x;boolean infeasible;
    if(mode==0)try(var s=new CountBoolean(rows,lo,hi,b)){while(!s.step()){}x=s.counts();infeasible=s.infeasible();}else try(var s=new CountCdcl(rows,lo,hi,b,250000)){while(!s.step()){}x=s.counts();infeasible=s.infeasible();}
    if(infeasible)throw new AssertionError("rejected planted SAT "+test);if(x!=null){for(var row:rows){var value=BigInteger.ZERO;for(var e:row.terms().entrySet())value=value.add(e.getValue().multiply(x[e.getKey()]));if(value.compareTo(row.upper())>0)throw new AssertionError("invalid "+mode);}}solved[mode]=x!=null;work[mode]=b.nodes();if(b.reservedBytes()!=0)throw new AssertionError("leak");}
   if(solved[0])oldSolved++;if(solved[1])newSolved++;if(!solved[0]&&solved[1])gains++;if(solved[0]&&!solved[1])losses++;oldWork+=work[0];newWork+=work[1];
  }
  System.out.println("Planted 3-SAT 192 cases, 40..88 variables, 5 clauses/variable, 250k work per backend: old="+oldSolved+" CDCL="+newSolved+" newly_solved="+gains+" old_only="+losses+" old_work="+oldWork+" CDCL_work="+newWork+"; independently checked witnesses");
 }
}
