package org.cgse.core;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class RepairDomainsSafety {
 public static void main(String[] args){
  int stopped=0,released=0;
  for(int sample=0;sample<4096;sample++){
   Random r=new Random(sample+777);int n=2+r.nextInt(12);BigInteger[] ol=new BigInteger[n],ou=new BigInteger[n],lo=new BigInteger[n],hi=new BigInteger[n];BitSet fixed=new BitSet();
   for(int i=0;i<n;i++){
    ol[i]=BigInteger.valueOf(r.nextInt(3));ou[i]=sample%3==0?null:ol[i].add(BigInteger.valueOf(1+r.nextInt(12)));
    if(i!=0&&r.nextBoolean()){lo[i]=hi[i]=ol[i].add(BigInteger.valueOf(ou[i]==null?r.nextInt(8):r.nextInt(ou[i].subtract(ol[i]).intValue()+1)));fixed.set(i);}
    else {lo[i]=ol[i];hi[i]=ou[i];}
   }
   BigInteger[] beforeLo=lo.clone(),beforeHi=hi.clone();BitSet original=(BitSet)fixed.clone();
   var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int j=0;j<n+3;j++){
    Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger rhs=BigInteger.valueOf(r.nextInt(16)-8);
    for(int i=0;i<n;i++)if(r.nextBoolean()){BigInteger a=BigInteger.valueOf(r.nextInt(19)-9).shiftLeft(sample%11==0?128:0);terms.put(i,a);}
    rows.add(new ExactLinearProgram.Constraint(terms,rhs));
   }
   AtomicInteger calls=new AtomicInteger();int stop=sample%13==0?1+r.nextInt(90):Integer.MAX_VALUE;
   var b=new PlanningBudget(0,sample%7==0?64:1_000_000,sample%5==0?1024:1L<<20,()->calls.incrementAndGet()>stop,System::nanoTime);
   try{released+=CountRepairDomains.widen(rows,ol,ou,lo,hi,fixed,b,sample%17==0?0:8192);}
   catch(CancellationException|PlanningBudget.Exhausted expected){stopped++;}
   RepairDomainsProbe.require(b.reservedBytes()==0,"probe reservation leaked");
   for(int i=0;i<n;i++){
    RepairDomainsProbe.require(lo[i].compareTo(ol[i])>=0&&lo[i].compareTo(beforeLo[i])<=0,"lower escaped or tightened");
    RepairDomainsProbe.require(hi[i]==null?ou[i]==null:ou[i]==null||hi[i].compareTo(ou[i])<=0,"upper escaped");
    RepairDomainsProbe.require(beforeHi[i]==null?hi[i]==null:hi[i]==null||hi[i].compareTo(beforeHi[i])>=0,"upper tightened");
    if(!original.get(i))RepairDomainsProbe.require(lo[i].equals(beforeLo[i])&&Objects.equals(hi[i],beforeHi[i]),"changed free/forced coordinate");
   }
  }
  System.out.println("PASS repair-domain scope cases=4096 cancellations/limits="+stopped+" released="+released);
 }
}
