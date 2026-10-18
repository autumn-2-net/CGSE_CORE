// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Independent literal-prefix oracles for whole-word refill reduction. */
public final class RefillWordPruningTest {
    private static int checks;
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    public static void main(String[] args) throws Exception {
        growth(); coproductsAndCredit(); nestedAndLarge(); contracts(); randomWords(); summaryReuse(); lifecycle(); scheduler(false); scheduler(true);
        System.out.println("RefillWordPruningTest: assertions=" + checks);
    }
    private static List<GraphRecipe<String>> growthRecipes() {
        return List.of(r("grow", Map.of("A", 1000L, "raw", 1L), Map.of("A", 1001L)), r("finish", Map.of("A", 1001L), Map.of("G", 1L)));
    }
    private static PlanStep word(long grow) { return new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", grow), new PlanStep.Batch("finish", 1))); }
    private static void growth() {
        var recipes = growthRecipes(); var compiler = new GraphCompiler<>(recipes);
        for (long seed : new long[] {0, 1000}) for (long held : new long[] {0, 1000}) for (boolean force : List.of(false, true)) {
            var stock = Map.of("A", held); var seeds = seed == 0 ? Map.<String, Long>of() : Map.of("A", seed);
            var old = literalPlan(recipes, word(1001 + seed), seeds, stock, Set.of());
            var p = prune(old, stock, Set.of(), force);
            check(p.patternTimesExact().equals(Map.of("grow", BigInteger.valueOf(seed + 1), "finish", ONE)), "Growth count " + p.patternTimesExact());
            check(p.missingExact().equals(held == 0 ? Map.of("A", BigInteger.valueOf(1000), "raw", BigInteger.valueOf(seed + 1)) : Map.of("raw", BigInteger.valueOf(seed + 1))), "Growth bill");
            var b = MissingMaterialExpansionTest.budget(100_000);
            check(MissingMaterialExpansion.needed(compiler, old), "Terminal-only raw shortage bypassed whole-word refinement");
            try (var expansion = new MissingMaterialExpansion<>(compiler, old, stock, Set.of(), Set.of(), force, false, b)) {
                while (!expansion.step()) {}
                check(expansion.result().missingExact().equals(p.missingExact()), "Expansion integration");
            }
            check(b.reservedBytes() == 0, "Expansion workspace");
        }
        for (boolean force : List.of(false, true)) {
            var b = MissingMaterialExpansionTest.budget(4_000_000);
            var work = new GraphPlanningWork<>(compiler, "G", 1, Map.of(), false, force, b);
            try {
                while (!work.step()) {}
                check(work.result().missing().equals(Map.of("A", 1000L, "raw", 1L)), "Main solver retained dominated suffix: " + work.result().missing());
                literalVerify(work.result(), Map.of(), Set.of());
            } finally { work.close(); }
            check(b.reservedBytes() == 0, "Main workspace");
            b = MissingMaterialExpansionTest.budget(100_000);
            var fallback = GraphFallback.plan(compiler, "G", 1, Map.of(), Set.of(), Map.of(), false, force, b);
            check(fallback.missing().equals(Map.of("A", 1000L, "raw", 1L)), "Fallback suffix");
            literalVerify(fallback, Map.of(), Set.of()); check(b.reservedBytes() == 0, "Fallback workspace");
        }
    }
    private static void coproductsAndCredit() {
        var grow = r("grow", Map.of("A", 10L, "raw", 1L), Map.of("A", 11L, "B", 1L));
        var finish = r("finish", Map.of("A", 11L, "B", 11L), Map.of("G", 1L));
        var original = literalPlan(List.of(grow, finish), word(100), Map.of(), Map.of(), Set.of());
        var p = prune(original, Map.of(), Set.of(), true);
        check(p.patternTimesExact().get("grow").equals(BigInteger.valueOf(11)), "Lost useful coproduct");
        check(p.missing().equals(Map.of("A", 10L, "raw", 11L)), "Coproduct bill");
        grow = r("grow", Map.of("A", 10L, "raw", 1L), Map.of("A", 11L, "G", 1L));
        finish = r("finish", Map.of("A", 11L), Map.of("G", 1L));
        original = literalPlan(List.of(grow, finish), word(100), Map.of(), Map.of(), Set.of());
        check(prune(original, Map.of(), Set.of(), true) == original, "Discarded existing forced-production credit");
        check(prune(original, Map.of(), Set.of(), false).patternTimesExact().get("grow").equals(ONE), "Ordinary target surplus not pruned");
        var complexForce = new GraphPlan<>("G", 200, false, original.steps(), original.recipes(),
                Map.of("A", 10L, "raw", 100L, "G", 99L), Map.of(), Map.of("A", 10L, "raw", 100L, "G", 99L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        check(prune(complexForce, Map.of(), Set.of(), true) == complexForce, "Rewrote a forced plan needing a separate count proof");
    }
    private static void nestedAndLarge() {
        var grow = r("grow", Map.of("A", 10L, "raw", 1L), Map.of("A", 11L));
        var side = r("side", Map.of("R", 1L), Map.of("B", 1L));
        var finish = r("finish", Map.of("A", 11L, "B", 1L), Map.of("G", 1L));
        var body = new PlanStep.Sequence(List.of(new PlanStep.Batch("grow", 1), new PlanStep.Batch("side", 1)));
        PlanStep nested = new PlanStep.Sequence(List.of(new PlanStep.Repeat(body, 100), new PlanStep.Batch("finish", 1)));
        var p = prune(literalPlan(List.of(grow, side, finish), nested, Map.of(), Map.of(), Set.of()), Map.of(), Set.of(), true);
        check(p.patternTimesExact().equals(Map.of("grow", ONE, "side", ONE, "finish", ONE)), "Composite repeat did not stay executable");
        var shared = new PlanStep.Sequence(List.of(new PlanStep.Repeat(body, 0), body, body, new PlanStep.Batch("grow", 0), new PlanStep.Batch("finish", 1)));
        p = prune(literalPlan(List.of(grow, side, finish), shared, Map.of(), Map.of(), Set.of()), Map.of(), Set.of(), true);
        check(p.patternTimesExact().get("grow").equals(ONE), "Shared word counted as one identity occurrence");
        var recipes = growthRecipes(); PlanStep deep = word(1001);
        for (int i = 0; i < 300; i++) deep = new PlanStep.Sequence(List.of(deep));
        var base = literalPlan(recipes, word(1001), Map.of(), Map.of(), Set.of());
        var deepPlan = new GraphPlan<>("G", 1, false, deep, base.recipes(), base.initialExact(), Map.of(), base.missingExact(), GraphPlan.Result.MISSING_INPUT, 0, 0);
        p = prune(deepPlan, Map.of(), Set.of(), true);
        check(p.patternTimesExact().get("grow").equals(ONE), "Deep sequence was not traversed");
        var huge = BigInteger.valueOf(Long.MAX_VALUE).pow(2).add(BigInteger.valueOf(17));
        var large = new PlanStep.Sequence(List.of(PlanStep.batch("grow", huge), new PlanStep.Batch("finish", 1)));
        var initial = Map.of("A", BigInteger.valueOf(1000), "raw", huge);
        var largePlan = new GraphPlan<>("G", 1, false, large, base.recipes(), initial, Map.of(), initial, GraphPlan.Result.MISSING_INPUT, 0, 0);
        p = prune(largePlan, Map.of(), Set.of(), true);
        check(p.patternTimesExact().equals(Map.of("grow", ONE, "finish", ONE)), "Exact nested counts clipped or unrolled");
        literalVerify(p, Map.of(), Set.of());
    }
    private static void contracts() {
        var recipes = growthRecipes();
        var original = literalPlan(recipes, word(1001), Map.of("raw", 5L, "G", 2L), Map.of(), Set.of("raw"));
        var p = prune(original, Map.of(), Set.of("raw"), true);
        check(!p.missingExact().containsKey("raw") && p.seeds().equals(original.seeds()), "External supply or final seed changed");
        var incomplete = new GraphPlan<>("G", 1, true, original.steps(), original.recipes(), original.initialExact(), original.seeds(), original.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL, 0, 0);
        check(prune(incomplete, Map.of(), Set.of("raw"), true).result() == GraphPlan.Result.INCOMPLETE_MODEL, "Local rewrite promoted incomplete coverage");
        var slots = List.of(new GraphRecipe.Slot<>("A", 10L), new GraphRecipe.Slot<>("tool", 1L, 0, true));
        var sensitive = new GraphRecipe<>("grow", "physical", slots, Map.of("A", 11L));
        var finish = r("finish", Map.of("A", 11L), Map.of("G", 1L));
        original = literalPlan(List.of(sensitive, finish), word(100), Map.of(), Map.of(), Set.of());
        check(prune(original, Map.of(), Set.of(), true) == original, "Changed consumed-configuration push boundaries");
        var reusable = new GraphRecipe<>("grow", "physical", List.of(new GraphRecipe.Slot<>("A", 10L), new GraphRecipe.Slot<>("tool", 1L, 0, true, true)), Map.of("A", 11L, "tool", 1L));
        original = literalPlan(List.of(reusable, finish), word(100), Map.of("tool", 1L), Map.of(), Set.of());
        p = prune(original, Map.of(), Set.of(), true);
        check(p.patternTimesExact().get("grow").equals(ONE) && p.initial().get("tool") == 1L, "Reusable tool startup lost");
        check(p.recipes().get("grow") == reusable && p.recipes().get("grow").binding().equals("physical"), "Physical recipe binding changed");
        try { p.initialExact().clear(); throw new AssertionError("Mutable publication"); } catch (UnsupportedOperationException expected) { checks++; }
    }
    private static void randomWords() {
        var random = new Random(19102326); int improved = 0;
        for (int test = 0; test < 500; test++) {
            var recipes = new ArrayList<GraphRecipe<String>>(); var batches = new ArrayList<PlanStep>();
            // This guarantees the applicability filter without constraining
            // the random suffix to the compiler's own recipe-selection logic.
            recipes.add(r("feedback", Map.of("A", 2L, "raw", 1L), Map.of("A", 3L)));
            batches.add(new PlanStep.Batch("feedback", 1 + random.nextInt(12)));
            for (int j = 0; j < 8; j++) {
                var in = new LinkedHashMap<String, Long>(); var out = new LinkedHashMap<String, Long>();
                for (String k : List.of("A", "B", "C")) {
                    if (random.nextBoolean()) in.put(k, 1L + random.nextInt(3));
                    if (random.nextBoolean()) out.put(k, 1L + random.nextInt(4));
                }
                if (out.isEmpty()) out.put("A", 1L);
                recipes.add(r("r" + j, in, out)); batches.add(new PlanStep.Batch("r" + j, 1 + random.nextInt(6)));
            }
            recipes.add(r("finish", Map.of("A", 1L + random.nextInt(6), "B", 1L + random.nextInt(6)), Map.of("G", 1L)));
            batches.add(new PlanStep.Batch("finish", 1));
            var seeds = Map.of("A", (long) random.nextInt(5), "C", (long) random.nextInt(5));
            var stock = Map.of("A", (long) random.nextInt(8), "B", (long) random.nextInt(8), "C", (long) random.nextInt(8));
            var external = test % 7 == 0 ? Set.of("raw") : Set.<String>of();
            var original = literalPlan(recipes, new PlanStep.Sequence(batches), seeds, stock, external);
            var p = prune(original, stock, external, test % 2 == 0);
            literalVerify(p, stock, external); if (p != original) improved++;
            for (var e : p.initialExact().entrySet()) check(e.getValue().compareTo(original.initialExact().getOrDefault(e.getKey(), ZERO)) <= 0, "Increased initial inventory");
            for (var e : p.missingExact().entrySet()) check(e.getValue().compareTo(original.missingExact().getOrDefault(e.getKey(), ZERO)) <= 0, "Increased supply bill");
        }
        check(improved > 250, "Random witnesses never exercised reduction");
    }
    private static void lifecycle() {
        var old = literalPlan(growthRecipes(), word(1001), Map.of(), Map.of(), Set.of());
        for (String mode : List.of("work", "time", "memory", "cancel")) for (int stop = 0; stop < 45; stop++) {
            var time = new AtomicLong(); var cancelled = new AtomicBoolean();
            var b = new PlanningBudget(1, 100_000, 1L << 20, cancelled::get, time::get); long held = 0;
            b.failureDetail("retained proof");
            try (var pruning = new RefillWordPruning<>(old, Map.of(), Set.of(), true, false, b, 8192)) {
                for (int i = 0; ; i++) {
                    if (i == stop) switch (mode) {
                        case "work" -> b.charge(b.remainingWork());
                        case "time" -> time.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                        case "cancel" -> cancelled.set(true);
                    }
                    if (pruning.step()) { literalVerify(pruning.result(), Map.of(), Set.of()); break; }
                }
            } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
            finally { b.release(held); }
            check(b.reservedBytes() == 0, "Optional workspace leak " + mode);
            check(b.failureDetail().equals("retained proof"), "Optional limit erased original diagnostics");
        }
        for (long cap : new long[] {0, 1, 32, 64, 128, 512}) {
            var b = MissingMaterialExpansionTest.budget(100_000);
            try (var pruning = new RefillWordPruning<>(old, Map.of(), Set.of(), true, false, b, cap)) {
                while (!pruning.step()) {}
                literalVerify(pruning.result(), Map.of(), Set.of());
                check(b.nodes() <= cap + 64, "Optional allowance overran a slice");
            }
            check(b.reservedBytes() == 0, "Soft-limit leak");
        }
        var compiler = new GraphCompiler<>(growthRecipes()); var stock = Map.of("A", 1000L);
        var terminal = literalPlan(growthRecipes(), word(1001), Map.of(), stock, Set.of());
        var b = MissingMaterialExpansionTest.budget(1); b.charge(1);
        try (var expansion = new MissingMaterialExpansion<>(compiler, terminal, stock, Set.of(), Set.of(), true, false, b)) {
            check(expansion.step() && expansion.result() == terminal, "Terminal bill lost at an exhausted entry boundary");
        }
        check(b.reservedBytes() == 0, "Terminal entry workspace");
    }
    private static void summaryReuse() {
        var original = literalPlan(growthRecipes(), word(1001), Map.of(), Map.of(), Set.of());
        long ordinary = 0;
        for (boolean reuse : List.of(false, true)) {
            var b = MissingMaterialExpansionTest.budget(100_000);
            var funded = new GraphPlan<>(original.target(), original.amount(), original.preserveSeeds(), original.steps(), original.recipes(),
                    original.initialExact(), original.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0, 0);
            try (var verification = new PlanVerification<>(funded, b)) {
                while (!verification.step()) {}
                long before = b.nodes();
                try (var pruning = new RefillWordPruning<>(original, Map.of(), Set.of(), true, false, b, 8192, reuse ? verification.summary() : null)) {
                    while (!pruning.step()) {}
                    check(pruning.result().missing().equals(Map.of("A", 1000L, "raw", 1L)), "Borrowed fold changed the result");
                    literalVerify(pruning.result(), Map.of(), Set.of());
                }
                long used = b.nodes() - before;
                if (reuse) check(used < ordinary, "Completed verifier was folded again"); else ordinary = used;
                check(verification.summary().delta("G").equals(ONE), "Borrowed summary mutated");
            }
            check(b.reservedBytes() == 0, "Borrowed summary ownership leaked");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var in = new LinkedHashMap<String, Long>(); in.put("A", 10L);
        for (int i = 0; i < 512; i++) in.put("raw" + i, 1L);
        var recipes = List.of(r("grow", in, Map.of("A", 11L)), r("finish", Map.of("A", 11L), Map.of("G", 1L)));
        var old = literalPlan(recipes, word(100), Map.of(), Map.of(), Set.of());
        var cancelled = new AtomicBoolean(); var b = new PlanningBudget(0, 100_000, 8L << 20, cancelled::get, System::nanoTime);
        var phase = RefillWordPruning.class.getDeclaredField("phase"); phase.setAccessible(true);
        try (var pruning = new RefillWordPruning<>(old, Map.of(), Set.of(), true, false, b, 50_000)) {
            for (int i = 0; !phase.get(pruning).toString().equals("SUPPORT"); i++) check(i < 10_000 && !pruning.step(), "No wide pruning handoff");
            long before = b.nodes(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) {
                            started = true; entered.countDown();
                            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Handoff timeout"); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        }
                        while (slice.nextUncharged()) if (pruning.step()) return true;
                        return false;
                    }
                    public GraphPlan<String> result() { return pruning.result(); }
                    public void close() { pruning.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Scheduler did not start");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long observed;
                    public boolean advance(PlanningScheduler.Slice slice) { observed = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return observed; }
                }, MissingMaterialExpansionTest.budget(1000));
                release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 64, "Pruning monopolized worker");
                try { var p = future.get(10, TimeUnit.SECONDS); check(!cancel, "Ignored cancellation"); literalVerify(p, Map.of(), Set.of()); }
                catch (CancellationException e) { check(cancel, "Unexpected cancellation"); }
                catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Unexpected scheduler failure " + e); }
            } finally { release.countDown(); }
        }
        check(b.reservedBytes() == 0, "Scheduled workspace leaked");
    }
    private static GraphPlan<String> prune(GraphPlan<String> original, Map<String, Long> stock, Set<String> external, boolean force) {
        var b = MissingMaterialExpansionTest.budget(100_000); GraphPlan<String> result;
        try (var pruning = new RefillWordPruning<>(original, stock, external, force, false, b, 8192)) {
            while (!pruning.step()) {} result = pruning.result();
        }
        check(b.reservedBytes() == 0, "Pruning workspace"); return result;
    }
    static GraphPlan<String> literalPlan(List<GraphRecipe<String>> recipes, PlanStep word, Map<String, Long> seeds,
                                                 Map<String, Long> stock, Set<String> external) {
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> bindings.put(r.id(), r));
        var balance = new HashMap<String, BigInteger>(); var need = new LinkedHashMap<String, BigInteger>();
        for (String id : literalWord(word)) {
            var recipe = bindings.get(id);
            recipe.inputs().forEach((k, n) -> {
                balance.merge(k, BigInteger.valueOf(n).negate(), BigInteger::add);
                need.merge(k, balance.get(k).negate().max(ZERO), BigInteger::max);
            });
            recipe.outputs().forEach((k, n) -> balance.merge(k, BigInteger.valueOf(n), BigInteger::add));
        }
        var goals = new HashMap<String, Long>(seeds); goals.merge("G", 1L, Long::sum);
        goals.forEach((k, n) -> need.merge(k, BigInteger.valueOf(n).subtract(balance.getOrDefault(k, ZERO)).max(ZERO), BigInteger::max));
        need.values().removeIf(n -> n.signum() == 0); var missing = new LinkedHashMap<String, BigInteger>();
        need.forEach((k, n) -> { var d = n.subtract(BigInteger.valueOf(stock.getOrDefault(k, 0L))); if (d.signum() > 0 && !external.contains(k)) missing.put(k, d); });
        return new GraphPlan<>("G", 1, !seeds.isEmpty(), word, bindings, need, seeds, missing, GraphPlan.Result.MISSING_INPUT, 0, 0);
    }
    private static List<String> literalWord(PlanStep word) {
        var result = new ArrayList<String>(); var todo = new ArrayDeque<PlanStep>(); todo.push(word);
        while (!todo.isEmpty()) {
            var step = todo.pop();
            if (step instanceof PlanStep.Batch batch) {
                if (batch.runs() > 10_000) throw new AssertionError("Literal oracle size");
                for (int i = 0; i < batch.runs(); i++) result.add(batch.recipe());
            } else if (step instanceof PlanStep.Sequence sequence) {
                for (int i = sequence.children().size() - 1; i >= 0; i--) todo.push(sequence.children().get(i));
            } else {
                var repeat = (PlanStep.Repeat) step;
                if (repeat.times() > 10_000) throw new AssertionError("Literal oracle size");
                for (int i = 0; i < repeat.times(); i++) todo.push(repeat.body());
            }
        }
        return result;
    }
    static void literalVerify(GraphPlan<String> p, Map<String, Long> stock, Set<String> external) {
        var inventory = new HashMap<>(p.initialExact());
        p.initialExact().forEach((k, n) -> check(external.contains(k) || n.compareTo(BigInteger.valueOf(stock.getOrDefault(k, 0L)).add(p.missingExact().getOrDefault(k, ZERO))) <= 0, "Unfunded material " + k));
        for (String id : literalWord(p.steps())) {
            var recipe = p.recipes().get(id);
            recipe.inputs().forEach((k, n) -> {
                var left = inventory.getOrDefault(k, ZERO).subtract(BigInteger.valueOf(n));
                if (left.signum() < 0) throw new AssertionError("Unfunded primitive prefix " + id + "/" + k);
                inventory.put(k, left);
            });
            recipe.outputs().forEach((k, n) -> inventory.merge(k, BigInteger.valueOf(n), BigInteger::add));
        }
        check(inventory.getOrDefault(p.target(), ZERO).compareTo(BigInteger.valueOf(p.amount())) >= 0, "Missing delivery");
        inventory.merge(p.target(), BigInteger.valueOf(p.amount()).negate(), BigInteger::add);
        p.seeds().forEach((k, n) -> check(inventory.getOrDefault(k, ZERO).compareTo(BigInteger.valueOf(n)) >= 0, "Lost final seed " + k));
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
