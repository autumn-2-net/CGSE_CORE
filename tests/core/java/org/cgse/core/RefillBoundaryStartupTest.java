// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** An artificial unlimited cycle port must become a finite purchase again. */
public final class RefillBoundaryStartupTest {
    private static int checks;

    public static void main(String[] args) {
        for (int order=0; order<8; order++) for (long gain : new long[] {1,2}) {
            run(order,gain,4,true,100_000);
            run(order,gain,4,false,100_000);
            run(order,gain,5,true,100_000);
        }
        for (long limit : new long[] {128,256,512,768,1024}) run(0,2,4,true,limit);
        terminalStartupBoundary();
        System.out.println("RefillBoundaryStartupTest PASS: " + checks + " checks");
    }

    private static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream()
                .map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }

    private static void terminalStartupBoundary() {
        for (int terminals : new int[] {1,25,49}) for (boolean late : List.of(false,true))
            for (int kind=0; kind<6; kind++) {
                var inputs=new LinkedHashMap<String,Long>();
                if (!late) inputs.put("A",3L);
                for (int i=0;i<terminals;i++) inputs.put("raw"+i,1L);
                if (late) inputs.put("A",3L);
                var finish=recipe("finish",inputs,Map.of("T",1L));
                var catalog=new ArrayList<GraphRecipe<String>>();catalog.add(finish);
                var external=kind==5?Set.of("A"):Set.<String>of();
                var excluded=kind==2?Set.of("unused"):Set.<String>of();
                if (kind==3) catalog.add(recipe("unused",Map.of("A",1L),Map.of("A",1L)));
                else if (kind!=0) {
                    // A is also a secondary output in one case. Neither this
                    // binding nor any growth firing occurs in the given word.
                    var outputs=new LinkedHashMap<String,Long>();
                    if (kind==4) outputs.put("coproduct",1L);
                    outputs.put("A",2L);
                    catalog.add(recipe("unused",Map.of("A",1L,"ore",1L),outputs));
                }
                var stock=Map.of("A",3L);
                var missing=new LinkedHashMap<>(inputs);missing.remove("A");
                var original=new GraphPlan<>("T",1,true,new PlanStep.Batch("finish",1),Map.of("finish",finish),
                        inputs,Map.of(),missing,GraphPlan.Result.MISSING_INPUT,0,0);
                var budget=new PlanningBudget(0,100_000,16L<<20,()->false,System::nanoTime);
                GraphPlan<String> result;
                try (var expansion=new MissingMaterialExpansion<>(new GraphCompiler<>(catalog),original,
                        stock,external,excluded,true,false,budget)) {
                    while (!expansion.step()) {}
                    result=expansion.result();
                }
                check(budget.reservedBytes()==0,"terminal startup scan leaked");
                var held=new HashMap<String,BigInteger>();held.put("A",BigInteger.valueOf(3));
                result.missingExact().forEach((k,v)->held.merge(k,v,BigInteger::add));
                execute(result.steps(),result.recipes(),held);
                check(held.getOrDefault("T",BigInteger.ZERO).equals(BigInteger.ONE),"terminal startup delivery changed");
                for (int i=0;i<terminals;i++)
                    check(BigInteger.ONE.equals(result.missingExact().get("raw"+i)),"terminal funding changed");
                if (kind==1 || kind==4) {
                    check(held.getOrDefault("A",BigInteger.ZERO).compareTo(BigInteger.ONE)>=0,
                            "unused producer was skipped after terminal-only funding");
                    check(result.seeds().getOrDefault("A",0L)==1,"unused producer lost its startup obligation");
                    check(!result.patternTimesExact().containsKey("unused"),"test unexpectedly selected the unused source");
                } else {
                    check(result.seeds().isEmpty(),"terminal, excluded or unchanged return inferred growth");
                    check(held.getOrDefault("A",BigInteger.ZERO).signum()==0,"ordinary terminal consumption changed");
                }
            }
    }

    private static void run(int order,long gain,long available,boolean preserve,long limit) {
        var recipes=new ArrayList<>(List.of(recipe("toX",Map.of("A",1L),Map.of("X",1L)),
                recipe("toA",Map.of("X",1L),Map.of("A",gain)),
                recipe("finish",Map.of("A",3L,"X",1L),Map.of("T",1L))));
        Collections.shuffle(recipes,new Random(order));
        recipes.add(0,recipe("alternate",Map.of("A",4L),Map.of("T",1L)));
        var indexed=new LinkedHashMap<String,GraphRecipe<String>>();recipes.forEach(r->indexed.put(r.id(),r));
        var original=new GraphPlan<>("T",1,preserve,new PlanStep.Batch("finish",1),indexed,
                Map.of("A",3L,"X",1L),Map.of(),Map.of("X",1L),GraphPlan.Result.MISSING_INPUT,0,0);
        var compiler=new GraphCompiler<>(recipes);var stock=Map.of("A",available);
        var budget=new PlanningBudget(0,limit,16L<<20,()->false,System::nanoTime);
        GraphPlan<String> result=original;
        try(var ordering=new RefillProducerOrder<>(compiler,original.missingExact().keySet(),Set.of(),Set.of(),budget)) {
            while(!ordering.step()){}
            try(var search=new RefillPlanningSearch<>(compiler,original,ordering,stock,Set.of(),Set.of(),true,false,budget)) {
                while(!search.step()){} result=search.result();
            }
        } catch(PlanningBudget.Exhausted expected) { check(limit<100_000,"unexpected full-budget limit"); }
        check(budget.reservedBytes()==0,"boundary startup workspace leaked");
        if(limit==100_000 && (!preserve || available>4 || gain==1))check(result.feasible(),"safe boundary proposal rejected");
        var options=new ArrayList<GraphPlan<String>>();options.add(result);options.addAll(result.alternatives());
        for(var plan:options) {
            var held=new HashMap<String,BigInteger>();held.put("A",BigInteger.valueOf(available));
            plan.missingExact().forEach((k,v)->held.merge(k,v,BigInteger::add));
            execute(plan.steps(),plan.recipes(),held);
            check(held.getOrDefault("T",BigInteger.ZERO).equals(BigInteger.ONE),"delivery changed");
            // A/X unit conversion alone is not renewable production. Its last
            // unit may be delivered; the gain=2 control must retain startup.
            if(preserve && gain>1)check(held.getOrDefault("A",BigInteger.ZERO).add(held.getOrDefault("X",BigInteger.ZERO)).signum()>0,
                    "temporary unlimited port erased actual cycle startup");
        }
    }

    private static void execute(PlanStep step,Map<String,GraphRecipe<String>> recipes,Map<String,BigInteger> held) {
        if(step instanceof PlanStep.Batch batch) {
            var recipe=recipes.get(batch.recipe());
            for(long i=0;i<batch.runs();i++) {
                recipe.inputs().forEach((k,v)->{
                    var left=held.getOrDefault(k,BigInteger.ZERO).subtract(BigInteger.valueOf(v));
                    check(left.signum()>=0,"unfunded finite boundary input");held.put(k,left);
                });
                recipe.outputs().forEach((k,v)->held.merge(k,BigInteger.valueOf(v),BigInteger::add));
            }
        } else if(step instanceof PlanStep.Repeat repeat) {
            for(long i=0;i<repeat.times();i++)execute(repeat.body(),recipes,held);
        } else for(var child:((PlanStep.Sequence)step).children())execute(child,recipes,held);
    }

    private static void check(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
}
