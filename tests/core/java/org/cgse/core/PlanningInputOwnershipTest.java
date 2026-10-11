// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Public capture boundaries must retain one input version through every continuation. */
public final class PlanningInputOwnershipTest {
    private static final GraphRecipe<String> MAKE = recipe("make", "ore");
    private static final GraphCompiler<String> COMPILER = new GraphCompiler<>(List.of(MAKE));
    private static int assertions;

    public static void main(String[] args) throws Exception {
        stockMutation();
        immutableAvailability();
        invalidInventory();
        recipeIdentity();
        System.out.println("PlanningInputOwnershipTest: PASS " + assertions);
    }

    private static void stockMutation() {
        for (boolean initiallyFunded : new boolean[] {true, false}) {
            var stock = new LinkedHashMap<String, Long>();
            if (initiallyFunded) stock.put("ore", 1L);
            var b = budget();
            var work = new GraphPlanner<>(COMPILER).begin("product", 1, stock, false, true, b);
            stock.clear();
            if (!initiallyFunded) stock.put("ore", 1L);
            try {
                while (!work.step()) {}
                check(work.result().feasible() == initiallyFunded, "begin() observed later caller stock mutation");
            } finally {
                work.close();
            }
            check(b.reservedBytes() == 0, "captured request leaked memory");
        }
        var source = new LinkedHashMap<>(Map.of("ore", 5L));
        var captured = PlanningInventory.snapshot(source);
        source.clear();
        check(captured.equals(Map.of("ore", 5L)), "snapshot aliases source");
        check(PlanningInventory.snapshot(captured) == captured, "continuations copied already owned inventory");
        var merged = PlanningInventory.availability(captured, Map.of());
        check(PlanningInventory.snapshot(merged) == merged, "availability cannot be reused as captured input");
    }

    private static void immutableAvailability() throws Exception {
        var stock = new LinkedHashMap<>(Map.of("ore", 1L));
        var b = budget();
        var work = new RequestPlanningWork<>(COMPILER, "product", 1, stock, Set.of(), null,
                false, false, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, false, false, b, () -> false, r -> {});
        stock.clear();
        var view = work.availability();
        expect(UnsupportedOperationException.class, () -> view.put("ore", 100L));
        expect(UnsupportedOperationException.class, () -> view.remove("ore"));
        expect(UnsupportedOperationException.class, () -> view.entrySet().iterator().next().setValue(0L));
        expect(UnsupportedOperationException.class, () -> view.compute("ore", (k, v) -> 0L));
        expect(UnsupportedOperationException.class, () -> view.values().clear());
        check(PlanningInventory.snapshot(view) == view, "request probes recapture immutable inventory");
        try (var scheduler = new PlanningScheduler(2, 4, 16, 2_000_000)) {
            check(scheduler.submit(work, b).get(30, TimeUnit.SECONDS).feasible(), "request changed between capture and worker execution");
        }
        check(view.equals(Map.of("ore", 1L)), "solver mutated owned inventory");
    }

    private static void invalidInventory() {
        var negative = Map.of("unrelated", -1L);
        expect(IllegalArgumentException.class, () -> new GraphPlanner<>(COMPILER).begin("product", 1, negative, false, true, budget()));
        expect(IllegalArgumentException.class, () -> PlanningInventory.availability(negative, Map.of()));
        expect(IllegalArgumentException.class, () -> PlanningInventory.availability(Map.of(), negative));
        // Even a direct-emission target is validated before exclusion.
        expect(IllegalArgumentException.class, () -> PlanningInventory.availability(negative, Map.of(), "unrelated"));
        var nullKey = new HashMap<String, Long>();
        nullKey.put(null, 1L);
        expect(NullPointerException.class, () -> PlanningInventory.snapshot(nullKey));
        expect(NullPointerException.class, () -> PlanningInventory.availability(Map.of(), nullKey));
        var merged = PlanningInventory.availability(Map.of("ore", Long.MAX_VALUE, "zero", 0L), Map.of("ore", 1L));
        check(merged.equals(Map.of("ore", Long.MAX_VALUE)), "availability saturation or zero normalization changed");
    }

    private static void recipeIdentity() throws Exception {
        for (var duplicate : List.of(MAKE, recipe("make", "different"))) {
            var recipes = List.of(MAKE, duplicate);
            expect(IllegalArgumentException.class, () -> new GraphCompiler<>(recipes));
            var index = new CatalogIndex<>(recipes);
            expect(IllegalArgumentException.class, () -> { while (!index.step(budget())) {} });
            expect(IllegalArgumentException.class, () -> index.step(budget()));
            expect(IllegalStateException.class, index::result);
            var build = new PreparedCatalog<>(recipes, Map.of()).build(budget());
            try (var scheduler = new PlanningScheduler(1, 4, 16, 2_000_000)) {
                try {
                    scheduler.submit(build, budget()).get(30, TimeUnit.SECONDS);
                    throw new AssertionError("prepared catalog published duplicate IDs");
                } catch (java.util.concurrent.ExecutionException expected) {
                    check(expected.getCause() instanceof IllegalArgumentException, "prepared duplicate diagnostic");
                }
            }
        }
        var variants = List.of(MAKE, recipe("variant", "different"));
        check(new GraphCompiler<>(variants).producers("product").size() == 2, "bindings must be allowed to repeat across unique variants");
        var index = new CatalogIndex<>(variants);
        while (!index.step(budget())) {}
        check(index.result().catalog().equals(variants), "incremental indexing changed variant order");
    }

    private static GraphRecipe<String> recipe(String id, String input) {
        return new GraphRecipe<>(id, "shared-provider", List.of(new GraphRecipe.Slot<>(input, 1)), Map.of("product", 1L));
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(0, 2_000_000, 256L << 20, () -> false, System::nanoTime);
    }

    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable run) {
        assertions++;
        try { run.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("expected " + type.getSimpleName(), error);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
