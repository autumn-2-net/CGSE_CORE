// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Seed optimization owns a verifier even when its local grant ends mid-fold. */
public final class SeedVerificationLifecycleTest {
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void run(int stop) throws Exception {
        var recipe = new GraphRecipe<>("make", "make", List.of(new GraphRecipe.Slot<>("raw", 1)), Map.of("T", 1L));
        var recipes = Map.of("make", recipe);
        PlanStep body = new PlanStep.Sequence(List.of(new PlanStep.Batch("make", 1), new PlanStep.Batch("make", 1)));
        PlanStep program = new PlanStep.Repeat(body, 5);
        var plan = new GraphPlan<>("T", 10, false, program, recipes, Map.of("raw", BigInteger.TEN), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        PlanVerifier.verify(plan);
        var budget = new PlanningBudget(0, 1_000_000, 16L << 20, () -> false, System::nanoTime);
        var optimizing = new SeedOptimization<>(new GraphCompiler<>(List.of(recipe)), plan, Map.of("raw", 10L), Map.of(), Set.of(), Set.of(), true, budget);
        try {
            var verifying = new PlanVerification<>(plan, budget);
            set(optimizing, "phase", 4);
            set(optimizing, "verifying", verifying);
            set(optimizing, "candidate", plan);
            for (int i = 0; i < 1000 && budget.reservedBytes() == 0; i++) {
                if (verifying.step()) throw new AssertionError("Verifier completed without a retained composite");
            }
            if (budget.reservedBytes() <= 0) throw new AssertionError("No live summary reservation");
            if (stop == 0) {
                optimizing.close();
            } else if (stop == 1) {
                // Expire the strategy grant, while the shared order still has work.
                set(optimizing, "work", 262_144L);
                if (!optimizing.step()) throw new AssertionError("Local budget did not stop");
                if (optimizing.result() != plan && !optimizing.result().steps().equals(plan.steps()))
                    throw new AssertionError("Unverified replacement escaped");
            } else if (stop == 2) {
                budget.cancel();
                try { optimizing.step(); throw new AssertionError("Cancellation ignored"); }
                catch (CancellationException expected) { optimizing.close(); }
            } else {
                while (!verifying.step()) {}
                optimizing.close();
            }
            if (budget.reservedBytes() != 0) throw new AssertionError("Seed verifier leaked " + budget.reservedBytes() + " bytes at stop " + stop);
        } finally { optimizing.close(); }
        if (budget.reservedBytes() != 0) throw new AssertionError("Repeated cleanup leaked");
    }

    public static void main(String[] args) throws Exception {
        for (int stop = 0; stop < 4; stop++) run(stop);
        System.out.println("PASS seed verifier local grant, cancellation, completion and repeated close");
    }
}
