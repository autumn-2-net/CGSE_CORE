package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class LpProductionCutProbe {
    static long models,cuts,points,nulls,certificates,assertions;
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static BigInteger[] fill(int n,long v){var a=new BigInteger[n];Arrays.fill(a,b(v));return a;}
    static void check(boolean v,String reason){assertions++;if(!v)throw new AssertionError(reason);}
    static boolean valid(ExactLinearProgram.Constraint r,BigInteger[]x){var sum=BigInteger.ZERO;for(var e:r.terms().entrySet())sum=sum.add(e.getValue().multiply(x[e.getKey()]));return sum.compareTo(r.upper())<=0;}
    static PlanningBudget budget(long work,long memory){return new PlanningBudget(0,work,memory,()->false,()->0L);}
    static void verify(List<ExactLinearProgram.Constraint> rows,int n,CountLpLearning.Cut cut){
        if(cut==null)return;
        var proof=new CountProof.Derivation("independent_lp_cut",n,rows.stream().map(CountProof::row).toList(),List.of(new CountProof.Combination(cut.parents(),cut.divisor(),CountProof.row(cut.row()))));
        check(CountProof.verify(proof,2_000_000)==CountProof.Verdict.VERIFIED,"invalid parent certificate "+cut);certificates++;
    }
    static void random(){
        Random rnd=new Random(61993489);
        for(int test=0;test<6000;test++){
            int n=2+rnd.nextInt(8),m=2+rnd.nextInt(18),plant=rnd.nextInt(1<<n);var lo=fill(n,0);var hi=fill(n,1);List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
            for(int r=0;r<m;r++){Map<Integer,BigInteger> terms=new TreeMap<>();BigInteger factor=r%3==0?BigInteger.TEN.pow(35):test%9==0?BigInteger.TEN.pow(70):BigInteger.ONE;BigInteger rhs=b(rnd.nextInt(9)-(test%4==0?6:0)).multiply(factor);for(int i=0;i<n;i++){int a=rnd.nextInt(21)-10;if(a!=0){BigInteger c=b(a).multiply(factor);terms.put(i,c);if(test%4!=0&&(plant&(1<<i))!=0)rhs=rhs.add(c);}}rows.add(new ExactLinearProgram.Constraint(terms,rhs));}
            for(int i=0;i<n;i++)if(rnd.nextInt(4)==0)lo[i]=hi[i]=b(rnd.nextInt(2));
            var budget=budget(2_000_000,64L<<20);
            try(var result=CountLpLearning.solve(rows,lo,hi,budget,200000)){
                models++;if(result==null){nulls++;continue;}verify(rows,n,result.cut);if(result.cut!=null)cuts++;
                for(int mask=0;mask<(1<<n);mask++){var x=fill(n,0);for(int i=0;i<n;i++)if((mask&(1<<i))!=0)x[i]=BigInteger.ONE;if(rows.stream().anyMatch(r->!valid(r,x)))continue;points++;if(result.cut!=null)check(valid(result.cut.row(),x),"cut not global test="+test+" localLow="+Arrays.toString(lo)+" localHi="+Arrays.toString(hi)+" original feasible="+Arrays.toString(x));}
            }finally{check(budget.reservedBytes()==0,"random owned leak "+test);}
        }
    }
    static void mixed(){
        for(int exponent:new int[]{0,1,10,35,70,120,150,200}) {
            var scale=BigInteger.TEN.pow(exponent);var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,scale,1,scale.negate()),BigInteger.ZERO),new ExactLinearProgram.Constraint(Map.of(0,b(-1)),b(-1)));
            var low=fill(3,0);var high=fill(3,1);high[1]=BigInteger.ZERO;var budget=budget(2_000_000,64L<<20);
            try(var result=CountLpLearning.solve(rows,low,high,budget,200000)){
                check(result!=null,"mixed result declined "+exponent);check(result.numericalInfeasible,"mixed not numerical infeasible "+exponent);verify(rows,3,result.cut);
                if(exponent<=150)check(result.cut!=null,"mixed exact reconstruction failed "+exponent);
                if(result.cut!=null)for(int mask=0;mask<8;mask++){var x=fill(3,0);for(int i=0;i<3;i++)if((mask&(1<<i))!=0)x[i]=BigInteger.ONE;if(rows.stream().allMatch(r->valid(r,x)))check(valid(result.cut.row(),x),"mixed global cut "+exponent);}
                System.out.println("MIXED exponent="+exponent+" cut="+(result.cut!=null)+" work="+budget.nodes()+" maxWeightBits="+(result.cut==null?0:result.cut.parents().values().stream().mapToInt(BigInteger::bitLength).max().orElse(0)));
            }check(budget.reservedBytes()==0,"mixed leak");
        }
    }
    static void lifecycle(){
        var simple=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(1)),b(0)));
        for(int kind=0;kind<6;kind++){var lo=fill(3,0);var hi=fill(3,1);if(kind==0)lo[0]=b(-1);if(kind==1)hi[0]=b(2);if(kind==2)lo[0]=hi[0]=BigInteger.TEN.pow(70);if(kind==3)hi[0]=null;if(kind==4)Arrays.fill(lo,BigInteger.ONE);if(kind==5)lo[0]=b(2);var budget=budget(100000,1L<<20);try(var result=CountLpLearning.solve(simple,lo,hi,budget,32768)){check(result==null,"unsupported domain "+kind);}check(budget.reservedBytes()==0,"unsupported leak");}
        List<ExactLinearProgram.Constraint> rows=new ArrayList<>();for(int r=0;r<40;r++){Map<Integer,BigInteger> terms=new TreeMap<>();for(int i=0;i<20;i++)terms.put(i,b((i+r)%19-9));rows.add(new ExactLinearProgram.Constraint(terms,b(r%8-2)));}
        for(int after:new int[]{0,1,10,100,1000})for(long mem:new long[]{1024,8192,65536,1048576}){AtomicInteger calls=new AtomicInteger();var budget=new PlanningBudget(0,100000,mem,()->calls.incrementAndGet()>after,()->0L);try(var result=CountLpLearning.solve(rows,fill(20,0),fill(20,1),budget,32768)){}catch(CancellationException|PlanningBudget.Exhausted expected){}check(budget.reservedBytes()==0,"cancel leak");}
        for(long cap:new long[]{1,17,64,1024,8192}){var budget=budget(cap,1L<<20);try(var result=CountLpLearning.solve(rows,fill(20,0),fill(20,1),budget,32768)){}catch(PlanningBudget.Exhausted expected){}check(budget.reservedBytes()==0,"global work leak");}
        var budget=budget(100000,1024);try(var result=CountLpLearning.solve(rows,fill(20,0),fill(20,1),budget,32768)){check(result==null,"tiny workspace admitted");}check(budget.reservedBytes()==0,"tiny memory leak");System.out.println("tinyMemory work="+budget.nodes()+" peak="+budget.peakBytes());
        var dense=new ArrayList<ExactLinearProgram.Constraint>();var terms=new TreeMap<Integer,BigInteger>();for(int i=0;i<128;i++)terms.put(i,b(i%3-1));for(int r=0;r<512;r++)dense.add(new ExactLinearProgram.Constraint(terms,b(r%4)));
        budget=budget(1000000,64L<<20);try(var result=CountLpLearning.solve(dense,fill(128,0),fill(128,1),budget,1024)){check(result==null,"dense small allowance should decline");}check(budget.reservedBytes()==0,"dense leak");System.out.println("denseAdmission maximumWork=1024 actualWork="+budget.nodes());check(budget.nodes()<=1040,"dense admission exceeds local work");
        budget=budget(100000,64L<<20);Thread.currentThread().interrupt();try(var result=CountLpLearning.solve(simple,fill(3,0),fill(3,1),budget,32768)){throw new AssertionError("interrupt ignored");}catch(CancellationException expected){}finally{Thread.interrupted();}check(budget.reservedBytes()==0,"interrupt leak");
    }
    public static void main(String[] args){random();mixed();lifecycle();System.out.println("LpProductionCutProbe models="+models+" cuts="+cuts+" globallyFeasiblePoints="+points+" nulls="+nulls+" independentCertificates="+certificates+" assertions="+assertions+" invalid=0 ownedLeaks=0");}
}
