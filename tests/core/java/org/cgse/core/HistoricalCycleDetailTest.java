// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;
import java.math.BigInteger;

/** Original CycleStressDetail generated inputs, with an asserted executable witness. */
public final class HistoricalCycleDetailTest {
    record Case(String name, List<GraphRecipe<String>> recipes, String target, long amount,
                Map<String, Long> stock, Map<String, Long> seeds, PlanStep witness, boolean possible) {}
    static void check(Case c,long workLimit){
        var recipes=new LinkedHashMap<String,GraphRecipe<String>>();c.recipes.forEach(r->recipes.put(r.id(),r));
        var f=new HistoricalFixtures.Fixture(c.name,c.recipes,c.stock,c.target,c.amount,false);
        HistoricalFixtures.verify(f,HistoricalFixtures.summary(c.witness,recipes));
        var budget=new PlanningBudget(0,workLimit,128L<<20,()->false,System::nanoTime);
        var work=new GraphPlanningWork<>(new GraphCompiler<>(c.recipes),c.target,c.amount,c.stock,true,true,budget).catalysts(new CatalystPolicy(8,0));
        try{
            while(!work.step()){}
            var p=work.result();
            if(!p.feasible())throw new AssertionError(c.name+" amount="+c.amount+" "+p.result()+" "+budget.diagnostics());
            PlanVerifier.verify(p);PlanVerifier.verifyRuntimeInventory(p);
            p.initial().forEach((k,v)->{if(v>c.stock.getOrDefault(k,0L))throw new AssertionError("unfunded initial "+k);});
            HistoricalFixtures.verify(f,HistoricalFixtures.summary(p.steps(),p.recipes()));
        }finally{work.close();}
        if(budget.reservedBytes()!=0)throw new AssertionError("memory retained");
    }
    public static void main(String[]args){
        int count=0;
        for(long n:new long[]{1,9,1000,100_000_017L,1_000_000_000_000L}){
            for(var c:List.of(hub(2,n,4),braid(2,n,4),nested(2,2,n,1),nested(3,2,n,1))){check(c,10_000_000);count++;}
        }
        check(nested(2,2,1,1),100_000_000);count++;
        for(int n:new int[]{128,512,1024}){check(ring(n,100_000_017L,8),10_000_000);count++;}
        System.out.println("PASS original cyclic inputs="+count+" exact prefix, goal, initial and memory checks");
    }
    static Case ring(int size, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        var body = new ArrayList<PlanStep>();
        for (int i = 0; i < size; i++) {
            var in = amounts("C" + i, 1);
            if (i == 0) in.put("R", 1L);
            var out = amounts("C" + ((i + 1) % size), 1);
            if (i + 1 == size) out.put("P", 1L);
            recipes.add(recipe("r" + i, in, out)); body.add(batch("r" + i));
        }
        return new Case("ring" + size + "-s" + seeds, recipes, "P", n, amounts("R", n, "C0", seeds), amounts("C0", seeds), repeat(seq(body), n), true);
    }
    static Case hub(int size, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var body = new ArrayList<PlanStep>(); var join = new TreeMap<String, Long>();
        for (int i = 0; i < size; i++) {
            recipes.add(recipe("a" + i, amounts("C", 1, "R", 1), amounts("I" + i, 1)));
            recipes.add(recipe("b" + i, amounts("I" + i, 1), amounts("C", 1, "Q" + i, 1)));
            join.put("Q" + i, 1L);
            body.add(repeat(seq(List.of(batch("a" + i), batch("b" + i))), n));
        }
        joinTree(recipes, body, join, n);
        return new Case("shared-hub" + size, recipes, "P", n, amounts("R", Math.multiplyExact(n, size), "C", seeds), amounts("C", seeds), seq(body), true);
    }
    static Case braid(int size, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>(); var body = new ArrayList<PlanStep>();
        for (int i = 0; i < size; i++) {
            recipes.add(recipe("a" + i, amounts("C" + i, 1, "R", 1), amounts("A" + i, 1, "B" + i, 1)));
            recipes.add(recipe("b" + i, amounts("A" + i, 1), amounts("D" + i, 1)));
            recipes.add(recipe("c" + i, amounts("B" + i, 1), amounts("E" + i, 1)));
            var output = amounts("C" + ((i + 1) % size), 1);
            if (i == size - 1) output.put("P", 1L);
            recipes.add(recipe("d" + i, amounts("D" + i, 1, "E" + i, 1), output));
            for (String id : new String[]{"a", "b", "c", "d"}) body.add(batch(id + i));
        }
        return new Case("braid" + size, recipes, "P", n, amounts("R", Math.multiplyExact(n, size), "C0", seeds), amounts("C0", seeds), repeat(seq(body), n), true);
    }
    static Case nested(int depth, int ratio, long n, int seeds) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < depth; i++) {
            recipes.add(recipe("open" + i, amounts("C" + i, 1, "R", 1), amounts("C" + (i + 1), 1)));
            recipes.add(recipe("close" + i, amounts("C" + (i + 1), 1, "Q" + (i + 1), ratio), amounts("C" + i, 1, "Q" + i, 1)));
        }
        recipes.add(recipe("inner", amounts("C" + depth, 1, "R", 1), amounts("C" + depth, 1, "Q" + depth, 1)));
        PlanStep witness = batch("inner"); long raw = 1;
        for (int i = depth - 1; i >= 0; i--) {
            witness = seq(List.of(batch("open" + i), repeat(witness, ratio), batch("close" + i)));
            raw = Math.addExact(1, Math.multiplyExact(raw, ratio));
        }
        return new Case("nested" + depth + "-ratio" + ratio, recipes, "Q0", n,
                amounts("R", Math.multiplyExact(raw, n), "C0", seeds), amounts("C0", seeds), repeat(witness, n), true);
    }
    static void joinTree(List<GraphRecipe<String>> recipes, List<PlanStep> body, Map<String, Long> inputs, long n) {
        List<String> keys = new ArrayList<>(inputs.keySet()); int level = 0;
        while (keys.size() > 8) {
            List<String> next = new ArrayList<>();
            for (int start = 0; start < keys.size(); start += 8) {
                Map<String, Long> group = new TreeMap<>();
                for (int k = start; k < Math.min(start + 8, keys.size()); k++) group.put(keys.get(k), 1L);
                String output = "J" + level + "x" + start, id = "join_" + output;
                recipes.add(recipe(id, group, amounts(output, 1))); body.add(new PlanStep.Batch(id, n)); next.add(output);
            }
            keys = next; level++;
        }
        Map<String, Long> last = new TreeMap<>(); keys.forEach(k -> last.put(k, 1L));
        recipes.add(recipe("join", last, amounts("P", 1))); body.add(new PlanStep.Batch("join", n));
    }
    static Map<String, Long> amounts(Object... pairs) {
        var map = new TreeMap<String, Long>(); for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue()); return map;
    }
    static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    static PlanStep batch(String id) { return new PlanStep.Batch(id, 1); }
    static PlanStep seq(List<PlanStep> steps) { return new PlanStep.Sequence(steps); }
    static PlanStep repeat(PlanStep s, long n) { return new PlanStep.Repeat(s, n); }
}
