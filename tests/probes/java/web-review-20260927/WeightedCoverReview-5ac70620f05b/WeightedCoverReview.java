package org.cgse.core;
import java.math.*;import java.util.*;
public class WeightedCoverReview {
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] p){for(var row:rows){BigInteger v=BigInteger.ZERO;for(var e:row.terms().entrySet())v=v.add(e.getValue().multiply(p[e.getKey()]));if(v.compareTo(row.upper())>0)return false;}return true;}
 static void row(List<ExactLinearProgram.Constraint> rows,long bound,long... coef){var m=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<coef.length;i++)if(coef[i]!=0)m.put(i,BigInteger.valueOf(coef[i]));rows.add(new ExactLinearProgram.Constraint(m,BigInteger.valueOf(bound)));}
 public static void main(String[] args){Random rng=new Random(449183);long assignments=0,solutions=0;
  for(int sample=0;sample<2000;sample++){
   int a=rng.nextInt(4),b=rng.nextInt(4),w=1+rng.nextInt(6),v=1+rng.nextInt(6),loss=rng.nextInt(9);int g0=rng.nextInt(w*a+v*b+1),g1=w*a+v*b-g0+rng.nextInt(3)-1;
   var rows=new ArrayList<ExactLinearProgram.Constraint>();row(rows,a,1,1);row(rows,b,0,0,1,1,2);row(rows,-g0,-w,0,-v,0,0,loss);row(rows,-g1,0,-w,0,-v);boolean paired=sample%2==0;if(paired)row(rows,1,0,0,0,0,1,1);
   BigInteger[] lo=new BigInteger[6],hi=new BigInteger[6];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.valueOf(3));if(paired)hi[4]=hi[5]=BigInteger.ONE;
   var budget=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);
   try(var reduction=new CountReduction(rows,lo,hi,budget)){while(!reduction.step()){}var reps=reduction.representatives();for(int code=0;code<4096;code++){int bits=code;var p=new BigInteger[6];boolean in=true;for(int i=0;i<6;i++){p[i]=BigInteger.valueOf(bits%4);bits/=4;in&=p[i].compareTo(hi[i])<=0;}if(!in)continue;assignments++;boolean expected=valid(rows,p);var q=new BigInteger[reps.length];for(int i=0;i<reps.length;i++)q[i]=p[reps[i]];boolean actual=valid(reduction.rows(),q)&&Arrays.equals(p,reduction.expand(q));for(int i=0;i<q.length;i++)actual&=q[i].compareTo(reduction.lower()[i])>=0&&(reduction.upper()[i]==null||q[i].compareTo(reduction.upper()[i])<=0);if(expected!=actual)throw new AssertionError("bad weighted cover "+sample+" "+Arrays.toString(p));if(expected)solutions++;}}
   if(budget.reservedBytes()!=0)throw new AssertionError("leak");
  }
  System.out.println("PASS 2000 weighted-cover models; assignments="+assignments+" solutions="+solutions+" incl exact/slack/shortage and binary partners");
 }
}
