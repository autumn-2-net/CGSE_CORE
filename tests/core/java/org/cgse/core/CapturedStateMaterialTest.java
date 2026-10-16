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
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact returned states can be ordinary ingredients, request targets or seeds. */
public final class CapturedStateMaterialTest {
    private static int checks, cases;
    private static long maxWork, maxBytes;

    private static final class Wear implements StateFamily<String> {
        int transitions;
        public String id() { return "material-wear"; }
        public boolean accepts(String key) { return key.startsWith("tool:"); }
        public long capacity(String key) { return Long.parseLong(key.substring(5)); }
        public String nominalAfterOne(String key) { transitions++; return next(key); }
    }

    private record Input(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
                         String target, Map<String, Long> seeds, Wear rule) {}

    public static void main(String[] args) {
        for (long capacity : new long[]{2, 3, 8, 8192, Long.MAX_VALUE})
            for (boolean reverse : List.of(false, true)) for (int mode = 0; mode < 5; mode++) {
                var input = input(capacity, reverse, mode, 1, false);
                var plan = solve(input, new CapturedCatalog<>(input.entries()), 1, false, false, true);
                if (capacity == 3 && !reverse) runtime(plan, 1);
            }
        for (boolean reverse : List.of(false, true)) for (int tools : new int[]{1, 2, 4})
            for (boolean manufacture : List.of(false, true)) {
                var input = input(8192, reverse, 0, tools, manufacture);
                solve(input, new CapturedCatalog<>(input.entries()), 1, false, false, true);
            }
        controls(); limits();
        System.out.println("CapturedStateMaterialTest: cases=" + cases + "; assertions=" + checks +
                "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    // Modes: physical ingredient, physical target, final seed, reusable read arc,
    // consumed per-dispatch configuration. One use returns the declared n-1;
    // another must supply the exact n-2, without expanding the whole wear range.
    private static Input input(long n, boolean reverse, int mode, int tools, boolean manufacture) {
        var family = new Wear(); String first = "tool:" + n, second = "tool:" + (n - 1);
        String last = n == 2 ? "empty" : "tool:" + (n - 2);
        var state = new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>(first, 1), second, false, false, family)), CapturedRecipe.InputMode.INDIVISIBLE);
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        entries.add(entry("use", List.of(state, ordinary("raw", 1, false, false)), "part", 1));
        String target = mode == 1 ? last : mode == 2 ? "part" : "product";
        Map<String, Long> seeds = mode == 2 ? Map.of(last, (long) tools) : Map.of();
        if (mode != 1 && mode != 2)
            entries.add(entry("finish", List.of(ordinary(last, tools, mode >= 3, mode == 3)), "product", 1));
        var stock = new LinkedHashMap<String, Long>(); stock.put("raw", 2L);
        if (manufacture) {
            entries.add(entry("forge", List.of(ordinary("metal", 1, false, false)), first, 1));
            stock.put("metal", (long) tools);
        } else stock.put(first, (long) tools);
        if (reverse) {
            Collections.reverse(entries);
            var values = new ArrayList<>(stock.entrySet()); Collections.reverse(values); stock.clear();
            values.forEach(value -> stock.put(value.getKey(), value.getValue()));
        }
        return new Input(entries, stock, target, seeds, family);
    }

    private static GraphPlan<String> solve(Input input, CapturedCatalog<String> catalog, long amount,
            boolean preserve, boolean force, boolean feasible) {
        var budget = budget(40_000_000, 64L << 20); budget.enableMetrics(); GraphPlan<String> plan;
        int before = input.rule().transitions;
        try (var work = catalog.begin(input.target(), amount, input.stock(), Set.of(), input.seeds(), preserve, force, budget)) {
            while (!work.step()) {}
            plan = work.result().plan();
            check(plan.feasible() == feasible, "Unexpected material state result: " + input + "; " + plan.result() + "; " + budget.diagnostics());
            check(!work.result().coverage().complete(), "Finite material view certified full state coverage");
            check(plan.result() != GraphPlan.Result.INFEASIBLE, "Restricted failure became global UNSAT");
            if (feasible) {
                check(budget.diagnostics().contains("candidate_state_boundary") && budget.diagnostics().contains("verified; states="),
                        "Missed boundary recovery");
                verify(plan, input, amount);
                try { work.certificate(100_000, 1L << 20); throw new AssertionError("Exported dynamic family as complete proof"); }
                catch (UnsupportedOperationException expected) { checks++; }
            }
        }
        check(input.rule().transitions - before < 128, "Enumerated unused durability / electric charge");
        check(budget.reservedBytes() == 0, "Material boundary leaked workspace");
        check(budget.searchWork() < 1_000_000, "Small declared boundary inflated");
        PlanningCostTraceTest.conserved(budget);
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        return plan;
    }

    private static void controls() {
        var original = input(8, false, 0, 1, false); var catalog = new CapturedCatalog<>(original.entries());
        // Repeated immutable capture, current amounts; candidate keys never fund
        // the physical transitions. Failed trials cannot poison later requests.
        for (long raw : new long[]{1, 2, 0, 2}) {
            var stock = new LinkedHashMap<>(original.stock()); stock.put("raw", raw);
            solve(new Input(original.entries(), stock, original.target(), Map.of(), original.rule()), catalog, 1, false, false, raw >= 2);
        }
        for (var stock : List.of(Map.of("raw", 2L), Map.of("raw", 2L, "tool:8", 1L))) {
            var two = input(8, false, 0, 2, false);
            solve(new Input(two.entries(), stock, two.target(), Map.of(), two.rule()), new CapturedCatalog<>(two.entries()), 1, false, false, false);
        }
        var manufactured = input(8, false, 0, 2, true);
        solve(new Input(manufactured.entries(), Map.of("raw", 2L, "metal", 1L), manufactured.target(), Map.of(), manufactured.rule()),
                new CapturedCatalog<>(manufactured.entries()), 1, false, false, false);
        // A returned key spent by finish cannot also satisfy a final seed.
        solve(new Input(original.entries(), original.stock(), "product", Map.of("tool:6", 1L), original.rule()), catalog, 1, false, false, false);
        var seed = input(8, false, 2, 1, false);
        var available = new LinkedHashMap<>(seed.stock()); available.put("part", 5L);
        var forced = new Input(seed.entries(), available, seed.target(), seed.seeds(), seed.rule());
        solve(forced, new CapturedCatalog<>(seed.entries()), 1, false, true, true);
        solve(forced, new CapturedCatalog<>(seed.entries()), 1, true, true, true);
        // A named distant state does not authorize a full recursive wear closure.
        var far = input(8192, false, 0, 1, false);
        var distant = new ArrayList<>(far.entries());
        distant.set(1, entry("finish", List.of(ordinary("tool:1", 1, false, false)), "product", 1));
        solve(new Input(distant, far.stock(), "product", Map.of(), far.rule()), new CapturedCatalog<>(distant), 1, false, false, false);
    }

    private static void limits() {
        var input = input(8, false, 2, 1, false);
        for (long cap : new long[]{1, 64, 256, 1024, 16_384}) {
            var budget = budget(cap, 64L << 20);
            try (var work = new CapturedCatalog<>(input.entries()).begin(input.target(), 1, input.stock(), Set.of(), input.seeds(), false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted expected) { /* Remains inconclusive. */ }
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Recovery renewed search quota");
        }
        for (long bytes : new long[]{1024, 2048, 4096, 8192, 32_768}) {
            var budget = budget(40_000_000, bytes);
            try (var expansion = CapturedStateExpansion.create(input.entries(), List.of("tool:8", "tool:7"), input.target(), 1,
                    input.stock(), Set.of(), input.seeds(), false, false, budget)) {
                if (expansion != null) { while (!expansion.step()) {} if (expansion.result() != null) verify(expansion.result(), input, 1); }
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Recovery exceeded / retained workspace");
        }
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime); budget.enableMetrics();
        try (var work = new CapturedCatalog<>(input.entries()).begin(input.target(), 1, input.stock(), Set.of(), input.seeds(), false, false, budget)) {
            while (!work.step()) if (budget.diagnostics().contains("expand_boundary")) { cancelled.set(true); break; }
            boolean stopped = false;
            try { work.step(); } catch (java.util.concurrent.CancellationException expected) { stopped = true; }
            check(cancelled.get() && stopped, "Boundary retry ignored cancellation");
        }
        check(budget.reservedBytes() == 0, "Cancelled material boundary leaked");
    }

    private static void verify(GraphPlan<String> plan, Input input, long amount) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        for (var recipe : plan.recipes().values()) {
            var source = input.entries().stream().filter(s -> s.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var outputs = new LinkedHashMap<String, Long>(); var chosen = new LinkedHashMap<Integer, String>();
            long[] fill = new long[source.recipe().inputs().size()];
            for (var slot : recipe.slots()) {
                var original = source.recipe().inputs().get(slot.inputSlot()); var candidate = original.candidates().get(0);
                fill[slot.inputSlot()] += slot.amount();
                String previous = chosen.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(original) || previous == null || previous.equals(slot.key()), "Mixed an indivisible tool slot");
                check(slot.configuration() == candidate.configuration() && slot.reusable() == candidate.reusable(), "Changed input contract");
                if (candidate.family() != null) {
                    check(slot.key().startsWith("tool:") && Long.parseLong(slot.key().substring(5)) > 0, "Invented tool rule");
                    outputs.merge(next(slot.key()), slot.amount(), Math::addExact);
                } else check(slot.key().equals(candidate.stack().what()), "Changed ordinary ingredient");
            }
            for (int i = 0; i < fill.length; i++) check(fill[i] == source.recipe().inputs().get(i).multiplier(), "Changed simultaneous input count");
            source.recipe().outputs().forEach(out -> outputs.merge(out.what(), out.amount(), Math::addExact));
            check(outputs.equals(recipe.executionOutputs()), "Changed exact state returns");
        }
        var stock = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, count) -> {
            check(count.compareTo(BigInteger.valueOf(input.stock().getOrDefault(key, 0L))) <= 0, "Invented starting stock"); stock.put(key, count);
        });
        var cursor = new PlanCursor(plan.steps()); int firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            var needed = new LinkedHashMap<String, BigInteger>(); var spent = new LinkedHashMap<String, BigInteger>();
            for (var slot : recipe.slots()) {
                var count = BigInteger.valueOf(slot.amount()).multiply(BigInteger.valueOf(slot.configuration() ? 1 : batch.runs()));
                needed.merge(slot.key(), count, BigInteger::add);
                if (!slot.reusable()) spent.merge(slot.key(), count, BigInteger::add);
            }
            needed.forEach((key, count) -> {
                check(stock.getOrDefault(key, BigInteger.ZERO).compareTo(count) >= 0, "Physical prefix not funded");
            });
            spent.forEach((key, count) -> stock.merge(key, count.negate(), BigInteger::add));
            recipe.executionOutputs().forEach((key, count) -> stock.merge(key, BigInteger.valueOf(count).multiply(BigInteger.valueOf(batch.runs())), BigInteger::add));
            check(++firings < 100, "Small physical boundary inflated"); cursor.dispatched(batch.runs());
        }
        check(stock.getOrDefault(input.target(), BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0, "Missed physical target");
        input.seeds().forEach((key, count) -> check(stock.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(count)) >= 0, "Missed physical final seed"));
    }

    private static void runtime(GraphPlan<String> plan, long amount) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Dispatched a tool still in flight"); physical.merge(key, -count, Long::sum);
                });
                var outputs = new LinkedHashMap<String, Long>(); recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 100 && host.delivered < amount; tick++) {
            host.job.tick(host, tick * 20L, 4, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum);
                check(host.job.accept(key, count, false) == count, "Lost exact delayed state return");
            });
            host.pending.clear();
        }
        check(host.delivered == amount, "Material state runtime stalled");
    }

    private static String next(String key) { long n = Long.parseLong(key.substring(5)); return n == 1 ? "empty" : "tool:" + (n - 1); }
    private static CapturedRecipe.Input<String> ordinary(String key, long count, boolean configuration, boolean reusable) {
        return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, configuration, reusable)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output, long amount) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, amount)), false, false));
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
