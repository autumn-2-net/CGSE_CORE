package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class WarmProjectedProbe {
 static BigInteger b(long x){return BigInteger.valueOf(x);}
 static BigInteger[] fill(int n,long v){var a=new BigInteger[n];Arrays.fill(a,b(v));return a;}
 static PlanningBudget budget(){return new PlanningBudget(0,200000000,128L<<20,()->false,()->0L);}
 static long checks;
 static void check(boolean condition,String detail){checks++;if(!condition)throw new AssertionError(detail);}
 public static void main(String[]args){
  var random=new Random(1981506);long coldWork=0,warmWork=0,coldNumerical=0,warmNumerical=0,reuses=0,calls=0,proofs=0;
  for(int sample=0;sample<9;sample++){
   int n=new int[]{24,48,96}[sample%3];var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int r=0;r<n;r++){var terms=new TreeMap<Integer,BigInteger>();long sum=0;for(int i=0;i<n;i++)if(random.nextBoolean()){int a=1+random.nextInt(9);terms.put(i,b(-a));sum+=a;}rows.add(new ExactLinearProgram.Constraint(terms,b(-Math.max(10,sum/3))));}
   var costs=new TreeMap<Integer,BigInteger>();long capacity=0;for(int i=0;i<n;i++){int a=1+random.nextInt(31);costs.put(i,b(a));capacity+=a;}rows.add(new ExactLinearProgram.Constraint(costs,b(capacity*4/5)));
   var cb=budget();var wb=budget();try(var session=new CountLpLearning.Session()){
    for(int step=0;step<40;step++){
     var lo=fill(n,0);var hi=fill(n,1);lo[n-1]=hi[n-1]=b(step%2);
     long before=cb.nodes();try(var result=CountLpLearning.solve(rows,lo,hi,cb,1000000)){check(result!=null,"cold null");coldNumerical+=result.numericalWork;}coldWork+=cb.nodes()-before;
     before=wb.nodes();try(var result=session.solve(rows,lo,hi,wb,1000000)){check(result!=null,"warm null");warmNumerical+=result.numericalWork;
      if(result.cut!=null){var proof=new CountProof.Derivation("warm_projected",n,rows.stream().map(CountProof::row).toList(),List.of(new CountProof.Combination(result.cut.parents(),result.cut.divisor(),CountProof.row(result.cut.row()))));check(CountProof.verify(proof,2000000)==CountProof.Verdict.VERIFIED,"bad global cut");proofs++;}
     }warmWork+=wb.nodes()-before;calls++;
    }reuses+=session.reused();
   }check(cb.reservedBytes()==0&&wb.reservedBytes()==0,"leak");
   System.out.println("sample="+sample+" n="+n+" cold="+cb.nodes()+" warm="+wb.nodes());
  }
  var budget=budget();try(var s=new CountLpLearning.Session()){
   var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(-1),1,b(-1)),b(-1)));
   try(var result=s.solve(rows,fill(2,0),fill(2,1),budget,100000)){check(result!=null,"init null");}
   check(budget.reservedBytes()>0,"no retained basis");
   try(var result=s.solve(rows,fill(2,1),fill(2,1),budget,100000)){check(result==null,"all fixed should decline");}check(budget.reservedBytes()==0,"all fixed did not drop cache");
   try(var result=s.solve(rows,fill(2,0),fill(2,1),budget,100000)){}
   try(var result=s.solve(rows,fill(2,1000000000000L),fill(2,1000000000001L),budget,100000)){check(result==null,"offset accepted");}check(budget.reservedBytes()==0,"offset retained old cache");
   try(var result=s.solve(rows,fill(2,0),fill(2,1),budget,100000)){}
   try(var result=s.solve(rows,fill(3,0),fill(3,1),budget,100000)){check(result!=null,"dimension change failed");}check(s.invalidated()>0,"dimension did not invalidate");
  }check(budget.reservedBytes()==0,"final leak");
  System.out.println("calls="+calls+" reused="+reuses+" coldWork="+coldWork+" warmWork="+warmWork+" coldNumerical="+coldNumerical+" warmNumerical="+warmNumerical+" exactProofs="+proofs+" assertions="+checks+" errors=0 leaks=0");
 }
}
