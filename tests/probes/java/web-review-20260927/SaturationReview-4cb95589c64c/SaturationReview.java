package org.cgse.core;
import java.math.*;import java.util.*;
public final class SaturationReview {
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){var s=BigInteger.ZERO;for(var e:r.terms().entrySet())s=s.add(e.getValue().multiply(x[e.getKey()]));if(s.compareTo(r.upper())>0)return false;}return true;}
 public static void main(String[] args){var rng=new Random(875642);int solutions=0;
 for(int sample=0;sample<4000;sample++){
  boolean mixed=sample%2==0;int n=mixed?5:4;BigInteger[] low=new BigInteger[n],high=new BigInteger[n];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.valueOf(4));if(mixed){low[4]=BigInteger.ONE;high[4]=BigInteger.TWO;}
  int[] coef=new int[4];var a=new LinkedHashMap<Integer,BigInteger>();var b=new LinkedHashMap<Integer,BigInteger>();var cap=new LinkedHashMap<Integer,BigInteger>();
  int first=rng.nextInt(16),second=rng.nextInt(16),slack=rng.nextInt(3)-1;
  for(int i=0;i<4;i++){coef[i]=1+rng.nextInt(4);var c=BigInteger.valueOf(coef[i]);(i<2?a:b).put(i,c.negate());cap.put(i,c);}if(mixed){a.put(4,BigInteger.valueOf(first));b.put(4,BigInteger.valueOf(second));}
  var rows=new ArrayList<ExactLinearProgram.Constraint>();rows.add(new ExactLinearProgram.Constraint(a,mixed?BigInteger.ZERO:BigInteger.valueOf(-first)));rows.add(new ExactLinearProgram.Constraint(b,mixed?BigInteger.ZERO:BigInteger.valueOf(-second)));rows.add(new ExactLinearProgram.Constraint(cap,BigInteger.valueOf(first+second+slack)));
  var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);
  try(var reduce=new CountReduction(rows,low,high,budget)){while(!reduce.step()){}var reps=reduce.representatives();
   for(int code=0;code<(mixed?1250:625);code++){int v=code;var x=new BigInteger[n];for(int i=0;i<4;i++){x[i]=BigInteger.valueOf(v%5);v/=5;}if(mixed)x[4]=BigInteger.valueOf(1+v);boolean truth=valid(rows,x);var y=new BigInteger[reps.length];for(int i=0;i<reps.length;i++)y[i]=x[reps[i]];boolean reduced=valid(reduce.rows(),y)&&Arrays.equals(x,reduce.expand(y));if(truth!=reduced)throw new AssertionError("wrong saturation sample="+sample+" x="+Arrays.toString(x));if(truth)solutions++;}
  }
  if(budget.reservedBytes()!=0)throw new AssertionError("leak");
 }
 System.out.println("PASS 4000 saturation models, 3750000 assignments, preserved "+solutions+" solutions incl. mixed rows, slack/rounding near misses");}
}
