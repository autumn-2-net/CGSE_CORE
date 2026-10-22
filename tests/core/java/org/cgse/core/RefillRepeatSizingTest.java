// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Quantity boundaries checked by enumerating literal words, not the sizing formula. */
public final class RefillRepeatSizingTest {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static int checks;
    private static Map<String, Long> m(Object... pairs) {
        var values = new LinkedHashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        return values;
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static boolean noLarger(Map<String, BigInteger> values, Map<String, BigInteger> ceiling) {
        for (var e : values.entrySet()) if (e.getValue().compareTo(ceiling.getOrDefault(e.getKey(), ZERO)) > 0) return false;
        return true;
    }
    private static List<GraphRecipe<String>> chain(int length, long demand) {
        var recipes = new ArrayList<GraphRecipe<String>>(); recipes.add(r("make", m("A", 1, "feed", 1), m("B", 1)));
        for (int i = 0; i < length - 2; i++) recipes.add(r("bridge" + i, m(i == 0 ? "B" : "m" + i, 1), m("m" + (i + 1), 1)));
        recipes.add(r("return", m(length == 2 ? "B" : "m" + (length - 2), 1), m("A", 2)));
        recipes.add(r("finish", m("A", demand * 2), m("G", 1))); return recipes;
    }
    public static void main(String[] args) throws Exception {
        quantities(); literalOracles(); exactLarge(); wideSlice(); limits(); parentRetention(); scheduler(false); scheduler(true);
        System.out.println("RefillRepeatSizingTest: assertions=" + checks);
    }
    private static void quantities() {
        for (int length : new int[] {2, 3, 4}) for (long demand : new long[] {10, 25, 50, 100})
            for (long feed : new long[] {4, 10, 18, 40, 61}) for (boolean reverse : List.of(false, true)) {
                var recipes = chain(length, demand); if (reverse) Collections.reverse(recipes);
                var stock = m("feed", feed); var b = MissingMaterialExpansionTest.budget(4_000_000);
                var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "G", 1, stock, Set.of(), Map.of(), false, true, b);
                try {
                    while (!work.step()) {} var plan = work.result();
                    // Each original B contributes2 final A; each feed adds1.
                    // An empty cycle cannot start. Literal replay establishes
                    // attainability as well as this independent lower bound.
                    check(plan.missing().equals(m("B", Math.max(1, demand - feed / 2))), "Quantity gap " + demand + "/" + feed + ": " + plan.missing());
                    RefillWordPruningTest.literalVerify(plan, stock, Set.of());
                } finally { work.close(); }
                check(b.reservedBytes() == 0, "Quantity workspace");
            }
    }
    private static void literalOracles() {
        var random = new Random(519245);
        for (int example = 0; example < 320; example++) {
            boolean force = random.nextBoolean(), ignore = random.nextBoolean();
            long spentTarget = random.nextInt(2), madeTarget = random.nextInt(3);
            var in = m("B", 1 + random.nextInt(4), "tool", 1);
            if (spentTarget > 0) in.put("G", spentTarget);
            var out = m("A", 1 + random.nextInt(5), "tool", 1, "joint", 1 + random.nextInt(2));
            if (madeTarget > 0) out.put("G", madeTarget);
            var secondIn = m("A", 1 + random.nextInt(4), "feed", 1 + random.nextInt(3));
            var secondOut = m("B", 1 + random.nextInt(5));
            var recipes = List.of(r("out", in, out), r("back", secondIn, secondOut),
                    r("finish", m("B", 1 + random.nextInt(16), "joint", random.nextInt(7) + 1), m("G", 1)));
            PlanStep word = new PlanStep.Sequence(List.of(new PlanStep.Batch("out", 1), new PlanStep.Batch("back", 1)));
            var stock = m("B", random.nextInt(12), "A", random.nextInt(8), "tool", random.nextInt(2), "feed", random.nextInt(32), "joint", random.nextInt(10), "G", random.nextInt(4));
            var effectiveStock = new LinkedHashMap<>(stock); if (ignore) effectiveStock.remove("G");
            Set<String> external = switch (example % 4) { case 0 -> Set.of("feed"); case 1 -> Set.of("G"); default -> Set.of(); };
            var seeds = example % 3 == 0 ? m("A", 1, "independent", 2) : Map.<String, Long>of();
            var original = RefillWordPruningTest.literalPlan(recipes, new PlanStep.Batch("finish", 1), seeds, effectiveStock, external);
            var bindings = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(recipe -> bindings.put(recipe.id(), recipe));
            var b = MissingMaterialExpansionTest.budget(100_000);
            var sizing = new RefillRepeatSizing<>(SequenceSummary.of(word, bindings), original, stock, external, force, ignore, b);
            while (!sizing.step()) {}
            long copies = sizing.result().longValueExact(); check(copies >= 0 && copies <= 32, "Oracle enumeration bound");
            var chosen = RefillWordPruningTest.literalPlan(recipes,
                    new PlanStep.Sequence(List.of(new PlanStep.Repeat(word, copies), original.steps())), seeds, effectiveStock, external);
            check(noLarger(chosen.missingExact(), original.missingExact()), "Sizing increased a shortage");
            check(!force || copies == 0 || madeTarget >= spentTarget, "Spent forced production credit");
            RefillWordPruningTest.literalVerify(chosen, effectiveStock, external);
            for (int n = 0; n <= 32; n++) {
                if (force && n > 0 && spentTarget > madeTarget) continue;
                var option = RefillWordPruningTest.literalPlan(recipes,
                        new PlanStep.Sequence(List.of(new PlanStep.Repeat(word, n), original.steps())), seeds, effectiveStock, external);
                if (noLarger(option.missingExact(), original.missingExact()))
                    check(noLarger(chosen.missingExact(), option.missingExact()), "Missed fixed-word quantity " + example + "/" + n);
            }
            check(b.reservedBytes() == 0, "Sizing workspace");
        }
    }
    private static void exactLarge() {
        BigInteger demand = BigInteger.valueOf(Long.MAX_VALUE).pow(2).add(BigInteger.TEN);
        var recipes = List.of(r("out", m("B", 1), m("A", 2)), r("back", m("A", 1, "feed", 1), m("B", 1)), r("finish", m("B", 1), m("G", 1)));
        var bindings = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(recipe -> bindings.put(recipe.id(), recipe));
        PlanStep word = new PlanStep.Sequence(List.of(new PlanStep.Batch("out", 1), new PlanStep.Batch("back", 2)));
        var original = new GraphPlan<>("G", 1, false, PlanStep.batch("finish", demand), bindings, Map.of("B", demand), Map.of(), Map.of("B", demand), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var b = MissingMaterialExpansionTest.budget(100_000);
        var sizing = new RefillRepeatSizing<>(SequenceSummary.of(word, bindings), original, Map.of(), Set.of("feed"), true, false, b);
        while (!sizing.step()) {} check(sizing.result().equals(demand.subtract(BigInteger.ONE)), "Exact repeat count clipped");
        var combined = new PlanStep.Sequence(List.of(PlanStep.repeat(word, sizing.result()), original.steps()));
        var initial = Map.of("B", BigInteger.ONE, "feed", demand.subtract(BigInteger.ONE).multiply(BigInteger.TWO));
        var funded = new GraphPlan<>("G", 1, false, combined, bindings, initial, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, 0, 0);
        try (var verification = new PlanVerification<>(funded, b)) { while (!verification.step()) {} }
        check(funded.patternTimesExact().get("finish").equals(demand), "Lost original exact suffix");
        check(b.reservedBytes() == 0, "Large sizing workspace");
    }
    private static void wideSlice() {
        var required = new LinkedHashMap<String, BigInteger>(); var delta = new LinkedHashMap<String, BigInteger>(); var stock = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 4096; i++) { required.put("tool" + i, BigInteger.ONE); delta.put("tool" + i, ZERO); stock.put("tool" + i, 1L); }
        required.put("B", BigInteger.ONE); delta.put("B", BigInteger.ONE);
        var original = RefillWordPruningTest.literalPlan(List.of(r("finish", m("B", 10), m("G", 1))), new PlanStep.Batch("finish", 1), Map.of(), stock, Set.of());
        var b = MissingMaterialExpansionTest.budget(100_000);
        var sizing = new RefillRepeatSizing<>(new SequenceSummary<>(required, delta, Map.of()), original, stock, Set.of(), true, false, b);
        long before = b.nodes(); check(!sizing.step() && b.nodes() - before <= 32, "Wide supply scan did not yield");
        while (!sizing.step()) {} check(sizing.result().equals(BigInteger.valueOf(9)), "Zero-net requirements lost");
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void reach(RefillSupplySearch<String> search, String phase) throws Exception {
        for (int i = 0; !(boolean) field(search, "resizing") || !field(search, "phase").toString().equals(phase); i++)
            check(i < 100_000 && !search.step(), "No sizing phase " + phase);
    }
    private static GraphPlan<String> original(List<GraphRecipe<String>> recipes, Map<String, Long> stock) {
        return RefillWordPruningTest.literalPlan(recipes, new PlanStep.Sequence(List.of(new PlanStep.Batch("return", 10), new PlanStep.Batch("finish", 1))), Map.of(), stock, Set.of());
    }
    private static void limits() throws Exception {
        for (String phase : List.of("SIZE_SUMMARY", "SIZE", "VERIFY")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var recipes = chain(2, 10); var stock = m("feed", 10); var plan = original(recipes, stock);
            var clock = new AtomicLong(); var cancel = new AtomicBoolean(); var b = new PlanningBudget(1, 100_000, 4L << 20, cancel::get, clock::get); long held = 0;
            try (var order = new RefillProducerOrder<>(new GraphCompiler<>(recipes), plan.missingExact().keySet(), Set.of(), Set.of(), b)) {
                while (!order.step()) {} b.failureDetail("retained proof");
                try (var search = new RefillSupplySearch<>(plan, order, stock, Set.of(), true, false, b)) {
                    reach(search, phase); var incumbent = search.result(); check(incumbent.missing().equals(m("B", 8)), "Earlier passes changed");
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork()); case "time" -> clock.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); } case "cancel" -> cancel.set(true);
                    }
                    try {
                        while (!search.step()) {} check(!mode.equals("cancel"), "Cancellation swallowed");
                        check(noLarger(search.result().missingExact(), incumbent.missingExact()), "Lost completed incumbent");
                        if (!mode.equals("memory")) check(search.result() == incumbent, "Published interrupted sizing");
                        RefillWordPruningTest.literalVerify(search.result(), stock, Set.of());
                    } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                    check(b.failureDetail().equals("retained proof"), "Replaced proof diagnostic");
                } finally { b.release(held); }
            }
            check(b.reservedBytes() == 0, "Sizing interruption workspace " + phase + "/" + mode);
        }
    }
    private static void parentRetention() throws Exception {
        var recipes = chain(2, 10); var stock = m("feed", 10); var b = MissingMaterialExpansionTest.budget(4_000_000);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "G", 1, stock, Set.of(), Map.of(), false, true, b);
        try {
            GraphPlan<String> accepted = null;
            for (int i = 0; accepted == null; i++) {
                check(i < 100_000 && !work.step(), "No retained sizing result");
                var expansion = field(work, "missingExpansion"); if (expansion == null) continue;
                // The common constructor can be nested below boundary search.
                // Interrupt after its completed result reaches the owning
                // expansion, rather than depending on the old child layout.
                @SuppressWarnings("unchecked") var value = ((MissingMaterialExpansion<String>) expansion).result();
                if (value != null && value.missing().equals(m("B", 5))) accepted = value;
            }
            b.charge(b.remainingWork()); while (!work.step()) {}
            check(work.result() == accepted, "Parent discarded completed sizing"); RefillWordPruningTest.literalVerify(accepted, stock, Set.of());
        } finally { work.close(); }
        check(b.reservedBytes() == 0, "Parent sizing workspace");
    }
    private static void scheduler(boolean cancel) throws Exception {
        var recipes = chain(2, 10); var stock = m("feed", 10); var plan = original(recipes, stock); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var order = new RefillProducerOrder<>(new GraphCompiler<>(recipes), plan.missingExact().keySet(), Set.of(), Set.of(), b)) {
            while (!order.step()) {}
            try (var search = new RefillSupplySearch<>(plan, order, stock, Set.of(), true, false, b); var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                reach(search, "SIZE"); long before = b.nodes(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) { started = true; entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker gate"); } catch (InterruptedException e) { throw new AssertionError(e); } }
                        while (slice.nextUncharged()) if (search.step()) return true; return false;
                    }
                    public GraphPlan<String> result() { return search.result(); } public void close() { search.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Worker not started");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long seen;
                    public boolean advance(PlanningScheduler.Slice slice) { seen = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return seen; }
                }, MissingMaterialExpansionTest.budget(1000));
                release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 256, "Sizing monopolized worker");
                try { var result = future.get(10, TimeUnit.SECONDS); check(!cancel, "Cancellation ignored"); RefillWordPruningTest.literalVerify(result, stock, Set.of()); }
                catch (CancellationException e) { check(cancel, "Unexpected cancel"); }
                catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Worker failure"); }
            }
        }
        check(b.reservedBytes() == 0, "Scheduled sizing workspace");
    }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
