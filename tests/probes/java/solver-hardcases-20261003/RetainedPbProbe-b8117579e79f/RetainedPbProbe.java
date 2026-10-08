package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public class RetainedPbProbe {
    static int checks, cases, sat, unsat, pauses;
    static BigInteger b(long n) { return BigInteger.valueOf(n); }
    static void check(boolean p,String s) { checks++; if(!p) throw new AssertionError(s); }
    static boolean holds(List<ExactLinearProgram.Constraint> rows, BigInteger[] x) {
        for(var row:rows){var n=b(0);for(var t:row.terms().entrySet())n=n.add(t.getValue().multiply(x[t.getKey()]));if(n.compareTo(row.upper())>0)return false;}return true;
    }
    static boolean oracle(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi) {
        int n=lo.length;for(int mask=0;mask<(1<<n);mask++){var x=lo.clone();for(int i=0;i<n;i++)if((mask&(1<<i))!=0)x[i]=hi[i];if(holds(rows,x))return true;}return false;
    }
    static void run(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,boolean possible,PlanningBudget budget,boolean limited) {
        cases++;CountProof.Journal journal=new CountProof.Journal(128L<<20);if(!limited)budget.proofJournal(journal);
        try(var search=new CountCdcl(rows,lo,hi,budget,1024).retained()) {
            while(true){while(!search.step()){}if(!search.paused())break;pauses++;check(search.counts()==null&&!search.infeasible(),"pause exported answer");search.resume(17+(cases%127));}
            var x=search.counts();if(x!=null){sat++;check(possible,"false SAT");check(holds(rows,x),"bad witness");for(int i=0;i<x.length;i++)check(x[i].compareTo(lo[i])>=0&&x[i].compareTo(hi[i])<=0,"bounds");}
            else if(search.infeasible()){unsat++;check(!possible,"false UNSAT");}
            else check(limited,"unexpected UNKNOWN");
        } catch(PlanningBudget.Exhausted|CancellationException e){check(limited,"unexpected exhausted");}
        for(var proof:journal.entries())check(CountProof.verify(proof,8_000_000)==CountProof.Verdict.VERIFIED,"invalid clause proof");
        for(var proof:journal.derivations())check(CountProof.verify(proof,8_000_000)==CountProof.Verdict.VERIFIED,"invalid weighted proof");
        check(budget.reservedBytes()==0,"leak");
    }
    public static void main(String[] args) {
        Random rng=new Random(9031003);
        for(int t=0;t<2500;t++) {
            int n=2+rng.nextInt(8);var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=b(rng.nextInt(7)-3);hi[i]=lo[i].add(b(rng.nextInt(5)==0?0:1));}
            List<ExactLinearProgram.Constraint> rows=new ArrayList<>();for(int j=0,m=2+rng.nextInt(24);j<m;j++){Map<Integer,BigInteger>a=new LinkedHashMap<>();for(int i=0;i<n;i++){int v=rng.nextInt(17)-8;if(v!=0)a.put(i,b(v));}rows.add(new ExactLinearProgram.Constraint(a,b(rng.nextInt(61)-30)));}
            run(rows,lo,hi,oracle(rows,lo,hi),new PlanningBudget(0,8_000_000,128L<<20,()->false,System::nanoTime),false);
        }
        for(int h=3;h<=5;h++) {
            int n=h*(h+1);var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,b(0));Arrays.fill(hi,b(1));List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
            for(int p=0;p<=h;p++){Map<Integer,BigInteger>a=new LinkedHashMap<>();for(int k=0;k<h;k++)a.put(p*h+k,b(-1));rows.add(new ExactLinearProgram.Constraint(a,b(-1)));}
            for(int k=0;k<h;k++)for(int p=0;p<=h;p++)for(int q=p+1;q<=h;q++)rows.add(new ExactLinearProgram.Constraint(Map.of(p*h+k,b(1),q*h+k,b(1)),b(1)));
            run(rows,lo,hi,false,new PlanningBudget(0,8_000_000,128L<<20,()->false,System::nanoTime),false);
            if(h==4){for(int limit=1;limit<1800;limit+=7)run(rows,lo,hi,false,new PlanningBudget(0,limit,128L<<20,()->false,System::nanoTime),true);
                for(int limit=1;limit<600;limit+=3){int stop=limit;var polls=new AtomicInteger();run(rows,lo,hi,false,new PlanningBudget(0,1_000_000,128L<<20,()->polls.incrementAndGet()>stop,System::nanoTime),true);}
                for(int mem=1;mem<50000;mem+=499)run(rows,lo,hi,false,new PlanningBudget(0,1_000_000,mem,()->false,System::nanoTime),true);
            }
        }
        check(pauses>0,"no pauses");System.out.println("PASS cases="+cases+" sat="+sat+" unsat="+unsat+" pauses="+pauses+" assertions="+checks);
    }
}
