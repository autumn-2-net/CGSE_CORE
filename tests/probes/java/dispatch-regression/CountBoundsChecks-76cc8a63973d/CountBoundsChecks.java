package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
public class CountBoundsChecks {
 static boolean accepts(ExactLinearProgram.Constraint r,int[] v){BigInteger t=BigInteger.ZERO;for(var e:r.terms().entrySet())t=t.add(e.getValue().multiply(BigInteger.valueOf(v[e.getKey()])));return t.compareTo(r.upper())<=0;}
 public static void main(String[] args){int checks=0;var random=new Random(518852);
  for(int sample=0;sample<5000;sample++){
   var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<3;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),BigInteger.valueOf(4)));
   for(int r=0;r<1+random.nextInt(9);r++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<3;i++){long n=random.nextInt(15)-7;if(n!=0)terms.put(i,BigInteger.valueOf(n));}rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(random.nextInt(41)-15)));}
   var budget=ExactAnalysisChecks.budget();var work=new CountBounds(3,rows,budget);while(!work.step()){}var bounds=work.tightened();
   for(int a=0;a<=4;a++)for(int b=0;b<=4;b++)for(int c=0;c<=4;c++){int[] v={a,b,c};if(rows.stream().allMatch(r->accepts(r,v))){if(work.blocked()||!bounds.stream().allMatch(r->accepts(r,v)))throw new AssertionError("removed feasible "+sample+" "+Arrays.toString(v));checks++;}}
   if(budget.reservedBytes()!=0)throw new AssertionError("memory leak");
  }System.out.println("BOUNDS_PASS cases=5000 preserved_feasible_points="+checks);
 }
}
