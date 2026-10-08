package org.cgse.core;
import java.math.BigInteger;import java.util.*;import java.util.concurrent.CancellationException;import java.util.concurrent.atomic.AtomicInteger;
public final class PreprocessLifecycleProbe {
    static int assertions;static void check(boolean v,String s){assertions++;if(!v)throw new AssertionError(s);}
    static final BigInteger[] LOW={BigInteger.ZERO,BigInteger.ZERO},HIGH={BigInteger.TEN,BigInteger.ONE};
    static List<ExactLinearProgram.Constraint> rows(){var r=new ArrayList<ExactLinearProgram.Constraint>();ResidueProbe.equality(r,ResidueProbe.row(16,6,10));return r;}
    static void reduction(PlanningBudget b){try(var r=new CountReduction(rows(),LOW,HIGH,b)){while(!r.step()){}var reps=r.representatives();var p=new BigInteger[reps.length];Arrays.fill(p,BigInteger.ONE);check(ResidueProbe.holds(r.rows(),p),"cutoff lost known solution");check(Arrays.equals(r.expand(p),new BigInteger[]{BigInteger.ONE,BigInteger.ONE}),"cutoff changed restoration");}catch(PlanningBudget.Exhausted|CancellationException expected){}check(b.reservedBytes()==0,"reduction lifecycle leak "+b.reservedBytes());}
    public static void main(String[]args){for(int work=1;work<=400;work++)reduction(new PlanningBudget(0,work,()->false));for(long bytes:new long[]{1,128,1024,4096,8192,32768,65536})reduction(new PlanningBudget(0,1000000,bytes,()->false,System::nanoTime));
        for(int threshold=1;threshold<=220;threshold++){int stop=threshold;var polls=new AtomicInteger();reduction(new PlanningBudget(0,1000000,64L<<20,()->polls.incrementAndGet()>stop,System::nanoTime));}
        for(int threshold=1;threshold<=120;threshold++){int stop=threshold;var polls=new AtomicInteger();var budget=new PlanningBudget(0,1000000,64L<<20,()->polls.incrementAndGet()>stop,System::nanoTime);try(var views=CountModelViews.create(List.of(ResidueProbe.row(7,4,6)),LOW,HIGH,budget)){if(views!=null)views.compileLight();}catch(PlanningBudget.Exhausted|CancellationException expected){}check(budget.reservedBytes()==0,"view lifecycle leak");}
        var longRows=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<100;i++)ResidueProbe.equality(longRows,ResidueProbe.row(2+2*i,1+i,1+i));var budget=new PlanningBudget(0,10000,()->false);try(var p=new CountResiduePresolve(longRows,LOW,HIGH,budget)){while(!p.step()){}check(ResidueProbe.holds(p.cuts(),new BigInteger[]{BigInteger.ONE,BigInteger.ONE}),"local cutoff became impossible");}check(budget.reservedBytes()==0,"local cutoff leak");
        System.out.println("PASS lifecycle assertions="+assertions);
    }
}
