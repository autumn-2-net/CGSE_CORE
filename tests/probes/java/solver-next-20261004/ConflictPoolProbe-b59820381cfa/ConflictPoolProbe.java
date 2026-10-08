package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ConflictPoolProbe {
    static long checks;
    static BigInteger z(long n) { return BigInteger.valueOf(n); }
    static void ok(boolean x,String message) { checks++; if(!x) throw new AssertionError(message); }
    static PlanningBudget budget() { return new PlanningBudget(0, Long.MAX_VALUE/32, 1L<<30, ()->false, System::nanoTime); }
    static ExactLinearProgram.Constraint row(int variable,long coefficient,long limit) { return new ExactLinearProgram.Constraint(Map.of(variable,z(coefficient)),z(limit)); }
    static CountConflict conflict(ExactLinearProgram.Constraint... rows) { return new CountConflict(Arrays.asList(rows)); }
    static boolean holds(CountConflict c,int[] point) {
        for(var r:c.assumptions()) {
            BigInteger sum=BigInteger.ZERO;
            for(var e:r.terms().entrySet()) sum=sum.add(e.getValue().multiply(z(point[e.getKey()])));
            if(sum.compareTo(r.upper())>0) return false;
        }
        return true;
    }
    static void equivalent(Collection<CountConflict> before,Collection<CountConflict> after) {
        for(int a=-3;a<=3;a++) for(int b=-3;b<=3;b++) for(int c=-3;c<=3;c++) {
            int[] p={a,b,c};
            ok(before.stream().anyMatch(x->holds(x,p))==after.stream().anyMatch(x->holds(x,p)),"subsumption lost excluded point");
        }
    }
    static void basics() throws Exception {
        var b=budget();
        try(var p=new CountConflictPool(b)) {
            var first=conflict(row(0,1,3),row(1,-1,-2));
            p.add(List.of(first));
            var s=p.snapshot(); long rev=p.revision();
            p.add(List.of(conflict(row(1,-1,-2),row(0,1,3))));
            ok(p.snapshot()==s,"permutation rebuilt snapshot");
            ok(p.revision()==rev,"permutation changed revision");
            p.add(List.of(conflict(row(0,1,2),row(1,-1,-3),row(2,1,4))));
            ok(p.snapshot().size()==1,"stronger assumptions must be redundant");
            p.add(List.of(conflict(row(0,1,4))));
            ok(p.snapshot().size()==1 && p.snapshot().get(0).assumptions().size()==1,"weaker assumption replaces stronger conjunction");
            var unrelated=conflict(row(2,1,0));p.add(List.of(unrelated));
            var snapshot=p.snapshot();p.used(List.of(unrelated));
            ok(p.snapshot()!=snapshot && p.snapshot().get(0).equals(unrelated),"activity did not invalidate ordering");
            var field=CountConflictPool.class.getDeclaredField("clock");field.setAccessible(true);field.setLong(p,Long.MAX_VALUE);
            p.used(List.of(unrelated)); ok(field.getLong(p)>0,"clock overflow");
            p.add(List.of(conflict()));ok(p.snapshot().size()==1 && p.snapshot().get(0).assumptions().isEmpty(),"empty conflict must subsume all");
            try { p.snapshot().add(first); throw new AssertionError("mutable snapshot"); } catch(UnsupportedOperationException expected) {checks++;}
            p.report();System.out.println(b.diagnostics());
        }
        ok(b.reservedBytes()==0,"basic memory leak");
        var other=budget();try(var p=new CountConflictPool(other)){ok(p.isEmpty(),"scope leaked into another pool");}
        var lifecycle=budget();try(var p=new CountConflictPool(lifecycle)) {
            var covering=conflict(row(0,1,2));var alias=conflict(row(0,1,1),row(1,1,4));
            p.add(List.of(covering,alias));ok(p.snapshot().size()==1,"alias not covered");
            long steady=lifecycle.nodes();for(int i=0;i<10000;i++)p.add(List.of(alias));
            ok(lifecycle.nodes()==steady,"same covered publication repeated charged checks");
            for(int i=2;i<131;i++)p.add(List.of(conflict(row(i,1,0))));
            ok(!p.snapshot().contains(covering),"covering not evicted");
            p.add(List.of(alias));ok(p.snapshot().contains(alias),"stale alias suppressed re-admission after covering eviction");
        }
        ok(lifecycle.reservedBytes()==0,"alias lifecycle leak");
    }
    static void randomPool() {
        Random rng=new Random(9260413);
        for(int round=0;round<500;round++) {
            var b=budget();try(var p=new CountConflictPool(b)) {
                List<CountConflict> all=new ArrayList<>();
                for(int j=0;j<24;j++) {
                    List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
                    int n=1+rng.nextInt(4);
                    for(int k=0;k<n;k++) {
                        Map<Integer,BigInteger> terms=new LinkedHashMap<>();
                        for(int v=0;v<3;v++) if(rng.nextBoolean()) terms.put(v,z(rng.nextInt(7)-3));
                        rows.add(new ExactLinearProgram.Constraint(terms,z(rng.nextInt(13)-6)));
                    }
                    if(!all.isEmpty()&&rng.nextInt(3)==0) {rows=new ArrayList<>(all.get(rng.nextInt(all.size())).assumptions());Collections.shuffle(rows,rng);}
                    var next=new CountConflict(rows);all.add(next);p.add(List.of(next));
                    if(j%6==0) equivalent(all,p.snapshot());
                }
                equivalent(all,p.snapshot());
            }
            ok(b.reservedBytes()==0,"random leak");
        }
    }
    static void propagation() {
        Random r=new Random(641041);
        var b=budget();
        for(int run=0;run<100000;run++) {
            List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
            int n=r.nextInt(7);
            for(int i=0;i<n;i++) {
                Map<Integer,BigInteger> terms=new LinkedHashMap<>();
                for(int j=0;j<4;j++) if(r.nextBoolean()) terms.put(j,z(r.nextInt(9)-4));
                BigInteger limit=z(r.nextInt(41)-20);
                if(run%17==0) {BigInteger scale=BigInteger.ONE.shiftLeft(130).add(BigInteger.ONE);terms.replaceAll((k,v)->v.multiply(scale));limit=limit.multiply(scale).add(BigInteger.ONE);}
                rows.add(new ExactLinearProgram.Constraint(terms,limit));
            }
            BigInteger[] lo=new BigInteger[4],hi=new BigInteger[4];
            for(int j=0;j<4;j++){lo[j]=z(r.nextInt(9)-4);hi[j]=r.nextInt(4)==0?null:lo[j].add(z(r.nextInt(6)));}
            var now=new CountConflict(rows);var old=new BaselineCountConflict(rows);
            var a=now.propagate(lo,hi,b);var c=old.propagate(lo,hi,b);
            ok((a==null)==(c==null),"propagation existence");
            if(a!=null) {ok(Objects.equals(a.row(),c.row()),"propagation row");ok(a.premises().equals(c.premises()),"premise order");}
            ok(now.impliedBy(lo,hi,b)==old.impliedBy(lo,hi,b),"implied result");
        }
        ok(b.reservedBytes()==0,"propagation budget");
    }
    static void limits() {
        var small=new PlanningBudget(0,1000000,1500,()->false,System::nanoTime);
        try(var p=new CountConflictPool(small)) {
            p.add(List.of(conflict(row(0,1,0))));long used=small.reservedBytes();
            var snapshot=p.snapshot();p.add(List.of(conflict(row(1,1,0))));
            ok(p.snapshot()==snapshot&&small.reservedBytes()==used,"denied admission changed existing pool");
        }
        ok(small.reservedBytes()==0,"small-budget leak");
        var b=budget();try(var p=new CountConflictPool(b)) {
            BigInteger huge=BigInteger.ONE.shiftLeft(600000);
            p.add(List.of(conflict(new ExactLinearProgram.Constraint(Map.of(0,huge,1,BigInteger.ONE),z(0)))));
            ok(p.isEmpty()&&b.reservedBytes()==0,"huge coefficient retained");
            p.add(List.of(conflict(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE),huge))));
            ok(p.isEmpty()&&b.reservedBytes()==0,"huge bound retained");
            for(int i=0;i<250;i++) p.add(List.of(conflict(row(i,1,0))));
            ok(p.snapshot().size()==128&&b.reservedBytes()<=524288,"capacity bound");
            for(int i=0;i<300;i++){p.used(List.of());ok(p.snapshot()==p.snapshot(),"unchanged snapshot rebuilt");}
        }
        ok(b.reservedBytes()==0,"capacity leak");
        AtomicBoolean cancel=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();
        var cancelled=new PlanningBudget(0,1000000,1L<<20,()->cancel.get()&&calls.incrementAndGet()>3,System::nanoTime);
        try(var p=new CountConflictPool(cancelled)) {
            for(int i=0;i<10;i++)p.add(List.of(conflict(row(i,1,0))));long held=cancelled.reservedBytes();
            cancel.set(true);try{p.add(List.of(conflict(row(12,1,0))));throw new AssertionError("cancel ignored");}catch(CancellationException expected){checks++;}
            ok(cancelled.reservedBytes()==held,"cancel candidate leak");cancel.set(false);ok(p.snapshot().size()==10,"cancel partially installed");
        }
        ok(cancelled.reservedBytes()==0,"cancel close leak");
        var exhausted=new PlanningBudget(0,3,1L<<20,()->false,System::nanoTime);
        try(var p=new CountConflictPool(exhausted)) {
            try{for(int i=0;i<20;i++)p.add(List.of(conflict(row(i,1,0))));throw new AssertionError("work uncharged");}catch(PlanningBudget.Exhausted expected){checks++;}
        }
        ok(exhausted.reservedBytes()==0,"exhausted close leak");
    }
    static void reuse() {
        var before=budget();var after=budget();
        try(var old=new BaselineCountConflictPool(before);var now=new CountConflictPool(after)) {
            List<ExactLinearProgram.Constraint> rows=new ArrayList<>();for(int i=0;i<6;i++)rows.add(row(i,1,0));
            Random r=new Random(178123);
            for(int i=0;i<1500;i++){Collections.shuffle(rows,r);var c=new CountConflict(rows);old.add(List.of(c));now.add(List.of(c));}
            ok(old.snapshot().size()==128&&now.snapshot().size()==1,"permutation coalescing");
            long bm=before.reservedBytes(),am=after.reservedBytes(),bw=before.nodes(),aw=after.nodes();
            BigInteger[] lo=new BigInteger[6],hi=new BigInteger[6];Arrays.fill(lo,z(0));Arrays.fill(hi,z(1));
            for(int i=0;i<2000;i++){
                for(var c:old.snapshot()) c.propagate(lo,hi,before);
                for(var c:now.snapshot()) c.propagate(lo,hi,after);
            }
            System.out.println("duplicate_family={\"beforeRetained\":128,\"afterRetained\":1,\"beforeBytes\":"+bm+",\"afterBytes\":"+am+",\"beforeWork\":"+(before.nodes()-bw)+",\"afterWork\":"+(after.nodes()-aw)+",\"beforeTotalWork\":"+before.nodes()+",\"afterTotalWork\":"+after.nodes()+"}");
        }
        ok(before.reservedBytes()==0&&after.reservedBytes()==0,"reuse leak");
    }
    public static void main(String[] args)throws Exception{basics();randomPool();propagation();limits();reuse();System.out.println("PASS checks="+checks);}
}
