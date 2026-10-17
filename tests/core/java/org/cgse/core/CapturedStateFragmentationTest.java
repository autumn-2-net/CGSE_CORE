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

/** Manufacturing for another slot can repair fragmented shared tool stock. */
public final class CapturedStateFragmentationTest {
    private static int requests, checks;
    private record Family(String id, boolean common) implements StateFamily<String> {
        public boolean accepts(String key) { return key.startsWith("s:") || key.startsWith(id.toLowerCase(java.util.Locale.ROOT) + ":") ||
                common && (key.startsWith("a:") || key.startsWith("b:")); }
        public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(2)) : 0; }
        public String nominalAfterOne(String key) { long n = capacity(key); return n <= 1 ? null : key.substring(0, 2) + (n - 1); }
    }

    public static void main(String[] args) {
        for (int n : new int[]{2, 3, 4}) for (long amount : new long[]{1, 4})
            for (int order = 0; order < 8; order++) for (boolean direct : List.of(false, true)) {
                var entries = entries(n, order, 0);
                var stock = stock(n, amount, order, true);
                witness(n, amount, stock);
                var budget = budget(40_000_000, 64L << 20); budget.enableMetrics();
                GraphPlan<String> plan;
                if (direct) try (var task = new CapturedStatePlanning<>(entries, "out", amount, stock, Set.of(), Map.of(), false, false, budget)) {
                    while (!task.step()) {} plan = task.result();
                    check(task.outcome().status() == CapturedStatePlanning.Status.VERIFIED, "Lost shared whole-key replacement: " + task.outcome());
                } else plan = new CapturedCatalog<>(entries).plan("out", amount, stock, false, false, budget).plan();
                check(plan != null && plan.feasible(), "Lost another slot's manufactured tool: " + budget.diagnostics());
                replay(plan, entries, stock, amount);
                check(budget.searchWork() < 200_000 && budget.peakBytes() < (16L << 20), "Small fragmentation exhausted the main allowance");
                check(budget.reservedBytes() == 0, "Replacement workspace leak");
                PlanningCostTraceTest.conserved(budget); requests++;
                if (n == 2 && amount == 4 && order == 0 && direct) runtime(plan, amount);
            }
        commonEligibility(); changingStock(); seeds(); limits();
        System.out.println("CapturedStateFragmentationTest: requests=" + requests + "; assertions=" + checks);
    }

    private static void commonEligibility() {
        // Distinct captured rules may have exactly the same eligibility.
        // Whole-key fragmentation still matters even without a Hall choice.
        for (int n : new int[]{2, 3, 4}) for (int order : new int[]{0, 7}) for (boolean direct : List.of(false, true)) {
            var entries = entries(n, order, 0, true);
            var stock = stock(n, 4, order, true); witness(n, 4, stock);
            var budget = budget(40_000_000, 64L << 20);
            GraphPlan<String> plan;
            if (direct) try (var task = new CapturedStatePlanning<>(entries, "out", 4, stock, Set.of(), Map.of(), false, false, budget)) {
                while (!task.step()) {} plan = task.result();
            } else plan = new CapturedCatalog<>(entries).plan("out", 4, stock, false, false, budget).plan();
            check(plan != null && plan.feasible(), "Lost replacement with identical eligibility");
            replay(plan, entries, stock, 4);
            check(budget.reservedBytes() == 0 && budget.searchWork() < 200_000, "Common eligibility recovery ballooned"); requests++;
        }
    }

    private static void changingStock() {
        for (int mixed = 0; mixed < 8; mixed++) for (int order : new int[]{0, 7}) {
            var entries = entries(2, order, mixed);
            var catalog = new CapturedCatalog<>(entries);
            for (boolean funded : List.of(true, false, true)) {
                var stock = stock(2, 4, order, funded);
                var budget = budget(40_000_000, 64L << 20);
                try (var task = catalog.begin("out", 4, stock, true, true, budget)) {
                    while (!task.step()) {}
                    var plan = task.result().plan();
                    // Without forging, A must use a:100 once and s:100 once.
                    // That is permitted exactly when A's slot allows mixing;
                    // B can then fill its two slots with b:100 and s:100 pairs.
                    check(plan.feasible() == (funded || (mixed & 1) != 0), "Stale stock, missed mixed allocation or split a whole slot");
                    if (plan.feasible()) replay(plan, entries, stock, 4);
                    else check(!task.result().coverage().complete() && plan.result() != GraphPlan.Result.INFEASIBLE,
                            "Restricted fragmentation became a global negative proof");
                }
                check(budget.reservedBytes() == 0, "Warm request retained a union floor"); requests++;
            }
        }
    }

    private static void seeds() {
        var entries = entries(2, 0, 0);
        var stock = stock(2, 4, 0, true);
        for (boolean possible : List.of(true, false)) {
            var seeds = Map.of(possible ? "a:96" : "a:100", 2L);
            var budget = budget(40_000_000, 64L << 20);
            try (var task = new CapturedCatalog<>(entries).begin("out", 4, stock, Set.of(), seeds, true, true, budget)) {
                while (!task.step()) {}
                var plan = task.result().plan();
                check(plan.feasible() == possible, "Lost an exact final seed across union refinement");
                if (possible) check(replay(plan, entries, stock, 4).getOrDefault("a:96", 0L) >= 2, "Seed was only a capacity token");
            }
            check(budget.reservedBytes() == 0, "Seed restoration leaked"); requests++;
        }
    }

    private static void limits() {
        var entries = entries(2, 0, 0);
        var stock = stock(2, 4, 0, true);
        for (long cap : new long[]{100, 2048, 20_000, 60_000, 100_000, 240_000}) {
            var budget = budget(cap, 64L << 20);
            try (var task = new CapturedStatePlanning<>(entries, "out", 4, stock, Set.of(), Map.of(), false, false, budget)) {
                while (!task.step()) {}
                if (task.result() != null) replay(task.result(), entries, stock, 4);
                check(task.outcome().status() != CapturedStatePlanning.Status.RUNNING, "Lost limited outcome");
            } catch (PlanningBudget.Exhausted expected) { /* The original parent cap remains binding. */ }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap, "Union recovery renewed a budget"); requests++;
        }
        for (long bytes : new long[]{2048, 16_384, 1L << 20}) {
            var budget = budget(40_000_000, bytes);
            try (var task = new CapturedStatePlanning<>(entries, "out", 4, stock, Set.of(), Map.of(), false, false, budget)) {
                while (!task.step()) {}
                if (task.result() != null) replay(task.result(), entries, stock, 4);
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Union recovery exceeded memory"); requests++;
        }
        var cancelled = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancelled::get, System::nanoTime); budget.enableMetrics();
        try (var task = new CapturedStatePlanning<>(entries, "out", 4, stock, Set.of(), Map.of(), false, false, budget)) {
            boolean refined = false;
            while (!task.step()) if (budget.diagnostics().contains("failure=whole_key_fragmentation")) { refined = true; break; }
            check(refined, "Cancellation did not reach whole-key union feedback");
            cancelled.set(true);
            try { task.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled union retained storage"); requests++;
    }

    /** Literal witness constructed from original physical keys before solving. */
    private static void witness(int n, long amount, Map<String, Long> stock) {
        var inventory = new LinkedHashMap<>(stock);
        inventory.merge("metal", -1L, Long::sum); inventory.merge("a:100", 1L, Long::sum);
        for (int run = 0; run < amount; run++) {
            for (String type : List.of("a:", "b:", "s:")) {
                String before = type + (100 - run);
                check(inventory.getOrDefault(before, 0L) >= n, "Unfunded independent cohort witness");
                inventory.merge(before, -(long) n, Long::sum);
            }
            for (String type : List.of("a:", "b:", "s:")) inventory.merge(type + (99 - run), (long) n, Long::sum);
            inventory.merge("raw", -1L, Long::sum); inventory.merge("out", 1L, Long::sum);
        }
        check(inventory.get("out") == amount && inventory.values().stream().allMatch(n0 -> n0 >= 0), "Invalid independent physical witness");
    }

    private static Map<String, Long> replay(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries,
                                             Map<String, Long> stock, long amount) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, Long>();
        plan.initialExact().forEach((key, n) -> {
            check(n.signum() >= 0 && n.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented physical inventory");
            inventory.put(key, n.longValueExact());
        });
        var cursor = new PlanCursor(plan.steps()); int firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            var source = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow().recipe();
            for (long run = 0; run < batch.runs(); run++) {
                var output = new LinkedHashMap<String, Long>();
                source.outputs().forEach(value -> output.merge(value.what(), value.amount(), Long::sum));
                long[] filled = new long[source.inputs().size()]; String[] chosen = new String[filled.length];
                for (var slot : recipe.slots()) {
                    check(inventory.getOrDefault(slot.key(), 0L) >= slot.amount(), "Assigned an unowned or in-flight tool");
                    inventory.merge(slot.key(), -slot.amount(), Long::sum);
                    var input = source.inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                    filled[slot.inputSlot()] += slot.amount();
                    check(source.mixed(input) || chosen[slot.inputSlot()] == null || chosen[slot.inputSlot()].equals(slot.key()), "Split a whole-key slot");
                    chosen[slot.inputSlot()] = slot.key();
                    if (candidate.family() instanceof Family family) {
                        check(slot.key().startsWith("s:") || slot.key().startsWith(family.id().equals("A") ? "a:" : "b:") ||
                                family.common() && (slot.key().startsWith("a:") || slot.key().startsWith("b:")), "Ineligible physical key");
                        long durability = Long.parseLong(slot.key().substring(2)); check(durability > 0, "Used a broken tool");
                        if (durability > 1) output.merge(slot.key().substring(0, 2) + (durability - 1), slot.amount(), Long::sum);
                    } else check(candidate.stack().what().equals(slot.key()), "Changed an ordinary source input");
                }
                for (int i = 0; i < filled.length; i++) check(filled[i] == source.inputs().get(i).multiplier(), "Unfilled source slot");
                check(output.equals(recipe.executionOutputs()), "Lost or invented a physical return");
                output.forEach((key, count) -> inventory.merge(key, count, Long::sum));
                check(++firings < 200, "Unexpected physical expansion");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("out", 0L) >= amount, "Unfulfilled physical request"); return inventory;
    }

    private static void runtime(GraphPlan<String> plan, long amount) {
        final class Host implements GraphJobRuntime.Adapter<String> {
            GraphJobRuntime<String> job = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            final Map<String, Long> physical = new LinkedHashMap<>(plan.initial());
            final List<Map<String, Long>> pending = new ArrayList<>(); long delivered;
            public long capacity(GraphRecipe<String> recipe, long requested) { return requested; }
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                inputs.forEach((key, count) -> {
                    check(physical.getOrDefault(key, 0L) >= count, "Shared an in-flight tool"); physical.merge(key, -count, Long::sum);
                });
                var output = new LinkedHashMap<String, Long>();
                recipe.executionOutputs().forEach((key, count) -> output.put(key, Math.multiplyExact(count, runs)));
                pending.add(output); return GraphJobRuntime.Outcome.ACCEPTED;
            }
            public long deliver(String key, long count) { check(key.equals("out"), "Delivered a tool"); delivered += count; return count; }
            public long refund(String key, long count) { return count; }
        }
        var host = new Host();
        for (int tick = 0; tick < 500 && host.delivered < amount; tick++) {
            host.job.tick(host, tick * 20L, 8, () -> 0L); host.job = new GraphJobRuntime<>(host.job.snapshot());
            if (tick % 3 != 2) continue;
            Collections.reverse(host.pending);
            for (var output : host.pending) output.forEach((key, count) -> {
                host.physical.merge(key, count, Long::sum); check(host.job.accept(key, count, false) == count, "Lost delayed return");
            });
            host.pending.clear();
        }
        check(host.delivered == amount, "Shared tool execution stalled");
    }

    private static List<CapturedCatalog.Entry<String>> entries(int n, int order, int mixed) {
        return entries(n, order, mixed, false);
    }
    private static List<CapturedCatalog.Entry<String>> entries(int n, int order, int mixed, boolean common) {
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        for (int i = 0; i < 3; i++) inputs.add(new CapturedRecipe.Input<>(n, List.of(new CapturedRecipe.Candidate<>(
                new CapturedRecipe.Amount<>("s:100", 1), "s:99", false, false, new Family(i == 0 ? "A" : "B", common))),
                (mixed & (1 << i)) != 0 ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE));
        inputs.add(raw("raw")); if ((order & 2) != 0) Collections.reverse(inputs);
        var entries = new ArrayList<>(List.of(entry("use", inputs, "out"), entry("forge", List.of(raw("metal")), "a:100")));
        if ((order & 4) != 0) Collections.reverse(entries); return entries;
    }
    private static Map<String, Long> stock(int n, long amount, int order, boolean funded) {
        var stock = new LinkedHashMap<String, Long>(); stock.put("s:100", 2L * n - 1); stock.put("a:100", (long) n - 1);
        stock.put("b:100", (long) n); stock.put("raw", amount); if (funded) stock.put("metal", 1L);
        if ((order & 1) == 0) return stock;
        var reversed = new LinkedHashMap<String, Long>(); var keys = new ArrayList<>(stock.keySet()); Collections.reverse(keys);
        for (var key : keys) reversed.put(key, stock.get(key)); return reversed;
    }
    private static CapturedRecipe.Input<String> raw(String key) {
        return new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
    }
    private static CapturedCatalog.Entry<String> entry(String id, List<CapturedRecipe.Input<String>> in, String out) {
        return new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(in, List.of(new CapturedRecipe.Amount<>(out, 1)), false, false));
    }
    private static PlanningBudget budget(long work, long bytes) { return new PlanningBudget(0, work, bytes, () -> false, System::nanoTime); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
