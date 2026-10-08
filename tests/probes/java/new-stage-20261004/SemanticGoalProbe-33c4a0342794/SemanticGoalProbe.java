package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public final class SemanticGoalProbe {
    static int checks, coordinatorCases, branchCases, proofCases;
    static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static BigInteger b(long value){return BigInteger.valueOf(value);}
    static Object get(Object object,String name)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    static PlanningBudget budget(){return new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> recipe(String id,String in,String out){return new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>(in,1)),Map.of(out,1L));}
    static CountConflict impossibleCount(int recipe,long count){return new CountConflict(List.of(new ExactLinearProgram.Constraint(Map.of(recipe,b(-1)),b(-count))));}
    static void coordinator()throws Exception{
        for(int seed=0;seed<120;seed++){
            long amount=1+seed%13;var stock=Map.of("A",amount+1);var compiler=new GraphCompiler<>(List.of(recipe("r","A","B")));var budget=budget();
            try(var search=new IntegerCountSearch<>(compiler,"B",amount,stock,Map.of(),Set.of(),Set.of(),false,true,budget,0,null,false)){
                var pool=(CountConflictPool)get(search,"choiceConflicts");pool.add(List.of(impossibleCount(0,amount+2)));
                var pending=(Deque<?>)get(search,"pending");ok(!pending.isEmpty(),"missing root branch");Object root=pending.peekFirst();
                int rounds=0;while(!search.step()){ok(++rounds<10000,"coordinator stalled");}
                ok(get(root,"portfolioMode")==CountPortfolioPolicy.Mode.FIRST_WITNESS,"learned choice silently selected proof mode");
                ok(search.result()!=null&&!search.infeasible(),"valid learned clause prevented first witness");PlanVerifier.verifyRuntimeInventory(search.result());
                coordinatorCases++;
            }ok(budget.reservedBytes()==0,"coordinator reservations leaked");
        }
    }
    static void changingChoices()throws Exception{
        for(int seed=0;seed<120;seed++){
            long amount=1+seed%11;var stock=Map.of("A",amount+1);var compiler=new GraphCompiler<>(List.of(recipe("r","A","B")));var budget=budget();
            try(var model=RecipeCountModel.create(compiler,"B",amount,stock,Map.of(),Set.of(),Set.of(),true,budget);var execution=new CountExecution<>(model,budget)){
                PlanPreference<String> incumbent;
                try(var branch=new IntegerCountBranch<>(model,execution,"B",amount,stock,Map.of(),Set.of(),false,true,budget,0,List.of())){
                    branch.run(1,List.of(),List.of(),List.of(),null,()->false);
                    ok(get(branch,"portfolioMode")==CountPortfolioPolicy.Mode.FIRST_WITNESS,"initial mode wrong");
                    int rounds=0;while(branch.state==IntegerCountBranch.State.OPEN){branch.run(1,List.of(),List.of(),List.of(impossibleCount(0,amount+2)),null,()->false);ok(get(branch,"portfolioMode")==CountPortfolioPolicy.Mode.FIRST_WITNESS,"a learned clause changed a witness task");ok(++rounds<10000,"branch stalled");}
                    ok(branch.state==IntegerCountBranch.State.FOUND,"feasible branch lost witness");incumbent=branch.preference;
                }
                try(var improve=new IntegerCountBranch<>(model,execution,"B",amount,stock,Map.of(),Set.of(),false,true,budget,0,List.of())){
                    improve.run(1,List.of(),List.of(),List.of(),incumbent,CountPortfolioPolicy.Mode.IMPROVEMENT,()->false);
                    ok(get(improve,"portfolioMode")==CountPortfolioPolicy.Mode.IMPROVEMENT,"verified incumbent did not enable improvement");
                }
                try(var invalid=new IntegerCountBranch<>(model,execution,"B",amount,stock,Map.of(),Set.of(),false,true,budget,0,List.of())){
                    try{invalid.run(1,List.of(),List.of(),List.of(),null,CountPortfolioPolicy.Mode.IMPROVEMENT,()->false);throw new AssertionError("missing incumbent accepted");}catch(IllegalArgumentException expected){checks++;}
                }
                branchCases++;
            }ok(budget.reservedBytes()==0,"branch reservations leaked");
        }
    }
    static void explicitProof()throws Exception{
        for(int seed=0;seed<40;seed++){
            var budget=budget();var compiler=new GraphCompiler<>(List.of(recipe("r","A","B")));var stock=Map.<String,Long>of();
            try(var model=RecipeCountModel.create(compiler,"B",1,stock,Map.of(),Set.of(),Set.of(),true,budget);var execution=new CountExecution<>(model,budget);var branch=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,budget,0,List.of())){
                int rounds=0;while(branch.state==IntegerCountBranch.State.OPEN){branch.run(1,List.of(),List.of(),List.of(),null,CountPortfolioPolicy.Mode.PROOF,()->false);ok(++rounds<10000,"explicit proof stalled");}
                ok(get(branch,"portfolioMode")==CountPortfolioPolicy.Mode.PROOF,"explicit proof goal ignored");ok(branch.state==IntegerCountBranch.State.DEAD,"impossible task not proved");proofCases++;
            }ok(budget.reservedBytes()==0,"proof branch leaked");
        }
        var budget=budget();var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(1)),b(0)),new ExactLinearProgram.Constraint(Map.of(0,b(-1)),b(-1)));
        try(var models=CountModelViews.create(rows,new BigInteger[]{b(0)},new BigInteger[]{b(1)},budget);var search=new CountViewSearch(models,budget)){
            search.mode(CountPortfolioPolicy.Mode.PROOF);search.resume(65536);while(!search.step()){}ok(search.infeasible(),"explicit proof portfolio not recognized");
            var policy=(CountPortfolioPolicy)get(search,"policy");ok(policy.mode()==CountPortfolioPolicy.Mode.PROOF,"proof mode overwritten");
            var feedback=(Map<?,?>)get(policy,"candidateCosts");ok(feedback.containsKey(CountPortfolioPolicy.Mode.PROOF),"proof outcome not recorded separately");
        }ok(budget.reservedBytes()==0,"proof portfolio leaked");
    }
    public static void main(String[] args)throws Exception{
        coordinator();changingChoices();explicitProof();String result="{\"assertions\":"+checks+",\"coordinatorCases\":"+coordinatorCases+",\"branchCases\":"+branchCases+",\"explicitProofCases\":"+proofCases+"}";
        Files.writeString(Path.of(args[0],"semantic-goal-probe.json"),result);System.out.println(result);
    }
}
