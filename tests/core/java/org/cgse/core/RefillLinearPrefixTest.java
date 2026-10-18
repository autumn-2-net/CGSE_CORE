// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Ratio shortcuts checked against literal firings, including incumbent retention. */
public final class RefillLinearPrefixTest {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static int checks;
    private static Map<String, Long> m(Object... pairs) {
        var result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        return result;
    }
    private static GraphRecipe<String> r(String id, Map<String, Long> in, Map<String, Long> out) {
        return MissingMaterialExpansionTest.recipe(id, in, out);
    }
    private static List<GraphRecipe<String>> chain(int length) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        recipes.add(r("make", m("A", 1, "feed", 1), m("B", 1)));
        for (int i = 0; i < length - 2; i++) recipes.add(r("bridge" + i, m(i == 0 ? "B" : "m" + i, 1), m("m" + (i + 1), 1)));
        recipes.add(r("return", m(length == 2 ? "B" : "m" + (length - 2), 1), m("A", 2)));
        recipes.add(r("finish", m("A", 10), m("G", 1)));
        return recipes;
    }
    public static void main(String[] args) throws Exception {
        mainMatrix(); ratioOracles(); intermediateObligations(); limits(); actionLimits(); scheduler(false); scheduler(true);
        System.out.println("RefillLinearPrefixTest: assertions=" + checks);
    }
    private static void mainMatrix() {
        for (int length : new int[] {2, 3, 4, 5}) for (long feed : new long[] {0, 6, 9})
            for (long seed : new long[] {0, 1}) for (boolean reverse : List.of(false, true)) {
                var recipes = chain(length); if (reverse) Collections.reverse(recipes);
                var stock = m("feed", feed); var b = MissingMaterialExpansionTest.budget(4_000_000);
                var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "G", 1, stock, Set.of(), seed == 0 ? Map.of() : m("A", seed), seed > 0, true, b);
                try {
                    while (!work.step()) {}
                    var p = work.result(); RefillWordPruningTest.literalVerify(p, stock, Set.of());
                    check(p.seeds().equals(seed == 0 ? Map.of() : m("A", seed)), "Lost final seed");
                    if (feed == 9) check(p.missing().equals(m("B", 1)), "Missed funded chain " + length + "/" + seed + ": " + p.missing());
                    if (feed == 6 && seed == 0) check(p.missing().equals(m("B", 2)), "Did not use partial stock");
                    // A shortcut-only portfolio chose A1/B2 here, regressing
                    // the existing A0/B4 bill. Retain every old coordinate.
                    if (length == 3 && feed == 6 && seed == 1)
                        check(p.missing().keySet().equals(Set.of("B")) && p.missing().get("B") <= 4, "Reintroduced an eliminated deficit");
                    if (feed == 0) check(!p.missing().containsKey("feed"), "Traded startup for missing feed");
                } finally { work.close(); }
                check(b.reservedBytes() == 0, "Main workspace");
            }
    }
    private static void ratioOracles() {
        var random = new Random(519241);
        for (int example = 0; example < 240; example++) {
            long spent = 1 + random.nextInt(3), made = 1 + random.nextInt(5), used = 1 + random.nextInt(5), returned = 1 + random.nextInt(5);
            var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
            recipes.put("out", r("out", m("B", spent, "feed", 1, "tool", 1), m("A", made, "tool", 1, "joint", 1)));
            recipes.put("back", r("back", m("A", used, "fuel", 1), m("B", returned)));
            var b = MissingMaterialExpansionTest.budget(100_000);
            try (var cycling = new RefillCycleActions<>(recipes, List.of(), "B", b)) {
                while (!cycling.step()) {}
                check(!cycling.actions().isEmpty() == (made * returned > spent * used), "Incorrect growth selection");
                for (var action : cycling.actions()) {
                    literalSummary(action, recipes);
                    long gcd = BigInteger.valueOf(made).gcd(BigInteger.valueOf(used)).longValueExact();
                    check(action.summary().delta("B").equals(BigInteger.valueOf((made * returned - spent * used) / gcd)), "Ratio rounded");
                    check(action.summary().delta("A").signum() == 0, "Transfer not balanced");
                    check(action.summary().required("tool").equals(BigInteger.ONE), "Shared tool multiplied");
                }
            }
            check(b.reservedBytes() == 0, "Ratio workspace");
        }
        // Exact multiplicities can create resource totals beyond long.
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("out", r("out", m("B", 1, "feed", Long.MAX_VALUE), m("A", Long.MAX_VALUE)));
        recipes.put("back", r("back", m("A", Long.MAX_VALUE - 1), m("B", 2)));
        var b = MissingMaterialExpansionTest.budget(100_000);
        try (var cycling = new RefillCycleActions<>(recipes, List.of(), "B", b)) {
            while (!cycling.step()) {}
            check(cycling.actions().size() == 1, "Large ratio omitted");
            var summary = cycling.actions().get(0).summary(); var n = BigInteger.valueOf(Long.MAX_VALUE);
            check(summary.required("feed").equals(n.multiply(n.subtract(BigInteger.ONE))), "Large feed clipped");
            check(summary.delta("A").signum() == 0 && summary.delta("B").equals(n.add(BigInteger.ONE)), "Large transfer imbalance");
        }
        check(b.reservedBytes() == 0, "Large ratio workspace");
    }
    private static void literalSummary(BackwardCoverability.Action<String> action, Map<String, GraphRecipe<String>> recipes) {
        var balance = new HashMap<String, BigInteger>(); var need = new HashMap<String, BigInteger>(); var peak = new HashMap<String, BigInteger>();
        var pending = new ArrayDeque<PlanStep>(); pending.push(action.program()); int firings = 0;
        while (!pending.isEmpty()) {
            var step = pending.pop();
            if (step instanceof PlanStep.Batch batch) {
                check(batch.runs() <= 1000, "Literal size");
                for (int i = 0; i < batch.runs(); i++) {
                    check(++firings <= 10_000, "Literal program size"); var recipe = recipes.get(batch.recipe());
                    recipe.inputs().forEach((k, n) -> {
                        balance.merge(k, BigInteger.valueOf(n).negate(), BigInteger::add);
                        need.merge(k, balance.get(k).negate().max(ZERO), BigInteger::max);
                    });
                    recipe.outputs().forEach((k, n) -> balance.merge(k, BigInteger.valueOf(n), BigInteger::add));
                    balance.forEach((k, n) -> peak.merge(k, n.max(ZERO), BigInteger::max));
                }
            } else if (step instanceof PlanStep.Sequence sequence) {
                for (int i = sequence.children().size() - 1; i >= 0; i--) pending.push(sequence.children().get(i));
            } else {
                var repeat = (PlanStep.Repeat) step; check(repeat.times() <= 1000, "Literal repeat size");
                for (int i = 0; i < repeat.times(); i++) pending.push(repeat.body());
            }
        }
        var keys = new HashSet<>(balance.keySet()); keys.addAll(action.summary().keys());
        for (String key : keys) {
            check(action.summary().required(key).equals(need.getOrDefault(key, ZERO)), "Prefix need " + key);
            check(action.summary().delta(key).equals(balance.getOrDefault(key, ZERO)), "Net balance " + key);
            check(action.summary().peak(key).equals(peak.getOrDefault(key, ZERO)), "Prefix peak " + key);
        }
    }
    private static void intermediateObligations() {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("head", r("head", m("B", 1), m("middle", 3)));
        recipes.put("tail", r("tail", m("middle", 2), m("A", 2)));
        recipes.put("make", r("make", m("A", 1, "feed", 1), m("B", 1)));
        var b = MissingMaterialExpansionTest.budget(100_000); var macros = new ArrayList<BackwardCoverability.Action<String>>();
        try (var compiler = new LinearMacroCompilation<>(recipes, "G", Set.of(), b)) {
            while (!compiler.step()) {}
            for (var recipe : compiler.recipes().values()) if (!recipes.containsKey(recipe.id())) {
                var word = compiler.expand(new PlanStep.Batch(recipe.id(), 1));
                try (var summary = new SummaryComputation<>(word, recipes, b)) {
                    while (!summary.step()) {} var action = new BackwardCoverability.Action<>(word, summary.result());
                    literalSummary(action, recipes); macros.add(action);
                    check(action.summary().delta("middle").signum() == 0 && action.summary().peak("middle").intValueExact() == 6, "Lost transfer key");
                }
            }
            check(macros.size() == 1, "No ratio chain");
            try (var cycling = new RefillCycleActions<>(recipes, macros, "B", b)) {
                while (!cycling.step()) {} check(cycling.actions().size() > macros.size(), "Did not compose chain");
                for (var action : cycling.actions()) literalSummary(action, recipes);
                // A required leftover intermediate cannot be supplied by a
                // fully contracted zero-delta transfer; original edges remain.
                try (var search = new BackwardCoverability<>(List.copyOf(recipes.values()), Map.of("B", BigInteger.ONE),
                        Map.of("middle", BigInteger.ONE), Set.of(), cycling.actions(), b, 2048, true)) {
                    while (!search.step()) {} check(search.result() == BackwardCoverability.Result.WITNESS, "Lost primitive alternative");
                    var witness = new BackwardCoverability.Action<>(search.witness(), SequenceSummary.of(search.witness(), recipes));
                    literalSummary(witness, recipes);
                    check(witness.summary().required("B").compareTo(BigInteger.ONE) <= 0 && witness.summary().delta("middle").signum() > 0, "Invalid intermediate witness");
                }
            }
        }
        check(b.reservedBytes() == 0, "Intermediate workspace");
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void reach(RefillSupplySearch<String> search, String phase) throws Exception {
        for (int i = 0; !field(search, "phase").toString().equals(phase); i++) check(i < 100_000 && !search.step(), "No phase " + phase);
    }
    private static GraphPlan<String> original(List<GraphRecipe<String>> recipes, Map<String, Long> stock) {
        var word = new PlanStep.Sequence(List.of(new PlanStep.Batch("bridge0", 5), new PlanStep.Batch("return", 5), new PlanStep.Batch("finish", 1)));
        return RefillWordPruningTest.literalPlan(recipes, word, Map.of(), stock, Set.of());
    }
    private static void limits() throws Exception {
        for (String phase : List.of("COMPILE", "MACRO_SUMMARY", "CYCLES")) for (String mode : List.of("work", "time", "memory", "cancel")) {
            var recipes = chain(3); var stock = m("feed", 9); var plan = original(recipes, stock);
            var clock = new AtomicLong(); var cancel = new AtomicBoolean();
            var b = new PlanningBudget(1, 100_000, 4L << 20, cancel::get, clock::get); long held = 0;
            try (var order = new RefillProducerOrder<>(new GraphCompiler<>(recipes), plan.missingExact().keySet(), Set.of(), Set.of(), b)) {
                while (!order.step()) {}
                b.failureDetail("old proof");
                try (var search = new RefillSupplySearch<>(plan, order, stock, Set.of(), true, false, b)) {
                    reach(search, phase); var incumbent = search.result(); check(incumbent != plan, "Old search did not complete first");
                    switch (mode) {
                        case "work" -> b.charge(b.remainingWork());
                        case "time" -> clock.set(2_000_000);
                        case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                        case "cancel" -> cancel.set(true);
                    }
                    try {
                        while (!search.step()) {} check(!mode.equals("cancel"), "Cancellation swallowed");
                        check(search.result() == incumbent, "Limit discarded incumbent");
                        RefillWordPruningTest.literalVerify(search.result(), stock, Set.of());
                    } catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancellation"); }
                    check(b.failureDetail().equals("old proof"), "Replaced original diagnostic");
                } finally { b.release(held); }
            }
            check(b.reservedBytes() == 0, "Interrupted workspace " + phase + "/" + mode);
        }
    }
    private static void actionLimits() throws Exception {
        var recipes = new LinkedHashMap<String, GraphRecipe<String>>();
        recipes.put("out", r("out", m("B", 1), m("A", 3)));
        recipes.put("back", r("back", m("A", 2, "feed", 1), m("B", 1)));
        for (String mode : List.of("work", "time", "memory", "cancel")) {
            var clock = new AtomicLong(); var cancel = new AtomicBoolean();
            var b = new PlanningBudget(1, 100_000, 4L << 20, cancel::get, clock::get); long held = 0;
            try (var cycling = new RefillCycleActions<>(recipes, List.of(), "B", b)) {
                if (mode.equals("memory")) {
                    // The following summary may use only already reserved
                    // frames. Exhaust memory before admitting the new word.
                    for (int i = 0; i < recipes.size(); i++) check(!cycling.step(), "Primitive setup");
                } else {
                    for (int i = 0; field(cycling, "summarizing") == null; i++) check(i < 100 && !cycling.step(), "No pending round-trip summary");
                }
                switch (mode) {
                    case "work" -> b.charge(b.remainingWork());
                    case "time" -> clock.set(2_000_000);
                    case "memory" -> { held = b.availableBytes(); b.reserve(held); }
                    case "cancel" -> cancel.set(true);
                }
                try { while (!cycling.step()) {} throw new AssertionError("Expected interruption: " + mode); }
                catch (PlanningBudget.Exhausted expected) { check(!mode.equals("cancel"), "Cancellation changed to limit"); }
                catch (CancellationException expected) { check(mode.equals("cancel"), "Unexpected cancel"); }
                check(cycling.actions().isEmpty(), "Published partial action summary");
            } finally { b.release(held); }
            check(b.reservedBytes() == 0, "Interrupted round-trip summary");
        }
    }
    private static void scheduler(boolean cancel) throws Exception {
        var recipes = chain(3); var stock = m("feed", 9); var plan = original(recipes, stock); var cancelled = new AtomicBoolean();
        var b = new PlanningBudget(0, 100_000, 4L << 20, cancelled::get, System::nanoTime);
        try (var order = new RefillProducerOrder<>(new GraphCompiler<>(recipes), plan.missingExact().keySet(), Set.of(), Set.of(), b)) {
            while (!order.step()) {}
            try (var search = new RefillSupplySearch<>(plan, order, stock, Set.of(), true, false, b);
                 var scheduler = new PlanningScheduler(1, 8, 64, 1_000_000_000)) {
                reach(search, "CYCLES"); long before = b.nodes(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var future = scheduler.submit(new PlanningScheduler.Work<GraphPlan<String>>() {
                    boolean started;
                    public boolean advance(PlanningScheduler.Slice slice) {
                        if (!started) { started = true; entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker gate"); } catch (InterruptedException e) { throw new AssertionError(e); } }
                        while (slice.nextUncharged()) if (search.step()) return true; return false;
                    }
                    public GraphPlan<String> result() { return search.result(); }
                    public void close() { search.close(); }
                }, b);
                check(entered.await(5, TimeUnit.SECONDS), "Worker not started");
                var marker = scheduler.submit(new PlanningScheduler.Work<Long>() {
                    long seen;
                    public boolean advance(PlanningScheduler.Slice slice) { seen = b.nodes() - before; if (cancel) cancelled.set(true); return true; }
                    public Long result() { return seen; }
                }, MissingMaterialExpansionTest.budget(1000));
                release.countDown(); check(marker.get(10, TimeUnit.SECONDS) <= 256, "Shortcut compiler monopolized worker");
                try { var result = future.get(10, TimeUnit.SECONDS); check(!cancel, "Cancellation ignored"); RefillWordPruningTest.literalVerify(result, stock, Set.of()); }
                catch (CancellationException e) { check(cancel, "Unexpected cancel"); }
                catch (ExecutionException e) { check(cancel && e.getCause() instanceof CancellationException, "Worker failure"); }
            }
        }
        check(b.reservedBytes() == 0, "Scheduled workspace");
    }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
