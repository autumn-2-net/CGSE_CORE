// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Private-chain admission keeps real boundary ports, domains and optional-aid lifetime. */
public final class CountRecoveryAdmissionTest {
    public static void main(String[] args) {
        for (int order = 0; order < 8; order++) {
            var recipes = chains(8, 32, 3, false);
            Collections.shuffle(recipes, new Random(9307 + order));
            check(shape(recipes, inventory(8, 2), false, false), "finite private chains missed");
            check(shape(recipes, inventory(8, Long.MAX_VALUE), false, false), "large finite domains missed");
            check(!shape(recipes, inventory(8, 2), true, false), "unbounded domain recruited");
            check(!shape(recipes, inventory(8, 2), false, true), "optional workspace refusal ignored");

            var compiler = new GraphCompiler<>(recipes);
            for (long available : new long[] {2, 0, 2}) {
                var budget = budget(() -> false);
                var plan = new GraphPlanner<>(compiler).plan("G", 1, inventory(8, available), true, true, budget);
                check(plan.feasible() == (available != 0), "stock update changed the oracle: " + plan.result());
                if (plan.feasible()) {
                    PlanVerifier.verify(plan);
                    PlanVerifier.verifyRuntimeInventory(plan);
                }
                check(budget.reservedBytes() == 0, "planning workspace leaked");
            }
        }
        check(!shape(chains(1, 256, 1, false), inventory(1, 2), false, false), "a long deterministic chain became a choice kernel");
        check(!shape(chains(8, 32, 1, true), inventory(8, 2), false, false), "per-dispatch inputs contracted as ordinary seams");
        cancellation();
        System.out.println("Recovery admission: finite domains, aliases, boundary byproducts, stock updates, long chains, configuration, memory and cancellation passed");
    }

    private static boolean shape(List<GraphRecipe<String>> recipes, Map<String, Long> stock, boolean unbounded, boolean memoryDenied) {
        var budget = budget(() -> false);
        try (var model = RecipeCountModel.forBounds(new GraphCompiler<>(recipes), "G", 1, stock, Map.of(), Set.of(), Set.of(), budget);
             var bounds = new CountBounds(model.recipes.size(), model.constraints, budget)) {
            while (!bounds.step()) {}
            check(!bounds.blocked(), "funded network rejected by propagation");
            // A real recipe may return byproducts outside the backward count
            // closure. They remain in primitive verification, without row IDs.
            check(!model.ids.containsKey("waste0"), "test did not retain an unmodeled boundary port");
            BigInteger[] lower = bounds.lowerBounds(), upper = bounds.upperBounds();
            if (unbounded) upper[upper.length / 2] = null;
            long retained = budget.reservedBytes();
            long denied = memoryDenied ? budget.availableBytes() : 0;
            budget.reserve(denied);
            try {
                boolean compact = CountRecoveryShape.compact(model, lower, upper, budget);
                check(budget.reservedBytes() == retained + denied, "shape observation leaked");
                return compact;
            } finally { budget.release(denied); }
        } finally { check(budget.reservedBytes() == 0, "model lifetime leaked"); }
    }

    private static void cancellation() {
        for (long offset : new long[] {0, 300, 1600, 3300, 7000}) {
            var cut = new AtomicLong(Long.MAX_VALUE);
            var ref = new PlanningBudget[1];
            var budget = budget(() -> ref[0].searchWork() >= cut.get());
            ref[0] = budget;
            try (var model = RecipeCountModel.forBounds(new GraphCompiler<>(chains(8, 32, 3, false)), "G", 1,
                    inventory(8, 2), Map.of(), Set.of(), Set.of(), budget);
                 var bounds = new CountBounds(model.recipes.size(), model.constraints, budget)) {
                while (!bounds.step()) {}
                long retained = budget.reservedBytes();
                cut.set(budget.searchWork() + offset);
                try { CountRecoveryShape.compact(model, bounds.lowerBounds(), bounds.upperBounds(), budget); }
                catch (CancellationException expected) { /* the caller cancels the order */ }
                check(budget.reservedBytes() == retained, "cancelled shape observation leaked");
            }
            check(budget.reservedBytes() == 0, "cancelled model leaked");
        }
    }

    private static List<GraphRecipe<String>> chains(int routes, int depth, int aliases, boolean configuration) {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int route = 0; route < routes; route++) for (int stage = 0; stage < depth; stage++) {
            String from = stage == 0 ? "F" + route : "p" + route + "/" + (stage - 1);
            Map<String, Long> to = stage + 1 == depth ? Map.of("G", 1L, "waste" + route, 1L) : Map.of("p" + route + "/" + stage, 1L);
            for (int alias = 0; alias < aliases; alias++) {
                String id = route + "/" + stage + "/" + alias;
                recipes.add(new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>(from, 1, 0, configuration)), to));
            }
        }
        return recipes;
    }

    private static Map<String, Long> inventory(int routes, long amount) {
        var result = new LinkedHashMap<String, Long>();
        for (int route = 0; route < routes; route++) if (amount != 0) result.put("F" + route, amount);
        return result;
    }

    private static PlanningBudget budget(java.util.function.BooleanSupplier cancelled) {
        return new PlanningBudget(0, 4_000_000, 128L << 20, cancelled, System::nanoTime);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
