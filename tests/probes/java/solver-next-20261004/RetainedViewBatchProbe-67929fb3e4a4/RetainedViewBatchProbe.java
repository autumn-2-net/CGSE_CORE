package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public class RetainedViewBatchProbe {
    static long checks,retained,parentCalls;
    static BigInteger z(long x){return BigInteger.valueOf(x);}
    static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static List<ExactLinearProgram.Constraint> rows(int holes){
        List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
        for(int p=0;p<=holes;p++){Map<Integer,BigInteger> m=new LinkedHashMap<>();for(int h=0;h<holes;h++)m.put(p*holes+h,z(-1));rows.add(new ExactLinearProgram.Constraint(m,z(-1)));}
        for(int h=0;h<holes;h++)for(int p=0;p<=holes;p++)for(int q=p+1;q<=holes;q++)rows.add(new ExactLinearProgram.Constraint(Map.of(p*holes+h,z(1),q*holes+h,z(1)),z(1)));
        return rows;
    }
    static void run(int holes,PlanningBudget budget,int maximum,boolean testTiny)throws Exception{
        int n=holes*(holes+1);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];Arrays.fill(lo,z(0));Arrays.fill(hi,z(1));
        var journal=new CountProof.Journal(16L<<20);budget.proofJournal(journal);
        try(var models=CountModelViews.create(rows(holes),lo,hi,budget)){
            if(models!=null){models.compileLight();try(var search=new CountViewSearch(models,budget)){
                if(testTiny){for(int q:new int[]{1,17,511,1023}){search.resume(q);while(!search.step()){}ok(field(search,"active")==null,"tiny tail admitted new engine");}}
                for(int i=0;i<maximum&&budget.remainingWork()>8192;i++){
                    Object previous=field(search,"active");long slices=previous==null?0:((CountPortfolioPolicy.Arm)field(previous,"scheduling")).selections;
                    search.resume(1024);long initial=(long)field(search,"work");long until=(long)field(search,"until");
                    while(!search.step()){}
                    parentCalls++;
                    ok((long)field(search,"work")>=initial,"parent work rewound");
                    Object current=field(search,"active");
                    if(previous!=null&&current==previous){retained++;ok(((CountPortfolioPolicy.Arm)field(current,"scheduling")).selections==slices,"handoff rescheduled unfinished batch");}
                    if(current!=null){Method paused=current.getClass().getDeclaredMethod("paused");paused.setAccessible(true);ok(!(boolean)paused.invoke(current),"paused engine remains active");ok((long)field(search,"work")>=until,"unfinished engine stopped before parent allowance");}
                    long accounted=0;
                    for(Object arm:(List<?>)field(search,"searches")) accounted+=((CountPortfolioPolicy.Arm)field(arm,"scheduling")).work;
                    if(current!=null)accounted+=(long)field(current,"sliceWork");
                    ok(accounted==(long)field(search,"work"),"completed plus live work does not match parent accounting");
                    ok(search.counts()==null,"pigeonhole false SAT");
                    if(search.infeasible()||!search.retained())break;
                }
            }}
        }catch(PlanningBudget.Exhausted|CancellationException expected){}
        ok(budget.reservedBytes()==0,"view batch leaked resources");
        for(var p:journal.entries())ok(CountProof.verify(p,8_000_000)==CountProof.Verdict.VERIFIED,"invalid learned proof");
        for(var p:journal.derivations())ok(CountProof.verify(p,8_000_000)==CountProof.Verdict.VERIFIED,"invalid weighted proof");
    }
    public static void main(String[] args)throws Exception{
        for(int h=3;h<=7;h++)run(h,new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime),180,true);
        for(int i=1;i<=80;i++){
            run(5,new PlanningBudget(0,i*79,128L<<20,()->false,System::nanoTime),80,false);
            final int cap=i*31;AtomicInteger calls=new AtomicInteger();run(5,new PlanningBudget(0,1000000,128L<<20,()->calls.incrementAndGet()>cap,System::nanoTime),80,false);
            run(5,new PlanningBudget(0,1000000,i*2000L,()->false,System::nanoTime),80,false);
        }
        ok(retained>0,"no unfinished batch crossed parent boundary");System.out.println("PASS checks="+checks+" parentCalls="+parentCalls+" sameBatchHandoffs="+retained);
    }
}
