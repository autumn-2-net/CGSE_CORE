package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class RepairSafetyProbe {
    static int checks, models, witnesses, oracleSat, cancelled, exhausted, retained;
    static BigInteger b(long v){return BigInteger.valueOf(v);}
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static boolean valid(RepairSchedulingProbe.Model m,BigInteger[]v){return RepairSchedulingProbe.valid(m,v);}
    static boolean oracle(RepairSchedulingProbe.Model m){BigInteger[]v=m.lo().clone();while(true){if(valid(m,v))return true;int i=0;while(i<v.length&&v[i].equals(m.hi()[i])){v[i]=m.lo()[i];i++;}if(i==v.length)return false;v[i]=v[i].add(b(1));}}
    static void sample(Random r,int id){
        int n=2+r.nextInt(4);BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];ExactRational[]p=new ExactRational[n];
        for(int i=0;i<n;i++){lo[i]=b(r.nextInt(5)-2);hi[i]=lo[i].add(b(2+r.nextInt(3)));p[i]=ExactRational.of(lo[i]).add(new ExactRational(b(1+r.nextInt(3)),b(2)));}
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int j=0;j<3+r.nextInt(9);j++){var terms=new LinkedHashMap<Integer,BigInteger>();ExactRational sum=ExactRational.ZERO;for(int i=0;i<n;i++){BigInteger c=b(r.nextInt(9)-4);if(c.signum()!=0){terms.put(i,c);sum=sum.add(p[i].multiply(ExactRational.of(c)));}}rows.add(new ExactLinearProgram.Constraint(terms,sum.ceil().subtract(b(r.nextInt(2)))));}
        var m=new RepairSchedulingProbe.Model(rows,lo,hi,p);boolean sat=oracle(m);models++;if(sat)oracleSat++;
        var budget=new PlanningBudget(0,4_000_000,32L<<20,()->false,System::nanoTime);
        try(var test=new CountNeighborhood(rows,lo,hi,p,budget).pump(id%2==0)){
            while(!test.step()){}BigInteger[]v=test.counts();if(v!=null){witnesses++;check(sat,"false SAT");check(valid(m,v),"invalid count");BigInteger old=v[0];v[0]=b(999);check(test.counts()[0].equals(old),"mutable returned counts");}
            for(int j=0;j<3;j++)check(test.step(),"terminal reopened");
        }check(budget.reservedBytes()==0,"oracle memory");
    }
    static void lifecycle(int stop,long bytes,long remaining)throws Exception{
        var m=RepairSchedulingProbe.coloring(4,1);var polls=new AtomicInteger();var budget=new PlanningBudget(0,4_000_000,bytes,()->stop>0&&polls.incrementAndGet()>stop,System::nanoTime);
        try(var test=new CountNeighborhood(m.rows(),m.lo(),m.hi(),m.point(),budget)){
            if(remaining>=0)budget.charge(Math.max(0,budget.remainingWork()-remaining));
            while(!test.step()){}if(test.counts()!=null)check(valid(m,test.counts()),"lifecycle witness");
            test.close();
        }catch(CancellationException e){cancelled++;}catch(PlanningBudget.Exhausted e){exhausted++;}
        check(budget.reservedBytes()==0,"lifecycle memory stop="+stop+" bytes="+bytes);
    }
    static void fairness()throws Exception{
        var m=RepairSchedulingProbe.coloring(6,0);var budget=new PlanningBudget(0,4_000_000,64L<<20,()->false,System::nanoTime);
        try(var test=new CountNeighborhood(m.rows(),m.lo(),m.hi(),m.point(),budget).pump(false)){
            Object lastActive=null,lastBinary=null,lastWide=null;boolean done=false;
            while(!(done=test.step())){
                Object binary=field(test,"search"),wide=field(test,"relaxedSearch"),active=field(test,"activeArm");
                if(lastActive!=null&&active!=lastActive&&(binary!=null&&binary==lastBinary||wide!=null&&wide==lastWide))retained++;
                lastActive=active;lastBinary=binary;lastWide=wide;
            }
            check(done,"unfinished");check((long)field(test,"work")<150000,"renewed local budget");
            var a=(CountPortfolioPolicy.Arm)field(test,"binaryArm");var z=(CountPortfolioPolicy.Arm)field(test,"relaxedArm");
            check(a.selections>0&&z.selections>0,"starved family");check(retained>0,"no retained handoffs");check(a.work>0&&z.work>0,"unaccounted observations");
        }check(budget.reservedBytes()==0,"fairness memory");
    }
    public static void main(String[]args)throws Exception{
        Random r=new Random(20261003);for(int i=0;i<400;i++)sample(r,i);
        for(int stop=1;stop<120;stop++)lifecycle(stop,64L<<20,-1);
        for(int stop=500;stop<=24000;stop+=500)lifecycle(stop,64L<<20,-1);
        for(long quota:new long[]{0,1,7,100,1000,4096,10000,25000})lifecycle(0,64L<<20,quota);
        for(long bytes:new long[]{1,4096,8192,12288,16384,32768,65536,131072,1048576})lifecycle(0,bytes,-1);
        fairness();check(cancelled>0&&exhausted>0,"no interruption coverage");
        System.out.println("PASS models="+models+" oracleSat="+oracleSat+" witnesses="+witnesses+" cancelled="+cancelled+" exhausted="+exhausted+" retained="+retained+" assertions="+checks);
    }
}
