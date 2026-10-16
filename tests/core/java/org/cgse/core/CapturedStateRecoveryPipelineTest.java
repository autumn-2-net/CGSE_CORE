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

/** Implicit physical returns must remain discoverable in a large captured catalog. */
public final class CapturedStateRecoveryPipelineTest {
    private static int checks, cases;
    private static long maxCompile, maxBytes, maxWarmCompile;
    private static final class Wear implements StateFamily<String> {
        int transitions;
        public String id() { return "wear"; }
        public boolean accepts(String key) { return key.startsWith("tool:"); }
        public long capacity(String key) { return Long.parseLong(key.substring(5)); }
        public String nominalAfterOne(String key) { transitions++; return next(key); }
    }
    private record Noise(int number) implements StateFamily<String> {
        public String id() { return "noise:" + number; }
        public boolean accepts(String key) { return key.startsWith("noise-tool:" + number + ":"); }
        public long capacity(String key) { return Long.parseLong(key.substring(key.lastIndexOf(':') + 1)); }
        public String nominalAfterOne(String key) { return capacity(key) == 1 ? "shell:" + number : "noise-tool:" + number + ":1"; }
    }
    private record Input(List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock,
                         String target, Map<String, Long> seeds, Wear rule) {}

    public static void main(String[] args) {
        for (int filler : new int[]{512, 16_384}) for (long n : new long[]{2, 8192})
            for (int mode = 0; mode < 3; mode++) for (boolean reverse : List.of(false, true))
                for (boolean stateNoise : List.of(false, true)) {
                    var input = input(filler, n, mode, reverse, stateNoise, 1, false);
                    var catalog = new CapturedCatalog<>(input.entries());
                    // The second request retains the immutable producer index;
                    // the third must reuse the newly recovered source support.
                    for (int pass = 0; pass < 3; pass++) solve(input, catalog, true, pass == 2);
                }
        for (boolean reverse : List.of(false, true)) for (int tools : new int[]{1, 2})
            for (boolean manufacture : List.of(false, true)) {
                var input = input(512, Long.MAX_VALUE, 0, reverse, false, tools, manufacture);
                var catalog = new CapturedCatalog<>(input.entries());
                for (int pass = 0; pass < 3; pass++) {
                    var plan = solve(input, catalog, true, pass == 2);
                    if (pass == 2) runtime(plan);
                }
            }
        controls();
        System.out.println("CapturedStateRecoveryPipelineTest: cases=" + cases + "; assertions=" + checks +
                "; max_compile=" + maxCompile + "; max_bytes=" + maxBytes + "; max_warm_compile=" + maxWarmCompile);
    }

    private static Input input(int filler, long n, int mode, boolean reverse, boolean stateNoise, int tools, boolean manufacture) {
        var rule = new Wear(); String last = n == 2 ? "empty" : "tool:" + (n - 2);
        String target = mode == 0 ? "product" : mode == 1 ? last : "part";
        var entries = new ArrayList<CapturedCatalog.Entry<String>>();
        var state = new CapturedRecipe.Input<>(tools, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("tool:" + n, 1), "tool:" + (n - 1), false, false, rule)), CapturedRecipe.InputMode.INDIVISIBLE);
        entries.add(entry("use", List.of(state, ordinary("raw", 1)), "part"));
        if (mode == 0) entries.add(entry("finish", List.of(ordinary(last, tools)), target));
        var stock = new LinkedHashMap<String, Long>(); stock.put("raw", 2L);
        if (manufacture) {
            entries.add(entry("forge", List.of(ordinary("metal", 1)), "tool:" + n)); stock.put("metal", (long) tools);
        } else stock.put("tool:" + n, (long) tools);
        for (int i = 0; i < filler; i++) {
            var inputs = new ArrayList<CapturedRecipe.Input<String>>();
            if (stateNoise) inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>("noise-tool:" + i + ":2", 1), "noise-tool:" + i + ":1", false, false, new Noise(i)))));
            inputs.add(ordinary("absent:" + i, 1)); entries.add(entry("noise:" + i, inputs, "unused:" + i));
        }
        if (reverse) Collections.reverse(entries);
        return new Input(entries, stock, target, mode == 2 ? Map.of(last, (long) tools) : Map.of(), rule);
    }

    private static GraphPlan<String> solve(Input input, CapturedCatalog<String> catalog, boolean feasible, boolean warm) {
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, () -> false, System::nanoTime); budget.enableMetrics();
        GraphPlan<String> plan; int before = input.rule().transitions;
        try (var work = catalog.begin(input.target(), 1, input.stock(), Set.of(), input.seeds(), false, false, budget)) {
            while (!work.step()) {}
            plan = work.result().plan();
            check(plan.feasible() == feasible, "Unexpected recovery result " + plan.result() + "; " + budget.diagnostics());
            check(!work.result().coverage().complete() && plan.result() != GraphPlan.Result.INFEASIBLE, "Positive support exported a global negative proof");
            if (feasible) {
                verify(plan, input);
                try { work.certificate(100_000, 1L << 20); throw new AssertionError("Opaque state certificate escaped"); }
                catch (UnsupportedOperationException expected) { checks++; }
            }
        }
        check(budget.reservedBytes() == 0, "State support leaked request workspace");
        check(input.rule().transitions - before < 256, "Enumerated unrequested wear / electric charge");
        if (feasible) {
            check(budget.compilationWork() < 750_000 && budget.peakBytes() < (12L << 20), "Small physical support compiled the unrelated catalog");
            if (warm) {
                check(budget.compilationWork() < 2000 && budget.diagnostics().contains("reused; selected="), "Failed support hid the verified expanded hint");
                maxWarmCompile = Math.max(maxWarmCompile, budget.compilationWork());
            }
        }
        PlanningCostTraceTest.conserved(budget);
        maxCompile = Math.max(maxCompile, budget.compilationWork()); maxBytes = Math.max(maxBytes, budget.peakBytes()); cases++;
        return plan;
    }

    private static void controls() {
        var original = input(128, 8, 0, false, false, 1, false);
        var catalog = new CapturedCatalog<>(original.entries());
        for (long raw : new long[]{2, 2, 1, 2, 0, 2}) {
            var stock = new LinkedHashMap<>(original.stock()); stock.put("raw", raw);
            solve(new Input(original.entries(), stock, original.target(), original.seeds(), original.rule()), catalog, raw == 2, false);
        }
        solve(new Input(original.entries(), Map.of("raw", 2L), "product", Map.of(), original.rule()), catalog, false, false);
        solve(new Input(original.entries(), original.stock(), "product", Map.of("tool:6", 1L), original.rule()), catalog, false, false);
        // A recovered source is not evidence that an arbitrary distant state is reachable.
        var far = new ArrayList<>(original.entries());
        far.set(1, entry("finish", List.of(ordinary("tool:1", 1)), "product"));
        solve(new Input(far, original.stock(), "product", Map.of(), original.rule()), new CapturedCatalog<>(far), false, false);
        solve(original, catalog, true, true);
    }

    private static void verify(GraphPlan<String> plan, Input input) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var sources = new LinkedHashMap<String, CapturedCatalog.Entry<String>>(); input.entries().forEach(s -> sources.put(s.binding(), s));
        for (var recipe : plan.recipes().values()) {
            var source = sources.get(recipe.binding()); check(source != null && !recipe.binding().startsWith("noise:"), "Invented source / funded unrelated source");
            var outputs = new LinkedHashMap<String, Long>(); long[] fill = new long[source.recipe().inputs().size()];
            var chosen = new LinkedHashMap<Integer, String>();
            for (var slot : recipe.slots()) {
                var original = source.recipe().inputs().get(slot.inputSlot()); var candidate = original.candidates().get(0);
                fill[slot.inputSlot()] += slot.amount(); String prior = chosen.put(slot.inputSlot(), slot.key());
                check(source.recipe().mixed(original) || prior == null || prior.equals(slot.key()), "Split whole-key tool group");
                check(!slot.configuration() && !slot.reusable(), "Changed original consuming input");
                if (candidate.family() != null) {
                    check(slot.key().startsWith("tool:") && Long.parseLong(slot.key().substring(5)) > 0, "Invented physical tool");
                    outputs.merge(next(slot.key()), slot.amount(), Math::addExact);
                } else check(slot.key().equals(candidate.stack().what()), "Changed ordinary input");
            }
            for (int i = 0; i < fill.length; i++) check(fill[i] == source.recipe().inputs().get(i).multiplier(), "Changed simultaneous tool demand");
            source.recipe().outputs().forEach(out -> outputs.merge(out.what(), out.amount(), Math::addExact));
            check(outputs.equals(recipe.executionOutputs()), "Changed original physical return");
        }
        var inventory = new LinkedHashMap<String, BigInteger>();
        plan.initialExact().forEach((key, count) -> {
            check(count.signum() >= 0 && count.compareTo(BigInteger.valueOf(input.stock().getOrDefault(key, 0L))) <= 0, "Invented initial stock");
            inventory.put(key, count);
        });
        var cursor = new PlanCursor(plan.steps()); int fired = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            for (long i = 0; i < batch.runs(); i++) {
                for (var slot : recipe.slots()) {
                    var available = inventory.getOrDefault(slot.key(), BigInteger.ZERO); var count = BigInteger.valueOf(slot.amount());
                    check(available.compareTo(count) >= 0, "Spent an absent / in-flight tool"); inventory.put(slot.key(), available.subtract(count));
                }
                recipe.executionOutputs().forEach((key, count) -> inventory.merge(key, BigInteger.valueOf(count), BigInteger::add));
                check(++fired < 100, "Small finite witness inflated");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault(input.target(), BigInteger.ZERO).signum() > 0, "Missed original target");
        input.seeds().forEach((key, count) -> check(inventory.getOrDefault(key, BigInteger.ZERO).compareTo(BigInteger.valueOf(count)) >= 0, "Missed final physical seed"));
    }

    private static void runtime(GraphPlan<String> plan) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Reused an in-flight entity"); physical.merge(key, -count, Long::sum);
                });
                var outputs = new LinkedHashMap<String, Long>(); recipe.executionOutputs().forEach((key, count) -> outputs.put(key, Math.multiplyExact(count, runs)));
                pending.add(outputs); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 100 && host.delivered == 0; tick++) {
            host.job.tick(host, tick * 20L, 4, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum); check(host.job.accept(key, count, false) == count, "Lost delayed physical return");
            });
            host.pending.clear();
        }
        check(host.delivered == 1, "Recovered support stalled after persistence / delayed returns");
    }

    private static String next(String key) { long n = Long.parseLong(key.substring(5)); return n == 1 ? "empty" : "tool:" + (n - 1); }
    private static CapturedRecipe.Input<String> ordinary(String key, long count) {
        return new CapturedRecipe.Input<>(count, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)));
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> inputs, String out) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, List.of(new CapturedRecipe.Amount<>(out, 1)), false, false));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
