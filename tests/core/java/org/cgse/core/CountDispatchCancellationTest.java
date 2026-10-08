// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Interrupt the handoff between a retained branch queue and its next worker. */
public final class CountDispatchCancellationTest {
    private static final StackWalker STACK = StackWalker.getInstance();

    public static void main(String[] args) {
        for (int stop : new int[] { 1, 2, 7, 17 }) for (boolean timeout : new boolean[] { false, true }) {
            AtomicInteger snapshots = new AtomicInteger();
            boolean[] interrupted = { false };
            var boundary = (java.util.function.BooleanSupplier) () -> {
                if (interrupted[0]) return true;
                boolean handoff = STACK.walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals(CountConflictPool.class.getName()) && frame.getMethodName().equals("snapshot")));
                if (handoff && snapshots.incrementAndGet() == stop) interrupted[0] = true;
                return interrupted[0];
            };
            PlanningBudget budget = new PlanningBudget(timeout ? 1 : 0, 20_000_000, 64L << 20,
                    () -> !timeout && boundary.getAsBoolean(), () -> timeout && boundary.getAsBoolean() ? 2_000_000L : 0L);
            var random = new Random(810085);
            var recipes = new ArrayList<GraphRecipe<String>>();
            var stock = new LinkedHashMap<String, Long>();
            var demand = new LinkedHashMap<String, Long>();
            for (int i = 0; i < 24; i++) {
                stock.put("raw" + i, 1L);
                int chosen = random.nextInt(3);
                long a = 1 + random.nextInt(1000), b = 1 + random.nextInt(1000);
                for (int c = 0; c < 3; c++) {
                    var outputs = Map.of("a" + c, a, "b" + c, b);
                    String id = "r" + i + "c" + c;
                    recipes.add(new GraphRecipe<>(id, id, List.of(new GraphRecipe.Slot<>("raw" + i, 1)), outputs));
                    if (c == chosen) outputs.forEach((key, value) -> demand.merge(key, value, Long::sum));
                }
            }
            recipes.add(new GraphRecipe<>("finish", "finish", demand.entrySet().stream()
                    .map(e -> new GraphRecipe.Slot<>(e.getKey(), e.getValue())).toList(), Map.of("goal", 1L)));
            var compiler = new GraphCompiler<>(recipes);
            try (var search = new IntegerCountSearch<>(compiler, "goal", 1, stock, Map.of(), Set.of(), Set.of(), false, true,
                    budget, 0, null, false)) {
                while (true) {
                    if (!search.step()) continue;
                    if (!search.paused()) throw new AssertionError("Handoff cutoff not reached");
                    search.resume();
                }
            } catch (CancellationException expected) {
                if (timeout) throw expected;
            } catch (PlanningBudget.Exhausted expected) {
                if (!timeout || expected.limit() != PlanningBudget.Limit.TIMEOUT) throw expected;
            }
            if (!interrupted[0]) throw new AssertionError("Missing handoff interruption");
            if (budget.reservedBytes() != 0) throw new AssertionError("Handoff " + stop + " timeout=" + timeout + " leaked " + budget.reservedBytes());
        }
        System.out.println("Count dispatch: cancellation and deadline at four conflict-sharing handoffs release all reservations");
    }
}
