package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class PhaseSavingSafety {
    static long assertions, assignments, phaseRuns, sat, unsat, proofs, resumes, cutoffs, cancelled, declines;
    static Field field(String name){try{Field f=CountLcg.class.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new RuntimeException(e);}}
    static final Field SAVED=field("savedValues"),NEXT=field("nextRestart"),RESTARTS=field("restarts");
    static void check(boolean ok,String text){assertions++;if(!ok)throw new AssertionError(text);}
    static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var row:rows){BigInteger s=BigInteger.ZERO;for(var t:row.terms().entrySet())s=s.add(t.getValue().multiply(x[t.getKey()]));if(s.compareTo(row.upper())>0)return false;}return true;}
    static boolean enumerate(int id,BigInteger[] lo,int[] spans,BigInteger[] x,List<ExactLinearProgram.Constraint> rows){
        if(id==x.length){assignments++;return valid(rows,x);}boolean possible=false;
        for(int i=0;i<=spans[id];i++){x[id]=lo[id].add(BigInteger.valueOf(i));possible|=enumerate(id+1,lo,spans,x,rows);}return possible;
    }
    static void state(CountLcg solver,BigInteger[] lo,BigInteger[] hi)throws Exception{
        var saved=(BigInteger[])SAVED.get(solver);if(saved==null)return;
        for(int i=0;i<saved.length;i++)if(saved[i]!=null)check(saved[i].compareTo(lo[i])>=0&&saved[i].compareTo(hi[i])<=0,"phase cached value outside original domain");
    }
    static void model(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,boolean oracle,boolean early,long limit,long bytes,int cancelAt)throws Exception{
        AtomicInteger checks=new AtomicInteger();var budget=new PlanningBudget(0,limit,bytes,()->checks.incrementAndGet()>=cancelAt,System::nanoTime);
        try(var solver=new CountLcg(rows,lo,hi,budget,1024,true)){
            // Exercise the exact phase transition on small truth-table models without requiring 64 conflicts.
            if(early)NEXT.setInt(solver,0);
            boolean ended=false;
            for(int step=0;step<1000000;step++){
                boolean done=solver.step();state(solver,lo,hi);
                if(!done)continue;
                if(solver.paused()){
                    check(!solver.infeasible()&&(solver.certificate()==null||!solver.certificate().closed()),"pause exported UNSAT");
                    solver.resume(4096);resumes++;continue;
                }
                ended=true;break;
            }
            check(ended,"step loop did not finish");if(SAVED.get(solver)!=null)phaseRuns++;
            var x=solver.counts();
            if(x!=null){check(oracle,"false SAT");check(valid(rows,x),"false witness");for(int i=0;i<x.length;i++)check(x[i].compareTo(lo[i])>=0&&x[i].compareTo(hi[i])<=0,"outside bounds");sat++;}
            else if(solver.infeasible()){check(!oracle,"false UNSAT");check(CountProof.verify(solver.certificate(),20_000_000)==CountProof.Verdict.VERIFIED,"invalid certificate");proofs++;unsat++;}
            else{declines++;check(bytes<64L<<20||limit<500000,"unexplained tiny model UNKNOWN");}
        }catch(PlanningBudget.Exhausted expected){cutoffs++;}
        catch(java.util.concurrent.CancellationException expected){cancelled++;}
        finally{check(budget.reservedBytes()==0,"reservation leak "+budget.reservedBytes());}
    }
    static List<ExactLinearProgram.Constraint> pigeon(int p,int h){var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<p;i++){var t=new LinkedHashMap<Integer,BigInteger>();for(int j=0;j<h;j++)t.put(i*h+j,BigInteger.ONE.negate());rows.add(new ExactLinearProgram.Constraint(t,BigInteger.ONE.negate()));}for(int j=0;j<h;j++){var t=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<p;i++)t.put(i*h+j,BigInteger.ONE);rows.add(new ExactLinearProgram.Constraint(t,BigInteger.ONE));}return rows;}
    public static void main(String[]args)throws Exception{
        Random rand=new Random(40961003);
        for(int test=0;test<1000;test++){
            int n=3+rand.nextInt(4);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n],planted=new BigInteger[n];int[] spans=new int[n];
            BigInteger offset=test%13==0?BigInteger.TEN.pow(50):BigInteger.valueOf(rand.nextInt(5)-2);BigInteger scale=test%17==0?BigInteger.TEN.pow(35):BigInteger.ONE;
            for(int i=0;i<n;i++){lo[i]=offset.add(BigInteger.valueOf(rand.nextInt(3)));spans[i]=1+rand.nextInt(3);hi[i]=lo[i].add(BigInteger.valueOf(spans[i]));planted[i]=lo[i].add(BigInteger.valueOf(rand.nextInt(spans[i]+1)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<n+2;r++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger b=BigInteger.ZERO;for(int i=0;i<n;i++){int a=rand.nextInt(13)-6;if(a!=0){BigInteger c=BigInteger.valueOf(a).multiply(scale);terms.put(i,c);b=b.add(c.multiply(planted[i]));}}b=b.add(BigInteger.valueOf(test%3==0?rand.nextInt(5):rand.nextInt(9)-4).multiply(scale));rows.add(new ExactLinearProgram.Constraint(terms,b));}
            boolean oracle=enumerate(0,lo,spans,new BigInteger[n],rows);model(rows,lo,hi,oracle,true,1_000_000,64L<<20,Integer.MAX_VALUE);
        }
        check(phaseRuns>50,"insufficient phase activation coverage");
        var rows=pigeon(6,5);BigInteger[] lo=new BigInteger[30],hi=new BigInteger[30];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);
        long before=phaseRuns;model(rows,lo,hi,false,false,4_000_000,64L<<20,Integer.MAX_VALUE);check(phaseRuns>before,"natural conflict restart did not activate");
        for(int edge=1;edge<=4096;edge+=67){model(rows,lo,hi,false,true,edge,64L<<20,Integer.MAX_VALUE);model(rows,lo,hi,false,true,1_000_000,64L<<20,edge);}
        for(int edge=8192;edge<=65536;edge+=4093)model(rows,lo,hi,false,true,edge,64L<<20,Integer.MAX_VALUE);
        for(long bytes:new long[]{1024,4096,8192,16384,32768,65536,131072})model(rows,lo,hi,false,true,100000,bytes,Integer.MAX_VALUE);
        check(cutoffs>0,"global work exhaustion was not exercised");
        System.out.println("PHASE_SAFETY models=1000 assignments="+assignments+" assertions="+assertions+" activated="+phaseRuns+" sat="+sat+" unsat="+unsat+" proofs="+proofs+" resumes="+resumes+" cutoffs="+cutoffs+" cancelled="+cancelled+" optional_declines="+declines+" leaks=0");
    }
}
