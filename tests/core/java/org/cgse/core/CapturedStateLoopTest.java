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

/** Cross-source words retain physical tool ownership, source contracts and order. */
public final class CapturedStateLoopTest {
    private static int checks, cases;

    private static final class Cycle implements StateFamily<String> {
        final int period, step, tail;
        long calls;
        Cycle(int period, int step, int tail) { this.period = period; this.step = step; this.tail = tail; }
        public String id() { return "loop-" + period + "-" + step + "-" + tail; }
        public boolean accepts(String key) { return key.startsWith("t:"); }
        public long capacity(String key) { return Long.MAX_VALUE; }
        String after(String key) {
            int phase = Integer.parseInt(key.substring(2));
            return "t:" + (phase < tail ? phase + 1 : tail + (phase - tail + step) % period);
        }
        public String nominalAfterOne(String key) { calls++; return after(key); }
    }
    private record Solved(GraphPlan<String> plan, CapturedStatePlanning.Outcome outcome, PlanningBudget budget) {}

    public static void main(String[] args) {
        interleaved(); sharedRules(); wide(); ordinaryAndConfigurations(); nestedDispatches(); replacements(); limits(); requestPath();
        System.out.println("CapturedStateLoopTest: cases=" + cases + "; assertions=" + checks);
    }

    private static void interleaved() {
        for (int period : new int[]{1, 2, 7, 31}) for (long n : new long[]{96, 1_000_000_000L}) for (boolean reverse : new boolean[]{false, true}) {
            var family = new Cycle(period, 1, 0);
            var entries = ring(List.of(family, family), 1);
            if (reverse) Collections.reverse(entries);
            var stock = new LinkedHashMap<String, Long>();
            if (reverse) stock.put("raw", n);
            stock.put("t:0", 1L); stock.put("baton0", 1L); stock.put("raw", n);
            String end = "t:" + BigInteger.valueOf(n).multiply(BigInteger.TWO).mod(BigInteger.valueOf(period));
            var solved = solve(entries, stock, n, Map.of(end, 1L, "baton0", 1L), false, 160_000);
            var plan = require(solved);
            check(family.calls < 6L * period + 10, "Cross-recipe restoration scaled with requested executions");
            check(solved.budget.diagnostics().contains("loop_jumps=1"), "Auxiliary Repeat boundary was lost");
            check(nodes(plan.steps()) < 10 * period + 60, "Cross-recipe word expanded into a flat program");
            check(solved.budget.peakBytes() < (2L << 20), "Cross-recipe word retained per-use memory");
            source(plan, entries, stock, n); exactCounts(plan, entries, n);
            if (n == 96) {
                replay(plan);
                if (period == 2 || period == 7) runtime(plan, 1);
            }
            cases++;
        }
        var tail = new Cycle(7, 1, 100);
        var entries = ring(List.of(tail, tail), 1);
        var stock = Map.of("t:0", 1L, "raw", 1_000_000_000L, "baton0", 1L);
        var plan = require(solve(entries, stock, 1_000_000_000L, Map.of(), false, 400_000));
        check(tail.calls < 150, "A finite prefix prevented observed loop reuse");
        source(plan, entries, stock, 1_000_000_000L); cases++;
    }

    private static void sharedRules() {
        for (boolean reverse : new boolean[]{false, true}) for (long n : new long[]{96, 1_000_000_000L}) {
            var a = new Cycle(7, 1, 0); var b = new Cycle(7, 2, 0);
            var entries = ring(List.of(a, b, a), 2);
            if (reverse) Collections.reverse(entries);
            var stock = new LinkedHashMap<String, Long>();
            stock.put(reverse ? "t:1" : "t:0", 1L); stock.put(reverse ? "t:0" : "t:1", 1L);
            stock.put("raw", n); stock.put("baton0", 1L);
            var solved = solve(entries, stock, n, Map.of(), false, 40_000_000);
            var plan = require(solved);
            if (n > 96) check(a.calls + b.calls < 300, "Shared-rule loops were expanded per request");
            source(plan, entries, stock, n); exactCounts(plan, entries, n);
            if (n == 96) { replay(plan); runtime(plan, 2); }
            cases++;
        }
    }

    private static void wide() {
        var family = new Cycle(2, 1, 0);
        var entries = ring(List.of(family, family), 1);
        var stock = Map.of("t:0", 2L, "raw", Long.MAX_VALUE, "baton0", 1L);
        var solved = solve(entries, stock, Long.MAX_VALUE, Map.of(), false, 160_000);
        var plan = require(solved);
        check(family.calls < 30, "Wide loop was restored one firing at a time");
        BigInteger total = plan.patternTimesExact().values().stream().reduce(BigInteger.ZERO, BigInteger::add);
        check(total.equals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)), "Loop jump overflowed exact firing count");
        source(plan, entries, stock, Long.MAX_VALUE); exactCounts(plan, entries, Long.MAX_VALUE); cases++;
    }

    private static void ordinaryAndConfigurations() {
        for (boolean reusable : new boolean[]{false, true}) {
            long n = 1_000_000_000L;
            var f = new Cycle(7, 1, 0); var entries = ring(List.of(f, f), 1);
            for (int i = 0; i < entries.size(); i++) {
                var old = entries.get(i); var inputs = new ArrayList<>(old.recipe().inputs());
                inputs.add(input("config", 1, null, true, reusable));
                entries.set(i, entry(old.id(), inputs, old.recipe().outputs()));
            }
            var stock = Map.of("t:0", 1L, "raw", n, "baton0", 1L, "config", reusable ? 1L : 2 * n);
            var plan = require(solve(entries, stock, n, Map.of(), false, 160_000));
            source(plan, entries, stock, n); exactCounts(plan, entries, n);
            if (!reusable) {
                var shortStock = new LinkedHashMap<>(stock); shortStock.put("config", 2 * n - 1);
                check(solve(entries, shortStock, n, Map.of(), false, 160_000).plan == null, "Repeated serial word reused consumed configuration");
            }
            cases++;
        }
        var f = new Cycle(7, 1, 0);
        var entries = ring(List.of(f, f), 1);
        var b = entries.get(1); var ordinary = new ArrayList<>(b.recipe().inputs()); ordinary.remove(0);
        var outputs = new ArrayList<>(b.recipe().outputs()); outputs.add(new CapturedRecipe.Amount<>("raw", 1));
        entries.set(1, entry(b.id(), ordinary, outputs));
        long n = 1_000_000_000L;
        var stock = Map.of("t:0", 1L, "raw", 1L, "baton0", 1L, "product", n);
        var plan = require(solve(entries, stock, n, Map.of("t:" + n % 7, 1L), true, 160_000));
        source(plan, entries, stock, n); exactCounts(plan, entries, n);
        check(f.calls < 30, "Stateful/ordinary loop lost acceleration");
        check(solve(entries, stock, n, Map.of("t:" + (n + 1) % 7, 1L), true, 160_000).plan == null, "Loop lost exact final state seed");
        var empty = new LinkedHashMap<>(stock); empty.put("raw", 0L);
        check(solve(entries, empty, n, Map.of(), true, 160_000).plan == null, "Net-zero raw flow erased startup input");
        cases += 3;
    }

    private static void replacements() {
        StateFamily<String> finite = new StateFamily<>() {
            public String id() { return "finite"; }
            public boolean accepts(String key) { return key.equals("t:0"); }
            public long capacity(String key) { return 1; }
            public String nominalAfterOne(String key) { return null; }
        };
        var entries = ring(List.of(finite, finite), 1);
        entries.add(entry("make", List.of(input("metal", 1, null, false, false)), List.of(new CapturedRecipe.Amount<>("t:0", 1))));
        var stock = Map.of("t:0", 1L, "raw", 32L, "metal", 63L, "baton0", 1L);
        var solved = solve(entries, stock, 32, Map.of(), false, 160_000);
        var plan = require(solved); source(plan, entries, stock, 32); replay(plan);
        check(solved.budget.diagnostics().contains("loop_jumps=0"), "Manufactured replacement masqueraded as a closed tool orbit");
        var missing = new LinkedHashMap<>(stock); missing.put("metal", 62L);
        check(solve(entries, missing, 32, Map.of(), false, 160_000).plan == null, "Loop invented a missing replacement"); cases += 2;
    }

    private static void nestedDispatches() {
        for (boolean changing : new boolean[]{false, true}) for (long n : new long[]{24, 1_000_000_000L}) {
            int width = 7;
            var f = new Cycle(changing ? 3 : 1, 1, 0);
            var tool = ring(List.of(f, f), 1).get(0).recipe().inputs().get(0);
            var entries = List.of(
                    entry("open", List.of(tool, input("raw", width, null, false, false), input("baton0", 1, null, false, false)),
                            List.of(new CapturedRecipe.Amount<>("mid", width), new CapturedRecipe.Amount<>("baton1", 1))),
                    entry("inner", List.of(tool, input("mid", 1, null, false, false), input("config", 1, null, true, false)),
                            List.of(new CapturedRecipe.Amount<>("part", 1))),
                    entry("close", List.of(input("part", width, null, false, false), input("baton1", 1, null, false, false)),
                            List.of(new CapturedRecipe.Amount<>("product", 1), new CapturedRecipe.Amount<>("baton0", 1))));
            var stock = Map.of("t:0", 1L, "raw", n * width, "config", n * width, "baton0", 1L);
            var solved = solve(entries, stock, n, Map.of(), false, 400_000);
            var plan = require(solved);
            source(plan, entries, stock, n);
            var counts = new LinkedHashMap<String, BigInteger>();
            plan.patternTimesExact().forEach((id, count) -> counts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
            check(counts.equals(Map.of("open", BigInteger.valueOf(n), "inner", BigInteger.valueOf(n * width), "close", BigInteger.valueOf(n))),
                    "Nested serial dispatch changed source counts");
            check(solved.budget.diagnostics().contains("loop_jumps=1"), "Nested word lost outer loop reuse: changing=" + changing + "; n=" + n + "; " + solved.budget.diagnostics());
            check(f.calls < 80, "Nested word expanded per order");
            if (n == 24) replay(plan);
            cases++;
        }
    }

    private static void limits() {
        var flag = new AtomicBoolean(); var f = new Cycle(31, 1, 0);
        var entries = ring(List.of(f, f), 1); var stock = Map.of("t:0", 1L, "raw", 1_000_000_000L, "baton0", 1L);
        var budget = new PlanningBudget(0, 160_000, 64L << 20, flag::get, System::nanoTime);
        try (var work = new CapturedStatePlanning<>(entries, "product", 1_000_000_000L, stock, Set.of(), Map.of(), false, false, budget)) {
            // A source/state pair first recurs after 62 calls. Cancel after
            // the outer checkpoint starts, while its next orbit is incomplete.
            while (f.calls < 70 && !work.step()) {}
            check(f.calls >= 70, "No partially observed loop reached"); flag.set(true);
            try { work.step(); throw new AssertionError("Ignored cancelled loop analysis"); }
            catch (CancellationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Cancelled loop retained memory");
        f = new Cycle(7, 1, 100_000);
        var limited = solve(ring(List.of(f, f), 1), stock, 1_000_000_000L, Map.of(), false, 8000);
        check(limited.plan == null && limited.outcome.status() == CapturedStatePlanning.Status.LIMITED, "Long transient became a negative proof");
        check(limited.budget.nodes() < 8000, "Loop detector renewed its arm allowance"); cases += 2;
    }

    private static void requestPath() {
        var f = new Cycle(7, 1, 0); var entries = ring(List.of(f, f), 1);
        var stock = Map.of("t:0", 1L, "raw", 1_000_000_000L, "baton0", 1L);
        var budget = new PlanningBudget(0, 160_000, 64L << 20, () -> false, System::nanoTime);
        try (var work = new CapturedCatalog<>(entries).begin("product", 1_000_000_000L, stock, false, false, budget)) {
            while (!work.step()) {}
            check(work.result().plan().feasible(), "Public request did not use observed loops");
            check(!work.result().coverage().complete(), "Observed word completed the arbitrary family domain");
            source(work.result().plan(), entries, stock, 1_000_000_000L);
        }
        check(budget.reservedBytes() == 0, "Public request leaked loop workspace"); cases++;
    }

    private static Solved solve(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n,
                                 Map<String, Long> seeds, boolean force, long cap) {
        var budget = new PlanningBudget(0, cap, 64L << 20, () -> false, System::nanoTime); budget.enableMetrics();
        Solved solved;
        try (var work = new CapturedStatePlanning<>(entries, "product", n, stock, Set.of(), seeds, false, force, budget)) {
            while (!work.step()) {}
            solved = new Solved(work.result(), work.outcome(), budget);
        }
        check(budget.reservedBytes() == 0, "Restoration leaked loop workspace"); PlanningCostTraceTest.conserved(budget); return solved;
    }
    private static GraphPlan<String> require(Solved solved) {
        check(solved.plan != null && solved.outcome.status() == CapturedStatePlanning.Status.VERIFIED,
                "No exact loop witness: " + solved.outcome + "; " + solved.budget.diagnostics()); return solved.plan;
    }
    private static ArrayList<CapturedCatalog.Entry<String>> ring(List<? extends StateFamily<String>> families, int tools) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int i = 0; i < families.size(); i++) {
            var f = families.get(i);
            var tool = new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("t:0", 1),
                    f instanceof Cycle c ? c.after("t:0") : null, false, false, f)), CapturedRecipe.InputMode.MIXED);
            entries.add(entry("source" + i, List.of(tool, input(i == 0 ? "raw" : "mid" + i, 1, null, false, false),
                    input("baton" + i, 1, null, false, false)), List.of(new CapturedRecipe.Amount<>(i + 1 == families.size() ? "product" : "mid" + (i + 1), 1),
                    new CapturedRecipe.Amount<>("baton" + ((i + 1) % families.size()), 1))));
        }
        return entries;
    }
    private static CapturedRecipe.Input<String> input(String key, long quantity, String remaining, boolean configuration, boolean reusable) {
        return new CapturedRecipe.Input<>(quantity, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), remaining, configuration, reusable)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, List<CapturedRecipe.Amount<String>> outputs) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, outputs, false, false));
    }
    private static void exactCounts(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, long n) {
        var counts = new LinkedHashMap<String, BigInteger>();
        plan.patternTimesExact().forEach((id, count) -> counts.merge(plan.recipes().get(id).binding(), count, BigInteger::add));
        for (var entry : entries) check(counts.get(entry.binding()).equals(BigInteger.valueOf(n)), "Cross-source count changed");
    }
    private static void source(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        plan.initialExact().forEach((key, count) -> check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented initial material"));
        for (var recipe : plan.recipes().values()) {
            var entry = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var outputs = new LinkedHashMap<String, Long>(); long[] filled = new long[entry.recipe().inputs().size()];
            entry.recipe().outputs().forEach(o -> outputs.merge(o.what(), o.amount(), Math::addExact));
            for (var slot : recipe.slots()) {
                var in = entry.recipe().inputs().get(slot.inputSlot()); var c = in.candidates().get(0); filled[slot.inputSlot()] += slot.amount();
                check(slot.configuration() == c.configuration() && slot.reusable() == c.reusable(), "Changed dispatch configuration");
                if (c.family() instanceof Cycle f) { check(f.accepts(slot.key()), "Changed tool domain"); outputs.merge(f.after(slot.key()), slot.amount(), Math::addExact); }
                else if (c.family() != null) check(c.family().accepts(slot.key()) && c.family().nominalAfterOne(slot.key()) == null, "Changed finite tool");
                else { check(c.stack().what().equals(slot.key()), "Changed source input"); if (c.remaining() != null) outputs.merge(c.remaining(), slot.amount(), Math::addExact); }
            }
            for (int i = 0; i < filled.length; i++) check(filled[i] == entry.recipe().inputs().get(i).multiplier(), "Lost source slot");
            check(outputs.equals(recipe.executionOutputs()), "Loop changed original source outputs");
        }
        check(plan.amount() == n, "Changed order quantity");
    }
    private static void replay(GraphPlan<String> plan) {
        var inventory = new LinkedHashMap<>(plan.initial()); var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                recipe.inputs().forEach((key, count) -> check(inventory.getOrDefault(key, 0L) >= count, "Unfunded primitive source order"));
                recipe.dispatchInputs(1).forEach((key, count) -> inventory.merge(key, -count, Long::sum));
                recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, count, Long::sum));
                check(++fired < 5000, "Unexpected primitive expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", 0L) >= plan.amount(), "Primitive replay lost delivery");
    }
    private static void runtime(GraphPlan<String> plan, int tools) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial()), refunded = new LinkedHashMap<>();
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered, inFlight; int attempts;
            public long capacity(GraphRecipe<String> recipe, long requested) { return Math.min(4, requested); }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                if (++attempts % 11 == 1) return GraphJobRuntime.Outcome.REJECTED;
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Dispatched an unreceived input"); physical.merge(key, -count, Long::sum);
                    if (key.startsWith("t:")) inFlight += count;
                });
                check(inFlight <= tools, "Loop multiplied concurrent physical tools");
                var outputs = new LinkedHashMap<String, Long>(); recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { refunded.merge(key, count, Long::sum); return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 6000 && !host.job.finished(); tick++) {
            host.job.tick(host, tick * 20L, 4, () -> 0L);
            if (tick % 17 == 0) host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var outputs : host.pending) outputs.forEach((key, count) -> {
                if (key.startsWith("t:")) host.inFlight -= count;
                host.physical.merge(key, count, Long::sum); check(host.job.accept(key, count, false) == count, "Lost delayed return");
            });
            host.pending.clear();
        }
        check(host.job.state() == GraphJobRuntime.State.COMPLETED && host.delivered == plan.amount(), "Cross-source runtime stalled");
        check(host.pending.isEmpty() && host.inFlight == 0, "Completed with owed returns");
        check(host.refunded.entrySet().stream().filter(e -> e.getKey().startsWith("t:")).mapToLong(Map.Entry::getValue).sum() == tools, "Final tool ownership changed");
    }
    private static int nodes(PlanStep step) {
        if (step instanceof PlanStep.Sequence s) return 1 + s.children().stream().mapToInt(CapturedStateLoopTest::nodes).sum();
        return step instanceof PlanStep.Repeat r ? 1 + nodes(r.body()) : 1;
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
