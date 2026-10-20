// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Interior coproduct mixtures, checked against primitive words and exact conservation. */
public final class RefillJointSourceTest {
    private static int checks;
    private static void check(boolean c, String detail) { checks++; if (!c) throw new AssertionError(detail); }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static PlanningBudget budget() { return MissingMaterialExpansionTest.budget(4_000_000); }
    private static List<GraphRecipe<String>> routes(long quantity, boolean tool) {
        var x = new LinkedHashMap<>(Map.of("X", 1L)); var y = new LinkedHashMap<>(Map.of("Y", 1L));
        var a = new LinkedHashMap<>(Map.of("A", 3L, "B", 1L)); var b = new LinkedHashMap<>(Map.of("A", 1L, "B", 3L));
        if (tool) { x.put("tool", 1L); y.put("tool", 1L); a.put("tool", 1L); b.put("tool", 1L); }
        return new ArrayList<>(List.of(r("makeX", Map.of("raw", 1L), Map.of("X", 1L)),
                r("makeY", Map.of("raw", 1L), Map.of("Y", 1L)), r("useX", x, a), r("useY", y, b),
                r("finish", Map.of("A", 4 * quantity, "B", 4 * quantity), Map.of("G", 1L))));
    }
    private static GraphPlan<String> solve(List<GraphRecipe<String>> recipes, Map<String, Long> stock, String entry) {
        var compiler = new GraphCompiler<>(recipes); var b = budget(); GraphPlan<String> answer;
        if (entry.equals("main")) answer = new GraphPlanner<>(compiler).plan("G", 1, stock, false, true, b);
        else if (entry.equals("fallback")) answer = GraphFallback.plan(compiler, "G", 1, stock, Set.of(), Map.of(), false, true, b);
        else {
            var finish = recipes.stream().filter(r -> r.id().equals("finish")).findFirst().orElseThrow();
            try (var work = new MissingMaterialExpansion<>(compiler, MissingMaterialExpansionTest.partial(finish, stock),
                    stock, Set.of(), Set.of(), true, false, b)) {
                while (!work.step()) {}
                answer = work.result();
            }
        }
        check(b.reservedBytes() == 0, "Entry workspace " + entry);
        RefillWordPruningTest.literalVerify(answer, stock, Set.of());
        return answer;
    }
    public static void main(String[] args) throws Exception {
        entries(); exactCorners(); scopes(); largeCounts(); limits(); scheduler();
        System.out.println("RefillJointSourceTest: assertions=" + checks);
    }
    private static void entries() {
        for (long quantity : new long[]{1, 3, 17}) for (int order = 0; order < 3; order++)
            for (boolean tool : List.of(false, true)) for (String entry : List.of("expand", "fallback", "main")) {
                var recipes = routes(quantity, tool);
                if (order == 1) Collections.reverse(recipes);
                if (order == 2) Collections.shuffle(recipes, new Random(20261020));
                var stock = tool ? Map.of("tool", 1L, "raw", 1L) : Map.<String, Long>of();
                var answer = solve(recipes, stock, entry);
                long raw = 2 * quantity - (tool ? 1 : 0);
                check(answer.missing().equals(Map.of("raw", raw)), "Mixture " + quantity + "/" + order + "/" + tool + "/" + entry + ": " + answer.missing());
                if (tool) check(answer.initial().get("tool") == 1L, "Multiplied shared startup entity");
                // Each source creates four total A+B. The target consumes
                // eight per unit, so 2q is a lower bound attained by q of each.
                var witness = new PlanStep.Sequence(List.of(new PlanStep.Batch("makeX", quantity), new PlanStep.Batch("makeY", quantity),
                        new PlanStep.Batch("useX", quantity), new PlanStep.Batch("useY", quantity), new PlanStep.Batch("finish", 1)));
                var independentlyFunded = RefillWordPruningTest.literalPlan(recipes, witness, Map.of(), stock, Set.of());
                RefillWordPruningTest.literalVerify(independentlyFunded, stock, Set.of());
                check(independentlyFunded.missing().equals(answer.missing()), "Conservation witness mismatch");
            }
        var direct = List.of(r("x", Map.of("raw", 1L), Map.of("A", 3L, "B", 1L)),
                r("y", Map.of("raw", 1L), Map.of("A", 1L, "B", 3L)),
                r("finish", Map.of("A", 4L, "B", 4L), Map.of("G", 1L)));
        for (String entry : List.of("expand", "fallback", "main")) {
            check(solve(direct, Map.of(), entry).missing().equals(Map.of("raw", 2L)), "One-hop joint sources " + entry);
            var answer = solve(routes(1, true), Map.of(), entry);
            check(answer.missing().equals(Map.of("raw", 2L, "tool", 1L)), "Missing returned tool must be funded once " + entry);
        }
    }
    private static void exactCorners() {
        // Enumerated small integer reference: the heuristic may miss an optimum,
        // but every emitted pair must obey all original rows and prefix funding.
        var random = new Random(20261020); int emitted = 0;
        for (int n = 0; n < 192; n++) {
            long a = 1 + random.nextInt(7), b = 1 + random.nextInt(7), c = 1 + random.nextInt(7), d = 1 + random.nextInt(7);
            long e = 1 + random.nextInt(7), f = 1 + random.nextInt(7);
            long u = 1 + random.nextInt(31), v = 1 + random.nextInt(31), w = 1 + random.nextInt(31);
            var first = r("first", Map.of("x", 1L), Map.of("A", a, "B", b, "C", e));
            var second = r("second", Map.of("y", 1L), Map.of("A", c, "B", d, "C", f));
            var needs = Map.of("A", BigInteger.valueOf(u), "B", BigInteger.valueOf(v), "C", BigInteger.valueOf(w)); var budget = budget();
            try (var work = new RefillPairCounts<>(new RefillPairCounts.Choice<>("A", first, second), k -> needs.getOrDefault(k, BigInteger.ZERO), budget)) {
                while (!work.step()) {}
                if (work.summary() == null) continue;
                emitted++;
                var word = (PlanStep.Sequence) work.word();
                long x = ((PlanStep.Batch) word.children().get(0)).runs(), y = ((PlanStep.Batch) word.children().get(1)).runs();
                check(x > 0 && y > 0 && a * x + c * y >= u && b * x + d * y >= v && e * x + f * y >= w, "Invalid rounded corner");
                long oracle = Long.MAX_VALUE;
                for (long i = 1; i <= 32; i++) for (long j = 1; j <= 32; j++)
                    if (a * i + c * j >= u && b * i + d * j >= v && e * i + f * j >= w) oracle = Math.min(oracle, i + j);
                check(x + y >= oracle, "Improvement below exhaustive optimum");
                var plan = RefillWordPruningTest.literalPlan(List.of(first, second, r("finish", Map.of("A", u, "B", v, "C", w), Map.of("G", 1L))),
                        new PlanStep.Sequence(List.of(work.word(), new PlanStep.Batch("finish", 1))), Map.of(), Map.of(), Set.of());
                RefillWordPruningTest.literalVerify(plan, Map.of(), Set.of());
            }
            check(budget.reservedBytes() == 0, "Corner workspace");
        }
        check(emitted == 192, "Unexpected corner admission loss");
    }
    private static GraphPlan<String> old(List<GraphRecipe<String>> recipes) {
        return RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(List.of(
                new PlanStep.Batch("makeX", 2), new PlanStep.Batch("makeY", 1),
                new PlanStep.Batch("useX", 2), new PlanStep.Batch("useY", 1), new PlanStep.Batch("finish", 1))),
                Map.of(), Map.of(), Set.of());
    }
    private static GraphPlan<String> refine(List<GraphRecipe<String>> recipes, GraphPlan<String> old, Set<String> excluded, Set<String> external, PlanningBudget budget) {
        GraphPlan<String> result;
        try (var work = RefillSourceSearch.joint(new GraphCompiler<>(recipes), old, Map.of(), external, excluded, true, false, budget, null)) {
            while (!work.step()) {}
            result = work.result();
        }
        check(budget.reservedBytes() == 0, "Joint owner leak");
        return result;
    }
    private static void scopes() {
        var recipes = routes(1, false); var original = old(recipes);
        check(refine(recipes, original, Set.of("useY"), Set.of(), budget()) == original, "Excluded producer");
        var altered = new ArrayList<>(recipes); altered.set(3, r("useY", Map.of("foreign", 1L), Map.of("A", 1L, "B", 3L)));
        check(refine(altered, original, Set.of(), Set.of(), budget()) == original, "Rebound physical ID");
        var seeded = new GraphPlan<>("G", 1, false, original.steps(), original.recipes(), Map.of("raw", 5L),
                Map.of("raw", 2L), Map.of("raw", 5L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var answer = refine(recipes, seeded, Set.of(), Set.of(), budget());
        check(answer.missing().equals(Map.of("raw", 4L)) && answer.seeds().equals(seeded.seeds()), "Consumed final seed");
        RefillWordPruningTest.literalVerify(answer, Map.of(), Set.of());
        var conditional = GraphPlan.ownedRefill(original, original.steps(), original.recipes(), original.initialExact(),
                original.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0);
        answer = refine(recipes, conditional, Set.of(), Set.of(), budget());
        check(answer.conditionalPreview() && answer.seedOptimality() == null, "Invented full coverage or optimality");
        var bindings = new LinkedHashMap<>(original.recipes());
        bindings.put("late", new GraphRecipe<>("late", "late", List.of(new GraphRecipe.Slot<>("tool", 1, 0, true, false)), Map.of("Z", 1L)));
        var sensitive = GraphPlan.ownedRefill(original, original.steps(), bindings, original.initialExact(), original.missingExact(), original.result(), 0);
        check(refine(recipes, sensitive, Set.of(), Set.of(), budget()) == sensitive, "Changed per-push contract");
        // A cheap mixture consumes one raw type of each; summing unlike inputs
        // would falsely dominate the original homogeneous missing vector.
        altered = new ArrayList<>(recipes); altered.set(1, r("makeY", Map.of("other", 1L), Map.of("Y", 1L)));
        var homogeneous = RefillWordPruningTest.literalPlan(altered, new PlanStep.Sequence(List.of(new PlanStep.Batch("makeX", 4),
                new PlanStep.Batch("useX", 4), new PlanStep.Batch("finish", 1))), Map.of(), Map.of(), Set.of());
        check(refine(altered, homogeneous, Set.of(), Set.of(), budget()) == homogeneous, "Summed incomparable bills");
        // Lossy cyclic cross-consumption is deliberately declined by this arm.
        var budget = budget();
        try (var work = new RefillPairCounts<>(new RefillPairCounts.Choice<>("A",
                r("x", Map.of("B", 2L), Map.of("A", 3L)), r("y", Map.of("A", 2L), Map.of("B", 3L))),
                k -> BigInteger.valueOf(4), budget)) {
            while (!work.step()) {}
            check(work.summary() == null, "Signed corner rounding admitted");
        }
        check(budget.reservedBytes() == 0, "Signed-row workspace");
    }
    private static void largeCounts() {
        var recipes = routes(1, false); BigInteger q = BigInteger.ONE.shiftLeft(80).add(BigInteger.valueOf(13));
        var word = new PlanStep.Sequence(List.of(PlanStep.batch("makeX", q.multiply(BigInteger.TWO)), PlanStep.batch("makeY", q),
                PlanStep.batch("useX", q.multiply(BigInteger.TWO)), PlanStep.batch("useY", q), PlanStep.batch("finish", q)));
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> bindings.put(r.id(), r));
        var old = new GraphPlan<>("G", 1, false, word, bindings, Map.of("raw", q.multiply(BigInteger.valueOf(3))),
                Map.of(), Map.of("raw", q.multiply(BigInteger.valueOf(3))), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var answer = refine(recipes, old, Set.of(), Set.of(), budget());
        check(answer.missingExact().equals(Map.of("raw", q.multiply(BigInteger.TWO))), "Large mixture counts narrowed");
        var counts = answer.patternTimesExact();
        for (var r : recipes) check(counts.get(r.id()).equals(q), "Lost exact forced credit " + r.id());
        PlanVerifier.verify(GraphPlan.ownedRefill(answer, answer.steps(), answer.recipes(), answer.initialExact(), Map.of(), GraphPlan.Result.FEASIBLE, 0));
    }
    private static void limits() throws Exception {
        for (boolean published : List.of(false, true)) for (String stop : List.of("work", "memory", "time", "cancel")) {
            var clock = new AtomicLong(); var cancel = new AtomicBoolean();
            var budget = new PlanningBudget(1, 100_000, 1L << 20, cancel::get, clock::get);
            var recipes = routes(1, false); var original = old(recipes); long held = 0;
            try (var work = RefillSourceSearch.joint(new GraphCompiler<>(recipes), original, Map.of(), Set.of(), Set.of(), true, false, budget, null)) {
                if (published) for (int n = 0; work.result() == original; n++) check(n < 1000 && !work.step(), "No joint publication");
                else {
                    var child = RefillSourceSearch.class.getDeclaredField("expansion"); child.setAccessible(true);
                    var pair = MissingMaterialExpansion.class.getDeclaredField("pair"); pair.setAccessible(true);
                    for (int n = 0; child.get(work) == null || pair.get(child.get(work)) == null; n++)
                        check(n < 1000 && !work.step(), "Never reached pair continuation");
                }
                var retained = work.result();
                switch (stop) {
                    case "work" -> budget.charge(budget.remainingWork());
                    case "memory" -> { held = budget.availableBytes(); budget.reserve(held); }
                    case "time" -> clock.set(2_000_000);
                    case "cancel" -> cancel.set(true);
                }
                try {
                    while (!work.step()) {}
                    check(!stop.equals("cancel"), "Swallowed cancellation");
                    check(work.result() == retained || stop.equals("memory"), "Lost incumbent");
                } catch (CancellationException expected) { check(stop.equals("cancel"), "Unexpected cancellation"); }
                RefillWordPruningTest.literalVerify(work.result(), Map.of(), Set.of());
            } finally { budget.release(held); }
            check(budget.reservedBytes() == 0, "Interrupted joint workspace " + stop);
        }
    }
    private static void scheduler() throws Exception {
        var recipes = routes(17, false); var b = budget();
        try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
            var compiler = new GraphCompiler<>(recipes);
            var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                final GraphPlanningWork<String> work = new GraphPlanningWork<>(compiler, "G", 1, Map.of(), false, true, b);
                public boolean advance(PlanningScheduler.Slice slice) {
                    while (slice.nextUncharged()) if (work.step()) return true;
                    return false;
                }
                public GraphPlan<String> result() { return work.result(); }
                public void close() { work.close(); }
            }, b);
            var answer = future.get(20, TimeUnit.SECONDS);
            check(answer.missing().equals(Map.of("raw", 34L)), "Scheduled joint sources");
            RefillWordPruningTest.literalVerify(answer, Map.of(), Set.of());
        }
        check(b.reservedBytes() == 0, "Scheduled owner workspace");
    }
}
