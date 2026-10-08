package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.*;
import java.util.*;

public final class JumpSweepProbe {
    static long checks, coordinates, exhaustiveWork, sweptWork;
    static final Method recompute, score;
    static { try { recompute=CountJump.class.getDeclaredMethod("recompute",int.class); score=CountJump.class.getDeclaredMethod("score",int.class,BigInteger.class); recompute.setAccessible(true); score.setAccessible(true); } catch(Exception e) { throw new RuntimeException(e); } }
    static Object field(Object x,String n)throws Exception { var f=x.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(x); }
    static BigInteger b(long v){return BigInteger.valueOf(v);}
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    static BigInteger floor(BigInteger a,BigInteger d){var qr=a.divideAndRemainder(d);return qr[1].signum()!=0&&a.signum()!=d.signum()?qr[0].subtract(BigInteger.ONE):qr[0];}
    static BigInteger clamp(BigInteger x,BigInteger lo,BigInteger hi){return hi==null?x.max(lo):x.max(lo).min(hi);}
    static void sample(Random random,int test,int degree,boolean lowMemory)throws Exception {
        int n=3;var lo=new BigInteger[]{b(0),b(0),b(0)};var hi=new BigInteger[]{b(1000),b(1000),b(1000)};
        if(test%5==0)hi[0]=null;
        var scale=test%7==0?BigInteger.TEN.pow(80):BigInteger.ONE;
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int r=0;r<degree;r++){
            var terms=new TreeMap<Integer,BigInteger>();
            for(int i=0;i<n;i++){int a=random.nextInt(40)-20;if(a==0)a=1;terms.put(i,b(a).multiply(scale));}
            rows.add(new ExactLinearProgram.Constraint(terms,b(random.nextInt(6001)-3000).multiply(scale)));
        }
        var budget=new PlanningBudget(0,1_000_000_000,64L<<20,()->false,()->0L);
        try(var jump=new CountJump(rows,lo,hi,budget,100_000_000).retained()){
            while((int)field(jump,"initialized")<rows.size())check(!jump.step(),"early completion");
            var values=(BigInteger[])field(jump,"values");var residual=(BigInteger[])field(jump,"residual");var weights=(double[])field(jump,"weights");
            for(int i=0;i<n;i++)values[i]=b(random.nextInt(1001));
            for(int r=0;r<rows.size();r++){var row=rows.get(r);var sum=row.upper().negate();for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(values[t.getKey()]));residual[r]=sum;weights[r]=1+random.nextInt(30);}
            @SuppressWarnings("unchecked") var affected=(List<List<?>>)field(jump,"affected");
            long held=lowMemory?budget.availableBytes():0;if(held>0)check(budget.tryReserve(held),"reserve guard");
            try{
                for(int i=0;i<n;i++){
                    var candidates=new TreeSet<BigInteger>();candidates.add(lo[i]);if(hi[i]!=null)candidates.add(hi[i]);
                    candidates.add(clamp(values[i].subtract(BigInteger.ONE),lo[i],hi[i]));candidates.add(clamp(values[i].add(BigInteger.ONE),lo[i],hi[i]));
                    for(var term:affected.get(i)){
                        int r=(int)field(term,"row");var a=(BigInteger)field(term,"coefficient");var at=floor(residual[r].negate(),a);
                        candidates.add(clamp(values[i].add(at),lo[i],hi[i]));candidates.add(clamp(values[i].add(at).add(BigInteger.ONE),lo[i],hi[i]));
                    }
                    candidates.remove(values[i]);long start=budget.threadWork();double best=Double.POSITIVE_INFINITY;
                    for(var v:candidates)best=Math.min(best,(double)score.invoke(jump,i,v));exhaustiveWork+=budget.threadWork()-start;
                    start=budget.threadWork();recompute.invoke(jump,i);sweptWork+=budget.threadWork()-start;
                    var actual=((double[])field(jump,"scores"))[i];coordinates++;
                    check(Math.abs(actual-best)<=1e-7*(1+Math.abs(best)),"coordinate minimum test="+test+" i="+i+" best="+best+" actual="+actual);
                    var value=((BigInteger[])field(jump,"jumps"))[i];check(value!=null&&value.compareTo(lo[i])>=0&&(hi[i]==null||value.compareTo(hi[i])<=0),"jump outside domain");
                }
            }finally{budget.release(held);}
        }check(budget.reservedBytes()==0,"jump memory leak");
    }
    public static void main(String[]args)throws Exception{
        var random=new Random(107719);
        for(int test=0;test<600;test++)sample(random,test,8+random.nextInt(120),test%10==0);
        for(int test=0;test<10;test++)sample(random,test,512,false);
        for(int test=0;test<500;test++){
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            BigInteger minimum=b(0);
            for(int r=0;r<8+test%60;r++){
                var coefficient=b(1+random.nextInt(100));var rhs=BigInteger.TEN.pow(test%2==0?30:3).add(b(random.nextInt(10000)));
                rows.add(new ExactLinearProgram.Constraint(Map.of(0,coefficient.negate(),1,b(101+random.nextInt(100))),rhs.negate()));
                minimum=minimum.max(rhs.add(coefficient).subtract(BigInteger.ONE).divide(coefficient));
            }
            var budget=new PlanningBudget(0,10_000_000,64L<<20,()->false,()->0L);
            try(var jump=new CountJump(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{null,b(0)},budget,1_000_000)){
                while((int)field(jump,"initialized")<rows.size())check(!jump.step(),"unbounded init");
                recompute.invoke(jump,0);var value=((BigInteger[])field(jump,"jumps"))[0];
                check(value!=null&&value.compareTo(minimum)>=0,"missed unbounded plateau "+test+" min="+minimum+" jump="+value);
            }
            check(budget.reservedBytes()==0,"unbounded sweep leak");
        }
        System.out.println("JumpSweepProbe coordinates="+coordinates+" exhaustiveWork="+exhaustiveWork+" sweptWork="+sweptWork+" checks="+checks+" errors=0 leaks=0");
    }
}
