// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** BatchQuantumSafety, observing the existing stop callback instead of a removed debug hook. */
public final class HistoricalBatchQuantumTest {
    static void check(boolean b,String detail){if(!b)throw new AssertionError(detail);}
    static List<GraphRecipe<String>> recipes(int n){var r=new ArrayList<GraphRecipe<String>>();for(int i=0;i<n;i++)r.add(new GraphRecipe<>("r"+i,"r"+i,List.of(new GraphRecipe.Slot<>("token"+i,1)),Map.of("T",1L)));return r;}
    static Map<String,Long> stock(int n){var s=new LinkedHashMap<String,Long>();for(int i=0;i<n;i++)s.put("token"+i,1L);return s;}
    static List<ExactLinearProgram.Constraint> pigeon(int p,int h){
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int i=0;i<p;i++){var t=new LinkedHashMap<Integer,BigInteger>();for(int j=0;j<h;j++)t.put(i*h+j,BigInteger.ONE.negate());rows.add(new ExactLinearProgram.Constraint(t,BigInteger.ONE.negate()));}
        for(int j=0;j<h;j++){var t=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<p;i++)t.put(i*h+j,BigInteger.ONE);rows.add(new ExactLinearProgram.Constraint(t,BigInteger.ONE));}
        return rows;
    }
    static IntegerCountBranch<String> prepared(RecipeCountModel<String> model,CountExecution<String> execution,
            Map<String,Long> stock,List<ExactLinearProgram.Constraint> restrictions,PlanningBudget budget,int mode){
        var b=new IntegerCountBranch<>(model,execution,"T",1,stock,Map.of(),Set.of(),false,false,budget,System.nanoTime(),restrictions);
        try {
            int n=model.recipes.size();b.auxiliaryMode=mode;b.partitioned=true;b.initialized=b.compiled=true;
            b.lower=new BigInteger[n];b.upper=new BigInteger[n];Arrays.fill(b.lower,BigInteger.ZERO);Arrays.fill(b.upper,BigInteger.ONE);
            b.linearConstraints=new ArrayList<>(model.constraints);b.linearConstraints.addAll(restrictions);
            b.reduction=new CountReduction(b.linearConstraints,b.lower,b.upper,budget);
            while(!b.reduction.step()){}
            b.auxiliaryLcg=new CountLcg(b.reduction.rows(),b.reduction.lower(),b.reduction.upper(),budget,1024,true);
            return b;
        } catch(RuntimeException|Error error){b.close();throw error;}
    }

    static void run(long quantum,int stopAfter,long room){
        var budget=new PlanningBudget(0,4000000,128L<<20,()->false,System::nanoTime);
        var stock=stock(42);
        try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes(42)),"T",1,stock,Map.of(),Set.of(),Set.of(),false,budget);
            var execution=new CountExecution<>(model,budget);
            var branch=prepared(model,execution,stock,pigeon(7,6),budget,2)){
            var solver=branch.auxiliaryLcg;
            if(room>=0)branch.auxiliaryUntil=branch.auxiliaryWork+room;
            AtomicInteger polls=new AtomicInteger();var boundaries=new ArrayList<Long>();var stoppedAt=new AtomicLong(-1);
            long before=budget.threadWork();boundaries.add(before);
            branch.run(quantum,List.of(),List.of(),List.of(),null,()->{
                long now=budget.threadWork();boundaries.add(now);
                boolean stopped=polls.incrementAndGet()>=stopAfter;
                if(stopped)stoppedAt.compareAndSet(-1,now);
                return stopped;
            });
            long after=budget.threadWork();boundaries.add(after);
            if(stopAfter==1)check(after==before,"stopped branch still charged work");
            else{
                check(after>before,"active branch made no progress");
                long allowance=Math.min(quantum,room<0?262144:room);
                for(int i=1;i<boundaries.size();i++)if(boundaries.get(i)>boundaries.get(i-1))
                    check(boundaries.get(i-1)-before<allowance,"new atomic work started beyond local grant");
                if(stoppedAt.get()>=0)check(stoppedAt.get()==after,"charged after stop callback");
                if(room>=0)check(branch.auxiliaryLcg==solver,"auxiliary allowance discarded continuation");
            }
            check(branch.limit==null&&!solver.infeasible(),"local boundary became global failure/proof");
            check(branch.work==after-before&&branch.auxiliaryWork==after-before,"branch billed work twice or omitted it");
        }
        check(budget.reservedBytes()==0,"batch quantum leaked");
    }
    public static void main(String[]args){
        for(long q:new long[]{1,2,4,16,64,256,1024,4096})run(q,Integer.MAX_VALUE,-1);
        for(int stop:new int[]{1,2,3,5,11,23})run(100000,stop,-1);
        for(long room:new long[]{1,2,16,64,256})run(4096,Integer.MAX_VALUE,room);
        System.out.println("Historical quantum: 19 slice/stop/auxiliary boundaries, exact accounting and cleanup passed");
    }
}
