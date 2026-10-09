// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;

/** A useful source absent from every proposed support must still enter a bounded count model. */
public final class ResidualNeighborhoodTest {
    private record Fixture(GraphCompiler<String> compiler, GraphCompiler.Compiled<String> selected,
                           GraphPlan<String> failed, Map<String, Long> stock) {}

    public static void main(String[] args) {
        for (int seed = 0; seed < 8; seed++) for (boolean chain : new boolean[]{false, true}) solve(seed, chain);
        outerPlanner();
        lowBudgetSplit();
        retainedPages();
        rankingReclamation();
        excludedAndChangedCatalog();
        cancellationAndMemory();
        System.out.println("Residual admission: 16 large catalog/order/chain witnesses; exclusions, catalog replacement, cancellation and memory passed");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), out);
    }

    private static PlanningBudget budget(long memory) {
        return new PlanningBudget(0, 4_000_000, memory, () -> false, System::nanoTime);
    }

    private static Fixture fixture(int order, boolean chain, boolean joint) {
        var consume = recipe("consume", Map.of("A", 10L, "B", 10L), Map.of("T", 1L));
        var a = recipe("single-a", Map.of("ore", 1L), Map.of("A", 10L));
        var b = recipe("single-b", Map.of("ore", 1L), Map.of("B", 10L));
        List<GraphRecipe<String>> catalog = new ArrayList<>(List.of(consume, a, b));
        for (int i = 0; i < 9000; i++) catalog.add(recipe("other" + i, Map.of("ore", 1L), Map.of(i % 2 == 0 ? "A" : "B", 10L)));
        if (joint) {
            // Reusable configuration and returned physical startup stock must
            // survive the residual boundary as original recipe semantics.
            catalog.add(new GraphRecipe<>("joint", "joint", List.of(new GraphRecipe.Slot<>(chain ? "M" : "ore", 1L),
                    new GraphRecipe.Slot<>("seed", 1L), new GraphRecipe.Slot<>("config", 1L, 2, true, true)),
                    Map.of("A", 10L, "B", 10L, "seed", 1L, "config", 1L)));
            if (chain) {
                catalog.add(recipe("bridge", Map.of("N", 1L), Map.of("M", 1L)));
                catalog.add(recipe("upstream", Map.of("ore", 1L), Map.of("N", 1L)));
            }
        }
        Collections.shuffle(catalog, new Random(51241 + order));
        var stock = Map.of("ore", 3L, "seed", 1L, "config", 1L, "T", 100L);
        var selected = new GraphCompiler<>(List.of(consume, a, b)).compile("T", Map.of(), Set.of(), budget(64L << 20));
        var failed = new GraphPlan<>("T", 3, false, new PlanStep.Sequence(List.of(new PlanStep.Batch("single-a", 3),
                new PlanStep.Batch("single-b", 3), new PlanStep.Batch("consume", 3))),
                Map.of("consume", consume, "single-a", a, "single-b", b), stock, Map.of(), Map.of("ore", 3L),
                GraphPlan.Result.MISSING_INPUT, 0, 0);
        return new Fixture(new GraphCompiler<>(catalog), selected, failed, stock);
    }

    private static GraphSupportNeighborhood<String> search(Fixture f, Set<String> excluded, PlanningBudget budget) {
        return new GraphSupportNeighborhood<>(f.compiler, "T", 3, f.stock, Set.of(), Map.of(), excluded,
                false, true, budget, System.nanoTime());
    }

    private static void solve(int order, boolean chain) {
        Fixture f = fixture(order, chain, true);
        var budget = budget(64L << 20);
        check(f.compiler.countClosure("T", Set.of(), Set.of(), 8192, 8192, budget) == null, "Full closure unexpectedly admitted");
        try (var search = search(f, Set.of(), budget)) {
            search.offer(f.selected, f.failed);
            search.offer(f.selected, f.failed);
            for (int turn = 0; turn < 8 && search.result() == null && search.beginTurn(262144); turn++) while (!search.step()) {}
            var result = search.result();
            check(result != null && result.feasible(), "Missing residual witness: " + order + "/" + chain + " " + budget.diagnostics());
            check(result.patternTimes().containsKey("joint"), "The unoffered common source never entered the model");
            check(!chain || result.patternTimes().keySet().containsAll(Set.of("bridge", "upstream")), "Boundary did not expand upstream");
            try (var verification = new PlanVerification<>(result, budget)) {
                while (!verification.step()) {}
                try (var proof = new ForceCraftProof<>(result, verification, Map.of(), budget)) {
                    while (!proof.step()) {}
                    check(proof.proved(), "Existing target stock replaced real production");
                }
            }
            check(result.recipes().get("joint").reusableInputs().get("config") == 1L, "Lost reusable input semantics");
            System.out.println("residual order=" + order + " chain=" + chain + " work=" + budget.searchWork() + " support=" + result.patternTimes().size());
        }
        check(budget.reservedBytes() == 0, "Residual workspace leaked");
    }

    private static void excludedAndChangedCatalog() {
        for (boolean joint : new boolean[]{true, false}) {
            Fixture f = fixture(1, false, joint);
            var budget = budget(64L << 20);
            try (var search = search(f, Set.of("joint"), budget)) {
                search.offer(f.selected, f.failed);
                check(search.beginTurn(65536), "No negative-scope scout");
                while (!search.step()) {}
                check(search.result() == null, "Excluded/stale recipe escaped its compiler scope");
            }
            check(budget.reservedBytes() == 0, "Negative neighborhood leaked");
        }
    }

    private static void outerPlanner() {
        for (boolean chain : new boolean[]{false, true}) {
            Fixture f = fixture(5, chain, true);
            var budget = budget(64L << 20);
            var search = new GraphPlanningWork<>(f.compiler, "T", 3, f.stock, false, true, budget);
            try {
                while (!search.step()) {}
                check(search.result().feasible() && search.result().patternTimes().containsKey("joint"),
                        "Large residual route failed through the complete planner: " + chain + " " + budget.diagnostics());
            } finally { search.close(); }
            check(budget.reservedBytes() == 0, "Outer residual planner leaked");
        }
    }

    private static void lowBudgetSplit() {
        var compiler = new GraphCompiler<>(List.of(recipe("x", Map.of("X", 1L), Map.of("A", 1L)),
                recipe("y", Map.of("Y", 1L), Map.of("A", 1L))));
        var budget = new PlanningBudget(0, 100_000, 64L << 20, () -> false, System::nanoTime);
        var result = new GraphPlanner<>(compiler).plan("A", 5, Map.of("X", 3L, "Y", 2L), true, true, budget);
        check(result.feasible() && result.patternTimes().equals(Map.of("x", 3L, "y", 2L)),
                "An unadmitted residual frontier blocked the original low-budget split: " + budget.diagnostics());
        check(budget.reservedBytes() == 0, "Low-budget split leaked");
    }

    private static void retainedPages() {
        List<GraphRecipe<String>> catalog = new ArrayList<>();
        for (int i = 0; i < 40; i++) catalog.add(recipe("page" + i, Map.of("ore", 1L), Map.of("A", 1L)));
        var compiler = new GraphCompiler<>(catalog);
        Set<String> expected = null;
        for (boolean sliced : new boolean[]{false, true}) {
            var budget = budget(64L << 20);
            Map<String, GraphRecipe<String>> pool = new LinkedHashMap<>();
            try (var frontier = new GraphResidualSources<>(compiler, Map.of("ore", 1L), Set.of(), Set.of("page1"),
                    pool, recipe -> pool.putIfAbsent(recipe.id(), recipe) == null, budget)) {
                frontier.offer("A");
                frontier.begin(4);
                if (sliced) {
                    for (int i = 0; i < 13; i++) check(!frontier.step(), "Unexpected early scan completion");
                    frontier.begin(4);
                }
                while (!frontier.step()) {}
                check(frontier.scanned() == 40 && pool.size() == 4 && frontier.pending(), "Partial scan restarted or lost its remaining page");
                frontier.begin(4);
                while (!frontier.step()) {}
                check(frontier.scanned() == 40 && pool.size() == 8 && !pool.containsKey("page1"), "Second page rescanned or imported excluded sources");
                if (expected == null) expected = Set.copyOf(pool.keySet());
                else check(expected.equals(pool.keySet()), "Slicing changed retained source selection");
            }
            check(budget.reservedBytes() == 0, "Source page leaked");
        }
        Fixture f = fixture(0, false, true);
        var budget = budget(64L << 20);
        try (var search = search(f, Set.of(), budget)) {
            search.offer(f.selected, f.failed);
            check(!search.beginTurn(8192) && !search.retained(), "Ungrantable catalog work kept the outer planner parked");
            check(search.beginTurn(262144), "A declined turn permanently poisoned later admission");
            while (!search.step()) {}
            check(search.result() != null, "Re-admitted source frontier lost its witness");
        }
        check(budget.reservedBytes() == 0, "Re-admitted source frontier leaked");
    }

    private static void cancellationAndMemory() {
        Fixture f = fixture(3, true, true);
        var budget = budget(64L << 20);
        var search = search(f, Set.of(), budget);
        try {
            search.offer(f.selected, f.failed);
            check(search.beginTurn(262144), "No cancellation scout");
            for (int i = 0; i < 100; i++) check(!search.step(), "Scan completed before cancellation");
            budget.cancel();
            try { search.step(); throw new AssertionError("Cancellation swallowed"); }
            catch (CancellationException expected) {}
        } finally { search.close(); search.close(); }
        check(budget.reservedBytes() == 0, "Cancelled scan leaked");
        for (long memory : new long[]{512, 8192, 32768, 131072, 524288}) {
            budget = budget(memory);
            try (var limited = search(f, Set.of(), budget)) {
                limited.offer(f.selected, f.failed);
                if (limited.beginTurn(32768)) while (!limited.step()) {}
            }
            check(budget.reservedBytes() == 0, "Memory refusal leaked: " + memory);
        }
    }

    private static void rankingReclamation() {
        var catalog = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < 50; i++) catalog.add(recipe("r" + i, Map.of("ore", 1L), Map.of("A", 1L)));
        var compiler = new GraphCompiler<>(catalog);
        var stock = Map.of("ore", 1L);
        var calibration = budget(64L << 20);
        try (var model = RecipeCountModel.create(new GraphCompiler<>(catalog.subList(0, 4)), "A", 1,
                stock, Map.of(), Set.of(), Set.of(), true, calibration)) {
            check(model != null, "Missing count model for memory calibration");
        }
        check(calibration.reservedBytes() == 0, "Count calibration leaked");
        var budget = budget(calibration.peakBytes() + 1280);
        var pool = new LinkedHashMap<String,GraphRecipe<String>>();
        try (var f = new GraphResidualSources<>(compiler, stock, Set.of(), Set.of(), pool,
                r -> pool.putIfAbsent(r.id(), r) == null, budget)) {
            f.offer("A"); f.begin(4); while (!f.step()) {}
            try (var denied = RecipeCountModel.create(new GraphCompiler<>(List.copyOf(pool.values())), "A", 1,
                    stock, Map.of(), Set.of(), Set.of(), true, budget)) {
                check(denied == null, "Test did not reach count admission under window pressure");
            }
            check(f.releaseRankings() > 0, "No optional ranking storage reclaimed");
            try (var admitted = RecipeCountModel.create(new GraphCompiler<>(List.copyOf(pool.values())), "A", 1,
                    stock, Map.of(), Set.of(), Set.of(), true, budget)) {
                check(admitted != null, "Optional rankings permanently denied the same count model");
            }
            f.begin(4); while (!f.step()) {}
            check(List.copyOf(pool.keySet()).equals(List.of("r0","r1","r2","r3","r4","r5","r6","r7")),
                    "Reclamation lost or reordered the pending catalog page");
        }
        check(budget.reservedBytes() == 0, "Downstream admission/reclamation leaked");

        budget = budget(64L << 20);
        pool.clear();
        try (var f = new GraphResidualSources<>(compiler, stock, Set.of(), Set.of(), pool,
                r -> pool.putIfAbsent(r.id(), r) == null, budget)) {
            f.offer("A"); f.begin(4);
            for (int i = 0; i < 37; i++) check(!f.step(), "No partial ranking window");
            check(f.releaseRankings() > 0, "Partial scan did not reclaim its window");
            while (!f.step()) {}
            check(f.scanned() == 50 && List.copyOf(pool.keySet()).equals(List.of("r0","r1","r2","r3")),
                    "Reclamation restarted or corrupted an in-progress scan");
        }
        check(budget.reservedBytes() == 0, "Partial-window reclamation leaked");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
