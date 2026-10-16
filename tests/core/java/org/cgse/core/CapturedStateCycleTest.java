// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Large periodic requests, primitive source replay, and actual return gating. */
public final class CapturedStateCycleTest {
    private static int checks, cases;

    private static final class Cycle implements StateFamily<String> {
        final int period, step, tail;
        long calls;
        Cycle(int period, int step, int tail) { this.period = period; this.step = step; this.tail = tail; }
        public String id() { return "cycle-" + period + "-" + step + "-" + tail; }
        public boolean accepts(String key) { return key.startsWith("t:"); }
        public long capacity(String key) { return Long.MAX_VALUE; }
        int next(int phase) { return phase < tail ? phase + 1 : tail + (phase - tail + step) % period; }
        public String nominalAfterOne(String key) { calls++; return "t:" + next(Integer.parseInt(key.substring(2))); }
    }

    private record Solved(GraphPlan<String> plan, CapturedStatePlanning.Outcome outcome, PlanningBudget budget) {}

    public static void main(String[] args) {
        largeWords();
        wideIntermediateCounts();
        multipleEntities();
        ordinaryBoundaries();
        sourceBoundaries();
        attrition();
        cancellation();
        requestPath();
        System.out.println("CapturedStateCycleTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void largeWords() {
        for (int period : new int[]{2, 3, 7, 31}) for (long n : new long[]{128, 1_000_000_000L, Long.MAX_VALUE}) {
            var family = new Cycle(period, 1, 0);
            var entries = List.of(entry("use", List.of(tool(family, 1), ordinary("raw")), "product"));
            String end = "t:" + n % period;
            var stock = Map.of("t:0", 1L, "raw", n);
            var solved = solve(entries, stock, n, Map.of(end, 1L), false, 80_000);
            var plan = require(solved);
            check(family.calls <= 3L * period + 2, "Restoration still scaled with requested executions");
            check(solved.budget.peakBytes() < (1L << 20), "Repeated word retained per-firing state");
            check(solved.budget.diagnostics().contains("cycle_jumps=1"), "Observed periodic word was not reused");
            check(plan.patternTimesExact().values().stream().reduce(BigInteger.ZERO, BigInteger::add).equals(BigInteger.valueOf(n)), "Wrong exact firing count");
            check(nodes(plan.steps()) < 4 * period + 30, "Serialized witness expanded per firing");
            verifySource(plan, entries, stock, n);
            if (n == 128) replay(plan, n);
            cases++;
        }
        var family = new Cycle(3, 1, 100);
        var entries = List.of(entry("tail", List.of(tool(family, 1), ordinary("raw")), "product"));
        var plan = require(solve(entries, Map.of("t:0", 1L, "raw", 1_000_000_000L), 1_000_000_000L, Map.of(), false, 400_000));
        check(family.calls <= 110, "Finite prefix prevented cycle reuse");
        verifySource(plan, entries, Map.of("t:0", 1L, "raw", 1_000_000_000L), 1_000_000_000L);
        cases++;
    }

    private static void multipleEntities() {
        for (boolean reverse : new boolean[]{false, true}) for (boolean sharedRules : new boolean[]{false, true}) {
            var a = new Cycle(3, 1, 0); var b = sharedRules ? new Cycle(3, 2, 0) : a;
            var inputs = new ArrayList<>(List.of(tool(a, 1), tool(b, 1), ordinary("raw")));
            if (reverse) Collections.reverse(inputs);
            var entries = List.of(entry("pair", inputs, "product"));
            var stock = new LinkedHashMap<String, Long>();
            stock.put(reverse ? "t:1" : "t:0", 1L); stock.put(reverse ? "t:0" : "t:1", 1L); stock.put("raw", 1_000_000_000L);
            var plan = require(solve(entries, stock, 1_000_000_000L, Map.of(), false, 400_000));
            check(a.calls + (b == a ? 0 : b.calls) < 100, "Multiple entities were expanded with order size");
            verifySource(plan, entries, stock, 1_000_000_000L);
            stock.put("raw", 96L);
            plan = require(solve(entries, stock, 96, Map.of(), false, 80_000));
            verifySource(plan, entries, stock, 96); replay(plan, 96); runtime(plan, 2);
            cases += 2;
        }
    }

    private static void wideIntermediateCounts() {
        var f = new Cycle(2, 1, 0);
        var raw = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("raw", 1), "raw", false)), CapturedRecipe.InputMode.INDIVISIBLE);
        var two = new CapturedRecipe.Input<>(2, ordinary("mid").candidates(), CapturedRecipe.InputMode.INDIVISIBLE);
        var entries = List.of(entry("use", List.of(tool(f, 1), raw), "mid"), entry("finish", List.of(two), "product"));
        var stock = Map.of("t:0", 2L, "raw", 1L);
        var solved = solve(entries, stock, Long.MAX_VALUE, Map.of(), false, 80_000);
        var plan = require(solved);
        BigInteger uses = BigInteger.ZERO, finishes = BigInteger.ZERO;
        for (var count : plan.patternTimesExact().entrySet()) {
            if (plan.recipes().get(count.getKey()).binding().equals("use")) uses = uses.add(count.getValue());
            else finishes = finishes.add(count.getValue());
        }
        check(uses.equals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)), "Truncated an intermediate count above long");
        check(finishes.equals(BigInteger.valueOf(Long.MAX_VALUE)), "Lost final wide request count");
        check(solved.budget.diagnostics().contains("cycle_jumps=2") && f.calls <= 20, "Second cursor window lost cycle acceleration");
        verifySource(plan, entries, stock, Long.MAX_VALUE); cases++;
    }

    private static void ordinaryBoundaries() {
        for (boolean reusable : new boolean[]{false, true}) {
            long n = 1_000_000_000L;
            var f = new Cycle(2, 1, 0);
            var config = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>("config", 1), null, true, reusable)), CapturedRecipe.InputMode.INDIVISIBLE);
            var entries = List.of(entry("configured", List.of(tool(f, 1), ordinary("raw"), config), "product"));
            var stock = Map.of("t:0", 1L, "raw", n, "config", reusable ? 1L : n);
            var plan = require(solve(entries, stock, n, Map.of(), false, 80_000));
            verifySource(plan, entries, stock, n);
            if (!reusable) check(solve(entries, Map.of("t:0", 1L, "raw", n, "config", 2L), n, Map.of(), false, 80_000).plan == null,
                    "A repeated serial word reused consumed configuration");
            cases++;
        }
        var f = new Cycle(2, 1, 0);
        var returned = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("raw", 1), "raw", false)), CapturedRecipe.InputMode.INDIVISIBLE);
        var entries = List.of(entry("returning", List.of(tool(f, 1), returned), "product"));
        var stock = Map.of("t:0", 1L, "raw", 1L, "product", 1_000_000_000L);
        var plan = require(solve(entries, stock, 1_000_000_000L, Map.of("t:0", 1L), true, 80_000));
        verifySource(plan, entries, stock, 1_000_000_000L);
        check(plan.patternTimesExact().values().stream().reduce(BigInteger.ZERO, BigInteger::add).equals(BigInteger.valueOf(1_000_000_000L)), "Force craft used stocked products");
        check(solve(entries, stock, 1_000_000_000L, Map.of("t:1", 1L), true, 80_000).plan == null, "Cycle lost exact final seed state");
        var overlap = List.of(entry("overlap", List.of(tool(f, 1), ordinary("t:0")), "product"));
        check(solve(overlap, Map.of("t:0", 1L), 8, Map.of(), false, 80_000).plan == null, "A pooled entity funded an ordinary slot");
        cases += 3;
    }

    private static void sourceBoundaries() {
        for (boolean reverse : new boolean[]{false, true}) {
            var f = new Cycle(3, 1, 0);
            var entries = new ArrayList<>(List.of(entry("A", List.of(tool(f, 1), ordinary("raw")), "mid"),
                    entry("B", List.of(tool(f, 1), ordinary("mid")), "product")));
            if (reverse) Collections.reverse(entries);
            var stock = Map.of("t:0", 1L, "raw", 1_000_000_000L);
            var plan = require(solve(entries, stock, 1_000_000_000L, Map.of(), false, 160_000));
            check(f.calls < 30, "A new source lost periodic restoration");
            check(plan.recipes().values().stream().map(GraphRecipe::binding).collect(java.util.stream.Collectors.toSet()).equals(Set.of("A", "B")), "A repeated word crossed host bindings");
            verifySource(plan, entries, stock, 1_000_000_000L); cases++;
        }
    }

    private static void attrition() {
        StateFamily<String> finite = new StateFamily<>() {
            public String id() { return "finite"; }
            public boolean accepts(String k) { return k.equals("spent-tool"); }
            public long capacity(String k) { return 1; }
            public String nominalAfterOne(String k) { return null; }
        };
        var input = new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("spent-tool", 1), null, false, false, finite)), CapturedRecipe.InputMode.INDIVISIBLE);
        var entries = List.of(entry("consume", List.of(input, ordinary("raw")), "product"),
                entry("build", List.of(ordinary("metal")), "spent-tool"));
        var stock = Map.of("spent-tool", 3L, "metal", 93L, "raw", 96L);
        var solved = solve(entries, stock, 96, Map.of(), false, 80_000);
        var plan = require(solved);
        check(solved.budget.diagnostics().contains("cycle_jumps=0"), "Finite replacement consumption was treated as a closed orbit");
        verifySource(plan, entries, stock, 96); replay(plan, 96);
        check(solve(entries, Map.of("spent-tool", 3L, "metal", 92L, "raw", 96L), 96, Map.of(), false, 80_000).plan == null,
                "Missing finite replacement was invented");
        cases += 2;
    }

    private static void cancellation() {
        var flag = new AtomicBoolean(); var f = new Cycle(2, 1, 0);
        var entries = List.of(entry("use", List.of(tool(f, 1), ordinary("raw")), "product"));
        var budget = new PlanningBudget(0, 80_000, 64L << 20, flag::get, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(entries, "product", 1_000_000_000L, Map.of("t:0", 1L, "raw", 1_000_000_000L),
                Set.of(), Map.of(), false, false, budget)) {
            while (f.calls < 4 && !work.step()) {}
            check(f.calls >= 4, "Active cycle workspace was not reached"); flag.set(true);
            try { work.step(); throw new AssertionError("Ignored cancellation"); }
            catch (CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled cycle retained memory");
        f = new Cycle(3, 1, 100_000);
        var limited = solve(List.of(entry("long-prefix", List.of(tool(f, 1), ordinary("raw")), "product")),
                Map.of("t:0", 1L, "raw", 1_000_000_000L), 1_000_000_000L, Map.of(), false, 8000);
        check(limited.plan == null && limited.outcome.status() == CapturedStatePlanning.Status.LIMITED, "Local prefix limit became a negative proof");
        check(limited.budget.searchWork() <= 8001, "Detector renewed the request allowance");
    }

    private static void requestPath() {
        var f = new Cycle(7, 1, 0);
        var entries = List.of(entry("use", List.of(tool(f, 1), ordinary("raw")), "product"));
        var stock = Map.of("t:0", 1L, "raw", 1_000_000_000L);
        var budget = new PlanningBudget(0, 80_000, 64L << 20, () -> false, System::nanoTime);
        try (var work = new CapturedCatalog<>(entries).begin("product", 1_000_000_000L, stock, false, false, budget)) {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "Request path did not use periodic restoration");
            check(!work.result().coverage().complete(), "A closed observed orbit completed the arbitrary family domain");
            verifySource(work.result().plan(), entries, stock, 1_000_000_000L);
        }
        check(budget.reservedBytes() == 0, "Request path retained cycle memory"); cases++;
    }

    private static Solved solve(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n,
                                Map<String, Long> seeds, boolean force, long cap) {
        var budget = new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); budget.enableMetrics();
        Solved solved;
        try (var work = new CapturedStatePlanning<>(entries, "product", n, stock, Set.of(), seeds, false, force, budget)) {
            while (!work.step()) {}
            solved = new Solved(work.result(), work.outcome(), budget);
        }
        check(budget.reservedBytes() == 0, "Restoration leaked a cycle workspace");
        PlanningCostTraceTest.conserved(budget);
        return solved;
    }

    private static GraphPlan<String> require(Solved solved) {
        check(solved.plan != null && solved.outcome.status() == CapturedStatePlanning.Status.VERIFIED,
                "No exact cycle witness: " + solved.outcome + "; " + solved.budget.diagnostics());
        return solved.plan;
    }

    private static void verifySource(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((key, count) -> check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented initial stock"));
        for (var recipe : plan.recipes().values()) {
            var source = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var expected = new LinkedHashMap<String, Long>(); long[] filled = new long[source.recipe().inputs().size()];
            source.recipe().outputs().forEach(output -> expected.merge(output.what(), output.amount(), Long::sum));
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled[slot.inputSlot()] += slot.amount();
                if (candidate.family() instanceof Cycle f) {
                    check(f.accepts(slot.key()), "Wrong state family");
                    String after = "t:" + f.next(Integer.parseInt(slot.key().substring(2)));
                    expected.merge(after, slot.amount(), Long::sum);
                } else if (candidate.family() != null) {
                    check(candidate.family().accepts(slot.key()), "Wrong finite tool");
                    check(candidate.family().nominalAfterOne(slot.key()) == null, "Expected a consumed finite entity");
                } else {
                    check(candidate.stack().what().equals(slot.key()), "Changed ordinary choice");
                    if (candidate.remaining() != null) expected.merge(candidate.remaining(), slot.amount(), Long::sum);
                }
            }
            for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Input slot lost quantity");
            check(expected.equals(recipe.executionOutputs()), "Exact word changed the original source contract");
        }
        check(plan.amount() == n, "Changed requested quantity");
    }

    private static void replay(GraphPlan<String> plan, long n) {
        var inventory = new LinkedHashMap<>(plan.initial()); var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (int i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, count) -> check(inventory.getOrDefault(key, 0L) >= count, "Unfunded primitive prefix"));
                recipe.dispatchInputs(1).forEach((key, count) -> inventory.merge(key, -count, Long::sum));
                recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, count, Long::sum));
                check(++fired < 2000, "Unexpected primitive expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", 0L) >= n, "Primitive replay lost target");
    }

    private static void runtime(GraphPlan<String> plan, int tools) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial()), refunded = new LinkedHashMap<>();
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered, inFlight;
            public long capacity(GraphRecipe<String> recipe, long requested) { return Math.min(4, requested); }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Dispatched an unreceived physical tool");
                    physical.merge(key, -count, Long::sum); if (key.startsWith("t:")) inFlight += count;
                });
                check(inFlight <= tools, "Repeated word multiplied concurrent ownership");
                var outputs = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { refunded.merge(key, count, Long::sum); return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 2000 && !host.job.finished(); tick++) {
            host.job.tick(host, tick * 20L, 4, () -> 0L);
            if (tick % 17 == 0) host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, count) -> {
                if (key.startsWith("t:")) host.inFlight -= count;
                host.physical.merge(key, count, Long::sum);
                check(host.job.accept(key, count, false) == count, "Lost actual periodic return");
            });
            host.pending.clear();
        }
        check(host.job.state() == GraphJobRuntime.State.COMPLETED && host.delivered == plan.amount(), "Repeated execution stalled");
        check(host.pending.isEmpty() && host.inFlight == 0, "Completed with missing returns");
        check(host.refunded.entrySet().stream().filter(e -> e.getKey().startsWith("t:")).mapToLong(Map.Entry::getValue).sum() == tools,
                "Final physical tool ownership changed");
    }

    private static CapturedRecipe.Input<String> tool(Cycle family, long copies) {
        return new CapturedRecipe.Input<>(copies, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("t:0", 1),
                "t:" + family.next(0), false, false, family)), CapturedRecipe.InputMode.MIXED);
    }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static int nodes(PlanStep step) {
        if (step instanceof PlanStep.Sequence s) return 1 + s.children().stream().mapToInt(CapturedStateCycleTest::nodes).sum();
        return step instanceof PlanStep.Repeat r ? 1 + nodes(r.body()) : 1;
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
