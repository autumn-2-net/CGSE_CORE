// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Optional refinement must keep original executable candidates and their owners. */
public final class CountRefinementOwnershipTest {
    private static final long MEMORY = 64L << 20;
    private static int checks;

    public static void main(String[] args) throws Exception {
        originalBeforeOptional();
        originalUnderMemoryPressure();
        allocationContinuation();
        startupContinuation();
        System.out.println("CountRefinementOwnershipTest PASS: checks=" + checks);
    }

    @SuppressWarnings("unchecked")
    private static void originalBeforeOptional() throws Exception {
        var recipes = List.of(r("finish", Map.of("raw", 1L), Map.of("goal", 1L)));
        var stock = Map.of("raw", 1L);
        var budget = budget();
        try (var search = new CountCyclicStockSearch<>(new GraphCompiler<>(recipes), "goal", 1,
                stock, Set.of(), Map.of(), Set.of(), false, true, budget, 0)) {
            for (int i = 0; get(search, "schedule") == null; i++)
                check(i < 10000 && !search.step(), "Missing funded scheduling fixture");
            var model = (RecipeCountModel<String>) get(search, "model");
            var original = (CountRefinedProjection<String>) get(search, "projection");
            var optional = new CountRefinedProjection<>(model, budget, 1_000_000, 128, false,
                    (BigInteger[]) get(search, "originalCounts"));
            while (!optional.step()) {}
            check(optional.result() != null, "Missing optional candidate at failure handoff");
            set(search, "originalProjection", original); set(search, "projection", optional);
            set(search, "originalTried", false); set(search, "projectionScheduled", true);
            // Exercise the exact failure handoff after the optional schedule
            // has exhausted its word, with little of the shared quota left.
            ((CountSchedule<?>) get(search, "schedule")).close(); set(search, "schedule", null);
            set(search, "work", (long) get(search, "allowance") - 2048);
            invoke(search, "tryOriginal");
            check((boolean) get(search, "originalTried") && get(search, "schedule") != null,
                    "Optional goal refinement preempted the original executable counts");
            finish(search);
            check(search.result() != null && search.result().feasible(), "Original witness lost its final scheduling quota");
            check(search.result().patternTimesExact().equals(Map.of("finish", BigInteger.ONE)),
                    "Original delivery acquired unnecessary full-stock work");
        }
        check(budget.reservedBytes() == 0, "Original priority leaked memory");
    }

    private static void originalUnderMemoryPressure() throws Exception {
        var recipe = r("craft", Map.of("raw", 1L), Map.of("goal", 1L));
        var budget = budget();
        try (var search = demand(List.of(recipe), Map.of("raw", 1L), budget)) {
            check(!search.step(), "Simple proposal failed preparation");
            var model = (RecipeCountModel<?>) get(search, "model");
            set(search, "counts", new BigInteger[]{BigInteger.ONE});
            long scheduleBytes = 4096L + 512L + 640L * 2 + 128L * model.keys.size();
            long held = budget.availableBytes() - scheduleBytes;
            budget.reserve(held);
            try {
                invoke(search, "scheduleCounts");
                check(get(search, "schedule") != null, "Optional refiner workspace blocked the old scheduling entry");
                check(((CountSchedule<?>) get(search, "schedule")).result() == null,
                        "Refinement bookkeeping stole the schedule's exact workspace");
            } finally { budget.release(held); }
            finish(search);
            check(search.result() != null && search.result().feasible(), "Original funded counts disappeared after pressure");
        }
        check(budget.reservedBytes() == 0, "Initial schedule pressure leaked memory");
        var bareBudget = budget();
        try (var model = RecipeCountModel.forShell(List.of(recipe), Map.of("goal", BigInteger.ONE),
                Map.of("raw", 1L), Set.of(), bareBudget)) {
            long held = bareBudget.availableBytes() - 32; bareBudget.reserve(held);
            try (var projection = new CountRefinedProjection<>(model, bareBudget, 32768, 128, false,
                    new BigInteger[]{BigInteger.ONE})) {
                while (!projection.step()) {}
                check(projection.result() != null && !projection.reject(),
                        "Optional bookkeeping refusal discarded a completed original candidate");
            } finally { bareBudget.release(held); }
        }
        check(bareBudget.reservedBytes() == 0, "Bare projection retained optional memory");
    }

    private static void allocationContinuation() throws Exception {
        var budget = budget();
        try (var search = demand(List.of(r("left", Map.of("a", 1L), Map.of("goal", 1L)),
                r("right", Map.of("b", 1L), Map.of("goal", 1L))), Map.of("a", 1L, "b", 1L), budget)) {
            check(!search.step(), "Allocation fixture failed preparation");
            invoke(search, "beginAllocation");
            var allocation = (CountDemandAllocation) get(search, "allocation");
            while (!allocation.step()) {}
            check(allocation.result() != null, "Allocation fixture lacks its first candidate");
            set(search, "counts", allocation.result());
            set(search, "projectedCounts", true); set(search, "reverseTried", true);
            set(search, "refinementTried", true);
            invoke(search, "nextAttempt");
            check(get(search, "allocation") == allocation,
                    "Refined candidate replaced its still-owned reversible allocation frontier");
        }
        check(budget.reservedBytes() == 0, "Refined allocation leaked the original allocator");
    }

    @SuppressWarnings("unchecked")
    private static void startupContinuation() throws Exception {
        var recipes = List.of(r("supply", Map.of("ore", 1L), Map.of("a", 1L)),
                r("gate", Map.of("a", 2L), Map.of("a", 2L, "goal", 1L)));
        var stock = Map.of("a", 1L, "ore", 8L);
        var budget = budget();
        try (var search = demand(recipes, stock, budget)) {
            check(!search.step(), "Startup owner fixture failed preparation");
            var model = (RecipeCountModel<String>) get(search, "model");
            var originalRows = List.copyOf(model.constraints); var originalGoals = new LinkedHashMap<>(model.goals);
            var bad = new BigInteger[model.recipes.size()];
            for (int i = 0; i < bad.length; i++) bad[i] = model.recipes.get(i).id().equals("gate") ? BigInteger.ONE : BigInteger.ZERO;
            var projection = new CountRefinedProjection<>(model, budget, 1_000_000, 128, false, bad);
            set(search, "projection", projection); set(search, "refinementTried", true);
            while (!projection.step()) {}
            check(projection.reject(), "Startup fixture could not refine the blocked candidate");
            while (!projection.step()) {}
            check(projection.result() != null, "Startup fixture did not find the funded guard branch");
            int pending = ((Collection<?>) get(projection, "pending")).size();
            check(pending > 0, "Startup fixture lost its omit-recipe alternative");
            set(search, "counts", projection.result());
            var index = new LinkedHashMap<String, GraphRecipe<String>>(); recipes.forEach(r -> index.put(r.id(), r));
            var candidate = new AllocationSearch.Candidate<>(new PlanStep.Batch("gate", 1), index, "goal", 1,
                    stock, Map.of(), Set.of(), true, true, false, budget, 0);
            // The overlay accepts a candidate-local startup obligation; its
            // strength is irrelevant to ownership and is never a global cut.
            candidate.startupLoans = Map.of("a", 3L); set(search, "candidate", candidate);
            invoke(search, "completeStartup");
            check(get(search, "startupProjection") == projection && get(search, "projection") == null,
                    "Temporary startup goals destroyed the original refiner owner");
            invoke(search, "restoreStartup");
            check(get(search, "projection") == projection && ((Collection<?>) get(projection, "pending")).size() == pending,
                    "Restoring startup goals discarded pending original guard branches");
            check(model.constraints.equals(originalRows) && model.goals.equals(originalGoals),
                    "Startup goal overlay escaped into original rows");
        }
        check(budget.reservedBytes() == 0, "Startup owner restoration leaked memory");
    }

    private static CountDemandSearch<String> demand(List<GraphRecipe<String>> recipes, Map<String, Long> stock, PlanningBudget budget) {
        return new CountDemandSearch<>(new GraphCompiler<>(recipes), "goal", 1, stock, Set.of(), Map.of(), Set.of(),
                false, true, budget, 0);
    }
    private static PlanningBudget budget() { return new PlanningBudget(0, 16_000_000, MEMORY, () -> false, System::nanoTime); }
    private static GraphRecipe<String> r(String id, Map<String, Long> inputs, Map<String, Long> outputs) {
        return new GraphRecipe<>(id, id, inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), outputs);
    }
    private static void finish(CountCyclicStockSearch<?> search) { for (int i = 0; !search.step(); i++) check(i < 10000, "Cyclic search failed to stop"); }
    private static void finish(CountDemandSearch<?> search) { for (int i = 0; !search.step(); i++) check(i < 10000, "Demand search failed to stop"); }
    private static Object get(Object o, String n) throws Exception { var f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    private static void set(Object o, String n, Object v) throws Exception { var f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
    private static void invoke(Object o, String n) throws Exception { var m = o.getClass().getDeclaredMethod(n); m.setAccessible(true); m.invoke(o); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
