// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;

/** A paid owner scan cannot consume the cooldown intended to bound repeat observations. */
public final class CountPackingObservationTest {
    private static int checks;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var recipes = List.of(
                HistoricalFixtures.r("one", Map.of("ore", 1L), Map.of("goal", 1L)),
                HistoricalFixtures.r("batch", Map.of("ore", 2L), Map.of("goal", 3L)));
        var budget = new PlanningBudget(0, 4_000_000, 64L << 20, () -> false, System::nanoTime);
        try (var search = new IntegerCountSearch<>(new GraphCompiler<>(recipes), "goal", 2,
                Map.of("ore", 2L), Map.of(), Set.of(), Set.of(), false, true, budget, System.nanoTime())) {
            var pending = (Deque<IntegerCountBranch<String>>) get(search, "pending");
            var original = new ArrayList<>(pending);
            check(original.size() == 1, "Expected one real unopened branch");
            var branch = original.get(0);
            long memory = budget.reservedBytes();
            try {
                // Repeated references model a large scheduling queue without
                // creating 131072 solver workspaces. Restore unique ownership
                // before disposal; this checks observation cost, not SAT/UNSAT.
                for (int size : new int[]{1, 65535, 65536, 65537, 131072}) {
                    pending.clear();
                    for (int i = 0; i < size; i++) pending.addLast(branch);
                    set(search, "packingCompletionProbeAfter", 0L);
                    long start = budget.nodes();
                    check(search.activePackingCompletion() == null, "Unopened branches invented an owner");
                    long after = budget.nodes(), deadline = (long) get(search, "packingCompletionProbeAfter");
                    check(after - start == size, "Owner scan was free or charged repeatedly");
                    check(deadline == (size < 65536 ? start : after) + 65536,
                            "Scan renewed small-queue timing or consumed its own large-queue cooldown");
                    for (int repeat = 0; repeat < 8; repeat++)
                        check(search.activePackingCompletion() == null, "Repeated observation invented an owner");
                    check(budget.nodes() == after && budget.reservedBytes() == memory,
                            "Immediate observations rescanned or allocated workspace");
                    budget.charge(65536);
                    long beforeNext = budget.nodes();
                    check(search.activePackingCompletion() == null && budget.nodes() == beforeNext + size,
                            "Cooldown permanently suppressed or duplicated the next paid observation");
                    check(budget.reservedBytes() == memory && !search.infeasible() && search.result() == null,
                            "Observation changed ownership or exported a search conclusion");
                }
                budget.charge(65536);
                budget.cancel();
                try { search.activePackingCompletion(); throw new AssertionError("Cancelled large scan continued"); }
                catch (CancellationException expected) { checks++; }
            } finally {
                pending.clear();
                pending.addAll(original);
            }
        }
        check(budget.reservedBytes() == 0, "Cancelled observation retained branch workspace");
        System.out.println("Packing observation: charged small/large queues, fixed cooldown, no allocation or conclusion, cancellation and unique cleanup passed; checks=" + checks);
    }

    private static Object get(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
