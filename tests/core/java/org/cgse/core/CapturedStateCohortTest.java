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

/** Unrelated physical supply must not redirect whole-key replacement feedback. */
public final class CapturedStateCohortTest {
    private static int requests, checks;
    private record Family(String id) implements StateFamily<String> {
        public boolean accepts(String key) { return key.startsWith(id.toLowerCase(java.util.Locale.ROOT) + ":") ||
                !id.equals("C") && key.startsWith("s:"); }
        public long capacity(String key) { return accepts(key) ? Long.parseLong(key.substring(2)) : 0; }
        public String nominalAfterOne(String key) { long n = capacity(key); return n <= 1 ? null : key.substring(0, 2) + (n - 1); }
    }

    public static void main(String[] args) {
        for (int surplus : new int[]{10, 100}) for (int n : new int[]{2, 4}) for (long amount : new long[]{1, 4})
            for (int order = 0; order < 8; order++) for (boolean direct : List.of(false, true)) solve(n, amount, surplus, order, direct);
        for (int surplus : new int[]{1000, 10_000}) for (int n : new int[]{2, 4}) for (int order : new int[]{0, 7})
            for (boolean direct : List.of(false, true)) solve(n, 4, surplus, order, direct);
        changingStock(); limits();
        System.out.println("CapturedStateCohortTest: requests=" + requests + "; assertions=" + checks);
    }

    private static void solve(int n, long amount, int surplus, int order, boolean direct) {
        var entries = entries(n, order, false); var stock = stock(n, amount, surplus, order, true);
        witness(n, amount, stock); // Independent of every solver path.
        var budget = budget(40_000_000, 64L << 20); budget.enableMetrics();
        GraphPlan<String> plan;
        if (direct) try (var task = new CapturedStatePlanning<>(entries, "out", amount, stock, Set.of(), Map.of(), false, false, budget)) {
            while (!task.step()) {} plan = task.result();
            check(task.outcome().status() == CapturedStatePlanning.Status.VERIFIED, "Lost replacement beside surplus supply: " + task.outcome());
        } else plan = new CapturedCatalog<>(entries).plan("out", amount, stock, false, false, budget).plan();
        check(plan != null && plan.feasible(), "Disjoint stock blocked a funded witness: " + budget.diagnostics());
        replay(plan, entries, stock, amount);
        // Four-tool cohorts already need >500k work for the first auxiliary
        // count witness. The later feedback must repair that assignment rather
        // than paying for several equally expensive fresh count searches.
        var refinements = java.util.regex.Pattern.compile("refinements=(\\d+)").matcher(budget.diagnostics());
        check(refinements.find() && Integer.parseInt(refinements.group(1)) <= n + 1,
                "Replacement feedback scaled with disjoint inventory: n=" + n + "; amount=" + amount + "; surplus=" + surplus +
                        "; order=" + order + "; direct=" + direct + "; " + budget.diagnostics());
        check(budget.diagnostics().contains("candidate_state_repair@") && budget.diagnostics().contains("result=verified; recipes="),
                "Replacement did not reuse a checked auxiliary assignment");
        check(budget.searchWork() < (n == 2 ? 200_000 : 800_000), "Proposal used over its regression allowance: " + budget.searchWork());
        check(budget.reservedBytes() == 0 && budget.peakBytes() < (32L << 20), "Cohort proposal leaked or ballooned");
        PlanningCostTraceTest.conserved(budget); requests++;
    }

    private static void changingStock() {
        for (boolean mixed : List.of(false, true)) for (int order : new int[]{0, 7}) {
            var entries = entries(2, order, mixed); var catalog = new CapturedCatalog<>(entries);
            for (boolean funded : List.of(true, false, true)) {
                var stock = stock(2, 4, 10_000, order, funded); var budget = budget(40_000_000, 64L << 20);
                var result = catalog.plan("out", 4, stock, true, true, budget); var plan = result.plan();
                // Without metal, A can take a1+s1 only if its slot is mixed.
                // Disjoint C entities cannot repair an indivisible A/B cohort.
                check(plan.feasible() == (funded || mixed), "Stale union, cross-family supply or lost mixed witness");
                if (plan.feasible()) replay(plan, entries, stock, 4);
                else check(plan.result() != GraphPlan.Result.INFEASIBLE && !result.coverage().complete(), "Auxiliary failure became canonical UNSAT");
                check(budget.reservedBytes() == 0, "Warm cohort feedback escaped the request"); requests++;
            }
        }
    }

    private static void limits() {
        var entries = entries(2, 0, false); var stock = stock(2, 4, 10_000, 0, true);
        for (long cap : new long[]{100, 2048, 20_000, 60_000, 100_000, 400_000}) {
            var budget = budget(cap, 64L << 20);
            try (var task = new CapturedStatePlanning<>(entries, "out", 4, stock, Set.of(), Map.of(), false, false, budget)) {
                while (!task.step()) {}
                if (task.result() != null) replay(task.result(), entries, stock, 4);
            } catch (PlanningBudget.Exhausted expected) { /* Parent allowance still binds. */ }
            check(budget.reservedBytes() == 0 && budget.searchWork() <= cap, "Recovery renewed the original budget"); requests++;
        }
        for (long bytes : new long[]{2048, 16_384, 1L << 20}) {
            var budget = budget(40_000_000, bytes);
            try (var task = new CapturedStatePlanning<>(entries, "out", 4, stock, Set.of(), Map.of(), false, false, budget)) {
                while (!task.step()) {}
                if (task.result() != null) replay(task.result(), entries, stock, 4);
            }
            check(budget.reservedBytes() == 0 && budget.peakBytes() <= bytes, "Recovery exceeded its workspace allowance"); requests++;
        }
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 40_000_000, 64L << 20, cancel::get, System::nanoTime); budget.enableMetrics();
        try (var task = new CapturedStatePlanning<>(entries, "out", 4, stock, Set.of(), Map.of(), false, false, budget)) {
            boolean refined = false;
            while (!task.step()) if (budget.diagnostics().contains("cohort_size=")) { refined = true; break; }
            check(refined, "Did not reach scoped cohort feedback"); cancel.set(true);
            try { task.step(); throw new AssertionError("Ignored cancellation"); } catch (CancellationException expected) {}
        }
        check(budget.reservedBytes() == 0, "Cancelled feedback retained storage"); requests++;
    }

    private static void witness(int n, long amount, Map<String, Long> stock) {
        var inventory = new LinkedHashMap<>(stock);
        inventory.merge("metal", -1L, Long::sum); inventory.merge("a:100", 1L, Long::sum);
        for (int run = 0; run < amount; run++) {
            for (String type : List.of("a:", "b:", "s:", "c:")) {
                String key = type + (100 - run);
                check(inventory.getOrDefault(key, 0L) >= n, "Independent witness lacks simultaneous tools");
                inventory.merge(key, -(long) n, Long::sum);
            }
            for (String type : List.of("a:", "b:", "s:", "c:")) inventory.merge(type + (99 - run), (long) n, Long::sum);
            inventory.merge("raw", -1L, Long::sum); inventory.merge("out", 1L, Long::sum);
        }
        check(inventory.get("out") == amount && inventory.values().stream().allMatch(v -> v >= 0), "Invalid independent witness");
    }

    private static void replay(GraphPlan<String> plan, List<CapturedCatalog.Entry<String>> entries, Map<String, Long> stock, long amount) {
        PlanVerifier.verify(plan); PlanVerifier.verifyRuntimeInventory(plan);
        var inventory = new LinkedHashMap<String, Long>();
        plan.initialExact().forEach((key, count) -> {
            check(count.signum() >= 0 && count.compareTo(BigInteger.valueOf(stock.getOrDefault(key, 0L))) <= 0, "Invented original stock");
            inventory.put(key, count.longValueExact());
        });
        var cursor = new PlanCursor(plan.steps()); int firings = 0;
        while (cursor.current() != null) {
            var batch = cursor.current(); var recipe = plan.recipes().get(batch.recipe());
            var source = entries.stream().filter(e -> e.binding().equals(recipe.binding())).findFirst().orElseThrow().recipe();
            for (long run = 0; run < batch.runs(); run++) {
                var output = new LinkedHashMap<String, Long>(); source.outputs().forEach(v -> output.merge(v.what(), v.amount(), Long::sum));
                long[] filled = new long[source.inputs().size()]; String[] chosen = new String[filled.length];
                for (var slot : recipe.slots()) {
                    check(inventory.getOrDefault(slot.key(), 0L) >= slot.amount(), "Shared an unavailable tool");
                    inventory.merge(slot.key(), -slot.amount(), Long::sum);
                    var input = source.inputs().get(slot.inputSlot()); var candidate = input.candidates().get(0);
                    filled[slot.inputSlot()] += slot.amount();
                    check(source.mixed(input) || chosen[slot.inputSlot()] == null || chosen[slot.inputSlot()].equals(slot.key()), "Split an indivisible slot");
                    chosen[slot.inputSlot()] = slot.key();
                    if (candidate.family() instanceof Family family) {
                        char type = slot.key().charAt(0);
                        check(type == Character.toLowerCase(family.id().charAt(0)) || type == 's' && !family.id().equals("C"), "Used an ineligible key");
                        long wear = Long.parseLong(slot.key().substring(2)); check(wear > 0, "Used a broken tool");
                        if (wear > 1) output.merge(type + ":" + (wear - 1), slot.amount(), Long::sum);
                    } else check(candidate.stack().what().equals(slot.key()), "Changed a physical ingredient");
                }
                for (int i = 0; i < filled.length; i++) check(filled[i] == source.inputs().get(i).multiplier(), "Unfilled captured slot");
                check(output.equals(recipe.executionOutputs()), "Invented or lost a return");
                output.forEach((key, count) -> inventory.merge(key, count, Long::sum)); // Only after all simultaneous inputs.
                check(++firings < 200, "Unexpected expanded plan");
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.getOrDefault("out", 0L) >= amount, "Unfulfilled physical order");
    }

    private static List<CapturedCatalog.Entry<String>> entries(int n, int order, boolean mixedA) {
        var inputs = new ArrayList<CapturedRecipe.Input<String>>();
        for (String id : List.of("A", "B", "B", "C")) {
            String prefix = id.equals("C") ? "c:" : "s:";
            inputs.add(new CapturedRecipe.Input<>(n, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>(prefix + "100", 1), prefix + "99", false, false, new Family(id))),
                    id.equals("A") && mixedA ? CapturedRecipe.InputMode.MIXED : CapturedRecipe.InputMode.INDIVISIBLE));
        }
        inputs.add(raw("raw")); if ((order & 2) != 0) Collections.reverse(inputs);
        var entries = new ArrayList<>(List.of(entry("use", inputs, "out"), entry("forge", List.of(raw("metal")), "a:100")));
        if ((order & 4) != 0) Collections.reverse(entries); return entries;
    }
    private static Map<String, Long> stock(int n, long amount, int surplus, int order, boolean funded) {
        var stock = new LinkedHashMap<String, Long>(); stock.put("s:100", 2L * n - 1); stock.put("a:100", (long) n - 1);
        stock.put("b:100", (long) n); stock.put("raw", amount); if (funded) stock.put("metal", 1L); stock.put("c:100", (long) surplus);
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
    private static PlanningBudget budget(long work, long memory) { return new PlanningBudget(0, work, memory, () -> false, System::nanoTime); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
