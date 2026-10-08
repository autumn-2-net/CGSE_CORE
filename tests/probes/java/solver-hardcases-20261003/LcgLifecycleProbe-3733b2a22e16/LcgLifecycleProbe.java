package org.cgse.core;
import java.math.BigInteger;import java.util.*;import java.util.concurrent.CancellationException;import java.util.concurrent.atomic.AtomicInteger;
public final class LcgLifecycleProbe {
    static int assertions,exhausted,cancelled;static void check(boolean b,String why){assertions++;if(!b)throw new AssertionError(why);}
    static final List<ExactLinearProgram.Constraint> ROWS=new ArrayList<>();static final BigInteger[] LO=new BigInteger[20],HI=new BigInteger[20];
    static{Arrays.fill(LO,BigInteger.ZERO);Arrays.fill(HI,BigInteger.ONE);for(int p=0;p<5;p++){var a=new long[20];for(int h=0;h<4;h++)a[4*p+h]=-1;ROWS.add(LazyLcgProbe.row(-1,a));}for(int h=0;h<4;h++)for(int p=0;p<5;p++)for(int q=p+1;q<5;q++){var a=new long[20];a[4*p+h]=a[4*q+h]=1;ROWS.add(LazyLcgProbe.row(1,a));}}
    static void run(PlanningBudget budget,int remaining){try(var solver=new CountLcg(ROWS,LO,HI,budget,1024,true).lockBranching().lockBranching()){
        if(remaining>=0)budget.charge(Math.max(0,budget.remainingWork()-remaining));
        while(true){while(!solver.step()){}if(solver.paused())solver.resume(1024);else break;}
        check(solver.counts()==null,"pigeonhole false SAT");
    }catch(PlanningBudget.Exhausted limit){exhausted++;}catch(CancellationException cancel){cancelled++;}check(budget.reservedBytes()==0,"leaked workspace "+budget.reservedBytes());}
    public static void main(String[]args){
        for(int left=1;left<=1200;left++)run(new PlanningBudget(0,8_000_000,64L<<20,()->false,System::nanoTime),left);
        for(int stop=1;stop<=1400;stop++){int threshold=stop;var polls=new AtomicInteger();run(new PlanningBudget(0,8_000_000,64L<<20,()->polls.incrementAndGet()>threshold,System::nanoTime),-1);}
        for(long bytes=10_000;bytes<=70_000;bytes+=128)run(new PlanningBudget(0,8_000_000,bytes,()->false,System::nanoTime),-1);
        var lo=new BigInteger[548];var hi=new BigInteger[548];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE.negate()),BigInteger.ONE.negate()));
        var budget=new PlanningBudget(0,8_000_000,64L<<20,()->false,System::nanoTime);try(var views=CountModelViews.create(rows,lo,hi,budget);var solver=new CountLcg(rows,lo,hi,budget,65536).lockBranching().lockBranching()){check(views!=null,"548 sparse admission denied");while(!solver.step()){}check(solver.counts()!=null,"548 sparse witness missing");for(var view:views.available())check(Arrays.equals(views.restoreAndCheck(view,solver.counts()),solver.counts()),"large mapping changed");}check(budget.reservedBytes()==0,"large sparse leak");
        check(exhausted>0&&cancelled>0,"missing interruption coverage");System.out.println("PASS assertions="+assertions+" work_exhausted="+exhausted+" cancelled="+cancelled+" sparse_variables=548");
    }
}
