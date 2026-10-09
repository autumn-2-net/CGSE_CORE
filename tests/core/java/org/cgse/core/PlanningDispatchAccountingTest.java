// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

/** The graph coordinator already charges its steps, independently of dispatch. */
public final class PlanningDispatchAccountingTest {
    public static void main(String[] args) throws Exception {
        for (int size : new int[] { 1, 17, 127 }) {
            var ordinary = plan(size, 0, 1);
            for (int workers : new int[] { 1, 4 }) for (int slice : new int[] { 1, 7, 4096 })
                check(plan(size, workers, slice).equals(ordinary), "Dispatcher billed an already charged step");
        }
        // Larger cold graphs can dispatch edge batches. Require a witness even
        // at the smallest quantum, without equating different parallel kernels.
        for (int slice : new int[] { 1, 4096 }) plan(1024, 4, slice);
        for (boolean selfCharged : new boolean[] { false, true }) {
            for (int slice : new int[] { 1, 13 }) {
                var budget = new PlanningBudget(0, 1000, () -> false);
                var closed = new CountDownLatch(1);
                try (var scheduler = new PlanningScheduler(2, 2, slice, 1_000_000)) {
                    var work = new PlanningScheduler.Work<Long>() {
                        int steps;
                        @Override public boolean advance(PlanningScheduler.Slice turn) {
                            while (selfCharged ? turn.nextUncharged() : turn.next()) {
                                if (selfCharged) budget.check();
                                if (++steps == 100) return true;
                            }
                            return false;
                        }
                        @Override public Long result() { return budget.searchWork(); }
                        @Override public void close() { closed.countDown(); }
                    };
                    check(scheduler.submit(work, budget).get(30, TimeUnit.SECONDS) == 100, "Step charge omitted or duplicated");
                    // Completion may become visible just before the coordinator
                    // finally releases its work. Shutdown still closes the job.
                }
                check(closed.await(30, TimeUnit.SECONDS) && budget.reservedBytes() == 0, "Dispatch owner not released");
            }
        }
        System.out.println("Planning dispatch: 18 synchronous/scheduled search+compilation equalities, two large graphs and four legacy/self-charged fairness checks passed");
    }

    private record WorkCounts(long search, long compilation) {}

    private static WorkCounts plan(int size, int workers, int slice) throws Exception {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size; i++) {
            String id = "recipe" + i;
            recipes.add(new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>("item" + i, 1)), Map.of("item" + (i + 1), 1L)));
        }
        var budget = new PlanningBudget(0, 1_000_000, 64L << 20, () -> false, System::nanoTime);
        var work = new GraphPlanningWork<>(new GraphCompiler<>(recipes), "item" + size, 100,
                Map.of("item0", 100L), false, true, budget);
        GraphPlan<String> plan;
        if (workers == 0) {
            try { while (!work.step()) {} plan = work.result(); }
            finally { work.close(); }
        } else {
            try (var scheduler = new PlanningScheduler(workers, 2, slice, 1_000_000)) {
                plan = scheduler.submit(work, budget).get(30, TimeUnit.SECONDS);
            }
        }
        check(plan.feasible(), "Dispatch changed witness: size=" + size + "; workers=" + workers + "; slice=" + slice +
                "; result=" + plan.result() + "; diagnostics=" + budget.diagnostics());
        PlanVerifier.verify(plan);
        PlanVerifier.verifyRuntimeInventory(plan);
        return new WorkCounts(budget.searchWork(), budget.compilationWork());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
