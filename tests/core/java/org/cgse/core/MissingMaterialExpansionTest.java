// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Refill boundaries are checked independently from the production search. */
public final class MissingMaterialExpansionTest {
    static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static PlanningBudget budget(long work) { return new PlanningBudget(0, work, 64L << 20, () -> false, System::nanoTime); }
    static void check(boolean value, Object why) { if (!value) throw new AssertionError(why); }
    static Map<String, GraphRecipe<String>> index(List<GraphRecipe<String>> recipes) {
        var indexed = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> indexed.put(r.id(), r)); return indexed;
    }
    static GraphPlan<String> partial(GraphRecipe<String> finish, Map<String, Long> stock) {
        var missing = new LinkedHashMap<String, Long>();
        finish.inputs().forEach((k,v) -> { if (v > stock.getOrDefault(k, 0L)) missing.put(k, v - stock.getOrDefault(k, 0L)); });
        return new GraphPlan<>("G", 1, true, new PlanStep.Batch(finish.id(), 1), Map.of(finish.id(), finish),
                finish.inputs(), Map.of(), missing, GraphPlan.Result.MISSING_INPUT, 0, 0);
    }
    static GraphPlan<String> expand(List<GraphRecipe<String>> recipes, GraphPlan<String> plan, Map<String, Long> stock) {
        var b = budget(4_000_000); GraphPlan<String> result;
        try (var expansion = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), plan, stock, Set.of(), Set.of(), true, false, b)) {
            while (!expansion.step()) {} result = expansion.result();
        }
        check(b.reservedBytes() == 0, "refill workspace leaked"); verify(result, stock); return result;
    }
    // Primitive execution is independent of the sparse backward requirement formula.
    static void verify(GraphPlan<String> plan, Map<String, Long> stock) {
        var inventory = new HashMap<String, BigInteger>(); stock.forEach((k,v) -> inventory.put(k, BigInteger.valueOf(v)));
        plan.missingExact().forEach((k,v) -> inventory.merge(k, v, BigInteger::add));
        fire(plan.steps(), plan.recipes(), inventory);
        check(inventory.getOrDefault(plan.target(), BigInteger.ZERO).compareTo(BigInteger.valueOf(plan.amount())) >= 0, "delivery");
        inventory.merge(plan.target(), BigInteger.valueOf(plan.amount()).negate(), BigInteger::add);
        plan.seeds().forEach((k,v) -> check(inventory.getOrDefault(k, BigInteger.ZERO).compareTo(BigInteger.valueOf(v)) >= 0, "seed " + k));
    }
    static void fire(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<String, BigInteger> held) {
        if (step instanceof PlanStep.Sequence seq) { for (var child : seq.children()) fire(child, recipes, held); return; }
        if (step instanceof PlanStep.Repeat repeat) { check(repeat.times() < 10_000, "oracle bound"); for (long i=0;i<repeat.times();i++) fire(repeat.body(),recipes,held); return; }
        var batch = (PlanStep.Batch)step; check(batch.runs() < 10_000, "oracle bound"); var r = recipes.get(batch.recipe());
        for (long i=0;i<batch.runs();i++) {
            r.inputs().forEach((k,v) -> { var rest=held.getOrDefault(k,BigInteger.ZERO).subtract(BigInteger.valueOf(v)); check(rest.signum()>=0,"unfunded "+r.id()+" "+k);held.put(k,rest); });
            r.outputs().forEach((k,v) -> held.merge(k,BigInteger.valueOf(v),BigInteger::add));
        }
    }
    static void siblings() {
        var inputs = new LinkedHashMap<String,Long>(); inputs.put("A",1L);inputs.put("B",1L);
        var recipes = List.of(recipe("goal",inputs,Map.of("G",1L)), recipe("A",Map.of("raw",1L),Map.of("A",1L)),recipe("B",Map.of("A",1L),Map.of("B",1L)));
        for (long amount : new long[]{1,4,100}) {
            var stock=Map.of("raw",amount);var b=budget(262_144);
            var plan=GraphFallback.plan(new GraphCompiler<>(recipes),"G",amount,stock,Set.of(),Map.of(),true,true,b);
            check(plan.missing().equals(Map.of("raw",amount)),"shared prerequisite stopped at intermediate: "+plan.missing());
            check(b.reservedBytes()==0,"fallback workspace");verify(plan,stock);
        }
    }
    static void deepAndWide() {
        for(int size:new int[]{65,129,1025,5000}) {
            var recipes=new ArrayList<GraphRecipe<String>>();
            for(int i=0;i<size;i++)recipes.add(recipe("r"+i,Map.of("k"+(i+1),1L),Map.of("k"+i,1L)));
            var finish=recipe("finish",Map.of("k0",3L),Map.of("G",1L));recipes.add(finish);
            var plan=expand(recipes,partial(finish,Map.of()),Map.of());
            check(plan.missing().equals(Map.of("k"+size,3L)),"depth truncated: "+size+" "+plan.missing());
        }
        var recipes=new ArrayList<GraphRecipe<String>>();var inputs=new LinkedHashMap<String,Long>();
        for(int i=0;i<300;i++){inputs.put("part"+i,1L);recipes.add(recipe("r"+i,Map.of("raw",1L),Map.of("part"+i,1L)));}
        var finish=recipe("finish",inputs,Map.of("G",1L));recipes.add(finish);
        check(expand(recipes,partial(finish,Map.of()),Map.of()).missing().equals(Map.of("raw",300L)),"wide refill truncated");
    }
    static void cyclesAndEscapes() {
        var finish=recipe("finish",Map.of("A",2L),Map.of("G",1L));
        var recipes=new ArrayList<>(List.of(finish,recipe("a",Map.of("B",1L),Map.of("A",1L)),recipe("b",Map.of("A",1L),Map.of("B",1L))));
        check(expand(recipes,partial(finish,Map.of()),Map.of()).missing().equals(Map.of("A",2L)),"closed cycle hid real seed");
        recipes.add(recipe("escape",Map.of("raw",3L),Map.of("B",1L)));
        for(int shuffle=0;shuffle<10;shuffle++){
            Collections.shuffle(recipes,new Random(shuffle));
            check(expand(recipes,partial(finish,Map.of()),Map.of()).missing().equals(Map.of("raw",6L)),"cyclic source hid raw escape");
        }
        // A downstream item must not be mistaken for the cycle's startup seed.
        recipes.removeIf(r->r.id().equals("escape"));recipes.add(recipe("c",Map.of("A",1L),Map.of("C",1L)));
        var downstream=recipe("downstream",Map.of("C",2L),Map.of("G",1L));recipes.add(downstream);
        check(expand(recipes,partial(downstream,Map.of()),Map.of()).missing().equals(Map.of("A",2L)),"downstream cycle boundary");
        var grow=recipe("grow",Map.of("A",1L,"feed",1L),Map.of("A",2L));
        var feed=recipe("feed",Map.of("raw",1L),Map.of("feed",1L));
        var demand=recipe("demand",Map.of("A",10L),Map.of("G",1L));
        var grown=expand(List.of(grow,feed,demand),partial(demand,Map.of()),Map.of());
        check(grown.missing().equals(Map.of("A",1L,"raw",10L)),"seed growth failed to expand its feedstock: "+grown.missing());
    }
    static void returnedToolsAndCoproducts() {
        var finish=recipe("finish",Map.of("plate",5L,"B",1L),Map.of("G",1L));
        var tool=recipe("plate",Map.of("tool",1L,"raw",1L),Map.of("plate",1L,"tool",1L,"B",1L));
        var make=recipe("tool",Map.of("iron",2L),Map.of("tool",1L));
        var recipes=List.of(finish,tool,make);var plan=expand(recipes,partial(finish,Map.of()),Map.of());
        check(plan.missing().equals(Map.of("iron",2L,"raw",5L)),"tool multiplied or coproduct ignored: "+plan.missing());
        var stock=Map.of("iron",2L,"raw",5L);plan=expand(recipes,partial(finish,stock),stock);
        check(plan.feasible(),"fully funded expanded preview not feasible");
    }
    static void forcedProduction() {
        var finish=recipe("finish",Map.of("A",1L),Map.of("G",1L));
        var recycle=recipe("recycle",Map.of("G",1L),Map.of("A",1L));
        var raw=recipe("raw",Map.of("raw",1L),Map.of("G",1L));
        var stock=Map.of("G",10L);
        var p=expand(List.of(finish,recycle,raw),partial(finish,stock),stock);
        check(p.missing().equals(Map.of("raw",1L)),"existing goal stock replaced new production: "+p.missing());
        check(SequenceSummary.of(p.steps(),p.recipes()).delta("G").equals(BigInteger.ONE),"prefix lost verified production credit");
        p=expand(List.of(finish,recycle),partial(finish,stock),stock);
        check(p.missing().equals(Map.of("A",1L)),"closed turnover manufactured an order");
    }
    static void randomDags() {
        var random=new Random(18102026);
        for(int trial=0;trial<120;trial++) {
            var recipes=new ArrayList<GraphRecipe<String>>();
            for(int i=0;i<8;i++) {
                var inputs=new LinkedHashMap<String,Long>();inputs.put(i==0?"raw":"k"+random.nextInt(i),1L+(i%2));
                if(i>1)inputs.merge("k"+random.nextInt(i),1L,Long::sum);
                recipes.add(recipe("r"+i,inputs,Map.of("k"+i,1L)));
            }
            var finish=recipe("finish",Map.of("k7",1L,"k6",1L),Map.of("G",1L));recipes.add(finish);
            var stock=Map.of("raw",(long)random.nextInt(8));Collections.shuffle(recipes,random);
            var plan=expand(recipes,partial(finish,stock),stock);
            check(plan.missingExact().keySet().stream().allMatch(k->k.equals("raw")),"nonterminal DAG shortage "+plan.missing());
            var mainBudget=budget(4_000_000);
            var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"G",1,stock,true,true,mainBudget);
            try{
                while(!work.step()){}var main=work.result();verify(main,stock);
                check(main.feasible()||main.missingExact().keySet().equals(Set.of("raw")),"main left intermediate "+main.missing());
            } finally { work.close(); }
            check(mainBudget.reservedBytes()==0,"main workspace leaked");
        }
    }
    static void limits() {
        var finish=recipe("finish",Map.of("A",1L),Map.of("G",1L));var recipes=List.of(finish,recipe("a",Map.of("raw",1L),Map.of("A",1L)));
        for(int limit=1;limit<160;limit++){
            var b=budget(limit);try(var e=new MissingMaterialExpansion<>(new GraphCompiler<>(recipes),partial(finish,Map.of()),Map.of(),Set.of(),Set.of(),true,false,b)){
                try{while(!e.step()){}check(e.result().missing().equals(Map.of("raw",1L)),"partial boundary published");}catch(PlanningBudget.Exhausted expected){}
            }check(b.reservedBytes()==0,"limited expansion leaked "+limit);
        }
    }
    static void randomCycles() {
        var random=new Random(18102126);
        for(int trial=0;trial<400;trial++){
            var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
            for(int i=0;i<7;i++){
                stock.put("k"+i,(long)random.nextInt(3));
                for(int alt=0;alt<2;alt++){
                    String input=switch(random.nextInt(9)){case 7->"raw";case 8->"G";default->"k"+random.nextInt(7);};
                    var in=new LinkedHashMap<String,Long>();in.put(input,1L);
                    if(random.nextBoolean())in.merge("k"+random.nextInt(7),1L,Long::sum);
                    var out=new LinkedHashMap<String,Long>();out.put("k"+i,1L+random.nextInt(2));
                    if(random.nextInt(4)==0)out.merge("k"+random.nextInt(7),1L,Long::sum);
                    recipes.add(recipe("r"+i+"-"+alt,in,out));
                }
            }
            stock.put("G",(long)random.nextInt(3));stock.put("raw",(long)random.nextInt(3));
            var finish=recipe("finish",Map.of("k0",3L,"k6",2L),Map.of("G",1L));recipes.add(finish);
            Collections.shuffle(recipes,random);var plan=expand(recipes,partial(finish,stock),stock);
            check(SequenceSummary.of(plan.steps(),plan.recipes()).delta("G").compareTo(BigInteger.ONE)>=0,"cycle spent production credit "+trial);
            var producible=new HashSet<String>();var finite=new HashSet<String>(Set.of("raw","G"));
            for(var r:recipes){finite.addAll(r.inputs().keySet());finite.addAll(r.outputs().keySet());r.outputs().forEach((k,v)->{if(v>r.inputs().getOrDefault(k,0L))producible.add(k);});}
            finite.removeAll(producible);boolean changed;
            do{changed=false;for(var r:recipes)if(finite.containsAll(r.inputs().keySet()))for(var e:r.outputs().entrySet())
                if(e.getValue()>r.inputs().getOrDefault(e.getKey(),0L))changed|=finite.add(e.getKey());
            }while(changed);
            check(plan.missing().keySet().stream().noneMatch(k->finite.contains(k)&&producible.contains(k)),"finite escape left behind "+trial+" "+plan.missing());
        }
    }
    static void exactCounts() {
        var finish=recipe("finish",Map.of("A",Long.MAX_VALUE),Map.of("G",1L));
        var make=recipe("make",Map.of("raw",2L),Map.of("A",1L));
        var count=BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        var plan=new GraphPlan<>("G",2,true,new PlanStep.Batch("finish",2),Map.of("finish",finish),Map.of("A",count),Map.of(),Map.of("A",count),GraphPlan.Result.MISSING_INPUT,0,0);
        var b=budget(5000);
        try(var e=new MissingMaterialExpansion<>(new GraphCompiler<>(List.of(finish,make)),plan,Map.of(),Set.of(),Set.of(),true,false,b)){
            while(!e.step()){}
            var p=e.result();check(p.missingExact().equals(Map.of("raw",count.multiply(BigInteger.TWO))),"wide refill lost units");
            check(p.patternTimesExact().get("make").equals(count),"wide prefix count changed");
        }
        check(b.reservedBytes()==0&&b.nodes()<5000,"wide refill expanded individual firings");
    }
    public static void main(String[] args) {
        siblings();deepAndWide();cyclesAndEscapes();returnedToolsAndCoproducts();forcedProduction();randomDags();randomCycles();limits();exactCounts();
        System.out.println("Missing material expansion: primitive stock replay, depth/width, cycles, escapes, coproducts, main/fallback and lifecycle PASS");
    }
}
