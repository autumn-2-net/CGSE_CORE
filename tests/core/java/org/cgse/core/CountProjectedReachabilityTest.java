// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;

/** A real-recipe startup boundary, with independently enumerated relaxed states. */
public final class CountProjectedReachabilityTest {
    private static final String TARGET = "gtceu:radox";
    private static final Map<String, BigInteger> GOAL = Map.of(TARGET, BigInteger.valueOf(100_000));

    public static void main(String[] args) throws Exception {
        projectedBoundary();
        changedInventoryAndSupply();
        limitsAndConfiguration();
        mainSolver();
        System.out.println("Projected startup: 49 states, 24 recipe orders, complete source matrices, serialized proofs, stock/producer/configuration negatives and budgets passed");
    }

    private static void projectedBoundary() throws Exception {
        var recipes = recipes();
        var stock = stock();
        var keys = projectedKeys(recipes);
        check(keys.size() == 18, "unexpected proof support");
        var independent = enumerate(recipes, keys, stock);
        check(independent.size() == 49, "startup oracle no longer has 49 states");
        for (var state : independent) check(state.get(keys.indexOf(TARGET)).signum() == 0, "oracle produced target");
        var random = new Random(1011026);
        for (int order = 0; order < 24; order++) {
            Collections.shuffle(recipes, random);
            var budget = budget();
            var journal = new CountProof.Journal(8L << 20);
            budget.proofJournal(journal);
            try (var model = RecipeCountModel.forShell(recipes, GOAL, stock, Set.of(), budget);
                 var search = CountReachability.create(model, 32768)) {
                check(model.keys.size() == 54 && search != null, "large surrounding catalogue blocked small proof support");
                while (!search.step()) {}
                check(search.result() == BackwardCoverability.Result.CLOSED, "relaxed complete network was not closed: " + search.result());
                check(journal.executions().size() == 1, "absence proof was not exported");
                var proof = journal.executions().get(0);
                check(proof.states().size() == 49 && proof.marked().isEmpty(), "partial closure escaped");
                check(ExecutionProof.verify(proof, 1_000_000) == CountProof.Verdict.VERIFIED, "independent closure rejected");
                // Match every projected arc to its original source, not merely
                // the few recipes executed by the failed integer candidate.
                var projected = model.keys.stream().filter(keys::contains).toList();
                check(proof.initial().equals(projected.stream().map(k -> BigInteger.valueOf(stock.getOrDefault(k, 0L))).toList()), "wrong current inventory");
                check(proof.inputs().size() == recipes.size(), "a source was omitted");
                for (int r = 0; r < recipes.size(); r++) {
                    var recipe = recipes.get(r);
                    check(proof.inputs().get(r).equals(projected.stream().map(k -> BigInteger.valueOf(recipe.inputs().getOrDefault(k, 0L))).toList()), "input projection changed");
                    check(proof.outputs().get(r).equals(projected.stream().map(k -> BigInteger.valueOf(recipe.outputs().getOrDefault(k, 0L))).toList()), "output projection changed");
                }
                if (order == 0) {
                    var path = Files.createTempFile("cgse-radox-startup-", ".cgp");
                    try {
                        journal.write(path);
                        var restored = CountProof.read(path);
                        check(!restored.truncated() && restored.executions().equals(journal.executions()), "serialized closure changed");
                        check(ExecutionProof.verify(restored.executions().get(0), 1_000_000) == CountProof.Verdict.VERIFIED, "serialized closure did not replay");
                    } finally { Files.deleteIfExists(path); }
                    // A new producer from a formerly omitted external resource
                    // changes the complete transition relation. Old states are
                    // not closed, even though no previous recipe was removed.
                    var in = new ArrayList<>(proof.inputs());
                    var out = new ArrayList<>(proof.outputs());
                    in.add(Collections.nCopies(projected.size(), BigInteger.ZERO));
                    var gas = new ArrayList<>(Collections.nCopies(projected.size(), BigInteger.ZERO));
                    gas.set(projected.indexOf("gtceu:radox_gas"), BigInteger.valueOf(216000));
                    out.add(gas);
                    var stale = new ExecutionProof.Certificate(proof.scope(), proof.kind(), proof.initial(), proof.goal(), in, out, proof.states(), proof.marked());
                    check(ExecutionProof.verify(stale, 1_000_000) == CountProof.Verdict.INVALID, "old boundary survived a new producer");
                    var replenished = new ArrayList<>(proof.initial());
                    replenished.set(projected.indexOf("gtceu:radox_gas"), BigInteger.valueOf(216100));
                    stale = new ExecutionProof.Certificate(proof.scope(), proof.kind(), replenished, proof.goal(), proof.inputs(), proof.outputs(), proof.states(), proof.marked());
                    check(ExecutionProof.verify(stale, 1_000_000) == CountProof.Verdict.INVALID, "old boundary survived changed tracked inventory");
                }
            }
            check(budget.reservedBytes() == 0, "projection/proof leaked memory");
        }
    }

    private static void changedInventoryAndSupply() {
        for (int mode = 0; mode < 5; mode++) {
            var recipes = recipes();
            var stock = stock();
            if (mode == 0) stock.put("gtceu:radox_gas", 216100L);
            if (mode == 1) stock.put("gtceu:tiny_radox_dust", 100L);
            if (mode == 2) recipes.add(recipe("new-external-supply", Map.of("gtceu:xenoxene", 1L), Map.of("gtceu:radox_gas", 216000L)));
            if (mode == 3) {
                stock.put("gtceu:ash_dust", 1L);
                recipes.add(recipe("new-waste-recovery", Map.of("gtceu:ash_dust", 1L), Map.of("gtceu:radox_gas", 216000L)));
                check(projectedKeys(recipes).contains("gtceu:ash_dust"), "newly consumed waste was projected out");
            }
            // Reducing an omitted base resource cannot make the relaxed
            // unreachability proof unsound; it already assumes infinite supply.
            if (mode == 4) stock.put("gtceu:oxygen_plasma", 0L);
            var budget = budget();
            try (var model = RecipeCountModel.forShell(recipes, GOAL, stock, Set.of(), budget);
                 var search = CountReachability.create(model, 32768)) {
                while (!search.step()) {}
                check(mode == 4 ? search.result() == BackwardCoverability.Result.CLOSED :
                        search.result() != BackwardCoverability.Result.CLOSED, "stale proof at changed case " + mode);
            }
            check(budget.reservedBytes() == 0, "changed request leaked");
        }
    }

    private static void limitsAndConfiguration() {
        var recipes = recipes();
        var budget = budget();
        try (var model = RecipeCountModel.forShell(recipes, GOAL, stock(), Set.of(), budget)) {
            var journal = new CountProof.Journal(1L << 20);
            budget.proofJournal(journal);
            try (var search = CountReachability.create(model, 1024)) {
                while (!search.step()) {}
                check(search.result() == BackwardCoverability.Result.UNKNOWN && journal.executions().isEmpty(), "truncated frontier became a proof");
            }
            long held = budget.availableBytes();
            budget.reserve(held);
            check(CountReachability.create(model, 32768) == null, "memory refusal ignored");
            budget.release(held);
            try (var search = CountReachability.create(model, 32768)) {
                search.step();
                budget.cancel();
                try { search.step(); throw new AssertionError("cancelled projection continued"); }
                catch (CancellationException expected) { /* request cancellation */ }
            }
        }
        check(budget.reservedBytes() == 0, "limited projection leaked");
        // Per-push consumed configurations may combine runs, so a primitive
        // firing model is not a relaxation of those batch semantics.
        var slots = new ArrayList<>(recipes.get(0).slots());
        var slot = slots.get(2);
        slots.set(2, new GraphRecipe.Slot<>(slot.key(), slot.amount(), slot.inputSlot(), true, false));
        var first = recipes.get(0);
        recipes.set(0, new GraphRecipe<>(first.id(), first.binding(), slots, first.outputs()));
        budget = budget();
        try (var model = RecipeCountModel.forShell(recipes, GOAL, stock(), Set.of(), budget)) {
            check(CountReachability.create(model, 32768) == null, "consumed configuration entered per-firing proof");
        }
        check(budget.reservedBytes() == 0, "configuration refusal leaked");
    }

    private static void mainSolver() {
        var compiler = new GraphCompiler<>(recipes());
        for (int mode = 0; mode < 3; mode++) {
            var stock = stock();
            if (mode == 1) stock.put("gtceu:radox_gas", 216100L);
            var budget = budget();
            var journal = new CountProof.Journal(8L << 20);
            budget.proofJournal(journal);
            var work = new GraphPlanningWork<>(compiler, TARGET, 100_000, stock, false, true, budget).catalysts(CatalystPolicy.MINIMAL);
            try {
                while (!work.step()) {}
                var plan = work.result();
                if (mode == 1) {
                    check(plan.feasible(), "old root proof survived replenished inventory: " + plan.result());
                    PlanVerifier.verify(plan);
                    var summary = GraphFixtureRegression.summary(plan.steps(), plan.recipes(), new IdentityHashMap<>());
                    for (var need : summary.need().entrySet())
                        check(BigInteger.valueOf(stock.getOrDefault(need.getKey(), 0L)).compareTo(need.getValue()) >= 0, "unfunded reused execution");
                } else {
                    check(Set.of(GraphPlan.Result.INFEASIBLE, GraphPlan.Result.MISSING_INPUT, GraphPlan.Result.MISSING_SEED).contains(plan.result()), "root proof not used: " + plan.result());
                    check(journal.executions().stream().anyMatch(p -> p.scope().equals("forward_original_closure") && p.states().size() == 49), "49-state root certificate missing");
                }
                System.out.println("radox/main/" + mode + " result=" + plan.result() + "; work=" + budget.nodes());
            } finally { work.close(); }
            check(budget.reservedBytes() == 0, "main root handoff leaked");
        }
    }

    private static List<String> projectedKeys(List<GraphRecipe<String>> recipes) {
        var produced = new TreeSet<String>();
        var consumed = new HashSet<String>();
        for (var r : recipes) { produced.addAll(r.outputs().keySet()); consumed.addAll(r.inputs().keySet()); }
        produced.retainAll(consumed);
        produced.add(TARGET);
        return List.copyOf(produced);
    }

    /** Raw primitive transitions; external base inputs are unbounded in this independent oracle. */
    private static Set<List<BigInteger>> enumerate(List<GraphRecipe<String>> recipes, List<String> keys, Map<String, Long> stock) {
        var initial = keys.stream().map(k -> BigInteger.valueOf(stock.getOrDefault(k, 0L))).toList();
        var todo = new ArrayDeque<List<BigInteger>>();
        var seen = new HashSet<List<BigInteger>>();
        todo.add(initial); seen.add(initial);
        while (!todo.isEmpty()) {
            var state = todo.remove();
            for (var recipe : recipes) {
                boolean enabled = true;
                var next = new ArrayList<BigInteger>();
                for (int k = 0; k < keys.size(); k++) {
                    var in = BigInteger.valueOf(recipe.inputs().getOrDefault(keys.get(k), 0L));
                    if (state.get(k).compareTo(in) < 0) enabled = false;
                    next.add(state.get(k).subtract(in).add(BigInteger.valueOf(recipe.outputs().getOrDefault(keys.get(k), 0L))));
                }
                if (enabled && seen.add(next)) todo.add(next);
            }
            check(seen.size() <= 1000, "oracle unexpectedly unbounded");
        }
        return seen;
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 20_000_000, 128L << 20, () -> false, System::nanoTime); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Normalized local recipe export, matching fixtures/startup-projection/radox_startup_49.json. */
    private static ArrayList<GraphRecipe<String>> recipes() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        recipes.add(new GraphRecipe<>("gtceu:cracker/superlightradox", "gtceu:cracker/superlightradox",
                List.of(new GraphRecipe.Slot<>("gtceu:lightradox", 100L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:silver_plasma", 10L, 1, false, false),
                        new GraphRecipe.Slot<>("virtual:gtceu:programmed_circuit", 1L, 2, true, true)),
                Map.ofEntries(Map.entry("virtual:gtceu:programmed_circuit", 1L),
                        Map.entry("gtceu:superlightradox", 50L))));
        recipes.add(new GraphRecipe<>("gtceu:cracker/crackedradox", "gtceu:cracker/crackedradox",
                List.of(new GraphRecipe.Slot<>("gtceu:superlightradox", 100L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:silver_plasma", 10L, 1, false, false),
                        new GraphRecipe.Slot<>("virtual:gtceu:programmed_circuit", 1L, 2, true, true)),
                Map.ofEntries(Map.entry("virtual:gtceu:programmed_circuit", 1L),
                        Map.entry("gtceu:crackedradox", 100L))));
        recipes.add(new GraphRecipe<>("gtceu:distillation_tower/radox_gas", "gtceu:distillation_tower/radox_gas",
                List.of(new GraphRecipe.Slot<>("gtceu:crackedradox", 1000L, 0, false, false)),
                Map.ofEntries(Map.entry("gtceu:radox_gas", 100L),
                        Map.entry("gtceu:lightradox", 200L),
                        Map.entry("gtceu:ash_dust", 1L))));
        recipes.add(new GraphRecipe<>("gtceu:qft/radox", "gtceu:qft/radox",
                List.of(new GraphRecipe.Slot<>("gtceu:radox_gas", 21600L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:oxygen_plasma", 75000L, 1, false, false),
                        new GraphRecipe.Slot<>("gtceu:titanium_50_tetrachloride", 1000L, 2, false, false),
                        new GraphRecipe.Slot<>("gtceu:molybdenum_trioxide_dust", 16L, 3, false, false),
                        new GraphRecipe.Slot<>("gtceu:chromium_trioxide_dust", 16L, 4, false, false),
                        new GraphRecipe.Slot<>("gtceu:phosphorus_pentoxide_dust", 14L, 5, false, false),
                        new GraphRecipe.Slot<>("gtceu:cubic_zirconia_dust", 12L, 6, false, false),
                        new GraphRecipe.Slot<>("gtceu:germanium_dioxide_dust", 12L, 7, false, false),
                        new GraphRecipe.Slot<>("gtceu:silicon_dioxide_dust", 12L, 8, false, false),
                        new GraphRecipe.Slot<>("gtceu:arsenic_trioxide_dust", 10L, 9, false, false),
                        new GraphRecipe.Slot<>("gtceu:antimony_trioxide_dust", 10L, 10, false, false),
                        new GraphRecipe.Slot<>("gtceu:boron_trioxide_dust", 10L, 11, false, false),
                        new GraphRecipe.Slot<>("gtceu:zincite_dust", 8L, 12, false, false),
                        new GraphRecipe.Slot<>("gtceu:magnesia_dust", 8L, 13, false, false),
                        new GraphRecipe.Slot<>("gtceu:cobalt_oxide_dust", 8L, 14, false, false),
                        new GraphRecipe.Slot<>("gtceu:massicot_dust", 8L, 15, false, false),
                        new GraphRecipe.Slot<>("gtceu:cupric_oxide_dust", 8L, 16, false, false),
                        new GraphRecipe.Slot<>("gtceu:potash_dust", 6L, 17, false, false),
                        new GraphRecipe.Slot<>("gtceu:silver_oxide_dust", 6L, 18, false, false),
                        new GraphRecipe.Slot<>("gtceu:sodium_oxide_dust", 6L, 19, false, false),
                        new GraphRecipe.Slot<>("gtceu:rare_earth_oxide_dust", 4L, 20, false, false)),
                Map.ofEntries(Map.entry("gtceu:radox", 10800L))));
        recipes.add(new GraphRecipe<>("gtceu:fluid_solidifier/solidify_radox_to_ingot", "gtceu:fluid_solidifier/solidify_radox_to_ingot",
                List.of(new GraphRecipe.Slot<>("gtceu:radox", 144L, 0, false, false),
                        new GraphRecipe.Slot<>("virtual:gtceu:ingot_casting_mold", 1L, 1, true, true)),
                Map.ofEntries(Map.entry("virtual:gtceu:ingot_casting_mold", 1L),
                        Map.entry("gtceu:radox_ingot", 1L))));
        recipes.add(new GraphRecipe<>("gtceu:macerator/macerate_radox_ingot", "gtceu:macerator/macerate_radox_ingot",
                List.of(new GraphRecipe.Slot<>("gtceu:radox_ingot", 1L, 0, false, false)),
                Map.ofEntries(Map.entry("gtceu:radox_dust", 1L))));
        recipes.add(new GraphRecipe<>("gtceu:packer/unpackage_radox_tiny_dust", "gtceu:packer/unpackage_radox_tiny_dust",
                List.of(new GraphRecipe.Slot<>("gtceu:radox_dust", 1L, 0, false, false),
                        new GraphRecipe.Slot<>("virtual:gtceu:programmed_circuit", 1L, 1, true, true)),
                Map.ofEntries(Map.entry("virtual:gtceu:programmed_circuit", 1L),
                        Map.entry("gtceu:tiny_radox_dust", 9L))));
        recipes.add(new GraphRecipe<>("gtceu:mixer/xenoxene_mixture", "gtceu:mixer/xenoxene_mixture",
                List.of(new GraphRecipe.Slot<>("gtceu:xenoxene", 1000L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:xenon", 9000L, 1, false, false),
                        new GraphRecipe.Slot<>("gtceu:tiny_radox_dust", 1L, 2, false, false),
                        new GraphRecipe.Slot<>("gtceu:antimony_dust", 1L, 3, false, false),
                        new GraphRecipe.Slot<>("gtceu:osmium_dust", 1L, 4, false, false),
                        new GraphRecipe.Slot<>("gtceu:ender_eye_dust", 1L, 5, false, false)),
                Map.ofEntries(Map.entry("gtceu:xenoxene_mixture", 10000L))));
        recipes.add(new GraphRecipe<>("gtceu:fluid_solidifier/xenoxene_crystal_dust", "gtceu:fluid_solidifier/xenoxene_crystal_dust",
                List.of(new GraphRecipe.Slot<>("gtceu:xenoxene_mixture", 1000L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:perlite_dust", 3L, 1, false, false)),
                Map.ofEntries(Map.entry("gtceu:xenoxene_crystal_dust", 3L))));
        recipes.add(new GraphRecipe<>("gtceu:electrolyzer/purified_xenoxene", "gtceu:electrolyzer/purified_xenoxene",
                List.of(new GraphRecipe.Slot<>("gtceu:oil", 1000L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:xenoxene_crystal_dust", 4L, 1, false, false)),
                Map.ofEntries(Map.entry("gtceu:purified_xenoxene", 1000L),
                        Map.entry("minecraft:water", 1000L))));
        recipes.add(new GraphRecipe<>("gtceu:mixer/enriched_xenoxene", "gtceu:mixer/enriched_xenoxene",
                List.of(new GraphRecipe.Slot<>("gtceu:purified_xenoxene", 10000L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:radox_gas", 100L, 1, false, false),
                        new GraphRecipe.Slot<>("gtceu:taranium_dust", 1L, 2, false, false)),
                Map.ofEntries(Map.entry("gtceu:enriched_xenoxene", 10000L))));
        recipes.add(new GraphRecipe<>("gtceu:pyrolyse_oven/rawradox1", "gtceu:pyrolyse_oven/rawradox1",
                List.of(new GraphRecipe.Slot<>("gtceu:enriched_xenoxene", 1000L, 0, false, false),
                        new GraphRecipe.Slot<>("kubejs:variation_wood", 16L, 1, false, false),
                        new GraphRecipe.Slot<>("virtual:gtceu:programmed_circuit", 1L, 2, true, true)),
                Map.ofEntries(Map.entry("virtual:gtceu:programmed_circuit", 1L),
                        Map.entry("gtceu:rawradox", 10000L),
                        Map.entry("gtceu:ash_dust", 1L))));
        recipes.add(new GraphRecipe<>("gtceu:distillation_tower/rawradox", "gtceu:distillation_tower/rawradox",
                List.of(new GraphRecipe.Slot<>("gtceu:rawradox", 5000L, 0, false, false)),
                Map.ofEntries(Map.entry("gtceu:oil_heavy", 600L),
                        Map.entry("gtceu:oil", 300L),
                        Map.entry("gtceu:creosote", 1000L),
                        Map.entry("minecraft:water", 1400L),
                        Map.entry("gtceu:bacteria", 50L),
                        Map.entry("gtceu:fermented_biomass", 50L),
                        Map.entry("gtceu:superheavyradox", 100L),
                        Map.entry("gtceu:heavyradox", 100L),
                        Map.entry("gtceu:dilutedxenoxene", 100L),
                        Map.entry("gtceu:lightradox", 100L),
                        Map.entry("gtceu:superlightradox", 100L),
                        Map.entry("gtceu:ash_dust", 5L))));
        recipes.add(new GraphRecipe<>("gtceu:centrifuge/superheavyradox", "gtceu:centrifuge/superheavyradox",
                List.of(new GraphRecipe.Slot<>("gtceu:superheavyradox", 1000L, 0, false, false)),
                Map.ofEntries(Map.entry("gtceu:heavyradox", 2000L))));
        recipes.add(new GraphRecipe<>("gtceu:cracker/lightradox", "gtceu:cracker/lightradox",
                List.of(new GraphRecipe.Slot<>("gtceu:heavyradox", 100L, 0, false, false),
                        new GraphRecipe.Slot<>("gtceu:silver_plasma", 10L, 1, false, false),
                        new GraphRecipe.Slot<>("virtual:gtceu:programmed_circuit", 1L, 2, true, true)),
                Map.ofEntries(Map.entry("virtual:gtceu:programmed_circuit", 1L),
                        Map.entry("gtceu:lightradox", 20L))));
        return recipes;
    }

    private static Map<String, Long> stock() {
        var stock = new LinkedHashMap<String, Long>();
        stock.put("gtceu:antimony_dust", 1000000000000L);
        stock.put("gtceu:antimony_trioxide_dust", 1000000000000L);
        stock.put("gtceu:arsenic_trioxide_dust", 1000000000000L);
        stock.put("gtceu:boron_trioxide_dust", 1000000000000L);
        stock.put("gtceu:chromium_trioxide_dust", 1000000000000L);
        stock.put("gtceu:cobalt_oxide_dust", 1000000000000L);
        stock.put("gtceu:cubic_zirconia_dust", 1000000000000L);
        stock.put("gtceu:cupric_oxide_dust", 1000000000000L);
        stock.put("gtceu:ender_eye_dust", 1000000000000L);
        stock.put("gtceu:germanium_dioxide_dust", 1000000000000L);
        stock.put("gtceu:magnesia_dust", 1000000000000L);
        stock.put("gtceu:massicot_dust", 1000000000000L);
        stock.put("gtceu:molybdenum_trioxide_dust", 1000000000000L);
        stock.put("gtceu:oil", 1000000000000L);
        stock.put("gtceu:osmium_dust", 1000000000000L);
        stock.put("gtceu:oxygen_plasma", 1000000000000L);
        stock.put("gtceu:perlite_dust", 1000000000000L);
        stock.put("gtceu:phosphorus_pentoxide_dust", 1000000000000L);
        stock.put("gtceu:potash_dust", 1000000000000L);
        stock.put("gtceu:rare_earth_oxide_dust", 1000000000000L);
        stock.put("gtceu:silicon_dioxide_dust", 1000000000000L);
        stock.put("gtceu:silver_oxide_dust", 1000000000000L);
        stock.put("gtceu:silver_plasma", 1000000000000L);
        stock.put("gtceu:sodium_oxide_dust", 1000000000000L);
        stock.put("gtceu:taranium_dust", 1000000000000L);
        stock.put("gtceu:titanium_50_tetrachloride", 1000000000000L);
        stock.put("gtceu:xenon", 1000000000000L);
        stock.put("gtceu:xenoxene", 1000000000000L);
        stock.put("gtceu:zincite_dust", 1000000000000L);
        stock.put("kubejs:variation_wood", 1000000000000L);
        stock.put("virtual:gtceu:ingot_casting_mold", 64L);
        stock.put("virtual:gtceu:programmed_circuit", 64L);
        stock.put("gtceu:tiny_radox_dust", 1L);
        stock.put("gtceu:radox_gas", 100L);
        return stock;
    }
}
