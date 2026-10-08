package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;

public class CacheStateSafety {
    static long assertions, assignments, states;
    static final Field lowField=field(CountLcg.class,"low"), highField=field(CountLcg.class,"high"), rootLowField=field(CountLcg.class,"rootLow"), rootHighField=field(CountLcg.class,"rootHigh"), rowsField=field(CountLcg.class,"rows"), activityField=field(CountLcg.class,"rowActivity");
    static Field field(Class<?> owner,String name){try{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new RuntimeException(e);}}
    static void expect(boolean b,String message){assertions++;if(!b)throw new AssertionError(message);}
    static void state(CountLcg search)throws Exception{
        BigInteger[] lo=(BigInteger[])lowField.get(search),hi=(BigInteger[])highField.get(search),rlo=(BigInteger[])rootLowField.get(search),rhi=(BigInteger[])rootHighField.get(search);
        @SuppressWarnings("unchecked") var rows=(List<ExactLinearProgram.Constraint>)rowsField.get(search);
        Object[] activities=(Object[])activityField.get(search);
        for(int r=0;r<Math.min(rows.size(),activities.length);r++){
            Object cache=activities[r];if(cache==null||!field(cache.getClass(),"valid").getBoolean(cache))continue;
            BigInteger sum=BigInteger.ZERO,max=BigInteger.ZERO;int inf=0,xor=0;
            for(var term:rows.get(r).terms().entrySet()){
                int id=term.getKey();BigInteger a=term.getValue();if(a.signum()==0)continue;
                BigInteger endpoint=a.signum()>0?lo[id]:hi[id];
                if(endpoint==null){inf++;xor^=id;}else sum=sum.add(a.multiply(endpoint));
                if(rhi[id]==null)max=null;else if(max!=null)max=max.max(a.abs().multiply(rhi[id].subtract(rlo[id])));
            }
            expect(sum.equals(field(cache.getClass(),"minimum").get(cache)),"stale minimum row"+r);
            expect(inf==field(cache.getClass(),"infinities").getInt(cache),"bad infinite count");
            expect(xor==field(cache.getClass(),"infiniteXor").getInt(cache),"bad infinite xor");
            expect(Objects.equals(max,field(cache.getClass(),"maximumChange").get(cache)),"bad immutable maximum");
        }
        states++;
    }
    static boolean satisfies(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){
        for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;
    }
    static boolean enumerate(int at,int[] lo,int[] hi,BigInteger[] values,List<ExactLinearProgram.Constraint> rows){
        if(at==lo.length){assignments++;return satisfies(rows,values);}
        boolean sat=false;for(int value=lo[at];value<=hi[at];value++){values[at]=BigInteger.valueOf(value);sat|=enumerate(at+1,lo,hi,values,rows);}return sat;
    }
    public static void main(String[] args)throws Exception{
        Random random=new Random(9193011);int models=0,sat=0,unsat=0,proofs=0;
        for(int test=0;test<4000;test++){
            int n=2+random.nextInt(5);int[] lower=new int[n],upper=new int[n];BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];int[] planted=new int[n];
            boolean binary=test%4==0;
            for(int i=0;i<n;i++){lower[i]=binary?0:random.nextInt(3);upper[i]=lower[i]+(binary?1:random.nextInt(4));lo[i]=BigInteger.valueOf(lower[i]);hi[i]=BigInteger.valueOf(upper[i]);planted[i]=lower[i]+random.nextInt(upper[i]-lower[i]+1);}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger scale=test%17==0?BigInteger.TEN.pow(70):BigInteger.ONE;
            for(int r=0,count=n+random.nextInt(7);r<count;r++){
                var terms=new LinkedHashMap<Integer,BigInteger>();long b=0;
                for(int i=0;i<n;i++){int a=random.nextInt(19)-9;if(a!=0){terms.put(i,BigInteger.valueOf(a).multiply(scale));b+=a*planted[i];}}
                b+=test%3==0?random.nextInt(7):random.nextInt(11)-6;
                rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(b).multiply(scale)));
            }
            // Infinite original endpoints become finite through actual row propagation.
            if(test%7==0)for(int i=0;i<n;i++){rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),hi[i]));hi[i]=null;}
            boolean oracle=enumerate(0,lower,upper,new BigInteger[n],rows);
            var budget=new PlanningBudget(0,1000000,64L<<20,()->false,System::nanoTime);
            try(var search=new CountLcg(rows,lo,hi,budget,4096,true)){
                if(binary)search.learnedRelaxation();
                for(int round=0;round<100000;round++){
                    boolean done=search.step();state(search);
                    if(!done)continue;
                    if(search.paused()){search.resume(127);continue;}
                    break;
                }
                var point=search.counts();expect(oracle==(point!=null),"oracle mismatch test="+test);
                if(point!=null){CountBenchmark.verify(rows,lo,hi,point);sat++;}
                else{expect(search.infeasible(),"tiny unknown");expect(CountProof.verify(search.certificate(),2_000_000)==CountProof.Verdict.VERIFIED,"proof failure");unsat++;proofs++;}
            }
            expect(budget.reservedBytes()==0,"leak");models++;
        }
        System.out.println("CACHE_STATE models="+models+" assignments="+assignments+" states="+states+" assertions="+assertions+" sat="+sat+" unsat="+unsat+" proofs="+proofs+" leaks=0");
    }
}
