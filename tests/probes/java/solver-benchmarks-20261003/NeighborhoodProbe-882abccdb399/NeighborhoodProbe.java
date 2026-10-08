package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class NeighborhoodProbe {
    static int assertions, solved, cases;
    static BigInteger b(long n) { return BigInteger.valueOf(n); }
    static void check(boolean yes, String why) { assertions++; if (!yes) throw new AssertionError(why); }
    static void valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi, BigInteger[] x) {
        for (int i=0;i<x.length;i++) check(x[i].compareTo(lo[i])>=0 && (hi[i]==null || x[i].compareTo(hi[i])<=0), "domain");
        for (var row:rows) {
            BigInteger sum=BigInteger.ZERO;
            for(var term:row.terms().entrySet()) sum=sum.add(term.getValue().multiply(x[term.getKey()]));
            check(sum.compareTo(row.upper())<=0,"row");
        }
    }
    static void family(boolean requireImprovement) {
        for(int offset: new int[]{0,1,90}) for(int divisor=3;divisor<=63;divisor+=2) for(boolean unbounded:new boolean[]{false,true}) {
            BigInteger shift=offset==0?BigInteger.ZERO:BigInteger.ONE.shiftLeft(offset);
            BigInteger rhs=shift.multiply(b(2-divisor)).add(BigInteger.ONE);
            var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(2),1,b(-divisor)),rhs),
                    new ExactLinearProgram.Constraint(Map.of(0,b(-2),1,b(divisor)),rhs.negate()));
            BigInteger[] low={shift,shift}, high={shift.add(b(80)),unbounded?null:shift.add(b(20))};
            ExactRational[] point={new ExactRational(shift.multiply(b(2)).add(BigInteger.ONE),b(2)),ExactRational.of(shift)};
            var budget=new PlanningBudget(0,5_000_000,128L<<20,()->false,System::nanoTime);
            try(var work=new CountNeighborhood(rows,low,high,point,budget).pump(false)) {
                while(!work.step()){}
                if(work.counts()!=null) {solved++;valid(rows,low,high,work.counts());}
                else if(requireImprovement) throw new AssertionError("missed divisor="+divisor+" shift="+offset+" unbounded="+unbounded+" diag="+budget.diagnostics());
            }
            check(budget.reservedBytes()==0,"memory");cases++;
        }
        System.out.println("FAMILY cases="+cases+" solved="+solved+" assertions="+assertions);
    }
    static void random() {
        var random=new Random(9017321);
        for(int test=0;test<600;test++) {
            int n=2+random.nextInt(4);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];
            ExactRational[] point=new ExactRational[n];
            Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,b(4));
            for(int i=0;i<n;i++) point[i]=new ExactRational(b(random.nextInt(9)),b(2));
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<2+random.nextInt(7);r++) {
                var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger value=BigInteger.ZERO;
                for(int i=0;i<n;i++) {int a=random.nextInt(9)-4;if(a!=0){terms.put(i,b(a));value=value.add(b(a).multiply(point[i].multiply(ExactRational.of(b(2))).numerator()));}}
                // A feasible fractional proposal; exact integer feasibility is independently enumerated.
                rows.add(new ExactLinearProgram.Constraint(terms,new ExactRational(value,b(2)).ceil().add(b(random.nextInt(3)))));
            }
            boolean possible=false;int maximum=(int)Math.pow(5,n);
            for(int code=0;code<maximum&&!possible;code++) {int v=code;var x=new BigInteger[n];for(int i=0;i<n;i++){x[i]=b(v%5);v/=5;}
                possible=rows.stream().allMatch(row->{BigInteger sum=BigInteger.ZERO;for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));return sum.compareTo(row.upper())<=0;});}
            var budget=new PlanningBudget(0,5_000_000,128L<<20,()->false,System::nanoTime);
            try(var work=new CountNeighborhood(rows,lo,hi,point,budget).pump(false)) {while(!work.step()){}var x=work.counts();if(x!=null){check(possible,"false witness");valid(rows,lo,hi,x);}}
            check(budget.reservedBytes()==0,"random memory");
        }
        System.out.println("RANDOM 600 systems passed assertions="+assertions);
    }
    static void lifecycle() {
        var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(2),1,b(-37)),b(1)),new ExactLinearProgram.Constraint(Map.of(0,b(-2),1,b(37)),b(-1)));
        BigInteger[] lo={b(0),b(0)}, hi={b(128),null};ExactRational[] point={new ExactRational(b(1),b(2)),ExactRational.ZERO};
        for(int cutoff=1;cutoff<=350;cutoff++) {
            var polls=new java.util.concurrent.atomic.AtomicInteger();final int stop=cutoff;
            var budget=new PlanningBudget(0,5_000_000,128L<<20,()->polls.incrementAndGet()>=stop,System::nanoTime);
            CountNeighborhood work=null;
            try {work=new CountNeighborhood(rows,lo,hi,point,budget).pump(false);while(!work.step()){}if(work.counts()!=null)valid(rows,lo,hi,work.counts());}
            catch(PlanningBudget.Exhausted | java.util.concurrent.CancellationException expected){}finally{if(work!=null)work.close();}
            check(budget.reservedBytes()==0,"cancel memory "+cutoff+"="+budget.reservedBytes());
        }
        for(long memory: new long[]{1,2048,4096,8192,32768,131072,1048576}) {
            var budget=new PlanningBudget(0,5_000_000,memory,()->false,System::nanoTime);
            try(var work=new CountNeighborhood(rows,lo,hi,point,budget).pump(false)){while(!work.step()){}if(work.counts()!=null)valid(rows,lo,hi,work.counts());}
            catch(PlanningBudget.Exhausted expected){}
            check(budget.reservedBytes()==0,"low memory");
        }
        System.out.println("LIFECYCLE 357 cutoffs passed assertions="+assertions);
    }
    public static void main(String[] args) {family(args.length==0||!args[0].equals("baseline"));random();if(args.length==0||!args[0].equals("baseline"))lifecycle();}
}
