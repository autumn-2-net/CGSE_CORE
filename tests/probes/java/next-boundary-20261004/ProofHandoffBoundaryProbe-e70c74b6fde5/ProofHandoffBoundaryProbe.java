package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministically orders completed sibling outcomes; proof flags come only from real solver runs. */
public final class ProofHandoffBoundaryProbe {
    static int checks, closed, mergeLimits, cancelled, partial;
    static void ok(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
    static Object get(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static GraphRecipe<String> recipe(){return new GraphRecipe<>("r","r",List.of(new GraphRecipe.Slot<>("A",1)),Map.of("B",1L));}
    static IntegerCountBranch<String> branch(IntegerCountSearch<String> search,PlanningBudget b,Map<String,Long> stock,boolean root)throws Exception{
        var model=(RecipeCountModel<String>)get(search,"model");var execution=(CountExecution<String>)get(search,"execution");
        var assumptions=root?List.<ExactLinearProgram.Constraint>of():List.of(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE),BigInteger.ZERO));
        var branch=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,b,0,assumptions);
        if(root)branch.proveRoot(100000);
        while(branch.state==IntegerCountBranch.State.OPEN)branch.run(4096,List.of(),List.of(),List.of(),null,CountPortfolioPolicy.Mode.PROOF,()->false);
        ok(branch.state==IntegerCountBranch.State.DEAD,"fixture did not actually prove its own domain");
        ok(branch.proofContradiction==root,"scope flag incorrect");return branch;
    }
    static void completedWave(boolean root,boolean cancellation,PlanningBudget.Limit limit)throws Exception{
        var b=new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);var stock=root?Map.<String,Long>of():Map.of("A",1L);var compiler=new GraphCompiler<>(List.of(recipe()));
        var work=new GraphPlanningWork<>(compiler,"B",1,stock,false,true,b);
        try(var search=new IntegerCountSearch<>(compiler,"B",1,stock,Map.of(),Set.of(),Set.of(),false,true,b,0)){
            var proven=branch(search,b,stock,root);
            var model=(RecipeCountModel<String>)get(search,"model");var execution=(CountExecution<String>)get(search,"execution");
            var sibling=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,b,0,List.of());
            sibling.state=IntegerCountBranch.State.UNRESOLVED;
            var wave=List.of(sibling,proven);set(search,"dispatched",wave);
            set(search,"running",CompletableFuture.failedFuture(cancellation?new CancellationException("completed sibling cancelled"):new PlanningBudget.Exhausted(limit)));
            set(work,"countSearch",search);set(work,"phase",14);
            try{
                ok(work.step(),"outer handoff not terminal");ok(!cancellation,"cancellation swallowed");
                var expected=root?GraphPlan.Result.INFEASIBLE:GraphPlan.Result.valueOf(limit.name());
                ok(work.result().result()==expected,"outer lost completed proof or promoted a restricted branch: "+work.result().result()+" expected="+expected+" rootGetter="+search.infeasible());
                if(root)closed++;else partial++;
            }catch(CancellationException expected){ok(cancellation,"unexpected cancellation");cancelled++;}
        }finally{work.close();}
        ok(b.reservedBytes()==0,"exceptional wave reservations leaked "+b.reservedBytes());
    }
    static void mergeInterrupted(boolean cancellation)throws Exception{
        var clock=new AtomicLong();var b=new PlanningBudget(1,1_000_000,64L<<20,()->false,clock::get);var compiler=new GraphCompiler<>(List.of(recipe()));
        var work=new GraphPlanningWork<>(compiler,"B",1,Map.of(),false,true,b);
        try(var search=new IntegerCountSearch<>(compiler,"B",1,Map.of(),Map.of(),Set.of(),Set.of(),false,true,b,0)){
            var proven=branch(search,b,Map.of(),true);var model=(RecipeCountModel<String>)get(search,"model");var execution=(CountExecution<String>)get(search,"execution");
            var sibling=new IntegerCountBranch<>(model,execution,"B",1,Map.of(),Map.of(),Set.of(),false,true,b,0,List.of());
            // A valid closed-root explanation from the impossible fixture. Its
            // optional pool admission is the first deadline-sensitive merge operation.
            sibling.learnedChoices.add(new CountConflict(List.of()));
            var wave=List.of(sibling,proven);set(search,"dispatched",wave);set(search,"running",CompletableFuture.completedFuture(wave));set(work,"countSearch",search);
            if(cancellation)b.cancel();else clock.set(2_000_000);
            try{search.step();throw new AssertionError("merge admission failed to exercise limit");}
            catch(PlanningBudget.Exhausted limit){
                ok(!cancellation,"cancel downgraded to limit");ok(search.infeasible(),"earlier sibling cache admission hid later root proof");
                ok(work.limited(limit).result()==GraphPlan.Result.INFEASIBLE,"merge deadline lost root conclusion at outer handoff");mergeLimits++;
            }catch(CancellationException expected){ok(cancellation,"unexpected merge cancellation");cancelled++;}
        }finally{work.close();}ok(b.reservedBytes()==0,"interrupted merge reservations leaked "+b.reservedBytes());
    }
    public static void main(String[]args)throws Exception{
        for(int i=0;i<40;i++){
            for(var limit:new PlanningBudget.Limit[]{PlanningBudget.Limit.TIMEOUT,PlanningBudget.Limit.SEARCH_LIMIT,PlanningBudget.Limit.MEMORY_LIMIT}){
                completedWave(true,false,limit);completedWave(false,false,limit);
            }
            completedWave(true,true,PlanningBudget.Limit.TIMEOUT);mergeInterrupted(false);mergeInterrupted(true);
        }
        String report="{\"assertions\":"+checks+",\"completedRootProofWithSiblingLimit\":"+closed+",\"earlyMergeLimit\":"+mergeLimits+",\"cancelled\":"+cancelled+",\"restrictedBranchNotPromoted\":"+partial+"}";
        Files.writeString(Path.of(args[0],"proof-handoff-boundary.json"),report);System.out.println(report);
    }
}
