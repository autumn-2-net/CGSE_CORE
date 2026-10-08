package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

public final class SolverViewsTest {
    static int checks, pauses, raw, light, reduced;
    static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
    static BigInteger bi(long n) { return BigInteger.valueOf(n); }
    static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] x) {
        for (var row:rows) {
            BigInteger sum=BigInteger.ZERO;
            for(var t:row.terms().entrySet()) sum=sum.add(x[t.getKey()].multiply(t.getValue()));
            if(sum.compareTo(row.upper())>0) return false;
        }
        return true;
    }
    static boolean brute(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high, int i, BigInteger[] x) {
        if(i==x.length) return valid(rows,x);
        for(BigInteger v=low[i];v.compareTo(high[i])<=0;v=v.add(BigInteger.ONE)) {
            x[i]=v;if(brute(rows,low,high,i+1,x))return true;
        }
        return false;
    }
    static void costs() {
        boolean[] cancelled={false};
        var budget=new PlanningBudget(0,10000,1<<20,()->cancelled[0],System::nanoTime);
        for(int i=0;i<64;i++) budget.operation(PlanningBudget.Operation.SCAN,0);
        check(budget.nodes()==16,"fractional deterministic work");
        for(int i=0;i<10;i++)budget.check();
        check(budget.nodes()==26,"generic accounting preserved");
        long start=budget.nodes();
        budget.operation(PlanningBudget.Operation.RATIONAL,256);
        check(budget.nodes()-start==16,"big rational operation cost");
        start=budget.threadWork();
        for(int i=0;i<64;i++)budget.operation(PlanningBudget.Operation.SCAN,0);
        check(budget.threadWork()-start==16,"thread and order use same cost");
        cancelled[0]=true;
        try {budget.operation(PlanningBudget.Operation.SCAN,0);throw new AssertionError("lost cancellation");}
        catch(CancellationException expected){}
        var capped=new PlanningBudget(0,1,()->false);
        for(int i=0;i<4;i++)capped.operation(PlanningBudget.Operation.SCAN,0);
        try{capped.operation(PlanningBudget.Operation.SCAN,0);throw new AssertionError("lost limit");}
        catch(PlanningBudget.Exhausted expected){check(expected.limit()==PlanningBudget.Limit.SEARCH_LIMIT,"work cap");}
    }
    public static void main(String[] args) {
        costs();
        var random=new Random(0xC65E);
        for(int trial=0;trial<1200;trial++) {
            int n=1+random.nextInt(5);
            BigInteger[] low=new BigInteger[n],high=new BigInteger[n];
            for(int i=0;i<n;i++){low[i]=bi(random.nextInt(2));high[i]=low[i].add(bi(random.nextInt(5)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int j=0;j<2+random.nextInt(9);j++){
                var terms=new LinkedHashMap<Integer,BigInteger>();int gcd=2+random.nextInt(13);
                for(int i=0;i<n;i++) {int a=random.nextInt(9)-4;if(a!=0)terms.put(i,bi(a*gcd));}
                rows.add(new ExactLinearProgram.Constraint(terms,bi(random.nextInt(51)-25)));
                if(random.nextInt(8)==0)rows.add(rows.get(rows.size()-1));
            }
            if(n>1&&trial%3==0) { // Recover fixed and reflected affine coordinates.
                rows.add(new ExactLinearProgram.Constraint(Map.of(0,bi(1),1,bi(1)),bi(3)));
                rows.add(new ExactLinearProgram.Constraint(Map.of(0,bi(-1),1,bi(-1)),bi(-3)));
            }
            boolean expected=brute(rows,low,high,0,new BigInteger[n]);
            var budget=new PlanningBudget(0,20_000_000,64L<<20,()->false,System::nanoTime);
            var models=CountModelViews.create(rows,low,high,budget);
            try(var reduction=new CountReduction(rows,low,high,budget);models) {
                models.compileLight();
                while(!reduction.step()){}
                models.addReduced(reduction);
                for(var view:models.available()) {
                    switch(view.name()){case "original"->raw++;case "normalized"->light++;case "reduced"->reduced++;}
                    try(var solver=new CountLcg(view.rows(),view.lower(),view.upper(),budget,1024)) {
                        while(true) {
                            while(!solver.step()){}
                            if(!solver.paused())break;
                            check(!solver.infeasible(),"pause became proof");pauses++;solver.resume(1024);
                        }
                        check((solver.counts()!=null)==expected,"view mismatch "+trial+" "+view.name());
                        check(solver.infeasible()==!expected,"negative result mismatch");
                        if(expected)check(valid(rows,models.restoreAndCheck(view,solver.counts())),"restoration");
                    }
                }
                try(var search=new CountViewSearch(models,budget)) {
                    for(int round=0;round<100;round++) {
                        search.resume(1024);
                        while(!search.step()){}
                        if(search.counts()!=null||search.infeasible()||!search.retained())break;
                    }
                    check((search.counts()!=null)==expected,"portfolio mismatch "+trial);
                    check(search.infeasible()==!expected,"portfolio negative mismatch");
                }
            }
            check(budget.reservedBytes()==0,"workspace leak "+budget.reservedBytes());
        }
        // An inconsistent odd cycle forces nontrivial implication analysis.
        int n=49;var rows=new ArrayList<ExactLinearProgram.Constraint>();
        BigInteger[] low=new BigInteger[n],high=new BigInteger[n];Arrays.fill(low,bi(0));Arrays.fill(high,bi(100));
        for(int i=0;i<n;i++) {
            rows.add(new ExactLinearProgram.Constraint(Map.of(i,bi(1),(i+1)%n,bi(1)),bi(1)));
            rows.add(new ExactLinearProgram.Constraint(Map.of(i,bi(-1),(i+1)%n,bi(-1)),bi(-1)));
        }
        var budget=new PlanningBudget(0,20_000_000,()->false);
        try(var solver=new CountLcg(rows,low,high,budget,1024,true)) {
            while(true){while(!solver.step()){}if(!solver.paused())break;pauses++;solver.resume(1024);}
            check(solver.infeasible(),"resumed odd cycle must close");
            check(CountProof.verify(solver.certificate(),20_000_000)==CountProof.Verdict.VERIFIED,"resumed learned proof must check independently");
        }
        check(budget.reservedBytes()==0,"resumed search leak");
        check(pauses>0,"continuation not exercised");
        System.out.println("checks="+checks+" views="+raw+","+light+","+reduced+" pauses="+pauses);
    }
}
