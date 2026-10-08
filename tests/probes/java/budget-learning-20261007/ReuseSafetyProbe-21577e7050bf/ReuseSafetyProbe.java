package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.Path;

public final class ReuseSafetyProbe {
    static PlanningBudget budget(){return new PlanningBudget(0,40_000_000,128L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> r(String id,Map<String,Long> input,Map<String,Long> output){return new GraphRecipe<>(id,id,input.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),output);}
    static int id(RecipeCountModel<String> model,String name){for(int i=0;i<model.recipes.size();i++)if(model.recipes.get(i).id().equals(name))return i;throw new AssertionError(name);}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void familyWork(){
        var recipes=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<20;i++)recipes.add(r("candidate"+i,Map.of("A",2L),Map.of("B",1L,"T",1L)));
        recipes.add(r("back",Map.of("B",2L),Map.of("A",3L)));
        recipes.add(r("repair",Map.of("X",1L),Map.of("A",1L)));
        var b=budget();try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),"T",1,Map.of("A",1L,"B",1L,"X",1L),Map.of(),Set.of(),Set.of(),true,b)){
            var all=new BigInteger[model.recipes.size()];Arrays.fill(all,BigInteger.ONE);all[id(model,"repair")]=BigInteger.ZERO;
            long start=b.searchWork();var family=CountStartup.separateAll(model,all,b);long grouped=b.searchWork()-start;
            check(family.size()==21,"not all alternate sources learned");
            start=b.searchWork();for(var proof:family){var one=new BigInteger[all.length];Arrays.fill(one,BigInteger.ZERO);one[proof.recipe()]=BigInteger.ONE;check(CountStartup.separate(model,one,b)!=null,"single explanation missing");}
            long repeated=b.searchWork()-start;check(grouped*3<repeated,"family closure not reused");
            System.out.println("startup guards="+family.size()+" repeated_work="+repeated+" shared_closure_work="+grouped);
        }check(b.reservedBytes()==0,"family workspace leaked");
    }
    public static void main(String[] args)throws Exception {
        var recipes=List.of(r("a",Map.of("A",1L),Map.of("Q",1L)),r("b",Map.of("B",1L),Map.of("Q",1L)));
        var compiler=new GraphCompiler<>(recipes);var sessions=compiler.countSessions;var budget=budget();
        var journal=new CountProof.Journal(1L<<20);budget.proofJournal(journal);
        long rememberWork;
        try(var model=RecipeCountModel.create(compiler,"Q",2,Map.of("A",1L,"B",1L),Map.of(),Set.of(),Set.of(),false,budget)){
            var bad=new CountConflict(List.of(new ExactLinearProgram.Constraint(Map.of(id(model,"a"),BigInteger.ONE.negate()),BigInteger.TWO.negate())));
            long start=budget.searchWork();sessions.remember(model,List.of(bad),budget);rememberWork=budget.searchWork()-start;
            check(rememberWork>0&&rememberWork<4096,"checker still pays full allowance: "+rememberWork);
            check(!sessions.reuse(model,budget).isEmpty(),"original core missing");
        }
        int rechecked=0;
        for(long amount:new long[]{1,2,1000})for(long a:new long[]{0,1,2,1000})for(long b:new long[]{0,1,1000}){
            try(var model=RecipeCountModel.create(compiler,"Q",amount,Map.of("A",a,"B",b),Map.of(),Set.of(),Set.of(),false,budget)){
                var reused=sessions.reuse(model,budget);
                if(a<2) check(!reused.isEmpty(),"valid resource core not reused");
                if(a>=2&&a+b>=amount) check(reused.isEmpty(),"a feasible violating count vector was pruned");
                rechecked++;
            }
        }
        try(var changed=RecipeCountModel.create(compiler,"Q",1,Map.of("A",1L,"B",1L),Map.of(),Set.of("A"),Set.of(),false,budget)){
            check(sessions.reuse(changed,budget).isEmpty(),"externally supplied A inherited finite stock conflict");
        }
        try(var changed=RecipeCountModel.create(compiler,"Q",1,Map.of("A",1L,"B",1L),Map.of(),Set.of(),Set.of("a"),false,budget)){
            check(sessions.reuse(changed,budget).isEmpty(),"different recipe catalog inherited coordinates");
        }
        var replacement=new GraphCompiler<>(List.of(r("a",Map.of(),Map.of("Q",1L)),recipes.get(1)));
        try(var changed=RecipeCountModel.create(replacement,"Q",1,Map.of("A",1L,"B",1L),Map.of(),Set.of(),Set.of(),false,budget)){
            check(replacement.countSessions.reuse(changed,budget).isEmpty(),"new catalog retained old session");
            check(sessions.reuse(changed,budget).isEmpty(),"same ids with changed input semantics passed revalidation");
        }
        Path archive=Path.of(args[0]);journal.write(archive);var replay=CountProof.read(archive);
        check(replay.entries().stream().anyMatch(p->p.scope().equals("revalidated_count_assumptions")),"no current-model certificate");
        for(var proof:replay.entries())check(CountProof.verify(proof,1_000_000)==CountProof.Verdict.VERIFIED,"bad persisted/revalidated proof");
        check(budget.reservedBytes()==0,"scope cache workspace leaked");
        familyWork();
        System.out.println("PASS snapshot/quantity variants="+rechecked+"; semantic replacement, catalog exclusion, external supply and independent proof replay; remember_work="+rememberWork);
    }
}
