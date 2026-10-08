package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class JumpRetainedProbe {
    static int assertions, systems, witnesses, pauses, exhausted, cancelled;
    static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static PlanningBudget budget(){return new PlanningBudget(0,16_000_000,64L<<20,()->false,System::nanoTime);}
    static void release(PlanningBudget b){check(b.reservedBytes()==0,"memory leak "+b.reservedBytes());}
    static void verify(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,BigInteger[] values){
        check(JumpGainProbe.holds(rows,values),"false witness");for(int i=0;i<lo.length;i++)check(values[i].compareTo(lo[i])>=0&&(hi[i]==null||values[i].compareTo(hi[i])<=0),"domain");
    }
    static void sample(Random random,int t){
        int n=2+random.nextInt(5);BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=b(random.nextInt(5)-2);hi[i]=lo[i].add(b(1+random.nextInt(4)));}
        var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int j=0;j<2+random.nextInt(10);j++){long[]a=new long[n];for(int i=0;i<n;i++)a[i]=random.nextInt(9)-4;rows.add(JumpGainProbe.row(random.nextInt(25)-12,a));}
        boolean sat=JumpGainProbe.oracle(rows,lo,hi);var budget=budget();systems++;
        try(var jump=new CountJump(rows,lo,hi,budget,1024).retained().retained()){
            for(int slices=0;slices<8;slices++){while(!jump.step()){}if(jump.counts()!=null){check(sat,"oracle UNSAT");verify(rows,lo,hi,jump.counts());witnesses++;break;}if(!jump.paused())break;pauses++;jump.resume(1024);}
        }release(budget);
    }
    static final BigInteger[] LO={b(0),b(0),b(0)},HI={b(1),b(1),b(1)};
    static final List<ExactLinearProgram.Constraint> UNSAT=List.of(JumpGainProbe.row(-1,-1,-1,0),JumpGainProbe.row(-1,0,-1,-1),JumpGainProbe.row(-1,-1,0,-1),JumpGainProbe.row(1,1,1,1));
    static void action(CountJump j,long quantum){while(j.step()){check(j.counts()==null,"UNSAT witness");check(j.paused(),"retained lost");pauses++;j.resume(quantum);}}
    static void resumeEquivalence()throws Exception{
        var first=budget();var second=budget();
        try(var a=new CountJump(UNSAT,LO,HI,first,1024).retained();var z=new CountJump(UNSAT,LO,HI,second,8192).retained()){
            long progress=0;for(int t=0;t<4000;t++){
                action(a,1024);action(z,8192);check(a.progress()>=progress,"nonmonotone progress");progress=a.progress();
                for(String name:List.of("values","residual","scores","weights","jumps","dirty","violated","moves","bumps","pairs","pairMode","last","initialized","work")){
                    Object x=field(a,name),y=field(z,name);check(Objects.deepEquals(x,y),"slice changed state "+name+" step="+t);
                }
            }
            check((boolean)field(a,"pairMode"),"pair mode untested");
        }release(first);release(second);
    }
    static void interrupted(PlanningBudget budget){interrupted(budget,-1);}
    static void interrupted(PlanningBudget budget,int left){
        try(var j=new CountJump(UNSAT,LO,HI,budget,1024).retained()){if(left>=0)budget.charge(Math.max(0,budget.remainingWork()-left));for(int i=0;i<3000;i++){if(j.step()){if(!j.paused())break;j.resume(1024);}}check(j.counts()==null,"UNSAT");}
        catch(PlanningBudget.Exhausted e){exhausted++;}catch(CancellationException e){cancelled++;}release(budget);
    }
    public static void main(String[]args)throws Exception{
        Random random=new Random(202610031);for(int t=0;t<500;t++)sample(random,t);
        resumeEquivalence();
        for(int work=1;work<=1400;work++)interrupted(budget(),work);
        for(int i=1;i<=1200;i++){var polls=new AtomicInteger();int stop=i;interrupted(new PlanningBudget(0,1_000_000,64L<<20,()->polls.incrementAndGet()>stop,System::nanoTime));}
        for(int bytes=0;bytes<=20000;bytes+=64)interrupted(new PlanningBudget(0,1_000_000,Math.max(1,bytes),()->false,System::nanoTime));
        var budget=budget();try(var j=new CountJump(UNSAT,LO,HI,budget,1024).retained()){while(!j.step()){}check(j.paused(),"expected pause");budget.charge(budget.remainingWork());for(int t=0;t<3;t++){try{j.resume(1024);throw new AssertionError("zero quota remained silent");}catch(PlanningBudget.Exhausted expected){assertions++;}}}release(budget);
        BigInteger huge=b(1).shiftLeft(160);var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,huge.negate(),1,huge),huge.negate()));BigInteger[]lo={huge,huge},hi={huge.add(b(3)),huge.add(b(3))};budget=budget();try(var j=new CountJump(rows,lo,hi,budget,65536).retained()){while(!j.step()){}check(j.counts()!=null,"huge witness");verify(rows,lo,hi,j.counts());}release(budget);
        check(witnesses>0&&pauses>0&&exhausted>0&&cancelled>0,"missing coverage");
        System.out.println("PASS systems="+systems+" witnesses="+witnesses+" pauses="+pauses+" exhausted="+exhausted+" cancelled="+cancelled+" assertions="+assertions);
    }
}
