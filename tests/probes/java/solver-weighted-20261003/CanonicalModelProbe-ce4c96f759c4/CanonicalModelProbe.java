package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class CanonicalModelProbe {
    static long assignments, models, permutations, assertions;
    static BigInteger b(long x) { return BigInteger.valueOf(x); }
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, () -> 0L); }
    static BigInteger[] fill(int n, int value) { var x = new BigInteger[n]; Arrays.fill(x, b(value)); return x; }
    record Legacy(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, int[] order) {}
    static Legacy legacy(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper) {
        int n=lower.length, objective=-1;
        for(int r=0;r<rows.size();r++) {
            var row=rows.get(r);
            if(row.terms().size()>=n/2 && row.terms().values().stream().allMatch(a->a.signum()>0) &&
                    (objective<0 || row.terms().size()>rows.get(objective).terms().size())) objective=r;
        }
        var costs=fill(n,0);
        if(objective>=0)for(var t:rows.get(objective).terms().entrySet())costs[t.getKey()]=t.getValue();
        List<List<String>> signatures=new ArrayList<>();for(int i=0;i<n;i++)signatures.add(new ArrayList<>());
        for(var row:rows){String shape=row.upper()+":"+row.terms().values().stream().sorted().toList();for(var t:row.terms().entrySet())signatures.get(t.getKey()).add(t.getValue()+":"+shape);}
        var keys=new String[n];for(int i=0;i<n;i++){Collections.sort(signatures.get(i));keys[i]=signatures.get(i).toString();}
        Integer[] sorted=new Integer[n];for(int i=0;i<n;i++)sorted[i]=i;
        Arrays.sort(sorted,(a,c)->{int v=costs[c].compareTo(costs[a]);return v!=0?v:keys[a].compareTo(keys[c]);});
        int[] order=new int[n],inverse=new int[n];var lo=new BigInteger[n];var hi=new BigInteger[n];
        for(int i=0;i<n;i++){order[i]=sorted[i];inverse[order[i]]=i;lo[i]=lower[order[i]];hi[i]=upper[order[i]];}
        var mapped=new ArrayList<ExactLinearProgram.Constraint>();
        for(var row:rows){var terms=new TreeMap<Integer,BigInteger>();for(var t:row.terms().entrySet())terms.put(inverse[t.getKey()],t.getValue());mapped.add(new ExactLinearProgram.Constraint(terms,row.upper()));}
        mapped.sort((a,c)->{for(int i=0;i<n;i++){int v=a.terms().getOrDefault(i,BigInteger.ZERO).compareTo(c.terms().getOrDefault(i,BigInteger.ZERO));if(v!=0)return v;}return a.upper().compareTo(c.upper());});
        return new Legacy(mapped,lo,hi,order);
    }
    static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] x) {
        for(var row:rows){var total=BigInteger.ZERO;for(var t:row.terms().entrySet())total=total.add(t.getValue().multiply(x[t.getKey()]));if(total.compareTo(row.upper())>0)return false;}return true;
    }
    static List<ExactLinearProgram.Constraint> randomRows(Random rng,int n,int m,int test) {
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int r=0;r<m;r++) {
            var terms=new TreeMap<Integer,BigInteger>();var scale=test%5==0&&r%3==0?BigInteger.TEN.pow(70):BigInteger.ONE;
            for(int i=0;i<n;i++){int v=rng.nextInt(11)-5;if(v!=0)terms.put(i,b(v).multiply(scale));}
            rows.add(new ExactLinearProgram.Constraint(terms,b(rng.nextInt(25)-12).multiply(scale)));
        }
        return rows;
    }
    static void oracle(Random rng) {
        for(int test=0;test<2500;test++) {
            int n=1+rng.nextInt(8);var lo=new BigInteger[n];var hi=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=b(rng.nextInt(3));hi[i]=lo[i].add(b(rng.nextInt(test%2==0?2:3)));}
            var rows=randomRows(rng,n,rng.nextInt(15),test);var expected=legacy(rows,lo,hi);var budget=budget(20_000_000,64L<<20);
            try(var model=CountCanonicalModel.create(rows,lo,hi,budget)) {
                check(model!=null,"random declined "+test);models++;
                check(model.rows().equals(expected.rows),"legacy rows "+test);
                check(Arrays.equals(model.order(),expected.order),"legacy order "+test);
                check(Arrays.equals(model.lower(),expected.low)&&Arrays.equals(model.upper(),expected.high),"legacy bounds");
                check(model.restore(null)==null,"null restore");
                var x=model.lower();var low=model.lower();var high=model.upper();int[] order=model.order();
                while(true) {
                    assignments++;var restored=model.restore(x);
                    check(valid(model.rows(),x)==valid(rows,restored),"equivalence "+test);
                    for(int i=0;i<n;i++)check(restored[order[i]].equals(x[i]),"inverse "+test);
                    int i=0;while(i<n&&x[i].equals(high[i])){x[i]=low[i];i++;}if(i==n)break;x[i]=x[i].add(BigInteger.ONE);
                }
                int[] copy=model.order();copy[0]=-1;check(model.order()[0]>=0,"order alias");
                var copyLower=model.lower();copyLower[0]=b(-1);check(model.lower()[0].signum()>=0,"bound alias");
                check(budget.reservedBytes()>0,"no retained charge");
            }
            check(budget.reservedBytes()==0,"random leak");
        }
    }
    static void permutations(Random rng) {
        for(int test=0;test<1000;test++) {
            int n=2+rng.nextInt(10);var lo=fill(n,0);var hi=fill(n,1);var rows=randomRows(rng,n,10,test);
            // Unique widest positive row. Unique coefficients make every variable
            // distinguishable independently of original row/variable numbering.
            rows.removeIf(r->r.terms().values().stream().allMatch(v->v.signum()>0));
            var costs=new TreeMap<Integer,BigInteger>();for(int i=0;i<n;i++)costs.put(i,b(i+1));
            rows.add(new ExactLinearProgram.Constraint(costs,b(n*n)));
            var ids=new ArrayList<Integer>();for(int i=0;i<n;i++)ids.add(i);Collections.shuffle(ids,rng);
            var mapped=new ArrayList<ExactLinearProgram.Constraint>();
            for(var row:rows){var terms=new TreeMap<Integer,BigInteger>();for(var t:row.terms().entrySet())terms.put(ids.get(t.getKey()),t.getValue());mapped.add(new ExactLinearProgram.Constraint(terms,row.upper()));}Collections.shuffle(mapped,rng);
            var budget=budget(20_000_000,64L<<20);
            try(var first=CountCanonicalModel.create(rows,lo,hi,budget);var next=CountCanonicalModel.create(mapped,lo,hi,budget)) {
                check(first!=null&&next!=null,"permutation declined");check(first.rows().equals(next.rows()),"canonical permutation");permutations++;
            }check(budget.reservedBytes()==0,"permutation leak");
        }
    }
    static void lifecycle() {
        var lo=fill(30,0);var hi=fill(30,1);var rows=randomRows(new Random(122),30,60,5);
        var bomb=new AbstractList<ExactLinearProgram.Constraint>() {public int size(){return 2049;}public ExactLinearProgram.Constraint get(int i){throw new AssertionError("unsupported accessed");}};
        for(int kind=0;kind<8;kind++) {
            var l=lo.clone();var h=hi.clone();if(kind==0)h[0]=null;if(kind==1)l[0]=b(-1);if(kind==2)l[0]=b(2);if(kind==3)h[0]=BigInteger.ONE.shiftLeft(4097);if(kind==4)l[0]=null;if(kind==5)h=new BigInteger[0];
            var budget=budget(kind==7?100:20_000_000,64L<<20);check(CountCanonicalModel.create(kind==6?bomb:rows,l,h,budget)==null,"unsupported "+kind);check(budget.reservedBytes()==0,"unsupported leak");
        }
        for(int after:new int[]{0,1,2,5,10,30,100,300,1000,5000})for(long memory:new long[]{1024,16384,65536,1L<<20,64L<<20}) {
            var calls=new AtomicInteger();var budget=new PlanningBudget(0,20_000_000,memory,()->calls.incrementAndGet()>after,()->0L);CountCanonicalModel model=null;
            try{model=CountCanonicalModel.create(rows,lo,hi,budget);}catch(CancellationException expected){}finally{if(model!=null){model.close();model.close();}}check(budget.reservedBytes()==0,"cancel leak "+after+" "+memory);
        }
        for(long cap:new long[]{16384,32768,65536,200000,1000000}) {
            var budget=budget(cap,64L<<20);try(var model=CountCanonicalModel.create(rows,lo,hi,budget)){check(budget.nodes()<=Math.min(262144,cap/16),"local work cap");}check(budget.reservedBytes()==0,"work leak");
        }
        var budget=budget(20_000_000,64L<<20);try(var model=CountCanonicalModel.create(randomRows(new Random(141),30,10,1),lo,hi,budget)){check(model!=null,"lifecycle create");long held=budget.reservedBytes();budget.cancel();try{model.restore(fill(30,0));throw new AssertionError("restore cancellation");}catch(CancellationException expected){}check(budget.reservedBytes()==held,"restore altered ownership");}check(budget.reservedBytes()==0,"restore leak");
        Thread.currentThread().interrupt();budget=budget(20_000_000,64L<<20);try{CountCanonicalModel.create(rows,lo,hi,budget);throw new AssertionError("interrupt ignored");}catch(CancellationException expected){}finally{Thread.interrupted();}check(budget.reservedBytes()==0,"interrupt leak");
        System.out.println("lifecycle unsupported=8 cancellation/memory=50 localWorkCaps=5 restoreCancellation=1 interruption=1");
    }
    public static void main(String[] args) {
        oracle(new Random(701091));permutations(new Random(701092));lifecycle();
        System.out.println("CanonicalModelProbe models="+models+" assignments="+assignments+" permutations="+permutations+" assertions="+assertions+" invalid=0 leaks=0");
    }
}
