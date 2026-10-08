package org.cgse.core;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only timing hook observes production atomic boundaries without charging or refunding work. */
public final class BatchQuantumSafety {
    static final List<long[]> calls=new ArrayList<>();static long assertions,quanta,stops,auxStops;
    public static void atomic(long before,long after){calls.add(new long[]{before,after});}
    static void check(boolean x,String s){assertions++;if(!x)throw new AssertionError(s);}
    static void run(long quantum,int stopAfter,long auxiliaryRoom){
        var budget=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);var stock=AuxiliaryRetainedSafety.stock(42);
        try(var model=RecipeCountModel.create(new GraphCompiler<>(AuxiliaryRetainedSafety.recipes(42)),"T",1,stock,Map.of(),Set.of(),Set.of(),false,budget);
            var execution=new CountExecution<>(model,budget);
            var branch=AuxiliaryRetainedSafety.prepared(model,execution,stock,AuxiliaryRetainedSafety.pigeon(7,6),budget,2)){
            var solver=branch.auxiliaryLcg;AtomicInteger polls=new AtomicInteger();
            if(auxiliaryRoom>=0)branch.auxiliaryUntil=branch.auxiliaryWork+auxiliaryRoom;
            calls.clear();long before=budget.threadWork();branch.run(quantum,List.of(),List.of(),List.of(),null,()->polls.incrementAndGet()>=stopAfter);long after=budget.threadWork();
            if(stopAfter==1){check(calls.isEmpty(),"stopped branch executed another atomic step");stops++;}
            else{
                check(!calls.isEmpty(),"no progress");
                long allowance=Math.min(quantum,auxiliaryRoom<0?262144:auxiliaryRoom);
                if(calls.size()>1)check(calls.get(calls.size()-1)[0]-before<allowance,"started an extra atomic step beyond worker/aux quota");
                long max=calls.stream().mapToLong(x->x[1]-x[0]).max().orElse(0);
                check(after-before<=allowance+max+2,"overshoot exceeded original atomic operation");
                if(stopAfter!=Integer.MAX_VALUE){check(calls.size()<=stopAfter-1,"ignored inner stopped signal");stops++;}
                if(auxiliaryRoom>=0){check(branch.state==IntegerCountBranch.State.UNRESOLVED,"outer auxiliary boundary not yielded");check(branch.auxiliaryLcg==solver,"aux quota disposed state");auxStops++;}
                quanta++;
            }
            check(branch.limit==null,"local boundary became global limit");check(!solver.infeasible(),"boundary became proof");
            check(branch.work==after-before&&branch.auxiliaryWork==after-before,"outer work accounting lost/billed twice");
        }finally{check(budget.reservedBytes()==0,"quantum leak");}
    }
    public static void main(String[]args){
        for(long q:new long[]{1,2,4,16,64,256,1024,4096})run(q,Integer.MAX_VALUE,-1);
        for(int stop:new int[]{1,2,3,5,11,23})run(100000,stop,-1);
        for(long room:new long[]{1,2,16,64,256})run(4096,Integer.MAX_VALUE,room);
        System.out.println("BATCH_QUANTUM assertions="+assertions+" quanta="+quanta+" stopped="+stops+" aux_boundaries="+auxStops+" leaks=0");
    }
}
