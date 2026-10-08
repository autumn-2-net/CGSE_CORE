package org.cgse.core;
import java.math.*;import java.util.*;
public final class FacesReview {
 public static void main(String[] args){var rng=new Random(847122);int found=0;
  for(int sample=0;sample<4000;sample++){
   int n=4+rng.nextInt(60);BigInteger[] low=new BigInteger[n],high=new BigInteger[n];var pos=new LinkedHashMap<Integer,BigInteger>();var neg=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=BigInteger.ZERO;
   for(int i=0;i<n;i++){low[i]=sample%3==0?BigInteger.ONE.shiftLeft(80):BigInteger.ZERO;high[i]=low[i].add(BigInteger.valueOf(1+rng.nextInt(1000000)));var c=BigInteger.valueOf((1+rng.nextInt(100000))*(rng.nextBoolean()?1:-1));pos.put(i,c);neg.put(i,c.negate());var planted=low[i].add(BigInteger.valueOf(rng.nextInt(high[i].subtract(low[i]).intValueExact()+1)));rhs=rhs.add(c.multiply(planted));}
   var rows=List.of(new ExactLinearProgram.Constraint(pos,rhs),new ExactLinearProgram.Constraint(neg,rhs.negate()));var budget=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);var search=new CountDiophantine(rows,low,high,budget);while(!search.step()){}var x=search.counts();if(x!=null){found++;if(!EquationReview.valid(rows,x,low,high))throw new AssertionError("invalid face "+sample);}
  }
  System.out.println("PASS 4000 planted large-domain faces; checked witnesses="+found+"; signs, >long bounds, up to 63 variables; unresolved is not an infeasibility proof");
 }
}
