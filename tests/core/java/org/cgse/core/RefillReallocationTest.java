// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Existing physical bindings do not freeze the counts of competing routes. */
public final class RefillReallocationTest {
    private static int checks;
    private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static PlanningBudget budget() { return MissingMaterialExpansionTest.budget(4_000_000); }
    private static List<GraphRecipe<String>> routes(int depth, long demand) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (String branch : List.of("X", "Y")) {
            for (int i = 0; i < depth; i++) recipes.add(r(branch + i,
                    Map.of(i + 1 == depth ? "raw" : branch + (i + 1), i + 1 == depth && branch.equals("X") ? 100L : 1L),
                    Map.of(branch + i, 1L)));
            recipes.add(r("use" + branch, Map.of(branch + "0", 1L), Map.of("A", 1L)));
        }
        recipes.add(r("finish", Map.of("A", demand), Map.of("G", 1L)));
        return recipes;
    }
    private static GraphPlan<String> mixed(List<GraphRecipe<String>> recipes, int depth,
                                            BigInteger x, BigInteger y, Map<String, Long> stock) {
        var steps = new ArrayList<PlanStep>();
        for (String branch : List.of("X", "Y")) {
            var count = branch.equals("X") ? x : y;
            for (int i = depth - 1; i >= 0; i--) steps.add(PlanStep.batch(branch + i, count));
            steps.add(PlanStep.batch("use" + branch, count));
        }
        steps.add(new PlanStep.Batch("finish", 1));
        return RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(steps), Map.of(), stock, Set.of());
    }
    private static GraphPlan<String> refine(List<GraphRecipe<String>> recipes, GraphPlan<String> original,
            Map<String, Long> stock, Set<String> external, Set<String> excluded, boolean force, PlanningBudget b) {
        GraphPlan<String> answer;
        try (var work = RefillSourceSearch.reallocate(new GraphCompiler<>(recipes), original, stock, external, excluded,
                force, false, b, null)) {
            while (!work.step()) {}
            answer = work.result();
        }
        check(b.reservedBytes() == 0, "Retained allocation workspace");
        for (var e : answer.missingExact().entrySet())
            check(e.getValue().compareTo(original.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO)) <= 0, "Worse missing vector");
        return answer;
    }
    private static void literal(GraphPlan<String> plan, Map<String, Long> stock) {
        RefillWordPruningTest.literalVerify(plan, stock, Set.of());
        check(true, "Primitive execution");
    }
    public static void main(String[] args) throws Exception {
        mixedPaths(); productionEntries(); interactions(); composed(); scopes(); exactCounts();
        borrowedOrder(); admission(); limits(false); limits(true); ownerPublication(); lateBinding(); scheduler();
        System.out.println("RefillReallocationTest: assertions=" + checks);
    }
    private static void mixedPaths() {
        for (int depth : new int[]{1, 8, 64, 512}) for (long demand : new long[]{3, 7, 31})
            for (int ordering = 0; ordering < 3; ordering++) {
                var recipes = routes(depth, demand);
                var stock = Map.of("raw", demand / 2);
                var original = mixed(recipes, depth, BigInteger.valueOf(demand - 1), BigInteger.ONE, stock);
                if (ordering == 1) Collections.reverse(recipes);
                if (ordering == 2) Collections.shuffle(recipes, new Random(20261020));
                var b = budget(); var answer = refine(recipes, original, stock, Set.of(), Set.of(), true, b);
                check(answer.missing().equals(Map.of("raw", demand - demand / 2)), "Mixed route " + depth + "/" + demand + "/" + ordering + ": " + answer.missing());
                literal(answer, stock);
                // Independent witness and lower bound: each A costs >= 1 raw.
                // Primitive execution of all-Y needs precisely demand raw.
                var witness = mixed(recipes, depth, BigInteger.ZERO, BigInteger.valueOf(demand), stock);
                literal(witness, stock); check(witness.missing().equals(answer.missing()), "All-Y witness mismatch");
            }
    }
    private static void productionEntries() {
        for (int depth : new int[]{64, 512}) for (long demand : new long[]{1, 3, 7})
            for (int ordering = 0; ordering < 3; ordering++) for (String entry : List.of("main", "fallback")) {
                var recipes = routes(depth, demand);
                if (ordering == 1) Collections.reverse(recipes);
                if (ordering == 2) Collections.shuffle(recipes, new Random(20261020));
                var b = budget();
                var compiler = new GraphCompiler<>(recipes);
                var answer = entry.equals("main") ? new GraphPlanner<>(compiler).plan("G", 1, Map.of(), false, true, b) :
                        GraphFallback.plan(compiler, "G", 1, Map.of(), Set.of(), Map.of(), false, true, b);
                check(answer.missing().equals(Map.of("raw", demand)), entry + " route " + depth + "/" + demand + "/" + ordering + ": " + answer.missing());
                check(b.reservedBytes() == 0, "Main workspace"); literal(answer, Map.of());
            }
        // A completed fallback word can bind multiple choices too. Run the
        // public refinement continuation, rather than only the private helper.
        var recipes = routes(8, 3); var old = mixed(recipes, 8, BigInteger.TWO, BigInteger.ONE, Map.of());
        var b = budget();
        try (var work = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), old, Map.of(), Set.of(), Set.of(), true, false, b)) {
            while (!work.step()) {}
            check(work.result().missing().equals(Map.of("raw", 3L)), "Expansion retained mixed counts");
            literal(work.result(), Map.of());
        }
        check(b.reservedBytes() == 0, "Expansion workspace");
    }
    private static void interactions() {
        // Extra coproducts are real obligations, not a local route price.
        var recipes = routes(1, 3);
        recipes.set(1, r("useX", Map.of("X0", 1L), Map.of("A", 1L, "B", 1L)));
        recipes.set(4, r("finish", Map.of("A", 3L, "B", 1L), Map.of("G", 1L)));
        var original = mixed(recipes, 1, BigInteger.ONE, BigInteger.TWO, Map.of());
        var answer = refine(recipes, original, Map.of(), Set.of(), Set.of(), true, budget());
        check(answer == original, "Discarded necessary coproduct"); literal(answer, Map.of());
        // A returned tool is one startup entity, not one new tool per firing.
        recipes = routes(1, 7);
        for (int at : new int[]{1, 3}) {
            var use = recipes.get(at);
            recipes.set(at, r(use.id(), Map.of(at == 1 ? "X0" : "Y0", 1L, "tool", 1L), Map.of("A", 1L, "tool", 1L)));
        }
        var stock = Map.of("tool", 1L, "raw", 2L);
        original = mixed(recipes, 1, BigInteger.valueOf(6), BigInteger.ONE, stock);
        answer = refine(recipes, original, stock, Set.of(), Set.of(), true, budget());
        check(answer.missing().equals(Map.of("raw", 5L)) && answer.initial().get("tool") == 1, "Returned tool multiplied");
        literal(answer, stock);
        // Shared stock across two goals must be evaluated as one whole bill.
        recipes = routes(1, 3);
        recipes.set(2, r("Y0", Map.of("held", 1L), Map.of("Y0", 1L)));
        recipes.set(4, r("finish", Map.of("A", 3L, "held", 2L), Map.of("G", 1L)));
        stock = Map.of("held", 3L);
        original = mixed(recipes, 1, BigInteger.TWO, BigInteger.ONE, stock);
        answer = refine(recipes, original, stock, Set.of(), Set.of(), true, budget());
        check(!answer.missing().containsKey("held"), "Stole shared goal stock");
        literal(answer, stock);
        recipes = routes(1, 3);
        recipes.set(2, r("Y0", Map.of("other", 1L), Map.of("Y0", 1L)));
        original = mixed(recipes, 1, BigInteger.TWO, BigInteger.ONE, Map.of());
        check(refine(recipes, original, Map.of(), Set.of(), Set.of(), true, budget()) == original, "Priced incomparable materials by summation");
    }
    private static void composed() {
        var recipes = new ArrayList<GraphRecipe<String>>(); var steps = new ArrayList<PlanStep>();
        var goals = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 3; i++) {
            for (String branch : List.of("X", "Y")) {
                String key = branch + i;
                recipes.add(r("make" + key, Map.of("raw" + i, branch.equals("X") ? 100L : 1L), Map.of(key, 1L)));
                recipes.add(r("use" + key, Map.of(key, 1L), Map.of("A" + i, 1L)));
                long count = branch.equals("X") ? 2 : 1;
                steps.add(new PlanStep.Batch("make" + key, count)); steps.add(new PlanStep.Batch("use" + key, count));
            }
            goals.put("A" + i, 3L);
        }
        recipes.add(r("finish", goals, Map.of("G", 1L))); steps.add(new PlanStep.Batch("finish", 1));
        var old = RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(steps), Map.of(), Map.of(), Set.of());
        for (int ordering = 0; ordering < 3; ordering++) {
            if (ordering == 1) Collections.reverse(recipes);
            if (ordering == 2) Collections.shuffle(recipes, new Random(20261020));
            var answer = refine(recipes, old, Map.of(), Set.of(), Set.of(), true, budget());
            check(answer.missing().equals(Map.of("raw0", 3L, "raw1", 3L, "raw2", 3L)), "Bound preferences did not compose: " + answer.missing());
            literal(answer, Map.of());
        }
    }
    private static void scopes() {
        var recipes = routes(1, 3); var old = mixed(recipes, 1, BigInteger.TWO, BigInteger.ONE, Map.of());
        check(refine(recipes, old, Map.of(), Set.of(), Set.of("useY"), true, budget()) == old, "Selected excluded bound producer");
        var incompatible = new ArrayList<>(recipes);
        incompatible.set(3, r("useY", Map.of("foreign", 1L), Map.of("A", 1L)));
        check(refine(incompatible, old, Map.of(), Set.of(), Set.of(), true, budget()) == old, "Changed old physical binding");
        var seeded = new GraphPlan<>("G", 1, true, old.steps(), old.recipes(), Map.of("raw", 203L),
                Map.of("raw", 2L), Map.of("raw", 203L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var answer = refine(recipes, seeded, Map.of(), Set.of(), Set.of(), true, budget());
        check(answer.missing().equals(Map.of("raw", 5L)) && answer.seeds().equals(seeded.seeds()), "Consumed final seeds"); literal(answer, Map.of());
        var conditional = GraphPlan.ownedRefill(old, old.steps(), old.recipes(), old.initialExact(), old.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0);
        answer = refine(recipes, conditional, Map.of(), Set.of(), Set.of(), true, budget());
        check(answer.conditionalPreview() && answer.seedOptimality() == null, "Invented coverage or minimum proof");
        recipes.set(0, r("X0", Map.of("raw", 100L), Map.of("X0", 1L, "G", 9L)));
        old = mixed(recipes, 1, BigInteger.TWO, BigInteger.ONE, Map.of());
        answer = refine(recipes, old, Map.of("G", 100L), Set.of(), Set.of(), true, budget());
        check(SequenceSummary.of(answer.steps(), answer.recipes()).delta("G").compareTo(BigInteger.valueOf(19)) >= 0, "Lost original forced production");
        literal(answer, Map.of("G", 100L));
    }
    private static void exactCounts() {
        var recipes = routes(1, 3);
        BigInteger n = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(7));
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> bindings.put(r.id(), r));
        var word = new PlanStep.Sequence(List.of(PlanStep.batch("X0", n), PlanStep.batch("useX", n),
                new PlanStep.Batch("Y0", 1), new PlanStep.Batch("useY", 1), new PlanStep.Batch("finish", 1)));
        var raw = n.multiply(BigInteger.valueOf(100)).add(BigInteger.ONE);
        var old = new GraphPlan<>("G", 1, false, word, bindings, Map.of("raw", raw), Map.of(), Map.of("raw", raw),
                GraphPlan.Result.MISSING_INPUT, 0, 0);
        var answer = refine(recipes, old, Map.of(), Set.of(), Set.of(), true, budget());
        check(answer.missing().equals(Map.of("raw", 3L)), "Did not drop unrequested old intermediate surplus");
        literal(answer, Map.of());
        var producing = new PlanStep.Sequence(List.of(PlanStep.batch("X0", n), PlanStep.batch("useX", n),
                PlanStep.batch("Y0", n.multiply(BigInteger.TWO)), PlanStep.batch("useY", n.multiply(BigInteger.TWO)),
                PlanStep.batch("finish", n)));
        raw = n.multiply(BigInteger.valueOf(102));
        old = new GraphPlan<>("G", 1, false, producing, bindings, Map.of("raw", raw), Map.of(), Map.of("raw", raw),
                GraphPlan.Result.MISSING_INPUT, 0, 0);
        answer = refine(recipes, old, Map.of(), Set.of(), Set.of(), true, budget());
        check(answer.missingExact().equals(Map.of("raw", n.multiply(BigInteger.valueOf(3)))), "Clipped large replacement counts");
        // Closed-form conservation for these unary routes is independent of
        // the verifier's fold: n forced G require precisely 3n cheap raw.
        var summary = SequenceSummary.of(answer.steps(), answer.recipes());
        check(summary.delta("G").equals(n) && summary.delta("raw").equals(n.multiply(BigInteger.valueOf(-3))), "Lost exact production credit");
        PlanVerifier.verify(GraphPlan.ownedRefill(answer, answer.steps(), answer.recipes(), answer.initialExact(),
                Map.of(), GraphPlan.Result.FEASIBLE, 0));
    }
    private static void borrowedOrder() {
        var recipes = routes(8, 3); var old = mixed(recipes, 8, BigInteger.TWO, BigInteger.ONE, Map.of());
        var compiler = new GraphCompiler<>(recipes); var b = budget();
        try (var order = new RefillProducerOrder<>(compiler, Set.of("G"), Set.of(), Set.of(), b)) {
            while (!order.step()) {}
            long held = b.reservedBytes();
            try (var work = RefillSourceSearch.reallocate(compiler, old, Map.of(), Set.of(), Set.of(), true, false, b, order)) {
                while (!work.step()) {}
                check(work.completedOrder() == order && work.result().missing().equals(Map.of("raw", 3L)), "Lost complete support reuse");
                literal(work.result(), Map.of());
            }
            check(b.reservedBytes() == held, "Released or leaked borrowed support");
        }
        check(b.reservedBytes() == 0, "Support owner leaked");
    }
    private static void admission() {
        var recipes = routes(1, 3); var compiler = new GraphCompiler<>(recipes); var b = budget();
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("X0", 3), new PlanStep.Batch("useX", 3), new PlanStep.Batch("finish", 1)));
        var old = RefillWordPruningTest.literalPlan(List.of(recipes.get(0), recipes.get(1), recipes.get(4)),
                word, Map.of(), Map.of(), Set.of());
        try (var work = new MissingMaterialExpansion<>(compiler, old, Map.of(), Set.of(), Set.of(), true, false, b)) {
            while (!work.step()) {}
            check(work.result().missing().equals(Map.of("raw", 3L)), "Displaced earlier unused-source opportunity");
            check(!b.diagnostics().contains("refill_reallocate"), "Immediately repeated completed single-source choices");
            literal(work.result(), Map.of());
        }
        check(b.reservedBytes() == 0, "Admission workspace");
        // Discovery stopped during the long binding scan cannot establish that
        // there is no bound mixture. The later continuation still gets service.
        recipes = routes(512, 3); compiler = new GraphCompiler<>(recipes);
        old = mixed(recipes, 512, BigInteger.ONE, BigInteger.TWO, Map.of()); b = budget();
        try (var prior = new RefillSourceSearch<>(compiler, old, Map.of(), Set.of(), Set.of(), true, false, b)) {
            while (!prior.step()) {}
            check(prior.mayReallocate(), "Truncated discovery used as a negative admission proof");
        }
        check(b.reservedBytes() == 0, "Truncated admission workspace");
    }
    private static String phase(Object work) throws Exception {
        var field = work.getClass().getDeclaredField("phase"); field.setAccessible(true); return field.get(work).toString();
    }
    private static void limits(boolean published) throws Exception {
        var recipes = routes(1, 3); var old = mixed(recipes, 1, BigInteger.TWO, BigInteger.ONE, Map.of());
        for (String stop : published ? List.of("published") : List.of("ORIGINAL", "RANK", "EXPAND", "COMPARE"))
            for (String mode : List.of("work", "time", "memory", "cancel")) {
                var cancelled = new AtomicBoolean(); var clock = new AtomicLong();
                var b = new PlanningBudget(1, 100_000, 1L << 20, cancelled::get, clock::get); long held = 0;
                try (var work = RefillSourceSearch.reallocate(new GraphCompiler<>(recipes), old, Map.of(), Set.of(), Set.of(), true, false, b, null)) {
                    for (int i = 0; published ? work.result() == old : !phase(work).equals(stop); i++)
                        check(i < 1000 && !work.step(), "Never reached " + stop);
                    var retained = work.result();
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork());
                        case "time" -> clock.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                        case "cancel" -> cancelled.set(true);
                    }
                    try {
                        while (!work.step()) {}
                        check(!mode.equals("cancel"), "Swallowed cancellation");
                        check(work.result() == retained || mode.equals("memory"), "Lost complete publication");
                    } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                    literal(work.result(), Map.of());
                } finally { b.release(held); }
                check(b.reservedBytes() == 0, "Interrupted workspace " + stop + "/" + mode);
            }
    }
    private static void lateBinding() {
        var recipes = routes(512, 3); var old = mixed(recipes, 512, BigInteger.TWO, BigInteger.ONE, Map.of());
        var bindings = new LinkedHashMap<>(old.recipes());
        bindings.put("late-config", new GraphRecipe<>("late-config", "late-config",
                List.of(new GraphRecipe.Slot<>("config", 1, 0, true, false)), Map.of("unused", 1L)));
        var guarded = GraphPlan.ownedRefill(old, old.steps(), bindings, old.initialExact(), old.missingExact(), old.result(), 0);
        check(refine(recipes, guarded, Map.of(), Set.of(), Set.of(), true, budget()) == guarded, "Skipped late per-push binding");
    }
    private static void ownerPublication() throws Exception {
        var recipes = routes(1, 3); var old = mixed(recipes, 1, BigInteger.TWO, BigInteger.ONE, Map.of());
        var b = budget();
        try (var work = new MissingMaterialExpansion<>(new GraphCompiler<>(recipes), old, Map.of(), Set.of(), Set.of(), true, false, b)) {
            var field = MissingMaterialExpansion.class.getDeclaredField("reallocation"); field.setAccessible(true);
            for (int i = 0; work.result() == null || !work.result().missing().equals(Map.of("raw", 3L)); i++)
                check(i < 1000 && !work.step(), "Parent failed to expose allocation publication");
            check(field.get(work) != null, "Did not exercise bound allocation");
            var retained = work.result(); b.charge(b.remainingWork());
            try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) {}
            // Boundary annotation has its own quota and may wrap the same word.
            check(work.result().steps() == retained.steps() && work.result().missingExact().equals(retained.missingExact()) &&
                    work.result().initialExact().equals(retained.initialExact()), "Parent lost accepted allocation at diagnostic cutoff");
            literal(work.result(), Map.of());
        }
        check(b.reservedBytes() == 0, "Parent allocation workspace");
    }
    private static void scheduler() throws Exception {
        var recipes = routes(512, 3); var old = mixed(recipes, 512, BigInteger.TWO, BigInteger.ONE, Map.of()); var b = budget();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var work = RefillSourceSearch.reallocate(new GraphCompiler<>(recipes), old, Map.of(), Set.of(), Set.of(), true, false, b, null);
             var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
            var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                boolean started;
                public boolean advance(PlanningScheduler.Slice slice) {
                    if (!started) {
                        started = true; entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Gate"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                    while (slice.nextUncharged()) if (work.step()) return true;
                    return false;
                }
                public GraphPlan<String> result() { return work.result(); }
            }, b);
            check(entered.await(5, TimeUnit.SECONDS), "Worker not started");
            var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                public boolean advance(PlanningScheduler.Slice slice) { return true; }
                public Long result() { return b.nodes(); }
            }, budget());
            release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 128, "Long binding scan monopolized worker");
            var answer = future.get(20, TimeUnit.SECONDS);
            check(answer.missing().equals(Map.of("raw", 3L)), "Scheduled allocation"); literal(answer, Map.of());
        } finally { release.countDown(); }
        check(b.reservedBytes() == 0, "Scheduled workspace");
    }
}
