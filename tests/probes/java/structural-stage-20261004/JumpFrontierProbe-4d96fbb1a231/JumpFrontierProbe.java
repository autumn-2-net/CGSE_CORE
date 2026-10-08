package org.cgse.core;
import java.util.*;
import java.math.*;
import java.lang.reflect.*;
public class JumpFrontierProbe {
 static long checks, solved;
 static Object get(Object o,String f)throws Exception{Field x=o.getClass().getDeclaredField(f);x.setAccessible(true);return x.get(o);}
 static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 public static void main(String[] a)throws Exception {
  for(int seed=0;seed<600;seed++){
   Random rng=new Random(33981+seed); int n=4+rng.nextInt(28);BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n], planted=new BigInteger[n];
   for(int i=0;i<n;i++){lo[i]=BigInteger.valueOf(rng.nextInt(3));hi[i]=lo[i].add(BigInteger.valueOf(rng.nextInt(4)==0?1:3+rng.nextInt(24)));planted[i]=lo[i].add(BigInteger.valueOf(rng.nextInt(hi[i].subtract(lo[i]).intValue()+1)));if(seed%8==0&&i%3==0)hi[i]=null;}
   List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
   for(int r=0;r<n*2;r++){Map<Integer,BigInteger>t=new LinkedHashMap<>();for(int j=0;j<2+rng.nextInt(7);j++){int id=rng.nextInt(n);BigInteger v=BigInteger.valueOf(rng.nextInt(13)-6);if(seed%7==0)v=v.multiply(BigInteger.TEN.pow(35));t.put(id,v);}BigInteger b=BigInteger.ZERO;for(var e:t.entrySet())b=b.add(e.getValue().multiply(planted[e.getKey()]));b=b.add(BigInteger.valueOf(rng.nextInt(5)));rows.add(new ExactLinearProgram.Constraint(t,b));}
   PlanningBudget budget=new PlanningBudget(0,1_000_000,32L<<20,()->false,System::nanoTime);
   try(CountJump jump=new CountJump(rows,lo,hi,budget,2048).retained()){
    int rounds=0;
    do { while(!jump.step()) {
      int initialized=(int)get(jump,"initialized");if(initialized!=rows.size())continue;
      BigInteger[]res=(BigInteger[])get(jump,"residual");int[]count=(int[])get(jump,"violatedIncidences");BitSet rel=(BitSet)get(jump,"relevant");
      if((boolean)get(jump,"wideDomains")) for(int i=0;i<n;i++){int want=0;for(int r=0;r<rows.size();r++)if(res[r].signum()>0&&rows.get(r).terms().getOrDefault(i,BigInteger.ZERO).signum()!=0&&!lo[i].equals(hi[i]))want++;check(count[i]==want,"incidence seed="+seed+" v="+i+" "+count[i]+" vs "+want);check(rel.get(i)==(want>0),"frontier");}
    }if(jump.counts()!=null){CountBenchmark.verify(rows,lo,hi,jump.counts());solved++;break;}if(!jump.paused()||++rounds>=32)break;jump.resume(2048);}while(true);
   }
   check(budget.reservedBytes()==0,"memory");
  }
  System.out.println("PASS cases=600 checks="+checks+" solved="+solved);
 }
}
