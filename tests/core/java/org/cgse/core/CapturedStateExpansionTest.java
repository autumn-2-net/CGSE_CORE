// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Omitted state candidates can hide a physical cross-rule cycle. */
public final class CapturedStateExpansionTest {
    private static int checks, cases;
    private static long maxWork, maxBytes;

    private record Rule(int parity, int length) implements StateFamily<String> {
        public String id() { return "phase-" + parity + "/" + length; }
        public boolean accepts(String key) { return key.startsWith("tool:") && (index(key) & 1) == parity; }
        public long capacity(String key) { return accepts(key) ? 1 : 0; }
        public String nominalAfterOne(String key) { return key.substring(0, key.lastIndexOf(':') + 1) + ((index(key) + 1) % length); }
    }

    public static void main(String[] args) {
        for (int length : new int[]{4, 8}) for (int permutation = 0; permutation < 4; permutation++)
            for (long n : new long[]{1, 7, 1_000_000_000L, Long.MAX_VALUE}) {
                var plan = solve(entries(length, 1, false, permutation), stock(n, 1, false, permutation), n, Map.of(), false, false, true);
                if (n == 7 && permutation == 0) runtime(plan, n);
            }
        for (boolean mixed : List.of(false, true)) for (int permutation = 0; permutation < 4; permutation++) {
            var plan = solve(entries(4, 2, mixed, permutation), stock(7, 2, mixed, permutation), 7, Map.of(), false, false, true);
            if (permutation == 0) runtime(plan, 7);
        }
        boundaries(); noStateEnumeration(); limits();
        System.out.println("CapturedStateExpansionTest: cases=" + cases + "; assertions=" + checks +
                "; max_work=" + maxWork + "; max_bytes=" + maxBytes);
    }

    private static GraphPlan<String> solve(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long n,
            Map<String, Long> seeds, boolean preserve, boolean force, boolean expanded) {
        var budget = budget(40_000_000, 64L << 20); budget.enableMetrics(); GraphPlan<String> plan;
        try (var work = new CapturedCatalog<>(entries).begin("product", n, stock, Set.of(), seeds, preserve, force, budget)) {
            while (!work.step()) {}
            plan = work.result().plan();
            check(plan.feasible(), "Lost a constructive physical cycle: " + n + "; " + budget.diagnostics());
            check(!work.result().coverage().complete(), "Finite expansion certified whole state domain");
            if (expanded) check(budget.diagnostics().contains("candidate_state_boundary") && budget.diagnostics().contains("verified; states="),
                    "Did not exercise exact boundary expansion");
            try { work.certificate(100_000, 1L << 20); throw new AssertionError("Exported dynamic algebra as finite proof"); }
            catch (UnsupportedOperationException expected) { checks++; }
        }
        check(budget.reservedBytes() == 0, "Expanded capture leaked");
        check(budget.searchWork() < 1_000_000 && budget.peakBytes() < (32L << 20), "Small state boundary inflated");
        PlanningCostTraceTest.conserved(budget); verify(plan, entries, stock, n);
        maxWork = Math.max(maxWork, budget.searchWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        return plan;
    }

    private static void boundaries() {
        var entries = entries(4, 1, false, 0);
        solve(entries, stock(7, 1, false, 0), 7, Map.of("tool:A:2", 1L), false, false, true);
        solve(entries, stock(8, 1, false, 0), 8, Map.of("tool:A:0", 1L), true, false, true);
        var available = stock(7, 1, false, 0); available.put("product", 5L);
        solve(entries, available, 7, Map.of(), false, true, true);
        // Exact final state tool:0 requires an even number of pairs. Raw stock
        // funds only seven, so the finite domain cannot promise this seed.
        negative(entries, stock(7, 1, false, 0), Map.of("tool:A:0", 1L));
        negative(entries, Map.of("raw", 7L), Map.of());
        negative(entries, Map.of("tool:A:0", 1L), Map.of());
        negative(entries(4, 2, false, 0), stock(7, 1, false, 0), Map.of());
        negative(entries(4, 2, false, 0), stock(7, 2, true, 0), Map.of());
        // A real ordinary producer can fund a missing tool; the same producer
        // without its material cannot bootstrap a tool out of a candidate key.
        var manufactured = new ArrayList<>(entries);
        manufactured.add(entry("forge", List.of(ordinary("metal")), "tool:A:0"));
        solve(manufactured, Map.of("raw", 7L, "metal", 1L), 7, Map.of(), false, false, true);
        negative(manufactured, Map.of("raw", 7L), Map.of());
        // Same immutable catalog, different requests: a failed finite capture
        // must not poison a later stock change, nor share its claimed inventory.
        var catalog = new CapturedCatalog<>(entries);
        for (var stock : List.of(Map.of("raw", 1L), Map.of("raw", 1L, "tool:A:0", 1L), Map.of("raw", 1L))) {
            var budget = budget(40_000_000, 64L << 20);
            try (var work = catalog.begin("product", 1, stock, false, false, budget)) {
                while (!work.step()) {}
                check(work.result().plan().feasible() == stock.containsKey("tool:A:0"), "Cross-request state domain became stock / stale failure");
                check(!work.result().coverage().complete(), "Request mutated canonical coverage");
            }
            check(budget.reservedBytes() == 0, "Repeated request leaked");
        }
    }

    private static void negative(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, Map<String, Long> seeds) {
        var budget = budget(40_000_000, 64L << 20); budget.enableMetrics();
        try (var work = new CapturedCatalog<>(entries).begin("product", 7, stock, Set.of(), seeds, false, false, budget)) {
            while (!work.step()) {}
            check(!work.result().plan().feasible(), "Invented tool, material or exact seed");
            check(work.result().plan().result() != GraphPlan.Result.INFEASIBLE && !work.result().coverage().complete(), "Restricted domain proved global infeasibility");
        }
        check(budget.reservedBytes() == 0, "Unfunded expansion leaked");
    }

    private static void noStateEnumeration() {
        final int[] calls = {0};
        final class Wear implements StateFamily<String> {
            private final String id;
            Wear(String id) { this.id = id; }
            public String id() { return id; }
            public boolean accepts(String key) { return key.startsWith("wear:") && Long.parseLong(key.substring(5)) > 0; }
            public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(5)) : 0; }
            public String nominalAfterOne(String key) { calls[0]++; return "wear:" + (Long.parseLong(key.substring(5)) - 1); }
        }
        for (long capacity : new long[]{8192, Long.MAX_VALUE}) {
            calls[0] = 0; String key = "wear:" + capacity;
            var entries = new ArrayList<CapturedCatalog.Entry<String>>();
            for (int i = 0; i < 2; i++) {
                var candidate = new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), "wear:" + (capacity - 1), false, false, new Wear("rule" + i));
                entries.add(entry("wear" + i, List.of(new CapturedRecipe.Input<>(1, List.of(candidate))), "product"));
            }
            var budget = budget(40_000_000, 64L << 20);
            try (var expansion = CapturedStateExpansion.create(entries, Set.of(key, "wear:" + (capacity - 1)), "product", 1,
                    Map.of(), Set.of(), Map.of(), false, false, budget)) {
                check(expansion != null, "Wear bound test not admitted"); while (!expansion.step()) {}
                check(expansion.result() == null, "Candidate durability became inventory");
                check(budget.diagnostics().contains("reason=no_eligibility_gain"), "Same-rule wear entered another finite solve");
            }
            check(calls[0] <= 4, "Enumerated same-rule durability / electric charge: " + calls[0]);
            check(budget.reservedBytes() == 0, "Wear domain leaked");
        }
        // Unbounded alternating eligibility cannot turn a local expansion into
        // a closure claim or consume unlimited workspace.
        final class Unbounded implements StateFamily<String> {
            final int parity; Unbounded(int parity) { this.parity = parity; }
            public String id() { return "unbounded-" + parity; }
            public boolean accepts(String key) { return key.startsWith("tool:") && (index(key) & 1) == parity; }
            public long capacity(String key) { return accepts(key) ? 1 : 0; }
            public String nominalAfterOne(String key) { return "tool:A:" + (index(key) + 1); }
        }
        var infinite = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int i = 0; i < 2; i++) infinite.add(entry("unbounded" + i, List.of(new CapturedRecipe.Input<>(1,
                List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>("tool:A:" + i, 1), "tool:A:" + (i + 1), false, false, new Unbounded(i))))), "product"));
        var budget = budget(40_000_000, 64L << 20); budget.enableMetrics();
        try (var expansion = CapturedStateExpansion.create(infinite, Set.of("tool:A:0"), "product", 1, Map.of("tool:A:0", 1L), Set.of(), Map.of(), false, false, budget)) {
            while (!expansion.step()) {}
            check(expansion.result() == null && budget.diagnostics().contains("reason=state_limit"), "Unbounded domain certified complete");
        }
        check(budget.reservedBytes() == 0 && budget.peakBytes() < (256L << 10), "Unbounded domain escaped workspace bound");
        // Ordinary outputs also belong to the finite support admission limit.
        var wide = new ArrayList<>(entries(4, 1, false, 0));
        var outputs = new ArrayList<CapturedRecipe.Amount<String>>();
        for (int i = 0; i < 4096; i++) outputs.add(new CapturedRecipe.Amount<>("unused:" + i, 1));
        wide.add(new CapturedCatalog.Entry<>("wide", "wide", new CapturedRecipe<>(List.of(ordinary("absent")), outputs, false, false)));
        budget = budget(40_000_000, 64L << 20); budget.enableMetrics();
        try (var expansion = CapturedStateExpansion.create(wide, Set.of("tool:A:0"), "product", 1, Map.of(), Set.of(), Map.of(), false, false, budget)) {
            while (!expansion.step()) {}
            check(expansion.result() == null && budget.diagnostics().contains("reason=domain_limit"), "Unbounded ordinary output set bypassed admission");
        }
        check(budget.reservedBytes() == 0, "Oversized output domain leaked");
    }

    private static void limits() {
        var entries = entries(4, 1, false, 0); var stock = stock(7, 1, false, 0);
        for (long cap : new long[]{1, 1024, 16_384, 48_000}) {
            var budget = budget(cap, 64L << 20);
            try (var work = new CapturedCatalog<>(entries).begin("product", 7, stock, false, false, budget)) {
                try { while (!work.step()) {} } catch (PlanningBudget.Exhausted limit) { /* Inconclusive. */ }
            }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap + 1, "Expansion renewed work allowance");
        }
        var known = new LinkedHashSet<>(List.of("tool:A:0", "tool:A:1", "tool:A:3"));
        for (long bytes : new long[]{1024, 2048, 4096, 8192, 32_768}) {
            var budget = budget(40_000_000, bytes);
            try (var expansion = CapturedStateExpansion.create(entries, known, "product", 7, stock, Set.of(), Map.of(), false, false, budget)) {
                if (expansion != null) { while (!expansion.step()) {} if (expansion.result() != null) verify(expansion.result(), entries, stock, 7); }
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Expansion retained / exceeded memory");
        }
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        try (var work = new CapturedCatalog<>(entries).begin("product", 7, stock, false, false, budget)) {
            while (!work.step()) if (budget.diagnostics().contains("candidate_state_boundary")) { cancel.set(true); break; }
            boolean stopped = false;
            try { work.step(); } catch (java.util.concurrent.CancellationException expected) { stopped = true; }
            check(cancel.get() && stopped, "Cancellation did not interrupt private solve");
        }
        check(budget.reservedBytes() == 0, "Cancelled expansion leaked");
    }

    private static List<CapturedCatalog.Entry<String>> entries(int length, int tools, boolean mixed, int permutation) {
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        for (int phase = 0; phase < 2; phase++) {
            var family = new Rule(phase, length); String key = "tool:A:" + (phase == 0 ? 0 : length - 1);
            var inputs = new ArrayList<>(List.of(new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>(key, 1), family.nominalAfterOne(key), false, false, family)),
                    mixed ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE), ordinary(phase == 0 ? "raw" : "part")));
            if ((permutation & 1) != 0) Collections.reverse(inputs);
            entries.add(entry(phase == 0 ? "even" : "odd", inputs, phase == 0 ? "part" : "product"));
        }
        if ((permutation & 2) != 0) Collections.reverse(entries);
        return entries;
    }

    private static LinkedHashMap<String, Long> stock(long n, int tools, boolean mixed, int permutation) {
        var stock = new LinkedHashMap<String, Long>();
        if (mixed) { stock.put("tool:A:0", 1L); stock.put("tool:B:0", (long) tools - 1); }
        else stock.put("tool:A:0", (long) tools);
        stock.put("raw", n);
        if ((permutation & 1) != 0) {
            var items = new ArrayList<>(stock.entrySet()); Collections.reverse(items); stock.clear();
            items.forEach(item -> stock.put(item.getKey(), item.getValue()));
        }
        return stock;
    }

    private static void verify(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> sources, Map<String, Long> stock, long n) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, count) -> {
            check(count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented initial entity"); inventory.put(key, count);
        });
        for (var recipe : plan.recipes().values()) {
            var source = sources.stream().filter(s -> s.binding().equals(recipe.binding())).findFirst().orElseThrow();
            var outputs = new LinkedHashMap<String, Long>(); var keys = new LinkedHashMap<Integer, String>(); long[] filled = new long[source.recipe().inputs().size()];
            for (var slot : recipe.slots()) {
                var input = source.recipe().inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                filled[slot.inputSlot()] += slot.amount(); String previous = keys.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(input) || previous == null || previous.equals(slot.key()), "Split a whole-key simultaneous input");
                if (candidate.family() instanceof Rule rule) {
                    check(slot.key().startsWith("tool:") && (index(slot.key()) & 1) == rule.parity(), "Used an ineligible rule");
                    String after = slot.key().substring(0, slot.key().lastIndexOf(':') + 1) + ((index(slot.key()) + 1) % rule.length());
                    outputs.merge(after, slot.amount(), Math::addExact);
                } else check(candidate.stack().what().equals(slot.key()), "Changed an ordinary input");
            }
            for (int i = 0; i < filled.length; i++) check(filled[i] == source.recipe().inputs().get(i).multiplier(), "Changed simultaneous multiplicity");
            source.recipe().outputs().forEach(output -> outputs.merge(output.what(), output.amount(), Math::addExact));
            check(outputs.equals(recipe.executionOutputs()), "Lost actual returns or source outputs");
        }
        if (n > 32) return;
        var cursor = new PlanCursor(plan.steps()); int firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                for (var slot : recipe.slots()) {
                    var available = inventory.getOrDefault(slot.key(), BigInteger.ZERO); var demand = BigInteger.valueOf(slot.amount());
                    check(available.compareTo(demand) >= 0, "Reused an entity inside the same firing"); inventory.put(slot.key(), available.subtract(demand));
                }
                recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, BigInteger.valueOf(count), BigInteger::add));
                check(++firings < 1000, "Uncompressed small witness");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("product", BigInteger.ZERO).compareTo(BigInteger.valueOf(n)) >= 0, "Literal replay missed target");
    }

    private static void runtime(GraphPlan<String> plan, long n) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> { check(physical.getOrDefault(key, 0L) >= count, "Used a tool still in flight"); physical.merge(key, -count, Long::sum); });
                var outputs = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 1000 && host.delivered < n; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum); check(host.job.accept(key, count, false) == count, "Lost actual return across reload");
            });
            host.pending.clear();
        }
        check(host.delivered == n, "Expanded plan stalled on delayed returns");
    }

    private static int index(String key) { return Integer.parseInt(key.substring(key.lastIndexOf(':') + 1)); }
    private static CapturedRecipe.Input<String> ordinary(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String output) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(output, 1)), false, false));
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
