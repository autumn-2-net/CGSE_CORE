package org.cgse.core;
import java.math.BigInteger;import java.util.*;
public class SaturationProbe{
 static long checks;static BigInteger b(long n){return BigInteger.valueOf(n);}
 static boolean pass(ExactLinearProgram.Constraint r,BigInteger[] x){BigInteger t=BigInteger.ZERO;for(var e:r.terms().entrySet())t=t.add(e.getValue().multiply(x[e.getKey()]));return t.compareTo(r.upper())<=0;}
 static void walk(int at,BigInteger[] x,BigInteger[] lo,BigInteger[] hi,ExactLinearProgram.Constraint a,ExactLinearProgram.Constraint z){if(at==x.length){checks++;if(pass(a,x)!=pass(z,x))throw new AssertionError(a+" -> "+z+" at "+Arrays.toString(x));return;}for(BigInteger v=lo[at];v.compareTo(hi[at])<=0;v=v.add(BigInteger.ONE)){x[at]=v;walk(at+1,x,lo,hi,a,z);}}
 public static void main(String[] args){Random r=new Random(8658793);PlanningBudget budget=new PlanningBudget(0,20_000_000,64L<<20,()->false,()->0L);int changed=0;
  for(int k=0;k<5000;k++){int n=2+r.nextInt(6);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];Map<Integer,BigInteger> terms=new TreeMap<>();BigInteger scale=k%5==0?BigInteger.TEN.pow(40):BigInteger.ONE;
   for(int i=0;i<n;i++){lo[i]=b(r.nextInt(11)-5);hi[i]=lo[i].add(b(r.nextInt(4)));int c=r.nextInt(121)-60;if(c!=0)terms.put(i,b(c).multiply(scale));}
   var a=new ExactLinearProgram.Constraint(terms,b(r.nextInt(1001)-500).multiply(scale));var z=CountSaturateProbeHelper.apply(a,lo,hi,budget);if(!a.equals(z))changed++;walk(0,new BigInteger[n],lo,hi,a,z);
  }System.out.println("SaturationProbe models=5000 changed="+changed+" exactIntegerPoints="+checks+" reserved="+budget.reservedBytes());
 }
}
