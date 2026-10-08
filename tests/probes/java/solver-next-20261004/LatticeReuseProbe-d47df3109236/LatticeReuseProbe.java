package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class LatticeReuseProbe {
    static int checks;
    static BigInteger b(long n) { return BigInteger.valueOf(n); }
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static PlanningBudget budget(long work, long memory) { return new PlanningBudget(0, work, memory, () -> false, System::nanoTime); }
    static List<ExactLinearProgram.Constraint> rows(Random random, int n, int[] witness, boolean interval) {
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (int k=0;k<2+random.nextInt(6);k++) {
            var terms=new LinkedHashMap<Integer,BigInteger>();
            BigInteger rhs=BigInteger.ZERO;
            for(int i=0;i<n;i++) {
                int a=random.nextInt(9)-4;
                if(a!=0) {terms.put(i,b(a));rhs=rhs.add(b((long)a*witness[i]));}
            }
            BigInteger low=rhs,high=rhs;
            if(interval && k%3!=0) {low=low.subtract(b(random.nextInt(4)));high=high.add(b(random.nextInt(4)));}
            rows.add(new ExactLinearProgram.Constraint(terms,high));
            var reverse=new LinkedHashMap<Integer,BigInteger>();
            terms.forEach((i,a)->reverse.put(i,a.negate()));
            rows.add(new ExactLinearProgram.Constraint(reverse,low.negate()));
            if(random.nextBoolean()) rows.add(new ExactLinearProgram.Constraint(terms,high.add(b(random.nextInt(4)))));
        }
        Collections.shuffle(rows,random);
        return rows;
    }
    static BigInteger[] run(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi, ExactRational[] point, int attempt, PlanningBudget budget, CountLatticeStructure structure) {
        try(var repair=new CountLatticeRepair(rows,lo,hi,point,attempt,budget,structure)) {
            int steps=0;while(!repair.step()) check(++steps<2000000,"termination");
            return repair.counts();
        }
    }
    static BigInteger[] reference(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi, ExactRational[] point, int attempt) {
        var budget=budget(10000000,256L<<20);
        try(var repair=new ReferenceLatticeRepair(rows,lo,hi,point,attempt,budget)) {
            while(!repair.step()) {}
            return repair.counts();
        } finally {check(budget.reservedBytes()==0,"reference leak");}
    }
    static void validate(BigInteger[] value,List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi) {
        if(value==null)return;
        for(int i=0;i<value.length;i++)check(value[i].compareTo(lo[i])>=0&&value[i].compareTo(hi[i])<=0,"domain");
        for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(value[e.getKey()]));check(sum.compareTo(row.upper())<=0,"row");}
    }
    static void randomized() {
        Random random=new Random(420031);
        for(int sample=0;sample<1200;sample++){
            int n=2+random.nextInt(5);int[] witness=new int[n];
            BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];ExactRational[] point=new ExactRational[n];
            for(int i=0;i<n;i++){lo[i]=BigInteger.ZERO;hi[i]=b(2+random.nextInt(4));witness[i]=random.nextInt(hi[i].intValue()+1);point[i]=new ExactRational(b(random.nextInt(hi[i].intValue()*3+1)),b(3));}
            var rows=rows(random,n,witness,(sample&1)==0);var budget=budget(50000000,256L<<20);
            try(var structure=CountLatticeStructure.create(rows,budget)){
                check(structure!=null,"structure");
                for(int attempt=0;attempt<4;attempt++){
                    var expected=reference(rows,lo,hi,point,attempt);
                    var actual=run(rows,lo,hi,point,attempt,budget,structure);
                    check(Arrays.equals(actual,expected),"trajectory sample="+sample+" attempt="+attempt+" old="+Arrays.toString(expected)+" new="+Arrays.toString(actual));
                    validate(actual,rows,lo,hi);
                }
            }
            check(budget.reservedBytes()==0,"cache leak");
        }
    }
    static List<ExactLinearProgram.Constraint> repeated(){
        List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
        for(int k=1;k<=80;k++)for(int sign:new int[]{-1,1}){
            rows.add(new ExactLinearProgram.Constraint(Map.of(0,b(k*sign),1,b(k*sign)),b(k*sign)));
            rows.add(new ExactLinearProgram.Constraint(Map.of(0,b(k*sign),1,b(-k*sign)),BigInteger.ZERO));
        }
        return rows;
    }
    static void failures(){
        var rows=repeated();BigInteger[] lo={b(0),b(0)},hi={b(3),b(3)};ExactRational[] point={new ExactRational(b(1),b(2)),new ExactRational(b(1),b(2))};
        for(int cancelAt:new int[]{1,10,300,1400,2100,2800,3600,5000,20000}){
            AtomicInteger calls=new AtomicInteger();var budget=new PlanningBudget(0,1000000,1L<<20,()->calls.incrementAndGet()>=cancelAt,System::nanoTime);
            try{run(rows,lo,hi,point,0,budget,null);}catch(java.util.concurrent.CancellationException expected){}finally{check(budget.reservedBytes()==0,"cancel leak "+cancelAt);}
        }
        for(int cap:new int[]{1,10,300,1400,2100,2800,3600,5000,20000}){
            var budget=budget(cap,1L<<20);
            try{run(rows,lo,hi,point,0,budget,null);}catch(PlanningBudget.Exhausted expected){}finally{check(budget.reservedBytes()==0,"work leak "+cap);}
        }
        for(long bytes:new long[]{1,256,2048,8192,65536,262144}){
            var budget=budget(1000000,bytes);
            run(rows,lo,hi,point,0,budget,null);check(budget.reservedBytes()==0,"memory leak "+bytes);
        }
        var budget=budget(1000000,1L<<20);
        try(var structure=CountLatticeStructure.create(rows,budget)){
            boolean rejected=false;
            try{run(new ArrayList<>(rows),lo,hi,point,0,budget,structure);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"foreign rows");
        }
        check(budget.reservedBytes()==0,"foreign leak");
    }
    static void efficiency(){
        var rows=repeated();BigInteger[] lo={b(0),b(0)},hi={b(3),b(3)};ExactRational[] point={new ExactRational(b(1),b(2)),new ExactRational(b(1),b(2))};
        long coldWork=0,warmWork=0,coldNanos=0,warmNanos=0;
        for(int round=0;round<30;round++)for(int mode=0;mode<2;mode++){
            var budget=budget(10000000,64L<<20);long beforeWork=budget.threadWork(),start=System.nanoTime();
            try(var structure=mode==0?null:CountLatticeStructure.create(rows,budget)){
                for(int a=0;a<8;a++){var result=run(rows,lo,hi,point,a%4,budget,structure);check(result==null,"parity must not solve");}
            }
            long elapsed=System.nanoTime()-start,work=budget.threadWork()-beforeWork;
            if(round>=5){if(mode==0){coldWork+=work;coldNanos+=elapsed;}else{warmWork+=work;warmNanos+=elapsed;}}
            check(budget.reservedBytes()==0,"efficiency leak");
        }
        check(warmWork<coldWork/3,"index reuse work saving");
        System.out.println("efficiency coldWork="+coldWork+" warmWork="+warmWork+" coldNanos="+coldNanos+" warmNanos="+warmNanos);
    }
    public static void main(String[] args){randomized();failures();efficiency();System.out.println("PASS checks="+checks);}
}
