// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** An interrupted allocation frontier keeps its turn, while the request pays all work. */
public final class AllocationWorkTest {

    public static void main(String[] args) {
        run(false);
        run(true);
    }

    private static void run(boolean interrupt) {
        var cancel = new AtomicBoolean();
        var budget = new PlanningBudget(0, 1_000_000, 64L << 20, cancel::get, System::nanoTime);
        var compiler = new GraphCompiler<>(List.of(
                new GraphRecipe<>("first", "first", List.of(new GraphRecipe.Slot<>("raw", 1)), Map.of("middle", 1L)),
                new GraphRecipe<>("last", "last", List.of(new GraphRecipe.Slot<>("middle", 1)), Map.of("target", 1L))));
        var search = new AllocationSearch<>(compiler, "target", 1, Map.of("raw", 1L), Set.of(), Map.of(),
                false, true, Set.of(), budget, System.nanoTime());
        long own = 0, other = 0;
        boolean cancelled = false;
        try {
            for (int step = 0; ; step++) {
                long before = budget.threadSearchWork();
                boolean done;
                try { done = search.step(); }
                finally {
                    own += budget.threadSearchWork() - before;
                    if (search.searchWork() != own) throw new AssertionError("Lost allocation work");
                }
                if (done) {
                    if (interrupt || search.result() == null || !search.result().feasible())
                        throw new AssertionError("Expected the retained executable frontier");
                    PlanVerifier.verify(search.result());
                    PlanVerifier.verifyRuntimeInventory(search.result());
                    break;
                }
                if (step < 3) {
                    long shared = budget.searchWork();
                    budget.operation(PlanningBudget.Operation.SCAN, 4096);
                    other += budget.searchWork() - shared;
                    if (search.searchWork() != own) throw new AssertionError("Paused turn consumed another strategy's work");
                    if (interrupt && step == 2) cancel.set(true);
                }
            }
        } catch (CancellationException expected) {
            if (!interrupt) throw expected;
            cancelled = true;
        } finally {
            search.discard();
        }
        if (cancelled != interrupt || other == 0 || budget.searchWork() != own + other)
            throw new AssertionError("Shared request accounting changed");
        if (budget.reservedBytes() != 0) throw new AssertionError("Allocation workspace leaked");
    }
}
