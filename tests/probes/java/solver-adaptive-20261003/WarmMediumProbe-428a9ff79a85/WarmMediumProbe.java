package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class WarmMediumProbe {
 static BigInteger b(long x){return BigInteger.valueOf(x);}
 static BigInteger[] fill(int n,long v){var a=new BigInteger[n];Arrays.fill(a,b(v));return a;}
 public static void main(String[]args){
  var random=new Random(2100443);long cw=0,ww=0,cn=0,wn=0,calls=0,reuses=0,misses=0;
  for(int sample=0;sample<18;sample++){
   int n=new int[]{24,48,96}[sample%3],m=n;var rows=new ArrayList<ExactLinearProgram.Constraint>();var target=fill(n,0);
   for(int i=0;i<n;i++)target[i]=b(random.nextInt(2));
   for(int r=0;r<m;r++){
    var terms=new TreeMap<Integer,BigInteger>();var rhs=b(random.nextInt(6));
    for(int i=0;i<n;i++)if(random.nextInt(4)==0){var a=b(random.nextInt(17)-8);if(a.signum()!=0){terms.put(i,a);rhs=rhs.add(a.multiply(target[i]));}}
    rows.add(new ExactLinearProgram.Constraint(terms,rhs));
   }
   var objective=new TreeMap<Integer,BigInteger>();var capacity=b(5);for(int i=0;i<n;i++){var cost=b(1+random.nextInt(31));objective.put(i,cost);capacity=capacity.add(cost.multiply(target[i]));}rows.add(new ExactLinearProgram.Constraint(objective,capacity));
   var cb=new PlanningBudget(0,100000000,128L<<20,()->false,()->0L);var wb=new PlanningBudget(0,100000000,128L<<20,()->false,()->0L);
   long startc=cb.nodes(),startw=wb.nodes();
   try(var session=new CountLpLearning.Session()){
    for(int step=0;step<40;step++){
     var lo=fill(n,0);var hi=fill(n,1);int fixed=step%20;
     for(int i=0;i<fixed;i++)lo[i]=hi[i]=target[i];
     long before=cb.nodes();try(var result=CountLpLearning.solve(rows,lo,hi,cb,1000000)){if(result!=null)cn+=result.numericalWork;else misses++;}cw+=cb.nodes()-before;
     before=wb.nodes();try(var result=session.solve(rows,lo,hi,wb,1000000)){if(result!=null)wn+=result.numericalWork;else misses++;}ww+=wb.nodes()-before;calls++;
    }reuses+=session.reused();
   }
   if(cb.reservedBytes()!=0||wb.reservedBytes()!=0)throw new AssertionError("leak");
   System.out.println("sample="+sample+" n="+n+" cold="+(cb.nodes()-startc)+" warm="+(wb.nodes()-startw));
  }
  System.out.println("calls="+calls+" coldWork="+cw+" warmWork="+ww+" coldNumerical="+cn+" warmNumerical="+wn+" reuses="+reuses+" nulls="+misses);
 }
}
