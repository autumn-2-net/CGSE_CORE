package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.nio.file.*;

public final class OrderRetryRegression {
    static int checks, solved, retries, suspended;
    static long work;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static PlanningBudget budget(){return new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);}
    static void solverCases(){
        for(int sample=0;sample<640;sample++){
            var random=new Random(101L+7001L*sample);long q=2+random.nextInt(5), p=q+random.nextInt(4), unit=sample%3==0?1000:1;
            long runs=new long[]{1,2,17,1000}[sample%4];
            var grow=recipe("grow",Map.of("A",2*unit,"B",3*unit),Map.of("A",unit,"C",p*unit));
            var back=recipe("back",Map.of("C",q*unit),Map.of("A",unit,"C",(q-1)*unit));
            var recipes=new ArrayList<>(List.of(back,grow));
            for(int distractor=0;distractor<sample%9;distractor++)recipes.add(recipe("d"+distractor,Map.of("missing"+distractor,1L),Map.of("C",p*unit)));
            Collections.shuffle(recipes,random);
            var stock=Map.of("A",2*unit,"B",3*unit*runs,"C",q*unit);
            long amount=(q+(p-1)*runs)*unit;boolean preserve=(sample&4)!=0, force=(sample&8)!=0, recovery=(sample&16)!=0;
            var budget=budget();var compiler=new GraphCompiler<>(recipes);
            try(var search=new IntegerCountSearch<>(compiler,"C",amount,stock,Map.of("A",2*unit),Set.of(),Set.of(),preserve,force,budget,System.nanoTime(),null,recovery)){
                int rounds=0;do{while(!search.step()){}if(search.result()!=null||search.infeasible()||!search.paused())break;search.resume();suspended++;}while(++rounds<1000);
                check(search.result()!=null,"lost order sample="+sample+" q="+q+" p="+p+" runs="+runs+" preserve="+preserve+" force="+force+" diag="+budget.diagnostics());
                PlanVerifier.verifyRuntimeInventory(search.result());
                check(search.result().patternTimesExact().get("grow").equals(BigInteger.valueOf(runs)),"grow count changed");
                check(search.result().patternTimesExact().get("back").equals(BigInteger.valueOf(runs)),"back count changed");
                check(search.result().seeds().getOrDefault("C",0L)==0,"inferred target seed remains");
                if(budget.diagnostics().contains("assembly_rejected"))retries++;
                solved++;
            }
            check(budget.reservedBytes()==0,"solver memory leak "+sample+"="+budget.reservedBytes());work+=budget.nodes();
        }
    }
    static void exhaustionAndLeases(){
        var recipe=recipe("only",Map.of("A",1L),Map.of("B",1L));var b=budget();var count=new BigInteger[]{BigInteger.ONE};
        try(var model=RecipeCountModel.forShell(List.of(recipe),Map.of(),Map.of("A",1L),Set.of(),b);var pool=new CountScheduleContinuations<>(model,b)){
            CountSchedule<String> active=pool.acquire(count);int witnesses=0;
            try{
                while(true){while(!active.step()){}check(active.result()!=CountSchedule.Result.DEAD,"assembly rejection became count proof");if(active.result()!=CountSchedule.Result.WITNESS)break;
                    witnesses++;check(witnesses<40,"unbounded same-order retry");check(pool.witness(count)==null,"unverified order cached");
                    if(!active.retryAfterRejectedWitness())break;
                    check(pool.retain(active),"rejected witness continuation refused");var old=active;active=pool.acquire(count);check(active==old,"retry continuation lost");
                }
                check(active.result()==CountSchedule.Result.UNKNOWN,"exhaustion must stay unknown");
            }finally{active.close();}
        }check(b.reservedBytes()==0,"retry close leak");
    }
    public static void main(String[] args)throws Exception{solverCases();exhaustionAndLeases();String result="solved="+solved+", retries="+retries+", suspended="+suspended+", assertions="+checks+", work="+work;Files.writeString(Path.of(args[0],"order-retry-regression.txt"),result);System.out.println(result);}
}
