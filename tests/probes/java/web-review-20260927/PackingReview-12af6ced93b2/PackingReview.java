package org.cgse.core;
import java.math.*;import java.util.*;
public final class PackingReview {
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){var s=BigInteger.ZERO;for(var e:r.terms().entrySet())s=s.add(e.getValue().multiply(x[e.getKey()]));if(s.compareTo(r.upper())>0)return false;}return true;}
 public static void main(String[] args){var rng=new Random(572903);int found=0,sat=0,absent=0;
 for(int sample=0;sample<5000;sample++){
  int n=4+rng.nextInt(8),m=1+rng.nextInt(5),fixed=rng.nextInt(3),size=n+fixed;
  BigInteger[] low=new BigInteger[size],high=new BigInteger[size];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);
  var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger scale=sample%4==0?BigInteger.ONE.shiftLeft(90):BigInteger.ONE;
  for(int i=n;i<size;i++)low[i]=high[i]=BigInteger.valueOf(rng.nextInt(5));
  for(int r=0;r<m+1;r++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=BigInteger.valueOf(rng.nextInt(n*4)+1).multiply(scale);if(r==m)rhs=rhs.negate();
   for(int i=0;i<size;i++){var c=BigInteger.valueOf(rng.nextInt(10)).multiply(scale);if(r==m)c=c.negate();if(c.signum()!=0)terms.put(i,c);if(i>=n)rhs=rhs.add(c.multiply(low[i]));}rows.add(new ExactLinearProgram.Constraint(terms,rhs));
  }
  boolean truth=false;for(int bits=0;bits<(1<<n);bits++){var x=low.clone();for(int j=0;j<n;j++)x[j]=BigInteger.valueOf((bits>>j)&1);if(valid(rows,x)){truth=true;break;}}
  if(truth)sat++;
  var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
  try(var work=new CountPacking(rows,low,high,budget)){while(!work.step()){}var x=work.counts();if(x!=null){found++;if(!truth||!valid(rows,x))throw new AssertionError("false packing "+sample);for(int i=0;i<size;i++)if(x[i].compareTo(low[i])<0||x[i].compareTo(high[i])>0)throw new AssertionError("bad domain");}else if(truth){absent++;if(budget.diagnostics().contains("exhausted_candidate") && budget.diagnostics().contains("choices="+n+";"))throw new AssertionError("bad packing exclusion "+sample);}}
  if(budget.reservedBytes()!=0)throw new AssertionError("memory leak");
 }
 System.out.println("PASS 5000 independently enumerated packing problems; SAT="+sat+" found="+found+" inapplicable="+absent+"; zero weights/profits, fixed variables, >long exact arithmetic");}
}
